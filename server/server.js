#!/usr/bin/env node
/*
 * Moddedrealms website server
 *
 * - Serves the static site in ../website
 * - Handles the feedback and player report forms (POST /api/feedback, /api/report):
 *   every submission is appended to data/*.jsonl and posted to a Discord webhook if one is set
 * - Proxies live data for the homepage (GET /api/status, /api/videos) with caching
 *
 * No dependencies. Requires Node.js 18 or newer. Configure with environment variables or a
 * .env file in the project root (see .env.example).
 */
"use strict";

const http = require("node:http");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const crypto = require("node:crypto");

const ROOT = path.resolve(__dirname, "..");
const WEB_ROOT = path.join(ROOT, "website");

if (Number(process.versions.node.split(".")[0]) < 18) {
  console.error(`Node.js 18 or newer is required (you have ${process.versions.node}).`);
  process.exit(1);
}

loadEnvFile(path.join(ROOT, ".env"));

const env = process.env;
const config = {
  host: env.HOST || "0.0.0.0",
  port: toInt(env.PORT, 3000),
  siteUrl: (env.SITE_URL || "").trim().replace(/\/+$/, ""),
  trustProxy: isTrue(env.TRUST_PROXY),
  dataDir: path.resolve(ROOT, env.DATA_DIR || "data"),
  feedbackWebhook: (env.DISCORD_FEEDBACK_WEBHOOK_URL || env.DISCORD_WEBHOOK_URL || "").trim(),
  reportWebhook: (env.DISCORD_REPORT_WEBHOOK_URL || env.DISCORD_WEBHOOK_URL || "").trim(),
  reportPingRoleId: (env.DISCORD_REPORT_PING_ROLE_ID || "").trim(),
  mcAddress: (env.MC_SERVER_ADDRESS || "moddedrealms.minehut.gg").trim().toLowerCase(),
  discordInvite: (env.DISCORD_INVITE_CODE || "AA3a3H3ECk").trim(),
  youtubeChannelId: (env.YOUTUBE_CHANNEL_ID || "UC408m1zoIwbW5JXeOkqSlxA").trim(),
  ipSalt: env.IP_HASH_SALT || crypto.randomBytes(16).toString("hex"),
  submitLimit: toInt(env.SUBMIT_RATE_LIMIT, 5),
};

const LINKS = {
  "/discord": `https://discord.gg/${config.discordInvite}`,
  "/youtube": "https://www.youtube.com/@Moddedrealms",
  "/modrinth": "https://modrinth.com/mod/ultimate-vehicle-experience",
  "/feedback": "/support#feedback",
  "/report": "/support#report",
};

const USER_AGENT = `ModdedrealmsWebsite/1.0 (+${config.siteUrl || "https://github.com/leebeargangman/moddedrealms"})`;

/* ------------------------------------------------------------------ */
/* Static files                                                        */
/* ------------------------------------------------------------------ */

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".txt": "text/plain; charset=utf-8",
  ".xml": "application/xml; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".avif": "image/avif",
  ".gif": "image/gif",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
  ".mp4": "video/mp4",
  ".webm": "video/webm",
};

const CACHE_RULES = {
  ".html": "no-cache",
  ".css": "no-cache",
  ".js": "no-cache",
  ".webmanifest": "no-cache",
  ".woff2": "public, max-age=2592000",
  ".woff": "public, max-age=2592000",
};
const DEFAULT_CACHE = "public, max-age=604800";

const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: https://i.ytimg.com",
  "font-src 'self'",
  "connect-src 'self'",
  "frame-src https://www.youtube-nocookie.com",
  "media-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

function setSecurityHeaders(res) {
  res.setHeader("Content-Security-Policy", CSP);
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
  res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
  if (config.siteUrl.startsWith("https://")) {
    res.setHeader("Strict-Transport-Security", "max-age=15552000");
  }
}

