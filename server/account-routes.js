/*
 * Account, staff dashboard and Minecraft plugin API routes.
 *
 *   /api/auth/*     sign up, sign in, sign out, password reset
 *   /api/account/*  the signed-in player's settings, Minecraft link and activity
 *   /api/admin/*    staff only: reports, feedback and players
 *   /api/plugin/*   called by the Moddedrealms Link plugin, authenticated with PLUGIN_API_KEY
 */
"use strict";

const crypto = require("node:crypto");
const { config } = require("./config");
const { db, nowIso, transaction } = require("./db");
const accounts = require("./accounts");
const { sendMail, mailEnabled } = require("./mailer");
const { sendJson, methodNotAllowed, jsonBodyOrError, clientIp, sameOrigin, isSecureRequest, siteUrlFor, cleanText, has } = require("./http");
const { REPORT_REASONS, REPORT_LOCATIONS, REPORT_STATUSES, FEEDBACK_CATEGORIES, FEEDBACK_STATUSES, MC_NAME } = require("./forms");

const LINK_CODE_TTL = 10 * 60 * 1000;
const VERIFY_TTL = 48 * 3600 * 1000;
const RESET_TTL = 60 * 60 * 1000;
const HOUR = 3600 * 1000;

/* ---------- Queries ---------- */

const q = {
  setPassword: db.prepare("UPDATE users SET password_hash = ? WHERE id = ?"),
  setEmail: db.prepare("UPDATE users SET email = ?, email_verified = 0 WHERE id = ?"),
  verifyEmail: db.prepare("UPDATE users SET email_verified = 1 WHERE id = ? AND email = ?"),
  setNotify: db.prepare("UPDATE users SET email_notify = ? WHERE id = ?"),
  unlink: db.prepare("UPDATE users SET mc_uuid = NULL, mc_name = NULL, mc_rank = NULL, mc_linked_at = NULL, rank_synced_at = NULL WHERE id = ?"),
  link: db.prepare("UPDATE users SET mc_uuid = ?, mc_name = ?, mc_rank = ?, mc_linked_at = ?, rank_synced_at = ? WHERE id = ?"),
  syncPlayer: db.prepare("UPDATE users SET mc_name = ?, mc_rank = ?, rank_synced_at = ? WHERE mc_uuid = ?"),
  emailTaken: db.prepare("SELECT id FROM users WHERE email = ? AND email_verified = 1 AND id != ?"),

  myReports: db.prepare(`SELECT id, created_at, updated_at, reported_name, reason, location, status, staff_response
    FROM reports WHERE reporter_user_id = ? ORDER BY created_at DESC LIMIT 100`),
  reportsAboutMe: db.prepare(`SELECT id, created_at, updated_at, reason, location, status
    FROM reports WHERE status IN ('resolved', 'dismissed')
      AND (reported_uuid = ? OR (reported_uuid IS NULL AND reported_name = ?))
    ORDER BY created_at DESC LIMIT 100`),
  myFeedback: db.prepare(`SELECT id, created_at, updated_at, category, rating, message, status, staff_reply
    FROM feedback WHERE user_id = ? ORDER BY created_at DESC LIMIT 100`),

  reportById: db.prepare("SELECT * FROM reports WHERE id = ?"),
  feedbackById: db.prepare("SELECT * FROM feedback WHERE id = ?"),
  updateReport: db.prepare("UPDATE reports SET status = ?, staff_response = ?, staff_note = ?, handled_by = ?, updated_at = ? WHERE id = ?"),
  updateFeedback: db.prepare("UPDATE feedback SET status = ?, staff_reply = ?, handled_by = ?, updated_at = ? WHERE id = ?"),
  reportCounts: db.prepare("SELECT status, COUNT(*) AS n FROM reports GROUP BY status"),
  feedbackCounts: db.prepare("SELECT status, COUNT(*) AS n FROM feedback GROUP BY status"),
  userCounts: db.prepare("SELECT COUNT(*) AS total, COUNT(mc_uuid) AS linked FROM users"),
  reportedUser: db.prepare("SELECT * FROM users WHERE (mc_uuid IS NOT NULL AND mc_uuid = ?) OR (? IS NULL AND mc_name = ?) LIMIT 1"),
  priorReports: db.prepare(`SELECT COUNT(*) AS n FROM reports WHERE id != ?
    AND ((reported_uuid IS NOT NULL AND reported_uuid = ?) OR reported_name = ?)`),
};

