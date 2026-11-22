// Global rate limiter: at most N AI calls in any 60 seconds (config rateLimit.requestsPerMinute),
// shared by all bots. Calls wait their turn in a first-come-first-served queue instead of failing.
//
// With 20 bots and a 20 s cooldown the bots could ask up to 60 times a minute in a busy moment
// (everyone attacked at nightfall), so this smooths those bursts out and keeps us far from the API's
// own limits (which would cost retries).

import { config } from "../config.js";
import { log } from "../log.js";

const WINDOW_MS = 60000;
const recent = [];   // start times of the calls in the last minute
let chain = Promise.resolve(); // the queue: each caller waits for the one before it
export const stats = { calls: 0, waited: 0, totalWaitMs: 0, maxWaitMs: 0, maxQueue: 0 };
let queued = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Wait until this call is allowed. Resolves with how long it waited (ms). */
export function takeSlot(priority = false) {
  const limit = config.rateLimit.requestsPerMinute;
  const asked = Date.now();
  queued++;
  stats.maxQueue = Math.max(stats.maxQueue, queued);
  const wait = async () => {
    for (;;) {
      const now = Date.now();
      while (recent.length && now - recent[0] >= WINDOW_MS) recent.shift();
      if (recent.length < limit) break;
      await sleep(Math.min(recent[0] + WINDOW_MS - now + 5, priority ? 250 : 60000));
    }
    recent.push(Date.now());
  };
  // A player waiting for an answer doesn't queue behind 20 villagers: it takes the next free slot.
  let mine;
  if (priority) mine = wait();
  else { mine = chain.then(wait); chain = mine.catch(() => {}); }
  return mine.then(() => {
    queued--;
    const waited = Date.now() - asked;
    stats.calls++;
    if (waited > 50) {
      stats.waited++;
      stats.totalWaitMs += waited;
      stats.maxWaitMs = Math.max(stats.maxWaitMs, waited);
      if (waited > 5000) log.info("ratelimit", `a call waited ${Math.round(waited / 1000)} s for a slot (${config.rateLimit.requestsPerMinute}/min limit)`);
    }
    return waited;
  });
}