async function serveStatic(req, res, pathname) {
  let rel;
  try {
    rel = decodeURIComponent(pathname);
  } catch {
    return notFound(req, res);
  }
  // Never serve dotfiles, ".." segments or null bytes.
  if (rel.includes("\0") || rel.split("/").some((seg) => seg.startsWith("."))) {
    return notFound(req, res);
  }
  if (rel === "/") rel = "/index.html";

  let filePath = path.join(WEB_ROOT, rel);
  if (!filePath.startsWith(WEB_ROOT + path.sep)) return notFound(req, res);

  let ext = path.extname(filePath).toLowerCase();
  if (!ext) {
    // Clean URLs: /support -> support.html
    filePath += ".html";
    ext = ".html";
  }
  const type = MIME[ext];
  if (!type) return notFound(req, res);

  let stat;
  try {
    stat = await fsp.stat(filePath);
  } catch {
    return notFound(req, res);
  }
  if (!stat.isFile()) return notFound(req, res);

  if (ext === ".html") {
    return sendHtml(req, res, filePath, 200);
  }

  const etag = `W/"${stat.size.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`;
  res.setHeader("Content-Type", type);
  res.setHeader("Cache-Control", CACHE_RULES[ext] || DEFAULT_CACHE);
  res.setHeader("ETag", etag);
  res.setHeader("Last-Modified", stat.mtime.toUTCString());

  if (req.headers["if-none-match"] === etag) {
    res.statusCode = 304;
    return res.end();
  }

  res.setHeader("Content-Length", stat.size);
  if (req.method === "HEAD") return res.end();

  const stream = fs.createReadStream(filePath);
  stream.on("error", (err) => {
    console.error("[static]", err.message);
    res.destroy();
  });
  stream.pipe(res);
}

async function sendHtml(req, res, filePath, status) {
  const raw = await fsp.readFile(filePath, "utf8");
  const html = raw.replace(/__SITE_URL__/g, siteUrlFor(req));
  const body = Buffer.from(html, "utf8");
  const etag = `"${crypto.createHash("sha1").update(body).digest("base64url")}"`;

  res.setHeader("Content-Type", MIME[".html"]);
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("ETag", etag);

  if (status === 200 && req.headers["if-none-match"] === etag) {
    res.statusCode = 304;
    return res.end();
  }
  res.statusCode = status;
  res.setHeader("Content-Length", body.length);
  res.end(req.method === "HEAD" ? undefined : body);
}

function notFound(req, res) {
  const page = path.join(WEB_ROOT, "404.html");
  if (fs.existsSync(page)) return sendHtml(req, res, page, 404);
  return sendText(res, 404, "Not found");
}

/** Absolute site URL used for canonical/Open Graph tags. */
function siteUrlFor(req) {
  if (config.siteUrl) return config.siteUrl;
  const fwdHost = config.trustProxy ? firstHeader(req.headers["x-forwarded-host"]) : "";
  const host = fwdHost || firstHeader(req.headers.host);
  if (!host || !/^[a-z0-9.\-]+(:\d+)?$/i.test(host)) return "";
  const fwdProto = config.trustProxy ? firstHeader(req.headers["x-forwarded-proto"]).toLowerCase() : "";
  const proto = fwdProto === "https" || req.socket.encrypted ? "https" : "http";
  return `${proto}://${host}`;
}

function sendRobots(req, res) {
  const site = siteUrlFor(req);
  const lines = ["User-agent: *", "Allow: /", "Disallow: /api/"];
  if (site) lines.push(`Sitemap: ${site}/sitemap.xml`);
  res.setHeader("Cache-Control", "public, max-age=3600");
  sendText(res, 200, lines.join("\n") + "\n");
}

