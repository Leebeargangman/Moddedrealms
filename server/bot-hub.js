/*
 * AI bot hub: the staff Bot tab, the bot managers (VPS, gaming PC...) and staff members' model connectors
 * all talk through here. Managers and connectors only connect out to the website (long-polling), so no
 * ports need to be opened anywhere else.
 *
 *   /api/bot/*      staff only: live state, event stream, commands, schematic uploads, connector tokens
 *   /api/botlink/*  bot managers, authenticated with BOT_MANAGER_KEY
 *   /api/llm/*      a staff member's connector (their own Ollama), authenticated with a per-staff token
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { config } = require("./config");
const { db, nowIso } = require("./db");
const accounts = require("./accounts");
const { sendJson, methodNotAllowed, jsonBodyOrError, sameOrigin, cleanText, httpError } = require("./http");

const BUILD_DIR = path.join(config.dataDir, "bot-builds");
const CONNECTOR_FILE = path.join(config.root, "ai-bot", "connector", "connector.js");
const POLL_MS = 25_000;
const ONLINE_MS = 60_000;
const MANAGER_NAME = /^[\w.-]{1,32}$/;

// Commands staff may send to a manager. Anything else is refused here, before it reaches a bot.
const COMMANDS = new Set([
  "startBot", "stopBot", "rejoin", "createBot", "updateBot", "removeBot",
  "createTeam", "updateTeam", "startTeam", "stopTeam", "removeTeam",
  "message", "order", "build", "inspectBuild",
  "addOffline", "linkMicrosoft", "removeAccount",
]);
// Events worth keeping in the database (the rest are live status only).
const KEEP = new Set(["chat", "said", "reply", "staff", "command", "team", "death", "logoff", "build", "result", "linkCode", "error", "disconnect"]);

fs.mkdirSync(BUILD_DIR, { recursive: true });

const q = {
  addEvent: db.prepare("INSERT INTO bot_events (at, manager, bot, type, who, data) VALUES (?, ?, ?, ?, ?, ?)"),
  recentEvents: db.prepare("SELECT at, manager, bot, type, who, data FROM bot_events ORDER BY id DESC LIMIT ?"),
  pruneEvents: db.prepare("DELETE FROM bot_events WHERE id < (SELECT MAX(id) FROM bot_events) - 5000"),
  addBuild: db.prepare("INSERT INTO bot_builds (id, name, size, sha256, uploaded_by, created_at) VALUES (?, ?, ?, ?, ?, ?)"),
  builds: db.prepare("SELECT id, name, size, uploaded_by, created_at FROM bot_builds ORDER BY created_at DESC LIMIT 100"),
  buildById: db.prepare("SELECT id, name FROM bot_builds WHERE id = ?"),
  addToken: db.prepare("INSERT INTO connector_tokens (token_hash, user_id, created_at) VALUES (?, ?, ?)"),
  dropTokens: db.prepare("DELETE FROM connector_tokens WHERE user_id = ?"),
  tokenUser: db.prepare("SELECT user_id FROM connector_tokens WHERE token_hash = ?"),
  touchToken: db.prepare("UPDATE connector_tokens SET last_used_at = ? WHERE token_hash = ?"),
};

const managers = new Map(); // name -> { name, lastSeen, snapshot, queue: [], waiters: Set }
const connectors = new Map(); // user id -> { userId, name, models, lastSeen, queue: [], waiters: Set }
const llmJobs = new Map(); // job id -> { userId, resolve, reject, timer }
const streams = new Set(); // staff SSE responses

// Each host's last snapshot, kept on disk: while a host is off (PC shut down, website restarted) the Bot tab
// still lists its bots as offline instead of showing nothing.
const HOSTS_FILE = path.join(config.dataDir, "bot-hosts.json");
try {
  for (const [name, h] of Object.entries(JSON.parse(fs.readFileSync(HOSTS_FILE, "utf8")))) {
    if (MANAGER_NAME.test(name) && h && h.snapshot) {
      managers.set(name, { name, lastSeen: Number(h.lastSeen) || 0, snapshot: h.snapshot, queue: [], waiters: new Set(), reportedOffline: true });
    }
  }
} catch {
  // first run, or an unreadable file: start empty
}
let saveTimer = null;
function saveHosts() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    const out = {};
    for (const m of managers.values()) if (m.snapshot) out[m.name] = { lastSeen: m.lastSeen, snapshot: m.snapshot };
    fs.writeFile(HOSTS_FILE, JSON.stringify(out), () => {});
  }, 30_000);
  saveTimer.unref();
}

const sha256 = (s) => crypto.createHash("sha256").update(s).digest("hex");
const newId = () => crypto.randomBytes(6).toString("hex");

function keyMatches(given, expected) {
  if (!expected || !given) return false;
  return crypto.timingSafeEqual(Buffer.from(sha256(given), "hex"), Buffer.from(sha256(expected), "hex"));
}

function bearer(req) {
  const h = String(req.headers.authorization || "");
  return h.startsWith("Bearer ") ? h.slice(7).trim() : "";
}

function readRaw(req, limit) {
  return new Promise((resolve, reject) => {
    if (Number(req.headers["content-length"] || 0) > limit) return reject(httpError(413, `That file is too large (max ${Math.round(limit / 1048576)} MB).`));
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) {
        reject(httpError(413, `That file is too large (max ${Math.round(limit / 1048576)} MB).`));
        req.destroy();
      } else chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

/* ---------- Events: live to the Bot tab, important ones stored ---------- */

