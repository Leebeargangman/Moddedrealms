#!/usr/bin/env node
/*
 * Moddedrealms website server
 *
 * - Serves the static site in ../website
 * - Feedback and player report forms (forms.js)
 * - Optional player/staff accounts linked to Minecraft via the Moddedrealms Link plugin (account-routes.js)
 * - Live data for the homepage (GET /api/status, /api/videos) with caching
 *
 * No npm packages. Requires Node.js 22.13 or newer (for the built-in SQLite database).
 * Configure with environment variables or a .env file in the project root (see .env.example).
 */
"use strict";

const http = require("node:http");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const crypto = require("node:crypto");

const { config } = require("./config");
require("./db");
const { handleSubmission } = require("./forms");
const { handleAccountApi, verifyEmailLink } = require("./account-routes");
const { handleBotApi } = require("./bot-hub");
const { mailEnabled } = require("./mailer");
const { sendJson, sendText, redirect, methodNotAllowed, siteUrlFor, has } = require("./http");

const WEB_ROOT = config.webRoot;

const LINKS = {
  "/discord": `https://discord.gg/${config.discordInvite}`,
  "/youtube": "https://www.youtube.com/@Moddedrealms",
  "/modrinth": "https://modrinth.com/mod/ultimate-vehicle-experience",
  "/feedback": "/support#feedback",
  "/report": "/support#report",
  "/login": "/signin",
  "/staff": "/admin",
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

// Pages with private, per-user content: never cache them and keep them out of search engines.
const PRIVATE_PAGES = new Set(["signin.html", "account.html", "admin.html", "bots.html", "reset.html"]);

const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: https://i.ytimg.com https://mc-heads.net",
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
  const isPrivate = PRIVATE_PAGES.has(path.basename(filePath));

  res.setHeader("Content-Type", MIME[".html"]);
  res.setHeader("Cache-Control", isPrivate ? "no-store" : "no-cache");
  if (isPrivate) res.setHeader("X-Robots-Tag", "noindex");
  else res.setHeader("ETag", etag);

  if (status === 200 && !isPrivate && req.headers["if-none-match"] === etag) {
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

function sendRobots(req, res) {
  const site = siteUrlFor(req);
  const lines = ["User-agent: *", "Allow: /", "Disallow: /api/", "Disallow: /account", "Disallow: /admin", "Disallow: /bots", "Disallow: /reset"];
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

/** Minehut's own API: live player count, and whether the server is up (its proxy answers pings even when it's asleep). */
async function loadMinehutState(name) {
  const res = await fetchOk(`https://api.minehut.com/server/${encodeURIComponent(name)}?byName=true`);
  const server = (await res.json()).server;
  if (!server || typeof server.online !== "boolean") throw new Error("unexpected Minehut response");
  return { online: server.online, players: count(server.playerCount) };
}

/** The server-list ping: the same player count and max that players see in Minecraft. */
async function loadPing() {
  const res = await fetchOk(`https://api.mcsrvstat.us/3/${encodeURIComponent(config.mcAddress)}`);
  const data = await res.json();
  return {
    online: Boolean(data.online),
    players: count(data.players && data.players.online),
    max: count(data.players && data.players.max),
  };
}

async function loadMinecraftStatus() {
  const minehut = config.mcAddress.match(/^([a-z0-9_-]+)\.minehut\.(?:gg|com)$/);
  const [hutResult, pingResult] = await Promise.allSettled([minehut ? loadMinehutState(minehut[1]) : Promise.resolve(null), loadPing()]);
  const hut = hutResult.status === "fulfilled" ? hutResult.value : null;
  const ping = pingResult.status === "fulfilled" ? pingResult.value : null;
  if (!hut && !ping) throw new Error("no Minecraft status source answered");

  // Minehut's player count is live; the ping service caches for up to 5 minutes, so it's only
  // used for the player count when Minehut didn't answer. The max always comes from the ping.
  const online = hut ? hut.online : ping.online;
  let players = null;
  let max = null;
  if (online) {
    players = hut && hut.players !== null ? hut.players : ping && ping.online ? ping.players : null;
    max = ping && ping.online ? ping.max : null;
  }
  if (config.mcMaxPlayers > 0) max = config.mcMaxPlayers;
  return { online, players, max };
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
/* Routing                                                             */
/* ------------------------------------------------------------------ */

async function handleApi(req, res, pathname, url) {
  res.setHeader("Cache-Control", "no-store");

  if (pathname === "/api/feedback" || pathname === "/api/report") {
    if (req.method !== "POST") return methodNotAllowed(res, "POST");
    return handleSubmission(req, res, pathname === "/api/report" ? "report" : "feedback");
  }

  if (pathname === "/api/status") {
    if (req.method !== "GET" && req.method !== "HEAD") return methodNotAllowed(res, "GET, HEAD");
    const [minecraft, discord] = await Promise.all([
      cached("minecraft", 30_000, loadMinecraftStatus),
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

  if (await handleAccountApi(req, res, pathname, url)) return;
  if (await handleBotApi(req, res, pathname, url)) return;

  return sendJson(res, 404, { ok: false, error: "Not found." });
}

async function handle(req, res) {
  setSecurityHeaders(res);

  let url;
  try {
    url = new URL(req.url, "http://localhost");
  } catch {
    return sendText(res, 400, "Bad request");
  }
  const pathname = url.pathname;

  if (pathname.startsWith("/api/")) return handleApi(req, res, pathname, url);
  if (pathname === "/healthz") return sendText(res, 200, "ok");

  if (req.method !== "GET" && req.method !== "HEAD") return methodNotAllowed(res, "GET, HEAD");

  if (pathname.length > 1 && pathname.endsWith("/")) {
    // Collapse leading slashes too: "//evil.com" would be a redirect to another site.
    return redirect(res, 301, "/" + pathname.replace(/^\/+|\/+$/g, "") + url.search);
  }
  if (pathname === "/index.html") return redirect(res, 301, "/");
  if (pathname === "/robots.txt") return sendRobots(req, res);
  if (pathname === "/sitemap.xml") return sendSitemap(req, res);
  if (pathname === "/verify-email") {
    res.setHeader("Cache-Control", "no-store");
    return verifyEmailLink(req, res, url);
  }
  if (has(LINKS, pathname)) return redirect(res, 302, LINKS[pathname] + (pathname === "/login" ? url.search : ""));

  return serveStatic(req, res, pathname);
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
  console.log(`  Data folder:     ${config.dataDir}`);
  console.log(`  Discord webhook: feedback=${config.feedbackWebhook ? "on" : "off"}, reports=${config.reportWebhook ? "on" : "off"}`);
  console.log(`  Account linking: ${config.pluginApiKey ? "on" : "off (set PLUGIN_API_KEY)"}`);
  console.log(`  Email:           ${mailEnabled() ? `on (${config.smtp.host})` : "off (set SMTP_HOST, SMTP_FROM and SITE_URL)"}`);
});

function shutdown(signal) {
  console.log(`${signal} received, shutting down...`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 5000).unref();
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