function sendSitemap(req, res) {
  const site = siteUrlFor(req);
  if (!site) return notFound(req, res);
  const urls = ["/", "/support"]
    .map((p) => `  <url><loc>${site}${p}</loc></url>`)
    .join("\n");
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`;
  res.setHeader("Content-Type", MIME[".xml"]);
  res.setHeader("Cache-Control", "public, max-age=3600");
  res.end(req.method === "HEAD" ? undefined : xml);
}

/* ------------------------------------------------------------------ */
/* Live data: Minecraft server, Discord, YouTube                       */
/* ------------------------------------------------------------------ */

const cache = new Map();

/** Cache a loader's result; on failure keep serving the last good value and retry soon. */
function cached(key, ttlMs, loader) {
  let entry = cache.get(key);
  if (entry && entry.expires > Date.now()) return Promise.resolve(entry.value);
  if (entry && entry.pending) return entry.pending;
  if (!entry) {
    entry = { value: null, expires: 0, pending: null };
    cache.set(key, entry);
  }
  entry.pending = loader()
    .then((value) => {
      entry.value = value;
      entry.expires = Date.now() + ttlMs;
      return value;
    })
    .catch((err) => {
      console.warn(`[${key}] ${err.message}`);
      entry.expires = Date.now() + Math.min(ttlMs, 30_000);
      return entry.value;
    })
    .finally(() => {
      entry.pending = null;
    });
  return entry.pending;
}

async function fetchOk(url, timeoutMs = 6000) {
  const res = await fetch(url, {
    headers: { "User-Agent": USER_AGENT, Accept: "application/json, application/xml;q=0.9, */*;q=0.5" },
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error(`${new URL(url).host} responded ${res.status}`);
  return res;
}

function count(n) {
  return Number.isFinite(n) && n >= 0 ? Math.floor(n) : null;
}

async function loadMinecraftStatus() {
  const minehut = config.mcAddress.match(/^([a-z0-9_-]+)\.minehut\.(?:gg|com)$/);
  if (minehut) {
    try {
      const res = await fetchOk(`https://api.minehut.com/server/${encodeURIComponent(minehut[1])}?byName=true`);
      const server = (await res.json()).server;
      if (server && typeof server.online === "boolean") {
        return { online: server.online, players: count(server.playerCount), max: count(server.maxPlayers) };
      }
    } catch (err) {
      console.warn(`[minehut] ${err.message}; falling back to mcsrvstat.us`);
    }
  }
  const res = await fetchOk(`https://api.mcsrvstat.us/3/${encodeURIComponent(config.mcAddress)}`);
  const data = await res.json();
  return {
    online: Boolean(data.online),
    players: count(data.players && data.players.online),
    max: count(data.players && data.players.max),
  };
}

async function loadDiscordStatus() {
  const res = await fetchOk(`https://discord.com/api/v10/invites/${encodeURIComponent(config.discordInvite)}?with_counts=true`);
  const data = await res.json();
  return {
    members: count(data.approximate_member_count),
    online: count(data.approximate_presence_count),
  };
}

