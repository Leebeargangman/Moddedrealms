/*
 * Settings for the Moddedrealms website, read from environment variables or a .env file
 * in the project root (see .env.example). Real environment variables win over .env.
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const ROOT = path.resolve(__dirname, "..");

loadEnvFile(path.join(ROOT, ".env"));

const env = process.env;

const config = {
  root: ROOT,
  webRoot: path.join(ROOT, "website"),
  host: env.HOST || "0.0.0.0",
  port: toInt(env.PORT, 3000),
  siteUrl: (env.SITE_URL || "").trim().replace(/\/+$/, ""),
  trustProxy: isTrue(env.TRUST_PROXY),
  dataDir: path.resolve(ROOT, env.DATA_DIR || "data"),

  // Discord
  feedbackWebhook: (env.DISCORD_FEEDBACK_WEBHOOK_URL || env.DISCORD_WEBHOOK_URL || "").trim(),
  reportWebhook: (env.DISCORD_REPORT_WEBHOOK_URL || env.DISCORD_WEBHOOK_URL || "").trim(),
  reportPingRoleId: (env.DISCORD_REPORT_PING_ROLE_ID || "").trim(),
  discordInvite: (env.DISCORD_INVITE_CODE || "AA3a3H3ECk").trim(),

  // Minecraft server + live data
  mcAddress: (env.MC_SERVER_ADDRESS || "moddedrealms.minehut.gg").trim().toLowerCase(),
  mcMaxPlayers: toInt(env.MC_MAX_PLAYERS, 0),
  youtubeChannelId: (env.YOUTUBE_CHANNEL_ID || "UC408m1zoIwbW5JXeOkqSlxA").trim(),

  // Forms
  ipSalt: env.IP_HASH_SALT || crypto.randomBytes(16).toString("hex"),
  submitLimit: toInt(env.SUBMIT_RATE_LIMIT, 5),

  // Accounts
  pluginApiKey: (env.PLUGIN_API_KEY || "").trim(),
  staffRanks: listSet(env.STAFF_RANKS || "owner,admin,administrator,manager,moderator,mod,helper,staff"),

  // Email (optional)
  smtp: {
    host: (env.SMTP_HOST || "").trim(),
    port: toInt(env.SMTP_PORT, 587),
    secure: env.SMTP_SECURE ? isTrue(env.SMTP_SECURE) : toInt(env.SMTP_PORT, 587) === 465,
    user: (env.SMTP_USER || "").trim(),
    pass: env.SMTP_PASS || "",
    from: (env.SMTP_FROM || env.SMTP_USER || "").trim(),
  },
};

function listSet(value) {
  return new Set(
    String(value)
      .split(",")
      .map((s) => s.trim().toLowerCase())
      .filter(Boolean)
  );
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

module.exports = { config, toInt, isTrue };