function publish(ev) {
  ev.at = ev.at || nowIso();
  if (KEEP.has(ev.type)) {
    const { at, manager, bot, type, who, ...rest } = ev;
    q.addEvent.run(at, manager || null, bot || null, type, who || null, JSON.stringify(rest).slice(0, 4000));
    if (Math.random() < 0.01) q.pruneEvents.run();
  }
  const line = `data: ${JSON.stringify(ev)}\n\n`;
  for (const res of streams) res.write(line);
}

function recentEvents(n) {
  return q.recentEvents.all(n).reverse().map((r) => ({ at: r.at, manager: r.manager, bot: r.bot, type: r.type, who: r.who, ...JSON.parse(r.data || "{}") }));
}

/* ---------- Long-poll queues ---------- */

function deliver(target, item) {
  target.queue.push(item);
  for (const res of target.waiters) flush(target, res);
}

function flush(target, res) {
  target.waiters.delete(res);
  clearTimeout(res._pollTimer);
  const items = target.queue.splice(0);
  sendJson(res, 200, { ok: true, items });
}

function longPoll(target, req, res) {
  if (target.queue.length) return flush(target, res);
  target.waiters.add(res);
  res._pollTimer = setTimeout(() => flush(target, res), POLL_MS);
  req.on("close", () => {
    clearTimeout(res._pollTimer);
    target.waiters.delete(res);
  });
}

/* ---------- Managers (/api/botlink) ---------- */

function manager(req) {
  if (!keyMatches(bearer(req), config.botManagerKey)) return null;
  const name = String(req.headers["x-manager-name"] || "");
  if (!MANAGER_NAME.test(name)) return null;
  let m = managers.get(name);
  if (!m) {
    m = { name, lastSeen: 0, snapshot: null, queue: [], waiters: new Set() };
    managers.set(name, m);
  }
  if (!m.lastSeen || Date.now() - m.lastSeen > ONLINE_MS) publish({ type: "manager", manager: name, online: true });
  m.lastSeen = Date.now();
  return m;
}

async function handleBotlink(req, res, pathname) {
  const m = manager(req);
  if (!m) return sendJson(res, 401, { ok: false, error: "Bad manager key or name." });

  if (pathname === "/api/botlink/poll") {
    if (req.method !== "GET") return methodNotAllowed(res, "GET");
    return longPoll(m, req, res);
  }
  if (pathname === "/api/botlink/events") {
    if (req.method !== "POST") return methodNotAllowed(res, "POST");
    const body = await jsonBodyOrError(req, res, 2 * 1024 * 1024);
    if (!body) return;
    if (body.snapshot && typeof body.snapshot === "object") {
      m.snapshot = body.snapshot;
      saveHosts();
    }
    for (const ev of (Array.isArray(body.events) ? body.events : []).slice(0, 500)) {
      if (ev && typeof ev.type === "string") publish({ ...ev, manager: m.name });
    }
    if (body.snapshot) publish({ type: "snapshot", manager: m.name, snapshot: m.snapshot });
    return sendJson(res, 200, { ok: true });
  }
  const build = pathname.match(/^\/api\/botlink\/builds\/([a-f0-9]{12})$/);
  if (build) {
    const row = q.buildById.get(build[1]);
    const file = path.join(BUILD_DIR, `${build[1]}.litematic`);
    if (!row || !fs.existsSync(file)) return sendJson(res, 404, { ok: false, error: "No such build." });
    res.statusCode = 200;
    res.setHeader("Content-Type", "application/octet-stream");
    res.setHeader("X-Build-Name", encodeURIComponent(row.name));
    return fs.createReadStream(file).pipe(res);
  }
  if (pathname === "/api/botlink/llm") {
    if (req.method !== "POST") return methodNotAllowed(res, "POST");
    const body = await jsonBodyOrError(req, res, 256 * 1024);
    if (!body) return;
    const user = accounts.findUserByName(String(body.staff || ""));
    const c = user && connectors.get(user.id);
    if (!c || Date.now() - c.lastSeen > ONLINE_MS) {
      return sendJson(res, 503, { ok: false, error: `${body.staff || "that staff member"}'s computer isn't connected (start the connector)` });
    }
    const id = newId();
    const timeoutMs = Math.min(Number(body.timeoutMs) || 120_000, 180_000);
    try {
      const response = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          llmJobs.delete(id);
          reject(new Error("the staff member's computer didn't answer in time"));
        }, timeoutMs);
        llmJobs.set(id, { userId: user.id, resolve, reject, timer });
        deliver(c, { id, request: body.request });
      });
      return sendJson(res, 200, { ok: true, response });
    } catch (err) {
      return sendJson(res, 504, { ok: false, error: err.message });
    }
  }
  return sendJson(res, 404, { ok: false, error: "Not found." });
}