/* ---------- Helpers ---------- */

function requireSameOrigin(req, res) {
  if (sameOrigin(req)) return true;
  sendJson(res, 403, { ok: false, error: "Cross-site requests are not allowed." });
  return false;
}

function requireUser(req, res) {
  const user = accounts.currentUser(req);
  if (!user) sendJson(res, 401, { ok: false, error: "Please sign in." });
  return user;
}

function requireStaff(req, res) {
  const user = requireUser(req, res);
  if (!user) return null;
  if (!accounts.isStaff(user)) {
    sendJson(res, 403, { ok: false, error: "Staff only." });
    return null;
  }
  return user;
}

function staffName(user) {
  return user.mc_name ? `${user.mc_name} (${user.username})` : user.username;
}

function tooMany(res) {
  return sendJson(res, 429, { ok: false, error: "Too many attempts. Please wait a few minutes and try again." });
}

function canEmail(user) {
  return Boolean(mailEnabled() && user && user.email && user.email_verified && user.email_notify);
}

function emailFooter(req) {
  const site = siteUrlFor(req);
  return `\n\n-- \nModdedrealms${site ? `\n${site}/account` : ""}\nYou can turn these emails off on your account page.`;
}

async function sendVerification(req, user, email) {
  const { token } = accounts.createToken("verify", user.id, VERIFY_TTL, email);
  const link = `${siteUrlFor(req)}/verify-email?token=${token}`;
  return sendMail({
    to: email,
    subject: "Confirm your email for Moddedrealms",
    text: `Hi ${user.username},\n\nConfirm this email address for your Moddedrealms account by opening this link:\n\n${link}\n\nThe link works for 48 hours. If you didn't ask for this, you can ignore this email.`,
  });
}

/* ---------- /api/auth ---------- */

async function register(req, res) {
  if (!requireSameOrigin(req, res)) return;
  const ip = clientIp(req);
  if (accounts.limited(`register:${ip}`, 5, HOUR)) return tooMany(res);
  const body = await jsonBodyOrError(req, res);
  if (!body) return;

  const username = cleanText(body.username);
  const password = typeof body.password === "string" ? body.password : "";
  const email = cleanText(body.email).toLowerCase();
  const fields = {};
  if (!accounts.USERNAME.test(username)) fields.username = "Use 3-24 letters, numbers, dots, dashes or underscores.";
  else if (accounts.findUserByName(username)) fields.username = "That username is taken.";
  const pwProblem = accounts.passwordProblem(password);
  if (pwProblem) fields.password = pwProblem;
  if (email && !accounts.EMAIL.test(email)) fields.email = "That doesn't look like an email address.";
  if (Object.keys(fields).length) return sendJson(res, 422, { ok: false, error: "Please fix the highlighted fields.", fields });

  let user;
  try {
    user = await accounts.createUser(username, password);
  } catch (err) {
    if (/UNIQUE/i.test(err.message)) return sendJson(res, 422, { ok: false, fields: { username: "That username is taken." } });
    throw err;
  }
  accounts.hit(`register:${ip}`);
  if (email) {
    q.setEmail.run(email, user.id);
    user = accounts.getUser(user.id);
    if (mailEnabled()) sendVerification(req, user, email);
  }
  accounts.startSession(res, user, isSecureRequest(req));
  console.log(`[account] registered ${user.username}`);
  sendJson(res, 201, { ok: true, user: accounts.publicUser(user) });
}

async function login(req, res) {
  if (!requireSameOrigin(req, res)) return;
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  const ip = clientIp(req);
  const username = cleanText(body.username).toLowerCase();
  const ipKey = `login-ip:${ip}`;
  const userKey = `login-user:${username}`;
  if (accounts.limited(ipKey, 20, 15 * 60 * 1000) || accounts.limited(userKey, 10, 15 * 60 * 1000)) return tooMany(res);

  const user = await accounts.checkLogin(username, body.password);
  if (!user) {
    accounts.hit(ipKey);
    accounts.hit(userKey);
    return sendJson(res, 401, { ok: false, error: "Wrong username or password." });
  }
  accounts.startSession(res, user, isSecureRequest(req));
  sendJson(res, 200, { ok: true, user: accounts.publicUser(user) });
}

