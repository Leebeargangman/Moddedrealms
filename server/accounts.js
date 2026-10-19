/*
 * Website accounts: passwords, sessions, single-use tokens and staff checks.
 */
"use strict";

const crypto = require("node:crypto");
const { config } = require("./config");
const { db, nowIso } = require("./db");

const SESSION_COOKIE = "mr_session";
const SESSION_DAYS = 30;

const USERNAME = /^[A-Za-z0-9_.-]{3,24}$/;
const EMAIL = /^[^\s@<>()[\]\\,;:"]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,}$/;
const MC_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const sha256 = (value) => crypto.createHash("sha256").update(value).digest("hex");

/* ---------- Passwords (scrypt) ---------- */

const SCRYPT = { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };

function scrypt(password, salt, length) {
  return new Promise((resolve, reject) => {
    crypto.scrypt(password, salt, length, SCRYPT, (err, key) => (err ? reject(err) : resolve(key)));
  });
}

async function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const key = await scrypt(password, salt, 64);
  return `scrypt$${salt.toString("base64")}$${key.toString("base64")}`;
}

async function verifyPassword(password, stored) {
  const [scheme, saltB64, keyB64] = String(stored).split("$");
  if (scheme !== "scrypt" || !saltB64 || !keyB64) return false;
  const expected = Buffer.from(keyB64, "base64");
  const actual = await scrypt(password, Buffer.from(saltB64, "base64"), expected.length);
  return crypto.timingSafeEqual(actual, expected);
}

// Compared against when a username doesn't exist, so login takes the same time either way.
const DUMMY_HASH = (() => {
  const salt = crypto.randomBytes(16);
  return `scrypt$${salt.toString("base64")}$${crypto.scryptSync("dummy", salt, 64, SCRYPT).toString("base64")}`;
})();

function passwordProblem(password) {
  if (typeof password !== "string" || password.length < 8) return "Use at least 8 characters.";
  if (password.length > 200) return "That password is too long.";
  return "";
}

/* ---------- Users ---------- */

const q = {
  userById: db.prepare("SELECT * FROM users WHERE id = ?"),
  userByName: db.prepare("SELECT * FROM users WHERE username = ?"),
  userByVerifiedEmail: db.prepare("SELECT * FROM users WHERE email = ? AND email_verified = 1"),
  userByUuid: db.prepare("SELECT * FROM users WHERE mc_uuid = ?"),
  insertUser: db.prepare("INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)"),
  touchLogin: db.prepare("UPDATE users SET last_login_at = ? WHERE id = ?"),
  insertSession: db.prepare("INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)"),
  sessionUser: db.prepare(
    "SELECT users.* FROM sessions JOIN users ON users.id = sessions.user_id WHERE sessions.token_hash = ? AND sessions.expires_at > ?"
  ),
  deleteSession: db.prepare("DELETE FROM sessions WHERE token_hash = ?"),
  deleteUserSessions: db.prepare("DELETE FROM sessions WHERE user_id = ?"),
  deleteOtherSessions: db.prepare("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?"),
  insertToken: db.prepare("INSERT INTO tokens (token_hash, kind, user_id, data, expires_at) VALUES (?, ?, ?, ?, ?)"),
  findToken: db.prepare("SELECT * FROM tokens WHERE token_hash = ? AND kind = ? AND expires_at > ?"),
  deleteToken: db.prepare("DELETE FROM tokens WHERE token_hash = ?"),
  deleteUserTokens: db.prepare("DELETE FROM tokens WHERE user_id = ? AND kind = ?"),
  cleanup: db.prepare("DELETE FROM sessions WHERE expires_at <= ?"),
  cleanupTokens: db.prepare("DELETE FROM tokens WHERE expires_at <= ?"),
};

setInterval(() => {
  const now = Date.now();
  q.cleanup.run(now);
  q.cleanupTokens.run(now);
}, 60 * 60 * 1000).unref();

async function createUser(username, password) {
  const info = q.insertUser.run(username, await hashPassword(password), nowIso());
  return q.userById.get(Number(info.lastInsertRowid));
}

function findUserByName(username) {
  return q.userByName.get(username);
}

function getUser(id) {
  return q.userById.get(id);
}

async function checkLogin(username, password) {
  const user = typeof username === "string" && username ? q.userByName.get(username) : null;
  const ok = await verifyPassword(String(password || ""), user ? user.password_hash : DUMMY_HASH);
  return ok && user ? user : null;
}

/**
 * Staff access comes from the in-game rank synced by the plugin (STAFF_RANKS),
 * or from the role set on the VPS with `node server/manage.js admin add <username>`.
 */
function isStaff(user) {
  if (!user) return false;
  if (user.role === "admin") return true;
  return Boolean(user.mc_uuid && user.mc_rank && config.staffRanks.has(String(user.mc_rank).toLowerCase()));
}

