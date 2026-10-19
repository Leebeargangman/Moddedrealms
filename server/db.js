/*
 * SQLite database (Node's built-in node:sqlite, no packages needed).
 * Stored in DATA_DIR/moddedrealms.db. Schema changes are applied in order using PRAGMA user_version.
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { config } = require("./config");

// node:sqlite still prints an "experimental" warning on Node 22; it is stable enough for this use.
const emitWarning = process.emitWarning;
process.emitWarning = function (warning, ...args) {
  const type = typeof args[0] === "string" ? args[0] : args[0] && args[0].type;
  if (type === "ExperimentalWarning" && /SQLite/i.test(String(warning))) return;
  return emitWarning.call(process, warning, ...args);
};

let DatabaseSync;
try {
  ({ DatabaseSync } = require("node:sqlite"));
} catch {
  console.error(`This website needs Node.js 22.13 or newer for its database (you have ${process.versions.node}).`);
  process.exit(1);
}

const MIGRATIONS = [
  `
  CREATE TABLE users (
    id              INTEGER PRIMARY KEY,
    username        TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash   TEXT NOT NULL,
    role            TEXT,
    email           TEXT COLLATE NOCASE,
    email_verified  INTEGER NOT NULL DEFAULT 0,
    email_notify    INTEGER NOT NULL DEFAULT 1,
    mc_uuid         TEXT UNIQUE,
    mc_name         TEXT COLLATE NOCASE,
    mc_rank         TEXT,
    mc_linked_at    TEXT,
    rank_synced_at  TEXT,
    created_at      TEXT NOT NULL,
    last_login_at   TEXT
  );

  CREATE TABLE sessions (
    token_hash  TEXT PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TEXT NOT NULL,
    expires_at  INTEGER NOT NULL
  );
  CREATE INDEX sessions_user ON sessions(user_id);

  -- Short-lived single-use tokens: Minecraft link codes, email verification, password resets.
  CREATE TABLE tokens (
    token_hash  TEXT PRIMARY KEY,
    kind        TEXT NOT NULL,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    data        TEXT,
    expires_at  INTEGER NOT NULL
  );
  CREATE INDEX tokens_user ON tokens(user_id, kind);

  CREATE TABLE reports (
    id                 TEXT PRIMARY KEY,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL,
    reporter_user_id   INTEGER REFERENCES users(id) ON DELETE SET NULL,
    reporter_name      TEXT NOT NULL,
    reporter_verified  INTEGER NOT NULL DEFAULT 0,
    discord_name       TEXT,
    reported_name      TEXT NOT NULL COLLATE NOCASE,
    reported_uuid      TEXT,
    reason             TEXT NOT NULL,
    location           TEXT,
    occurred_at        TEXT,
    timezone           TEXT,
    description        TEXT NOT NULL,
    evidence           TEXT NOT NULL DEFAULT '[]',
    status             TEXT NOT NULL DEFAULT 'open',
    staff_response     TEXT,
    staff_note         TEXT,
    handled_by         TEXT,
    flagged            TEXT,
    ip_hash            TEXT,
    user_agent         TEXT
  );
  CREATE INDEX reports_status ON reports(status, created_at);
  CREATE INDEX reports_reporter ON reports(reporter_user_id);
  CREATE INDEX reports_reported ON reports(reported_uuid);
  CREATE INDEX reports_reported_name ON reports(reported_name);

  CREATE TABLE feedback (
    id              TEXT PRIMARY KEY,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    user_id         INTEGER REFERENCES users(id) ON DELETE SET NULL,
    minecraft_name  TEXT,
    discord_name    TEXT,
    category        TEXT NOT NULL,
    rating          INTEGER,
    message         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'new',
    staff_reply     TEXT,
    handled_by      TEXT,
    flagged         TEXT,
    ip_hash         TEXT,
    user_agent      TEXT
  );
  CREATE INDEX feedback_status ON feedback(status, created_at);
  CREATE INDEX feedback_user ON feedback(user_id);
  `,
];

fs.mkdirSync(config.dataDir, { recursive: true });
const db = new DatabaseSync(path.join(config.dataDir, "moddedrealms.db"));
db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");

const current = db.prepare("PRAGMA user_version").get().user_version;
for (let v = current; v < MIGRATIONS.length; v++) {
  db.exec("BEGIN");
  try {
    db.exec(MIGRATIONS[v]);
    db.exec(`PRAGMA user_version = ${v + 1}`);
    db.exec("COMMIT");
  } catch (err) {
    db.exec("ROLLBACK");
    throw err;
  }
}

/** Run fn inside a transaction. */
function transaction(fn) {
  db.exec("BEGIN");
  try {
    const result = fn();
    db.exec("COMMIT");
    return result;
  } catch (err) {
    db.exec("ROLLBACK");
    throw err;
  }
}

const nowIso = () => new Date().toISOString();

module.exports = { db, transaction, nowIso };