function logout(req, res) {
  if (!requireSameOrigin(req, res)) return;
  accounts.endSession(req, res, isSecureRequest(req));
  sendJson(res, 200, { ok: true });
}

async function forgot(req, res) {
  if (!requireSameOrigin(req, res)) return;
  const ip = clientIp(req);
  if (accounts.limited(`forgot:${ip}`, 5, HOUR)) return tooMany(res);
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  accounts.hit(`forgot:${ip}`);
  const email = cleanText(body.email).toLowerCase();
  const user = accounts.EMAIL.test(email) ? accounts.userByVerifiedEmail(email) : null;
  if (user && mailEnabled()) {
    const { token } = accounts.createToken("reset", user.id, RESET_TTL);
    sendMail({
      to: user.email,
      subject: "Reset your Moddedrealms password",
      text: `Hi ${user.username},\n\nSomeone (hopefully you) asked to reset the password for your Moddedrealms account. Choose a new password here:\n\n${siteUrlFor(req)}/reset?token=${token}\n\nThe link works for 1 hour. If you didn't ask for this, you can ignore this email and your password stays the same.`,
    });
  }
  // Same answer either way, so this can't be used to find out who has an account.
  sendJson(res, 200, { ok: true });
}

async function resetPassword(req, res) {
  if (!requireSameOrigin(req, res)) return;
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  const problem = accounts.passwordProblem(body.password);
  if (problem) return sendJson(res, 422, { ok: false, fields: { password: problem } });
  const token = accounts.useToken("reset", body.token);
  if (!token) return sendJson(res, 400, { ok: false, error: "This reset link is invalid or has expired. Request a new one." });
  q.setPassword.run(await accounts.hashPassword(body.password), token.user_id);
  accounts.endAllSessions(token.user_id);
  const user = accounts.getUser(token.user_id);
  accounts.startSession(res, user, isSecureRequest(req));
  sendJson(res, 200, { ok: true });
}

/** GET /verify-email?token=... from the confirmation email. */
function verifyEmailLink(req, res, url) {
  const token = accounts.useToken("verify", url.searchParams.get("token") || "");
  let result = "invalid";
  if (token) {
    const changed = q.verifyEmail.run(token.user_id, token.data);
    result = changed.changes ? "verified" : "invalid";
  }
  res.statusCode = 302;
  res.setHeader("Location", `/account?email=${result}`);
  res.end();
}

/* ---------- /api/account ---------- */

async function changePassword(req, res, user) {
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  if (accounts.limited(`pw:${user.id}`, 10, HOUR)) return tooMany(res);
  accounts.hit(`pw:${user.id}`);
  const fresh = accounts.getUser(user.id);
  if (!(await accounts.verifyPassword(String(body.current || ""), fresh.password_hash))) {
    return sendJson(res, 422, { ok: false, fields: { current: "That isn't your current password." } });
  }
  const problem = accounts.passwordProblem(body.password);
  if (problem) return sendJson(res, 422, { ok: false, fields: { password: problem } });
  q.setPassword.run(await accounts.hashPassword(body.password), user.id);
  accounts.endOtherSessions(req, user);
  sendJson(res, 200, { ok: true });
}

async function changeEmail(req, res, user) {
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  if (accounts.limited(`email:${user.id}`, 5, HOUR)) return tooMany(res);
  const email = cleanText(body.email).toLowerCase();
  if (email && !accounts.EMAIL.test(email)) return sendJson(res, 422, { ok: false, fields: { email: "That doesn't look like an email address." } });
  if (email && q.emailTaken.get(email, user.id)) return sendJson(res, 422, { ok: false, fields: { email: "That email is already used by another account." } });
  accounts.hit(`email:${user.id}`);
  q.setEmail.run(email || null, user.id);
  const fresh = accounts.getUser(user.id);
  const sent = email && mailEnabled() ? await sendVerification(req, fresh, email) : false;
  sendJson(res, 200, { ok: true, user: accounts.publicUser(fresh), verificationSent: sent });
}

