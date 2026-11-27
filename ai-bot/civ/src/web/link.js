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
import { schedule, inWindow, windowKey } from "../schedule.js";
import { kv } from "../village/db.js";
import * as settings from "../settings.js";
import { resetMemories, resetEverything } from "../village/reset.js";
import { village } from "../village/village.js";

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
      if (cmd.command === "civStart") kv("scheduleSkip", null); // a manual start cancels "done for today"
      if (cmd.command === "civStart") r = await control.startSession(`started from the website by ${cmd.staff?.name || "staff"}`, Number(cmd.args?.minutes) || schedule().manualMinutes);
      else if (cmd.command === "civStop") {
        r = await control.endSession(`stopped from the website by ${cmd.staff?.name || "staff"}`);
        // Done for today: the schedule must not start it again until the next window.
        if (inWindow()) { kv("scheduleSkip", windowKey()); r = { ...r, detail: `${r.detail}; no more sessions until the next scheduled window` }; }
      }
      else if (cmd.command === "civPause") { setPaused(true); r = { ok: true, detail: "AI paused: villagers use plain-code behaviour, no API calls" }; }
      else if (cmd.command === "civResume") { setPaused(false); r = { ok: true, detail: "AI resumed" }; }
      else if (cmd.command === "civReset") {
        // "memories": live, the session keeps running. "everything": end the session, wipe, start again.
        if (cmd.args?.scope === "everything") {
          const s = control.getSession();
          if (s) {
            r = await control.restartSession(`new village started by ${cmd.staff?.name || "staff"}`,
              s.reason === "schedule" ? "schedule" : `new village started by ${cmd.staff?.name || "staff"}`,
              s.reason === "schedule" ? null : schedule().manualMinutes, () => resetEverything());
          } else r = resetEverything();
        } else {
          r = resetMemories([...village.bots.values()]);
        }
      }
      else if (cmd.command === "civSettings") {
        r = settings.save(cmd.args || {});
        // "Save and restart": apply villagers/civilisations/instructions right away.
        if (r.ok && cmd.args?.restart && control.getSession()) {
          const reason = control.getSession().reason;
          await control.restartSession(`restarted by ${cmd.staff?.name || "staff"} with new settings`,
            reason === "schedule" ? "schedule" : `restarted from the website by ${cmd.staff?.name || "staff"}`, reason === "schedule" ? null : schedule().manualMinutes);
          r = { ok: true, detail: "settings saved and the session restarted with them" };
        } else if (r.ok) r = { ok: true, detail: control.getSession() ? "settings saved: villagers, civilisations and instructions apply from the next session (or use save and restart)" : "settings saved" };
        if (r.ok) log.info("website", `settings changed by ${cmd.staff?.name || "staff"}: ${JSON.stringify(settings.get()).slice(0, 300)}`);
      }
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
      const events = outbox.splice(0, 200);
      try {
        await post("/api/botlink/events", { events, snapshot: snapshot(control) });
        if (failing) log.info("website", "connected to the website again");
        failing = false;
      } catch (e) {
        outbox.unshift(...events); // keep command results to send again (e.g. the website restarting)
        if (outbox.length > 500) outbox.splice(0, outbox.length - 500);
        if (!failing) log.warn("website", `couldn't update the website: ${e.message}`);
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