/** What the browser is allowed to see about the signed-in user. */
function publicUser(user) {
  if (!user) return null;
  return {
    username: user.username,
    staff: isStaff(user),
    email: user.email || "",
    emailVerified: Boolean(user.email_verified),
    emailNotify: Boolean(user.email_notify),
    minecraft: user.mc_uuid
      ? { uuid: user.mc_uuid, name: user.mc_name, rank: user.mc_rank || null, linkedAt: user.mc_linked_at, rankSyncedAt: user.rank_synced_at }
      : null,
    createdAt: user.created_at,
  };
}

/* ---------- Sessions ---------- */

function parseCookies(req) {
  const out = {};
  for (const part of String(req.headers.cookie || "").split(";")) {
    const idx = part.indexOf("=");
    if (idx < 0) continue;
    const key = part.slice(0, idx).trim();
    if (key) out[key] = part.slice(idx + 1).trim();
  }
  return out;
}

function sessionToken(req) {
  const token = parseCookies(req)[SESSION_COOKIE];
  return token && /^[A-Za-z0-9_-]{20,100}$/.test(token) ? token : "";
}

function currentUser(req) {
  if (req._user !== undefined) return req._user;
  const token = sessionToken(req);
  req._user = token ? q.sessionUser.get(sha256(token), Date.now()) || null : null;
  return req._user;
}

function cookieHeader(value, maxAgeSeconds, secure) {
  return [
    `${SESSION_COOKIE}=${value}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    `Max-Age=${maxAgeSeconds}`,
    secure ? "Secure" : "",
  ]
    .filter(Boolean)
    .join("; ");
}

function startSession(res, user, secure) {
  const token = crypto.randomBytes(32).toString("base64url");
  q.insertSession.run(sha256(token), user.id, nowIso(), Date.now() + SESSION_DAYS * 86400_000);
  q.touchLogin.run(nowIso(), user.id);
  res.setHeader("Set-Cookie", cookieHeader(token, SESSION_DAYS * 86400, secure));
}

function endSession(req, res, secure) {
  const token = sessionToken(req);
  if (token) q.deleteSession.run(sha256(token));
  res.setHeader("Set-Cookie", cookieHeader("", 0, secure));
}

function endOtherSessions(req, user) {
  const token = sessionToken(req);
  q.deleteOtherSessions.run(user.id, sha256(token));
}

function endAllSessions(userId) {
  q.deleteUserSessions.run(userId);
}

/* ---------- Single-use tokens ---------- */

const LINK_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/** Create a token of the given kind. Link codes are short so players can type them in-game. */
function createToken(kind, userId, ttlMs, data = null) {
  q.deleteUserTokens.run(userId, kind);
  for (let attempt = 0; attempt < 5; attempt++) {
    let token;
    if (kind === "link") {
      token = "";
      for (let i = 0; i < 6; i++) token += LINK_ALPHABET[crypto.randomInt(LINK_ALPHABET.length)];
    } else {
      token = crypto.randomBytes(32).toString("base64url");
    }
    try {
      q.insertToken.run(sha256(token), kind, userId, data, Date.now() + ttlMs);
      return { token, expiresAt: new Date(Date.now() + ttlMs).toISOString() };
    } catch (err) {
      if (!/UNIQUE|PRIMARY/i.test(err.message)) throw err;
    }
  }
  throw new Error("Could not create a unique token");
}

/** Look up a token without using it. */
function peekToken(kind, token) {
  if (typeof token !== "string" || !token || token.length > 100) return null;
  const value = kind === "link" ? token.trim().toUpperCase() : token.trim();
  return q.findToken.get(sha256(value), kind, Date.now()) || null;
}

/** Look up and delete a token in one go. */
function useToken(kind, token) {
  const row = peekToken(kind, token);
  if (row) q.deleteToken.run(row.token_hash);
  return row;
}

/* ---------- Simple in-memory rate limiter ---------- */

const buckets = new Map();

/** Returns true if `key` has already used `limit` attempts in the last `windowMs`. */
function limited(key, limit, windowMs) {
  const now = Date.now();
  const list = (buckets.get(key) || []).filter((t) => now - t < windowMs);
  buckets.set(key, list);
  return list.length >= limit;
}

function hit(key) {
  const list = buckets.get(key) || [];
  list.push(Date.now());
  buckets.set(key, list);
}

setInterval(() => {
  const now = Date.now();
  for (const [key, list] of buckets) {
    const fresh = list.filter((t) => now - t < 24 * 3600_000);
    if (fresh.length) buckets.set(key, fresh);
    else buckets.delete(key);
  }
}, 15 * 60 * 1000).unref();

module.exports = {
  USERNAME,
  EMAIL,
  MC_UUID,
  sha256,
  hashPassword,
  verifyPassword,
  passwordProblem,
  createUser,
  findUserByName,
  getUser,
  userByUuid: (uuid) => q.userByUuid.get(uuid),
  userByVerifiedEmail: (email) => q.userByVerifiedEmail.get(email),
  checkLogin,
  isStaff,
  publicUser,
  currentUser,
  startSession,
  endSession,
  endOtherSessions,
  endAllSessions,
  createToken,
  peekToken,
  useToken,
  limited,
  hit,
};