async function resendVerification(req, res, user) {
  if (!user.email || user.email_verified) return sendJson(res, 400, { ok: false, error: "There's no email waiting to be confirmed." });
  if (accounts.limited(`email:${user.id}`, 5, HOUR)) return tooMany(res);
  accounts.hit(`email:${user.id}`);
  const sent = await sendVerification(req, user, user.email);
  sendJson(res, sent ? 200 : 502, sent ? { ok: true } : { ok: false, error: "The email couldn't be sent. Please try again later." });
}

async function setNotify(req, res, user) {
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  q.setNotify.run(body.enabled ? 1 : 0, user.id);
  sendJson(res, 200, { ok: true, user: accounts.publicUser(accounts.getUser(user.id)) });
}

function linkCode(req, res, user) {
  if (!config.pluginApiKey) {
    return sendJson(res, 503, { ok: false, error: "Account linking isn't set up on this server yet." });
  }
  if (accounts.limited(`link:${user.id}`, 10, HOUR)) return tooMany(res);
  accounts.hit(`link:${user.id}`);
  const { token, expiresAt } = accounts.createToken("link", user.id, LINK_CODE_TTL);
  sendJson(res, 200, { ok: true, code: token, expiresAt });
}

function unlink(req, res, user) {
  q.unlink.run(user.id);
  sendJson(res, 200, { ok: true, user: accounts.publicUser(accounts.getUser(user.id)) });
}

function activity(req, res, user) {
  const reports = q.myReports.all(user.id).map((r) => ({
    id: r.id,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    reportedName: r.reported_name,
    reason: REPORT_REASONS[r.reason] || r.reason,
    location: REPORT_LOCATIONS[r.location] || r.location,
    status: r.status,
    statusLabel: REPORT_STATUSES[r.status] || r.status,
    staffResponse: r.staff_response || "",
  }));
  // Reports about a player are only shown once staff have closed them, and never say who reported.
  const aboutMe = user.mc_uuid
    ? q.reportsAboutMe.all(user.mc_uuid, user.mc_name).map((r) => ({
        id: r.id,
        createdAt: r.created_at,
        reviewedAt: r.updated_at,
        reason: REPORT_REASONS[r.reason] || r.reason,
        location: REPORT_LOCATIONS[r.location] || r.location,
        status: r.status,
        statusLabel: REPORT_STATUSES[r.status] || r.status,
      }))
    : [];
  const feedback = q.myFeedback.all(user.id).map((f) => ({
    id: f.id,
    createdAt: f.created_at,
    updatedAt: f.updated_at,
    category: FEEDBACK_CATEGORIES[f.category] || f.category,
    rating: f.rating,
    message: f.message,
    status: f.status,
    statusLabel: FEEDBACK_STATUSES[f.status] || f.status,
    staffReply: f.staff_reply || "",
  }));
  sendJson(res, 200, { ok: true, reports, aboutMe, feedback });
}

/* ---------- /api/admin ---------- */

function adminReport(r) {
  let evidence = [];
  try {
    evidence = JSON.parse(r.evidence || "[]");
  } catch {
    evidence = [];
  }
  return {
    id: r.id,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    reporterName: r.reporter_name,
    reporterVerified: Boolean(r.reporter_verified),
    reporterHasAccount: Boolean(r.reporter_user_id),
    discordName: r.discord_name || "",
    reportedName: r.reported_name,
    reportedUuid: r.reported_uuid || "",
    reason: r.reason,
    reasonLabel: REPORT_REASONS[r.reason] || r.reason,
    location: REPORT_LOCATIONS[r.location] || r.location || "",
    occurredAt: r.occurred_at || "",
    timezone: r.timezone || "",
    description: r.description,
    evidence,
    status: r.status,
    staffResponse: r.staff_response || "",
    staffNote: r.staff_note || "",
    handledBy: r.handled_by || "",
    flagged: r.flagged || "",
  };
}

function adminFeedback(f) {
  return {
    id: f.id,
    createdAt: f.created_at,
    updatedAt: f.updated_at,
    hasAccount: Boolean(f.user_id),
    minecraftName: f.minecraft_name || "",
    discordName: f.discord_name || "",
    category: f.category,
    categoryLabel: FEEDBACK_CATEGORIES[f.category] || f.category,
    rating: f.rating,
    message: f.message,
    status: f.status,
    staffReply: f.staff_reply || "",
    handledBy: f.handled_by || "",
    flagged: f.flagged || "",
  };
}