/* ---------- Connectors (/api/llm) ---------- */

function connectorUser(req) {
  const token = bearer(req);
  if (!/^[a-f0-9]{48}$/.test(token)) return null;
  const hash = sha256(token);
  const row = q.tokenUser.get(hash);
  if (!row) return null;
  const user = accounts.getUser(row.user_id);
  if (!user || !accounts.isStaff(user)) return null;
  q.touchToken.run(nowIso(), hash);
  let c = connectors.get(user.id);
  if (!c) {
    c = { userId: user.id, name: user.username, models: [], lastSeen: 0, queue: [], waiters: new Set() };
    connectors.set(user.id, c);
  }
  if (!c.lastSeen || Date.now() - c.lastSeen > ONLINE_MS) publish({ type: "connector", who: user.username, online: true });
  c.lastSeen = Date.now();
  return c;
}

async function handleLlm(req, res, pathname) {
  const c = connectorUser(req);
  if (!c) return sendJson(res, 401, { ok: false, error: "Bad or revoked connector token (get a new one in the Bot tab)." });
  if (pathname === "/api/llm/hello") {
    if (req.method !== "POST") return methodNotAllowed(res, "POST");
    const body = await jsonBodyOrError(req, res);
    if (!body) return;
    c.models = (Array.isArray(body.models) ? body.models : []).map(String).filter((s) => /^[\w.:/-]{1,64}$/.test(s)).slice(0, 50);
    publish({ type: "connector", who: c.name, online: true, models: c.models });
    return sendJson(res, 200, { ok: true, name: c.name });
  }
  if (pathname === "/api/llm/poll") {
    if (req.method !== "GET") return methodNotAllowed(res, "GET");
    return longPoll(c, req, res);
  }
  if (pathname === "/api/llm/result") {
    if (req.method !== "POST") return methodNotAllowed(res, "POST");
    const body = await jsonBodyOrError(req, res, 512 * 1024);
    if (!body) return;
    const job = llmJobs.get(String(body.id || ""));
    if (!job || job.userId !== c.userId) return sendJson(res, 404, { ok: false, error: "Unknown job." });
    llmJobs.delete(String(body.id));
    clearTimeout(job.timer);
    if (body.error) job.reject(new Error(String(body.error).slice(0, 300)));
    else job.resolve(body.response);
    return sendJson(res, 200, { ok: true });
  }
  return sendJson(res, 404, { ok: false, error: "Not found." });
}

/* ---------- Staff (/api/bot) ---------- */

function staffState(user) {
  return {
    ok: true,
    me: { username: user.username, minecraft: user.mc_name || null },
    managers: [...managers.values()].map((m) => ({ name: m.name, online: Date.now() - m.lastSeen < ONLINE_MS, lastSeen: m.lastSeen, snapshot: m.snapshot })),
    connectors: [...connectors.values()].map((c) => ({ name: c.name, models: c.models, online: Date.now() - c.lastSeen < ONLINE_MS, mine: c.userId === user.id })),
    builds: q.builds.all(),
    events: recentEvents(200),
  };
}

