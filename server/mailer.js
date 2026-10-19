/*
 * Minimal SMTP client for optional email notifications (no dependencies).
 * Supports implicit TLS (port 465) and STARTTLS (port 587). Credentials are never sent unencrypted.
 */
"use strict";

const net = require("node:net");
const tls = require("node:tls");
const os = require("node:os");
const crypto = require("node:crypto");
const { config } = require("./config");

const smtp = config.smtp;

function mailEnabled() {
  return Boolean(smtp.host && smtp.from);
}

/** Reads SMTP replies (including multi-line "250-..." replies) from a socket. */
function replyReader(socket) {
  let buffer = "";
  let lines = [];
  const waiting = [];
  const ready = [];
  let failure = null;

  function onData(chunk) {
    buffer += chunk.toString("utf8");
    let idx;
    while ((idx = buffer.indexOf("\r\n")) >= 0) {
      const line = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      lines.push(line);
      if (/^\d{3} /.test(line) || /^\d{3}$/.test(line)) {
        const reply = { code: Number(line.slice(0, 3)), lines: lines.map((l) => l.slice(4)) };
        lines = [];
        if (waiting.length) waiting.shift().resolve(reply);
        else ready.push(reply);
      }
    }
  }
  function onError(err) {
    failure = err;
    while (waiting.length) waiting.shift().reject(err);
  }
  function onClose() {
    onError(new Error("SMTP connection closed"));
  }
  socket.on("data", onData);
  socket.on("error", onError);
  socket.on("close", onClose);

  return {
    next() {
      if (ready.length) return Promise.resolve(ready.shift());
      if (failure) return Promise.reject(failure);
      return new Promise((resolve, reject) => waiting.push({ resolve, reject }));
    },
    detach() {
      socket.off("data", onData);
      socket.off("error", onError);
      socket.off("close", onClose);
    },
  };
}

async function command(socket, reader, line, expect) {
  if (line !== null) socket.write(line + "\r\n");
  const reply = await reader.next();
  if (!expect.includes(reply.code)) {
    const shown = line && line.startsWith("AUTH") ? "AUTH ..." : line;
    throw new Error(`SMTP ${shown || "greeting"} failed: ${reply.code} ${reply.lines.join(" ")}`);
  }
  return reply;
}

function connect() {
  return new Promise((resolve, reject) => {
    const opts = { host: smtp.host, port: smtp.port, servername: smtp.host };
    const socket = smtp.secure ? tls.connect(opts) : net.connect(opts);
    socket.setTimeout(20_000, () => socket.destroy(new Error("SMTP timed out")));
    socket.once(smtp.secure ? "secureConnect" : "connect", () => resolve(socket));
    socket.once("error", reject);
  });
}

function encodeHeader(value) {
  return /^[\x20-\x7e]*$/.test(value) ? value : `=?UTF-8?B?${Buffer.from(value, "utf8").toString("base64")}?=`;
}

function buildMessage({ to, subject, text }) {
  const domain = (smtp.from.split("@")[1] || "localhost").replace(/[>\s]/g, "");
  const body = Buffer.from(text.replace(/\r?\n/g, "\r\n"), "utf8")
    .toString("base64")
    .replace(/.{1,76}/g, "$&\r\n");
  return [
    `From: Moddedrealms <${smtp.from}>`,
    `To: <${to}>`,
    `Subject: ${encodeHeader(subject)}`,
    `Date: ${new Date().toUTCString()}`,
    `Message-ID: <${crypto.randomUUID()}@${domain}>`,
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=utf-8",
    "Content-Transfer-Encoding: base64",
    "",
    body,
  ].join("\r\n");
}

/** Send a plain-text email. Resolves true on success, false (and logs) on failure. */
async function sendMail({ to, subject, text }) {
  if (!mailEnabled() || !to) return false;
  if (/[\r\n<>]/.test(to) || /[\r\n]/.test(subject)) return false;

  let socket;
  try {
    socket = await connect();
    let reader = replyReader(socket);
    await command(socket, reader, null, [220]);
    const helo = `EHLO ${os.hostname().replace(/[^\w.-]/g, "") || "localhost"}`;
    let ehlo = await command(socket, reader, helo, [250]);

    if (!smtp.secure) {
      if (!ehlo.lines.some((l) => /^STARTTLS\b/i.test(l))) {
        if (smtp.user) throw new Error("SMTP server doesn't offer STARTTLS; refusing to send the password unencrypted");
      } else {
        await command(socket, reader, "STARTTLS", [220]);
        reader.detach();
        socket = await new Promise((resolve, reject) => {
          const secured = tls.connect({ socket, servername: smtp.host }, () => resolve(secured));
          secured.once("error", reject);
        });
        socket.setTimeout(20_000, () => socket.destroy(new Error("SMTP timed out")));
        reader = replyReader(socket);
        ehlo = await command(socket, reader, helo, [250]);
      }
    }

    if (smtp.user) {
      const auth = Buffer.from(`\0${smtp.user}\0${smtp.pass}`, "utf8").toString("base64");
      await command(socket, reader, `AUTH PLAIN ${auth}`, [235]);
    }
    await command(socket, reader, `MAIL FROM:<${smtp.from}>`, [250]);
    await command(socket, reader, `RCPT TO:<${to}>`, [250, 251]);
    await command(socket, reader, "DATA", [354]);
    await command(socket, reader, buildMessage({ to, subject, text }) + "\r\n.", [250]);
    socket.write("QUIT\r\n");
    socket.end();
    return true;
  } catch (err) {
    console.error(`[email] could not send to ${to.replace(/^(.).*(@.*)$/, "$1***$2")}: ${err.message}`);
    if (socket) socket.destroy();
    return false;
  }
}

module.exports = { sendMail, mailEnabled };