function decodeXml(s) {
  return s
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

async function loadVideos() {
  const res = await fetchOk(`https://www.youtube.com/feeds/videos.xml?channel_id=${encodeURIComponent(config.youtubeChannelId)}`);
  const xml = await res.text();
  return xml
    .split("<entry>")
    .slice(1)
    .map((entry) => {
      const id = (entry.match(/<yt:videoId>([\w-]{11})<\/yt:videoId>/) || [])[1];
      const title = (entry.match(/<title>([\s\S]*?)<\/title>/) || [])[1];
      const published = (entry.match(/<published>([^<]+)<\/published>/) || [])[1];
      return id && title ? { id, title: decodeXml(title).trim().slice(0, 200), published: published || null } : null;
    })
    .filter(Boolean)
    .slice(0, 8);
}

/* ------------------------------------------------------------------ */
/* Forms                                                               */
/* ------------------------------------------------------------------ */

const MC_NAME = /^[\w.*\- ]{2,32}$/;

const FEEDBACK_CATEGORIES = {
  general: "General",
  battles: "Battles",
  vehicles: "Vehicles",
  suggestion: "Suggestion",
  bug: "Bug report",
  website: "Website",
  other: "Other",
};

const REPORT_REASONS = {
  cheating: "Cheating / hacks",
  griefing: "Griefing",
  harassment: "Harassment or hate",
  exploiting: "Bug exploiting",
  spam: "Spam or advertising",
  inappropriate: "Inappropriate content",
  other: "Other",
};

const REPORT_LOCATIONS = {
  unknown: "Not sure / other",
  "battles-ww1": "Battles: WW1",
  "battles-ww2": "Battles: WW2",
  "battles-modern": "Battles: Modern",
  "battles-other": "Battles: other mode",
  minigames: "Minigames",
  lobby: "Lobby / spawn",
  discord: "Discord server",
};

const has = (obj, key) => typeof key === "string" && Object.prototype.hasOwnProperty.call(obj, key);

/** Normalise user text: strip control/bidi characters, unify newlines, trim. */
function cleanText(value, { multiline = false } = {}) {
  if (typeof value !== "string") return "";
  let s = value.normalize("NFC").replace(/\r\n?/g, "\n").replace(/\t/g, " ");
  s = s.replace(/[\u0000-\u0009\u000B-\u001F\u007F-\u009F​-‏‪-‮⁦-⁩]/g, "");
  if (!multiline) s = s.replace(/\n/g, " ");
  else s = s.replace(/\n{3,}/g, "\n\n");
  return s.trim();
}

function validateFeedback(body) {
  const errors = {};
  const data = {
    minecraftName: cleanText(body.minecraftName),
    discordName: cleanText(body.discordName),
    category: has(FEEDBACK_CATEGORIES, body.category) ? body.category : "",
    rating: Number.isInteger(body.rating) && body.rating >= 1 && body.rating <= 5 ? body.rating : null,
    message: cleanText(body.message, { multiline: true }),
  };

  if (data.minecraftName && !MC_NAME.test(data.minecraftName)) errors.minecraftName = "That doesn't look like a Minecraft username.";
  if (data.discordName.length > 40) errors.discordName = "Discord usernames are 40 characters or fewer.";
  if (!data.category) errors.category = "Pick a topic.";
  if (data.message.length < 10) errors.message = "Please write at least 10 characters.";
  else if (data.message.length > 2000) errors.message = "Please keep it under 2000 characters.";

  return { data, errors };
}

function validateReport(body) {
  const errors = {};
  const rawEvidence = Array.isArray(body.evidence) ? body.evidence : typeof body.evidence === "string" ? body.evidence.split(/\s+/) : [];
  const evidence = [];
  let badEvidence = rawEvidence.length > 5;
  for (const item of rawEvidence.slice(0, 5)) {
    const s = cleanText(item);
    if (!s) continue;
    try {
      const u = new URL(s);
      if ((u.protocol !== "https:" && u.protocol !== "http:") || s.length > 500) throw new Error("bad url");
      evidence.push(u.href);
    } catch {
      badEvidence = true;
    }
  }

  const occurredAt = cleanText(body.occurredAt);
  const timezone = cleanText(body.timezone);

  const data = {
    reporterName: cleanText(body.reporterName),
    discordName: cleanText(body.discordName),
    reportedName: cleanText(body.reportedName),
    reason: has(REPORT_REASONS, body.reason) ? body.reason : "",
    location: has(REPORT_LOCATIONS, body.location) ? body.location : "unknown",
    occurredAt: /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(occurredAt) ? occurredAt : "",
    timezone: /^[A-Za-z0-9_+\-/]{1,64}$/.test(timezone) ? timezone : "",
    description: cleanText(body.description, { multiline: true }),
    evidence,
  };

  if (!data.reporterName) errors.reporterName = "Enter your Minecraft username.";
  else if (!MC_NAME.test(data.reporterName)) errors.reporterName = "That doesn't look like a Minecraft username.";
  if (data.discordName.length > 40) errors.discordName = "Discord usernames are 40 characters or fewer.";
  if (!data.reportedName) errors.reportedName = "Enter the username of the player you're reporting.";
  else if (!MC_NAME.test(data.reportedName)) errors.reportedName = "That doesn't look like a Minecraft username.";
  if (!data.reason) errors.reason = "Choose a reason.";
  if (data.description.length < 20) errors.description = "Please describe what happened (at least 20 characters).";
  else if (data.description.length > 2000) errors.description = "Please keep it under 2000 characters.";
  if (badEvidence) errors.evidence = "Add up to 5 links, each starting with http:// or https://";
  if (body.confirm !== true) errors.confirm = "Please confirm your report is honest.";

  return { data, errors };
}

/** The hidden honeypot field is only ever filled in by bots. */
function filledHoneypot(body) {
  return typeof body.website === "string" && body.website.trim() !== "";
}

/** Submitted faster than a person could fill the form in. Kept on disk, but not posted to Discord. */
function submittedTooFast(body) {
  return !(Number.isFinite(body.elapsedMs) && body.elapsedMs >= 2000);
}

function makeId(prefix) {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let id = "";
  for (let i = 0; i < 6; i++) id += alphabet[crypto.randomInt(alphabet.length)];
  return `${prefix}-${id}`;
}

function hashIp(ip) {
  return crypto.createHash("sha256").update(`${config.ipSalt}:${ip}`).digest("hex").slice(0, 16);
}

async function saveRecord(type, record) {
  try {
    await fsp.mkdir(config.dataDir, { recursive: true });
    const file = path.join(config.dataDir, type === "report" ? "reports.jsonl" : "feedback.jsonl");
    await fsp.appendFile(file, JSON.stringify(record) + "\n", { mode: 0o600 });
    return true;
  } catch (err) {
    console.error(`[save] could not write ${type}: ${err.message}`);
    return false;
  }
}

/* ---------- Discord webhook ---------- */

function escapeMd(value) {
  return String(value).replace(/([\\*_`~|<>[\]()#@:-])/g, "\\$1");
}

function truncate(value, max) {
  const s = String(value);
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
}

function embedField(name, value, inline = true) {
  return { name, value: truncate(value || "—", 1024), inline };
}

function buildEmbed(type, r) {
  if (type === "feedback") {
    return {
      title: `\u{1F4AC} New feedback · ${r.id}`,
      color: 0x66d95b,
      description: truncate(escapeMd(r.message), 4000),
      fields: [
        embedField("Topic", FEEDBACK_CATEGORIES[r.category]),
        embedField("Rating", r.rating ? "★".repeat(r.rating) + "☆".repeat(5 - r.rating) : "Not rated"),
        embedField("Minecraft", r.minecraftName ? escapeMd(r.minecraftName) : "Not given"),
        embedField("Discord", r.discordName ? escapeMd(r.discordName) : "Not given"),
      ],
      footer: { text: "Moddedrealms website · feedback" },
      timestamp: r.createdAt,
    };
  }
  const when = r.occurredAt ? `${r.occurredAt.replace("T", " ")}${r.timezone ? ` (${r.timezone})` : ""}` : "Not specified";
  return {
    title: `\u{1F6A9} Player report · ${r.id}`,
    color: 0xff5a4e,
    description: truncate(escapeMd(r.description), 4000),
    fields: [
      embedField("Reported player", `**${escapeMd(r.reportedName)}**`),
      embedField("Reason", REPORT_REASONS[r.reason]),
      embedField("Where", REPORT_LOCATIONS[r.location]),
      embedField("Reported by", escapeMd(r.reporterName)),
      embedField("Reporter's Discord", r.discordName ? escapeMd(r.discordName) : "Not given"),
      embedField("When", escapeMd(when)),
      embedField("Evidence", r.evidence.length ? r.evidence.join("\n") : "None provided", false),
    ],
    footer: { text: "Moddedrealms website · player report" },
    timestamp: r.createdAt,
  };
}

async function sendToDiscord(type, record) {
  const webhook = type === "report" ? config.reportWebhook : config.feedbackWebhook;
  if (!webhook) return false;

  const payload = {
    username: "Moddedrealms Website",
    embeds: [buildEmbed(type, record)],
    // Never let user text ping @everyone, @here, users or roles.
    allowed_mentions: { parse: [] },
  };
  if (config.siteUrl) payload.avatar_url = `${config.siteUrl}/image/web/icon-192.png`;
  if (type === "report" && /^\d{5,25}$/.test(config.reportPingRoleId)) {
    payload.content = `<@&${config.reportPingRoleId}> New player report`;
    payload.allowed_mentions.roles = [config.reportPingRoleId];
  }

  try {
    const url = new URL(webhook);
    url.searchParams.set("wait", "true");
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", "User-Agent": USER_AGENT },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      console.error(`[discord] webhook responded ${res.status}: ${text.slice(0, 300)}`);
      return false;
    }
    return true;
  } catch (err) {
    console.error(`[discord] webhook failed: ${err.message}`);
    return false;
  }
}

/* ---------- Rate limiting ---------- */

const RATE_WINDOW_MS = 15 * 60 * 1000;
const submissions = new Map();

function recentSubmissions(ip) {
  const now = Date.now();
  const list = (submissions.get(ip) || []).filter((t) => now - t < RATE_WINDOW_MS);
  if (list.length) submissions.set(ip, list);
  else submissions.delete(ip);
  return list;
}

function recordSubmission(ip) {
  const list = recentSubmissions(ip);
  list.push(Date.now());
  submissions.set(ip, list);
}

setInterval(() => {
  for (const ip of submissions.keys()) recentSubmissions(ip);
}, RATE_WINDOW_MS).unref();

function clientIp(req) {
  if (config.trustProxy) {
    // The reverse proxy appends the address it saw, so the last entry is the trustworthy one.
    const forwarded = String(req.headers["x-forwarded-for"] || "")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    if (forwarded.length) return forwarded[forwarded.length - 1];
  }
  return req.socket.remoteAddress || "unknown";
}

function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  let originHost;
  try {
    originHost = new URL(origin).host.toLowerCase();
  } catch {
    return false;
  }
  const allowed = new Set([firstHeader(req.headers.host).toLowerCase()]);
  if (config.trustProxy && req.headers["x-forwarded-host"]) allowed.add(firstHeader(req.headers["x-forwarded-host"]).toLowerCase());
  if (config.siteUrl) {
    try {
      allowed.add(new URL(config.siteUrl).host.toLowerCase());
    } catch {
      /* ignore bad SITE_URL */
    }
  }
  return allowed.has(originHost);
}

function readJsonBody(req, limit = 32 * 1024) {
  return new Promise((resolve, reject) => {
    if (!/^application\/json\b/i.test(String(req.headers["content-type"] || ""))) {
      return reject(httpError(415, "Expected a JSON body."));
    }
    if (Number(req.headers["content-length"] || 0) > limit) {
      return reject(httpError(413, "That submission is too large."));
    }
    const chunks = [];
    let size = 0;
    let done = false;
    req.on("data", (chunk) => {
      if (done) return;
      size += chunk.length;
      if (size > limit) {
        done = true;
        return reject(httpError(413, "That submission is too large."));
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      if (done) return;
      done = true;
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"));
      } catch {
        reject(httpError(400, "Invalid JSON."));
      }
    });
    req.on("error", (err) => {
      if (!done) {
        done = true;
        reject(err);
      }
    });
  });
}

async function handleSubmission(req, res, type) {
  if (!sameOrigin(req)) return sendJson(res, 403, { ok: false, error: "Cross-site submissions are not allowed." });

  const ip = clientIp(req);
  if (recentSubmissions(ip).length >= config.submitLimit) {
    return sendJson(res, 429, { ok: false, error: "You're sending submissions too quickly. Please wait a few minutes and try again." });
  }

  let body;
  try {
    body = await readJsonBody(req);
  } catch (err) {
    if (err.status === 413) res.setHeader("Connection", "close");
    return sendJson(res, err.status || 400, { ok: false, error: err.status ? err.message : "Could not read your submission." });
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return sendJson(res, 400, { ok: false, error: "Invalid submission." });
  }

  const id = makeId(type === "report" ? "R" : "F");
  if (filledHoneypot(body)) {
    recordSubmission(ip);
    console.warn(`[${type}] dropped a bot submission (${hashIp(ip)})`);
    return sendJson(res, 201, { ok: true, id });
  }

  const { data, errors } = type === "report" ? validateReport(body) : validateFeedback(body);
  if (Object.keys(errors).length) {
    return sendJson(res, 422, { ok: false, error: "Please fix the highlighted fields.", fields: errors });
  }

  recordSubmission(ip);
  const tooFast = submittedTooFast(body);
  const record = {
    id,
    type,
    createdAt: new Date().toISOString(),
    ...data,
    ...(tooFast ? { flagged: "submitted unusually fast, possible bot" } : {}),
    ipHash: hashIp(ip),
    userAgent: cleanText(req.headers["user-agent"]).slice(0, 200),
  };

  const [saved, sent] = await Promise.all([saveRecord(type, record), tooFast ? false : sendToDiscord(type, record)]);
  console.log(`[${type}] ${id} saved=${saved} discord=${sent}${tooFast ? " (flagged: too fast)" : ""}`);
  if (!saved && !sent) {
    return sendJson(res, 502, { ok: false, error: "We couldn't save your submission right now. Please try again later." });
  }
  return sendJson(res, 201, { ok: true, id });
}

/* ------------------------------------------------------------------ */
/* Routing                                                             */
/* ------------------------------------------------------------------ */

async function handleApi(req, res, pathname) {
  res.setHeader("Cache-Control", "no-store");

  if (pathname === "/api/feedback" || pathname === "/api/report") {
    if (req.method !== "POST") return methodNotAllowed(res, "POST");
    return handleSubmission(req, res, pathname === "/api/report" ? "report" : "feedback");
  }

  if (pathname === "/api/status") {
    if (req.method !== "GET" && req.method !== "HEAD") return methodNotAllowed(res, "GET, HEAD");
    const [minecraft, discord] = await Promise.all([
      cached("minecraft", 60_000, loadMinecraftStatus),
      cached("discord", 5 * 60_000, loadDiscordStatus),
    ]);
    res.setHeader("Cache-Control", "public, max-age=30");
    return sendJson(res, 200, { address: config.mcAddress, minecraft, discord });
  }

  if (pathname === "/api/videos") {
    if (req.method !== "GET" && req.method !== "HEAD") return methodNotAllowed(res, "GET, HEAD");
    const videos = await cached("youtube", 30 * 60_000, loadVideos);
    res.setHeader("Cache-Control", "public, max-age=600");
    return sendJson(res, 200, { videos: videos || [] });
  }

  return sendJson(res, 404, { ok: false, error: "Not found." });
}

async function handle(req, res) {
  setSecurityHeaders(res);

  let pathname;
  try {
    pathname = new URL(req.url, "http://localhost").pathname;
  } catch {
    return sendText(res, 400, "Bad request");
  }

  if (pathname.startsWith("/api/")) return handleApi(req, res, pathname);
  if (pathname === "/healthz") return sendText(res, 200, "ok");

  if (req.method !== "GET" && req.method !== "HEAD") return methodNotAllowed(res, "GET, HEAD");

  if (pathname.length > 1 && pathname.endsWith("/")) {
    const query = req.url.slice(req.url.indexOf("?") >= 0 ? req.url.indexOf("?") : req.url.length);
    return redirect(res, 301, pathname.replace(/\/+$/, "") + query);
  }
  if (pathname === "/index.html") return redirect(res, 301, "/");
  if (pathname === "/robots.txt") return sendRobots(req, res);
  if (pathname === "/sitemap.xml") return sendSitemap(req, res);
  if (has(LINKS, pathname)) return redirect(res, 302, LINKS[pathname]);

  return serveStatic(req, res, pathname);
}

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.statusCode = status;
  res.setHeader("Content-Type", MIME[".json"]);
  res.setHeader("Content-Length", Buffer.byteLength(body));
  res.end(res.req && res.req.method === "HEAD" ? undefined : body);
}

function sendText(res, status, text) {
  res.statusCode = status;
  res.setHeader("Content-Type", MIME[".txt"]);
  res.setHeader("Content-Length", Buffer.byteLength(text));
  res.end(res.req && res.req.method === "HEAD" ? undefined : text);
}

function redirect(res, status, location) {
  res.statusCode = status;
  res.setHeader("Location", location);
  res.end();
}

function methodNotAllowed(res, allow) {
  res.setHeader("Allow", allow);
  return sendJson(res, 405, { ok: false, error: "Method not allowed." });
}

function httpError(status, message) {
  const err = new Error(message);
  err.status = status;
  return err;
}

function firstHeader(value) {
  return String(Array.isArray(value) ? value[0] : value || "")
    .split(",")[0]
    .trim();
}

function toInt(value, fallback) {
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
}

function isTrue(value) {
  return /^(1|true|yes|on)$/i.test(String(value || "").trim());
}

/** Minimal .env loader so the server needs no dependencies. Real env vars win; within the file the last value wins. */
function loadEnvFile(file) {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch {
    return;
  }
  const values = {};
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
    if (!m) continue;
    let value = m[2].trim();
    if (/^(["']).*\1$/.test(value)) value = value.slice(1, -1);
    else value = value.replace(/\s+#.*$/, "");
    values[m[1]] = value;
  }
  for (const [key, value] of Object.entries(values)) {
    if (process.env[key] === undefined) process.env[key] = value;
  }
}

/* ------------------------------------------------------------------ */
/* Start                                                               */
/* ------------------------------------------------------------------ */

const server = http.createServer((req, res) => {
  handle(req, res).catch((err) => {
    console.error("[error]", err);
    if (!res.headersSent) sendJson(res, 500, { ok: false, error: "Internal server error." });
    else res.destroy();
  });
});

server.headersTimeout = 20_000;
server.requestTimeout = 30_000;
server.keepAliveTimeout = 65_000;

for (const [name, url] of [
  ["DISCORD_FEEDBACK_WEBHOOK_URL", config.feedbackWebhook],
  ["DISCORD_REPORT_WEBHOOK_URL", config.reportWebhook],
]) {
  if (url && !/^https:\/\/(?:(?:canary|ptb)\.)?discord(?:app)?\.com\/api\/webhooks\//.test(url)) {
    console.warn(`[config] ${name} doesn't look like a Discord webhook URL.`);
  }
}

server.listen(config.port, config.host, () => {
  console.log(`Moddedrealms website running on http://${config.host === "0.0.0.0" ? "localhost" : config.host}:${config.port}`);
  console.log(`  Site URL:        ${config.siteUrl || "(auto from request)"}`);
  console.log(`  Submissions dir: ${config.dataDir}`);
  console.log(`  Discord webhook: feedback=${config.feedbackWebhook ? "on" : "off"}, reports=${config.reportWebhook ? "on" : "off"}`);
  if (!config.feedbackWebhook || !config.reportWebhook) {
    console.log("  Tip: set DISCORD_WEBHOOK_URL in .env to get submissions in a Discord channel.");
  }
});

function shutdown(signal) {
  console.log(`${signal} received, shutting down...`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 5000).unref();
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