function listQuery(table, url, searchColumns) {
  const status = url.searchParams.get("status") || "";
  const search = cleanText(url.searchParams.get("q") || "").slice(0, 64);
  const where = [];
  const params = [];
  if (status && status !== "all") {
    where.push("status = ?");
    params.push(status);
  }
  if (search) {
    where.push(`(${searchColumns.map((c) => `${c} LIKE ? ESCAPE '\\'`).join(" OR ")})`);
    const like = `%${search.replace(/[\\%_]/g, "\\$&")}%`;
    for (let i = 0; i < searchColumns.length; i++) params.push(like);
  }
  const sql = `SELECT * FROM ${table} ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY created_at DESC LIMIT 300`;
  return db.prepare(sql).all(...params);
}

function adminSummary(req, res) {
  const reports = Object.fromEntries(q.reportCounts.all().map((r) => [r.status, r.n]));
  const feedback = Object.fromEntries(q.feedbackCounts.all().map((r) => [r.status, r.n]));
  const users = q.userCounts.get();
  sendJson(res, 200, {
    ok: true,
    reports,
    feedback,
    users: { total: users.total, linked: users.linked },
    statuses: { reports: REPORT_STATUSES, feedback: FEEDBACK_STATUSES },
  });
}

function adminReports(req, res, url) {
  const rows = listQuery("reports", url, ["id", "reporter_name", "reported_name", "description", "discord_name"]);
  sendJson(res, 200, {
    ok: true,
    reports: rows.map((r) => ({ ...adminReport(r), priorReports: q.priorReports.get(r.id, r.reported_uuid, r.reported_name).n })),
  });
}

function adminFeedbackList(req, res, url) {
  const rows = listQuery("feedback", url, ["id", "minecraft_name", "discord_name", "message"]);
  sendJson(res, 200, { ok: true, feedback: rows.map(adminFeedback) });
}

function adminUsers(req, res, url) {
  const search = cleanText(url.searchParams.get("q") || "").slice(0, 64);
  const like = `%${search.replace(/[\\%_]/g, "\\$&")}%`;
  const rows = db
    .prepare(
      `SELECT * FROM users ${search ? "WHERE username LIKE ? ESCAPE '\\' OR mc_name LIKE ? ESCAPE '\\'" : ""} ORDER BY created_at DESC LIMIT 300`
    )
    .all(...(search ? [like, like] : []));
  sendJson(res, 200, {
    ok: true,
    users: rows.map((u) => ({
      username: u.username,
      staff: accounts.isStaff(u),
      role: u.role || "",
      minecraftName: u.mc_name || "",
      minecraftUuid: u.mc_uuid || "",
      rank: u.mc_rank || "",
      rankSyncedAt: u.rank_synced_at || "",
      emailVerified: Boolean(u.email && u.email_verified),
      createdAt: u.created_at,
      lastLoginAt: u.last_login_at || "",
    })),
  });
}

