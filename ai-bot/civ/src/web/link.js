// Link to the website (Bots page), the same way the existing bot manager connects: only outbound HTTPS,
// authenticated with BOT_MANAGER_KEY, as a bot host named "civ".
//   - every 5 s: POST /api/botlink/events with the village snapshot (status.js) and new events
//   - long-poll /api/botlink/poll for staff commands: civStart, civStop, civPause, civResume
//
// The website URL and key are read from the existing manager's config (../manager/manager.json, so the
// key is not copied anywhere), or from .env: CIV_WEBSITE_URL and BOT_MANAGER_KEY.

import fs from "node:fs";
import path from "node:path";
import { ROOT, config } from "../config.js";
import { log } from "../log.js";
import { snapshot } from "./status.js";
import { setPaused } from "../ai/client.js";
import { schedule } from "../schedule.js";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function websiteSettings() {
  const w = config.website || {};
  let url = process.env.CIV_WEBSITE_URL || w.url || "";
  let key = process.env.BOT_MANAGER_KEY || "";
  if ((!url || !key) && w.managerConfig) {
    try {
      const m = JSON.parse(fs.readFileSync(path.resolve(ROOT, w.managerConfig), "utf8"));
      url = url || m.website?.url || "";
      key = key || m.website?.key || "";
    } catch {
      // no manager config on this machine
    }
  }
  return { url: url.replace(/\/$/, ""), key, name: (w.managerName || "civ").replace(/[^\w.-]/g, "-").slice(0, 32) };
}

export function connectWebsite(control) {
  if (config.website?.enabled === false) return;
  const { url, key, name } = websiteSettings();
  if (!url || !key) {
    log.info("website", "no website link configured (see config.json website): the Bots page won't show the village");
    return;
  }
  const headers = { authorization: `Bearer ${key}`, "x-manager-name": name };
  const outbox = [];
  const post = async (p, body) => {
    const res = await fetch(url + p, { method: "POST", headers: { ...headers, "content-type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(15000) });
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.ok === false) throw new Error(data.error || `HTTP ${res.status}`);
  };

  // Staff commands from the Bots page. Starting costs money, so the website only lets admins do it.
  async function run(cmd) {
    let r;
    try {
      if (cmd.command === "civStart") r = await control.startSession(`started from the website by ${cmd.staff?.name || "staff"}`, Number(cmd.args?.minutes) || schedule().manualMinutes);
      else if (cmd.command === "civStop") r = await control.endSession(`stopped from the website by ${cmd.staff?.name || "staff"}`);
      else if (cmd.command === "civPause") { setPaused(true); r = { ok: true, detail: "AI paused: villagers use plain-code behaviour, no API calls" }; }
      else if (cmd.command === "civResume") { setPaused(false); r = { ok: true, detail: "AI resumed" }; }
      else r = { ok: false, detail: `unknown command ${cmd.command}` };
    } catch (e) {
      r = { ok: false, detail: e.message };
    }
    log.info("website", `${cmd.staff?.name || "staff"}: ${cmd.command} -> ${r.detail}`);
    outbox.push({ type: "result", id: cmd.id, ok: r.ok, text: r.detail, ...(r.ok ? {} : { error: r.detail }) });
  }

  (async function pushLoop() {
    let failing = false;
    for (;;) {
      try {
        await post("/api/botlink/events", { events: outbox.splice(0, 200), snapshot: snapshot(control) });
        if (failing) log.info("website", "connected to the website again");
        failing = false;
      } catch (e) {
        if (!failing) log.warn("website", `can't reach the website: ${e.message}`);
        failing = true;
      }
      await sleep(5000);
    }
  })();

  (async function pollLoop() {
    let backoff = 2000;
    for (;;) {
      try {
        const res = await fetch(url + "/api/botlink/poll", { headers, signal: AbortSignal.timeout(40000) });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
        backoff = 2000;
        for (const cmd of data.items ?? []) run(cmd);
      } catch {
        await sleep(backoff);
        backoff = Math.min(backoff * 2, 60000);
      }
    }
  })();
  log.info("website", `linked to ${url} as bot host "${name}"`);
}