function openStream(req, res) {
  res.writeHead(200, { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-store", Connection: "keep-alive", "X-Accel-Buffering": "no" });
  res.write(": connected\n\n");
  streams.add(res);
  const ping = setInterval(() => res.write(": ping\n\n"), 20_000);
  req.on("close", () => {
    clearInterval(ping);
    streams.delete(res);
  });
}

async function command(req, res, user) {
  const body = await jsonBodyOrError(req, res, 64 * 1024);
  if (!body) return;
  const m = managers.get(String(body.manager || ""));
  if (!m || Date.now() - m.lastSeen > ONLINE_MS) return sendJson(res, 409, { ok: false, error: "That bot host isn't connected right now." });
  const cmd = String(body.command || "");
  if (!COMMANDS.has(cmd)) return sendJson(res, 400, { ok: false, error: "Unknown command." });
  const args = body.args && typeof body.args === "object" && !Array.isArray(body.args) ? body.args : {};
  if (typeof args.text === "string") args.text = cleanText(args.text).slice(0, 500);
  const id = newId();
  const staff = { name: user.username, minecraft: user.mc_name || null };
  deliver(m, { id, command: cmd, args, staff });
  publish({ type: cmd === "message" ? "staff" : "command", manager: m.name, bot: args.bot || null, who: user.username, command: cmd, text: cmd === "message" ? args.text : undefined, id });
  console.log(`[bot] ${user.username} -> ${m.name}: ${cmd}${args.bot ? ` (${args.bot})` : ""}`);
  return sendJson(res, 200, { ok: true, id });
}

async function upload(req, res, user, url) {
  const name = path.basename(String(url.searchParams.get("name") || "")).replace(/[^\w.() -]/g, "_").slice(0, 80);
  if (!/\.litematic$/i.test(name)) return sendJson(res, 400, { ok: false, error: "Upload a .litematic file." });
  let buf;
  try {
    buf = await readRaw(req, config.botUploadMaxMb * 1048576);
  } catch (err) {
    return sendJson(res, err.status || 400, { ok: false, error: err.message });
  }
  if (buf.length < 20 || buf[0] !== 0x1f || buf[1] !== 0x8b) return sendJson(res, 400, { ok: false, error: "That isn't a Litematica file (they are gzip-compressed)." });
  const id = newId();
  fs.writeFileSync(path.join(BUILD_DIR, `${id}.litematic`), buf);
  q.addBuild.run(id, name, buf.length, crypto.createHash("sha256").update(buf).digest("hex"), user.username, nowIso());
  publish({ type: "upload", who: user.username, id, name });
  return sendJson(res, 200, { ok: true, build: { id, name, size: buf.length } });
}

function connectorToken(req, res, user) {
  const token = crypto.randomBytes(24).toString("hex");
  q.dropTokens.run(user.id); // one connector per staff member; a new token replaces the old one
  q.addToken.run(sha256(token), user.id, nowIso());
  return sendJson(res, 200, { ok: true, token });
}

async function handleStaff(req, res, pathname, url) {
  const user = accounts.currentUser(req);
  if (!user) return sendJson(res, 401, { ok: false, error: "Please sign in." });
  if (!accounts.isStaff(user)) return sendJson(res, 403, { ok: false, error: "Staff only." });

  if (pathname === "/api/bot/state") {
    if (req.method !== "GET") return methodNotAllowed(res, "GET");
    return sendJson(res, 200, staffState(user));
  }
  if (pathname === "/api/bot/stream") {
    if (req.method !== "GET") return methodNotAllowed(res, "GET");
    return openStream(req, res);
  }
  if (pathname === "/api/bot/connector.js") {
    if (req.method !== "GET") return methodNotAllowed(res, "GET");
    res.statusCode = 200;
    res.setHeader("Content-Type", "text/javascript; charset=utf-8");
    res.setHeader("Content-Disposition", 'attachment; filename="connector.js"');
    return fs.createReadStream(CONNECTOR_FILE).pipe(res);
  }
  if (req.method !== "POST") return methodNotAllowed(res, "POST");
  if (!sameOrigin(req)) return sendJson(res, 403, { ok: false, error: "Cross-site requests are not allowed." });
  if (pathname === "/api/bot/command") return command(req, res, user);
  if (pathname === "/api/bot/builds") return upload(req, res, user, url);
  if (pathname === "/api/bot/connector-token") return connectorToken(req, res, user);
  return sendJson(res, 404, { ok: false, error: "Not found." });
}

/** Handles bot API routes. Returns false if the path isn't one of them. */
async function handleBotApi(req, res, pathname, url) {
  if (pathname.startsWith("/api/bot/")) {
    await handleStaff(req, res, pathname, url);
  } else if (pathname.startsWith("/api/botlink/")) {
    if (!config.botManagerKey) sendJson(res, 503, { ok: false, error: "BOT_MANAGER_KEY isn't set on the website." });
    else await handleBotlink(req, res, pathname);
  } else if (pathname.startsWith("/api/llm/")) {
    await handleLlm(req, res, pathname);
  } else return false;
  return true;
}

// A manager that stops polling drops offline in the Bot tab.
setInterval(() => {
  for (const m of managers.values()) {
    if (m.lastSeen && Date.now() - m.lastSeen > ONLINE_MS && !m.reportedOffline) {
      m.reportedOffline = true;
      publish({ type: "manager", manager: m.name, online: false });
    } else if (Date.now() - m.lastSeen <= ONLINE_MS) m.reportedOffline = false;
  }
}, 15_000).unref();

module.exports = { handleBotApi };