async function updateReport(req, res, staff, id) {
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  const report = q.reportById.get(id);
  if (!report) return sendJson(res, 404, { ok: false, error: "Report not found." });
  const status = has(REPORT_STATUSES, body.status) ? body.status : report.status;
  const response = cleanText(body.staffResponse, { multiline: true }).slice(0, 2000);
  const note = cleanText(body.staffNote, { multiline: true }).slice(0, 4000);
  q.updateReport.run(status, response || null, note || null, staffName(staff), nowIso(), id);
  const updated = q.reportById.get(id);
  console.log(`[admin] ${staff.username} set report ${id} to ${status}`);

  const statusChanged = status !== report.status;
  const responseChanged = (response || "") !== (report.staff_response || "");

  // Let the reporter know.
  if ((statusChanged || responseChanged) && report.reporter_user_id) {
    const reporter = accounts.getUser(report.reporter_user_id);
    if (canEmail(reporter)) {
      sendMail({
        to: reporter.email,
        subject: `Update on your report ${id}`,
        text: `Hi ${reporter.username},\n\nYour report about ${report.reported_name} (${REPORT_REASONS[report.reason] || report.reason}) has been updated.\n\nStatus: ${REPORT_STATUSES[status]}${response ? `\n\nMessage from staff:\n${response}` : ""}${emailFooter(req)}`,
      });
    }
  }

  // Once it's closed, let the reported player know (without saying who reported them).
  if (statusChanged && (status === "resolved" || status === "dismissed")) {
    const reported = q.reportedUser.get(updated.reported_uuid, updated.reported_uuid, updated.reported_name);
    if (canEmail(reported)) {
      sendMail({
        to: reported.email,
        subject: "A report about you was reviewed",
        text: `Hi ${reported.username},\n\nA report about your Minecraft account ${reported.mc_name} on Moddedrealms has been reviewed by staff.\n\nReason: ${REPORT_REASONS[report.reason] || report.reason}\nWhere: ${REPORT_LOCATIONS[report.location] || "Not specified"}\nOutcome: ${REPORT_STATUSES[status]}\n\nYou can see this on your account page. If you have questions, contact staff on our Discord.${emailFooter(req)}`,
      });
    }
  }

  sendJson(res, 200, { ok: true, report: adminReport(updated) });
}

async function updateFeedback(req, res, staff, id) {
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  const item = q.feedbackById.get(id);
  if (!item) return sendJson(res, 404, { ok: false, error: "Feedback not found." });
  const status = has(FEEDBACK_STATUSES, body.status) ? body.status : item.status;
  const reply = cleanText(body.staffReply, { multiline: true }).slice(0, 2000);
  q.updateFeedback.run(status, reply || null, staffName(staff), nowIso(), id);
  console.log(`[admin] ${staff.username} set feedback ${id} to ${status}`);

  if ((reply || "") !== (item.staff_reply || "") && reply && item.user_id) {
    const author = accounts.getUser(item.user_id);
    if (canEmail(author)) {
      sendMail({
        to: author.email,
        subject: `Staff replied to your feedback ${id}`,
        text: `Hi ${author.username},\n\nThanks for your feedback! Staff replied:\n\n${reply}\n\nStatus: ${FEEDBACK_STATUSES[status]}${emailFooter(req)}`,
      });
    }
  }
  sendJson(res, 200, { ok: true, feedback: adminFeedback(q.feedbackById.get(id)) });
}

/* ---------- /api/plugin ---------- */

function pluginAuthorized(req) {
  if (!config.pluginApiKey) return false;
  const header = String(req.headers.authorization || "");
  const given = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  const a = crypto.createHash("sha256").update(given).digest();
  const b = crypto.createHash("sha256").update(config.pluginApiKey).digest();
  return given.length > 0 && crypto.timingSafeEqual(a, b);
}

function cleanRank(value) {
  const rank = cleanText(value).slice(0, 32);
  return /^[\w .+-]{1,32}$/.test(rank) ? rank : null;
}

function cleanPlayer(p) {
  if (!p || typeof p !== "object") return null;
  const uuid = String(p.uuid || "").toLowerCase();
  const name = cleanText(p.name);
  if (!accounts.MC_UUID.test(uuid) || !MC_NAME.test(name)) return null;
  return { uuid, name, rank: cleanRank(p.rank) };
}

async function pluginLink(req, res) {
  const body = await jsonBodyOrError(req, res);
  if (!body) return;
  const player = cleanPlayer(body);
  if (!player) return sendJson(res, 400, { ok: false, message: "Invalid player data." });
  const code = String(body.code || "").trim().toUpperCase();
  if (!/^[A-Z0-9]{6}$/.test(code)) return sendJson(res, 400, { ok: false, message: "That code doesn't look right. It's 6 letters and numbers from the website." });

  const token = accounts.useToken("link", code);
  if (!token) return sendJson(res, 404, { ok: false, message: "That code is wrong or has expired. Get a new one on the website." });

  const user = accounts.getUser(token.user_id);
  const now = nowIso();
  transaction(() => {
    // The player in-game proved they own this Minecraft account, so it moves to the new website account.
    const previous = accounts.userByUuid(player.uuid);
    if (previous && previous.id !== user.id) q.unlink.run(previous.id);
    q.link.run(player.uuid, player.name, player.rank, now, now, user.id);
  });
  console.log(`[link] ${player.name} linked to website account ${user.username}`);
  sendJson(res, 200, { ok: true, username: user.username, message: `Linked to website account ${user.username}!` });
}

