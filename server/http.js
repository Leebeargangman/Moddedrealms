/*
 * Small HTTP helpers shared by the routes.
 */
"use strict";

const { config } = require("./config");

const JSON_TYPE = "application/json; charset=utf-8";
const TEXT_TYPE = "text/plain; charset=utf-8";

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.statusCode = status;
  res.setHeader("Content-Type", JSON_TYPE);
  res.setHeader("Content-Length", Buffer.byteLength(body));
  res.end(res.req && res.req.method === "HEAD" ? undefined : body);
}

function sendText(res, status, text) {
  res.statusCode = status;
  res.setHeader("Content-Type", TEXT_TYPE);
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
        const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
        if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("not an object");
        resolve(body);
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

/** Read a JSON body, answering the request with an error (and returning null) if it can't be read. */
async function jsonBodyOrError(req, res, limit) {
  try {
    return await readJsonBody(req, limit);
  } catch (err) {
    if (err.status === 413) res.setHeader("Connection", "close");
    sendJson(res, err.status || 400, { ok: false, error: err.status ? err.message : "Could not read your request." });
    return null;
  }
}

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

function isSecureRequest(req) {
  if (req.socket.encrypted) return true;
  if (config.trustProxy && firstHeader(req.headers["x-forwarded-proto"]).toLowerCase() === "https") return true;
  return config.siteUrl.startsWith("https://");
}

/** Absolute site URL used for canonical/Open Graph tags and links in emails. */
function siteUrlFor(req) {
  if (config.siteUrl) return config.siteUrl;
  if (!req) return "";
  const fwdHost = config.trustProxy ? firstHeader(req.headers["x-forwarded-host"]) : "";
  const host = fwdHost || firstHeader(req.headers.host);
  if (!host || !/^[a-z0-9.\-]+(:\d+)?$/i.test(host)) return "";
  const fwdProto = config.trustProxy ? firstHeader(req.headers["x-forwarded-proto"]).toLowerCase() : "";
  const proto = fwdProto === "https" || req.socket.encrypted ? "https" : "http";
  return `${proto}://${host}`;
}

/** Normalise user text: strip control/bidi characters, unify newlines, trim. */
function cleanText(value, { multiline = false } = {}) {
  if (typeof value !== "string") return "";
  let s = value.normalize("NFC").replace(/\r\n?/g, "\n").replace(/\t/g, " ");
  s = s.replace(/[\u0000-\u0009\u000B-\u001F\u007F-\u009F​-‏‪-‮⁦-⁩]/g, "");
  if (!multiline) s = s.replace(/\n/g, " ");
  else s = s.replace(/\n{3,}/g, "\n\n");
  return s.trim();
}

const has = (obj, key) => typeof key === "string" && Object.prototype.hasOwnProperty.call(obj, key);

module.exports = {
  sendJson,
  sendText,
  redirect,
  methodNotAllowed,
  httpError,
  firstHeader,
  readJsonBody,
  jsonBodyOrError,
  clientIp,
  sameOrigin,
  isSecureRequest,
  siteUrlFor,
  cleanText,
  has,
};
