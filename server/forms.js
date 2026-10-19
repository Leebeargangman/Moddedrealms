/*
 * Feedback and player report forms (POST /api/feedback, /api/report).
 * Submissions are stored in the database, appended to data/*.jsonl as a backup, and posted
 * to a Discord webhook if one is set. Signed-in players with a linked Minecraft account are
 * recorded as verified, and can follow their submissions from their account page.
 */
"use strict";

const fsp = require("node:fs/promises");
const path = require("node:path");
const crypto = require("node:crypto");
const { config } = require("./config");
const { db, nowIso } = require("./db");
const accounts = require("./accounts");
const { sendJson, jsonBodyOrError, clientIp, sameOrigin, cleanText, has } = require("./http");

const USER_AGENT = `ModdedrealmsWebsite/1.0 (+${config.siteUrl || "https://github.com/leebeargangman/moddedrealms"})`;

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

const REPORT_STATUSES = {
  open: "Open",
  investigating: "Investigating",
  resolved: "Resolved: action taken",
  dismissed: "Closed: no action",
};

const FEEDBACK_STATUSES = {
  new: "New",
  reviewed: "Reviewed",
  planned: "Planned",
  done: "Done",
  declined: "Declined",
};

/* ---------- Validation ---------- */

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

/** Submitted faster than a person could fill the form in. Kept, but not posted to Discord. */
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

/* ---------- Storage ---------- */

const insertReport = db.prepare(`
  INSERT INTO reports (id, created_at, updated_at, reporter_user_id, reporter_name, reporter_verified, discord_name,
    reported_name, reported_uuid, reason, location, occurred_at, timezone, description, evidence, flagged, ip_hash, user_agent)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);

const insertFeedback = db.prepare(`
  INSERT INTO feedback (id, created_at, updated_at, user_id, minecraft_name, discord_name, category, rating, message, flagged, ip_hash, user_agent)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);

const linkedUserByName = db.prepare("SELECT mc_uuid FROM users WHERE mc_name = ? AND mc_uuid IS NOT NULL");
const setReportedUuid = db.prepare("UPDATE reports SET reported_uuid = ? WHERE id = ? AND reported_uuid IS NULL");

function saveToDatabase(type, r) {
  try {
    if (type === "report") {
      insertReport.run(
        r.id, r.createdAt, r.createdAt, r.userId, r.reporterName, r.reporterVerified ? 1 : 0, r.discordName || null,
        r.reportedName, r.reportedUuid || null, r.reason, r.location, r.occurredAt || null, r.timezone || null,
        r.description, JSON.stringify(r.evidence), r.flagged || null, r.ipHash, r.userAgent
      );
    } else {
      insertFeedback.run(
        r.id, r.createdAt, r.createdAt, r.userId, r.minecraftName || null, r.discordName || null, r.category,
        r.rating, r.message, r.flagged || null, r.ipHash, r.userAgent
      );
    }
    return true;
  } catch (err) {
    console.error(`[db] could not save ${type} ${r.id}: ${err.message}`);
    return false;
  }
}

/** Append-only backup log, kept even if the database is lost. */
async function appendBackup(type, record) {
  try {
    await fsp.mkdir(config.dataDir, { recursive: true });
    const file = path.join(config.dataDir, type === "report" ? "reports.jsonl" : "feedback.jsonl");
    await fsp.appendFile(file, JSON.stringify(record) + "\n", { mode: 0o600 });
  } catch (err) {
    console.error(`[backup] could not write ${type}: ${err.message}`);
  }
}

/**
 * Work out the reported player's UUID so the report still reaches them after a name change:
 * first from a linked website account, otherwise from Mojang. Best effort only.
 */
async function resolveReportedUuid(reportId, name) {
  const linked = linkedUserByName.get(name);
  let uuid = linked && linked.mc_uuid;
  if (!uuid && /^\w{3,16}$/.test(name)) {
    try {
      const res = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(name)}`, {
        headers: { "User-Agent": USER_AGENT },
        signal: AbortSignal.timeout(6000),
      });
      if (res.ok) {
        const data = await res.json();
        if (/^[0-9a-f]{32}$/i.test(data.id || "")) {
          uuid = data.id.replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, "$1-$2-$3-$4-$5").toLowerCase();
        }
      }
    } catch {
      /* Mojang unavailable; the name is still used for matching */
    }
  }
  if (uuid) setReportedUuid.run(uuid, reportId);
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
        embedField("Minecraft", r.minecraftName ? escapeMd(r.minecraftName) + (r.verified ? " ✅" : "") : "Not given"),
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
      embedField("Reported by", escapeMd(r.reporterName) + (r.reporterVerified ? " ✅ verified" : "")),
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

/* ---------- Handler ---------- */

async function handleSubmission(req, res, type) {
  if (!sameOrigin(req)) return sendJson(res, 403, { ok: false, error: "Cross-site submissions are not allowed." });

  const ip = clientIp(req);
  const rateKey = `submit:${ip}`;
  if (accounts.limited(rateKey, config.submitLimit, RATE_WINDOW_MS)) {
    return sendJson(res, 429, { ok: false, error: "You're sending submissions too quickly. Please wait a few minutes and try again." });
  }

  const body = await jsonBodyOrError(req, res);
  if (!body) return;

  const id = makeId(type === "report" ? "R" : "F");
  if (filledHoneypot(body)) {
    accounts.hit(rateKey);
    console.warn(`[${type}] dropped a bot submission (${hashIp(ip)})`);
    return sendJson(res, 201, { ok: true, id });
  }

  // A signed-in player with a linked Minecraft account submits under their verified name.
  const user = accounts.currentUser(req);
  const linkedName = user && user.mc_uuid ? user.mc_name : "";
  if (linkedName) {
    if (type === "report") body.reporterName = linkedName;
    else body.minecraftName = linkedName;
  }

  const { data, errors } = type === "report" ? validateReport(body) : validateFeedback(body);
  if (Object.keys(errors).length) {
    return sendJson(res, 422, { ok: false, error: "Please fix the highlighted fields.", fields: errors });
  }

  accounts.hit(rateKey);
  const tooFast = submittedTooFast(body);
  const record = {
    id,
    type,
    createdAt: nowIso(),
    ...data,
    userId: user ? user.id : null,
    ...(type === "report" ? { reporterVerified: Boolean(linkedName) } : { verified: Boolean(linkedName) }),
    ...(tooFast ? { flagged: "submitted unusually fast, possible bot" } : {}),
    ipHash: hashIp(ip),
    userAgent: cleanText(req.headers["user-agent"]).slice(0, 200),
  };

  const saved = saveToDatabase(type, record);
  appendBackup(type, record);
  const sent = tooFast ? false : await sendToDiscord(type, record);
  console.log(`[${type}] ${id} saved=${saved} discord=${sent}${tooFast ? " (flagged: too fast)" : ""}`);
  if (!saved && !sent) {
    return sendJson(res, 502, { ok: false, error: "We couldn't save your submission right now. Please try again later." });
  }
  if (type === "report" && saved) resolveReportedUuid(id, record.reportedName).catch(() => {});
  return sendJson(res, 201, { ok: true, id, tracked: Boolean(user) });
}

module.exports = {
  handleSubmission,
  FEEDBACK_CATEGORIES,
  REPORT_REASONS,
  REPORT_LOCATIONS,
  REPORT_STATUSES,
  FEEDBACK_STATUSES,
  MC_NAME,
};