async function pluginSync(req, res) {
  const body = await jsonBodyOrError(req, res, 256 * 1024);
  if (!body) return;
  const players = (Array.isArray(body.players) ? body.players : []).slice(0, 1000).map(cleanPlayer).filter(Boolean);
  const now = nowIso();
  let updated = 0;
  transaction(() => {
    for (const p of players) updated += Number(q.syncPlayer.run(p.name, p.rank, now, p.uuid).changes);
  });
  sendJson(res, 200, { ok: true, received: players.length, updated });
}

/* ---------- Router ---------- */

/** Handles account-related API routes. Returns false if the path isn't one of them. */
async function handleAccountApi(req, res, pathname, url) {
  const method = req.method;

  if (pathname === "/api/auth/me") {
    if (method !== "GET") return methodNotAllowed(res, "GET"), true;
    const user = accounts.currentUser(req);
    sendJson(res, 200, {
      ok: true,
      user: accounts.publicUser(user),
      features: { email: mailEnabled(), linking: Boolean(config.pluginApiKey) },
    });
    return true;
  }

  const authRoutes = {
    "/api/auth/register": register,
    "/api/auth/login": login,
    "/api/auth/logout": logout,
    "/api/auth/forgot": forgot,
    "/api/auth/reset": resetPassword,
  };
  if (has(authRoutes, pathname)) {
    if (method !== "POST") return methodNotAllowed(res, "POST"), true;
    await authRoutes[pathname](req, res);
    return true;
  }

  if (pathname.startsWith("/api/account/")) {
    const user = requireUser(req, res);
    if (!user) return true;
    if (pathname === "/api/account/activity") {
      if (method !== "GET") return methodNotAllowed(res, "GET"), true;
      activity(req, res, user);
      return true;
    }
    const routes = {
      "/api/account/password": changePassword,
      "/api/account/email": changeEmail,
      "/api/account/email/resend": resendVerification,
      "/api/account/notify": setNotify,
      "/api/account/link-code": linkCode,
      "/api/account/unlink": unlink,
    };
    if (!has(routes, pathname)) return false;
    if (method !== "POST") return methodNotAllowed(res, "POST"), true;
    if (!requireSameOrigin(req, res)) return true;
    await routes[pathname](req, res, user);
    return true;
  }

  if (pathname.startsWith("/api/admin/")) {
    const staff = requireStaff(req, res);
    if (!staff) return true;
    const item = pathname.match(/^\/api\/admin\/(reports|feedback)\/([RF]-[A-Z0-9]{6})$/);
    if (item) {
      if (method !== "POST") return methodNotAllowed(res, "POST"), true;
      if (!requireSameOrigin(req, res)) return true;
      if (item[1] === "reports") await updateReport(req, res, staff, item[2]);
      else await updateFeedback(req, res, staff, item[2]);
      return true;
    }
    const lists = {
      "/api/admin/summary": adminSummary,
      "/api/admin/reports": adminReports,
      "/api/admin/feedback": adminFeedbackList,
      "/api/admin/users": adminUsers,
    };
    if (!has(lists, pathname)) return false;
    if (method !== "GET") return methodNotAllowed(res, "GET"), true;
    lists[pathname](req, res, url);
    return true;
  }

  if (pathname.startsWith("/api/plugin/")) {
    if (!pluginAuthorized(req)) {
      sendJson(res, 401, { ok: false, message: config.pluginApiKey ? "Wrong API key." : "PLUGIN_API_KEY isn't set on the website." });
      return true;
    }
    if (pathname === "/api/plugin/ping") {
      sendJson(res, 200, { ok: true, message: "Connected to the Moddedrealms website." });
      return true;
    }
    const routes = { "/api/plugin/link": pluginLink, "/api/plugin/sync": pluginSync };
    if (!has(routes, pathname)) return false;
    if (method !== "POST") return methodNotAllowed(res, "POST"), true;
    await routes[pathname](req, res);
    return true;
  }

  return false;
}

module.exports = { handleAccountApi, verifyEmailLink };
