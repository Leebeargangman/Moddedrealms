// When the villagers play: config.schedule = { timezone, start: "17:30", end: "20:30", days }.
// Times are wall-clock times in that timezone (Europe/London), so British Summer Time is handled by
// itself: 17:30 is always 17:30 in the UK, whatever the server's own clock says (the VPS is on Berlin time).

import { config } from "./config.js";

export const schedule = () => ({ timezone: "Europe/London", start: "17:30", end: "20:30", days: [0, 1, 2, 3, 4, 5, 6], manualMinutes: 60, ...(config.schedule || {}) });

/** { day: 0-6 (Sunday = 0), minutes: minutes since midnight } in the schedule's timezone. */
export function localNow(date = new Date()) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", {
    timeZone: schedule().timezone, weekday: "short", hour: "2-digit", minute: "2-digit", hour12: false,
  }).formatToParts(date).map((p) => [p.type, p.value]));
  const day = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].indexOf(parts.weekday);
  return { day, minutes: (Number(parts.hour) % 24) * 60 + Number(parts.minute) };
}

const toMin = (hhmm) => { const [h, m] = String(hhmm).split(":").map(Number); return h * 60 + (m || 0); };

/** True while inside today's play window. */
export function inWindow(date = new Date()) {
  const s = schedule();
  const { day, minutes } = localNow(date);
  return s.days.includes(day) && minutes >= toMin(s.start) && minutes < toMin(s.end);
}

export const windowText = () => { const s = schedule(); return `${s.start}-${s.end}`; };

/** When the next window starts (a Date), looking ahead minute by minute up to 8 days (cheap enough). */
export function nextStart(from = new Date()) {
  const t = new Date(from.getTime());
  t.setSeconds(0, 0);
  for (let i = 0; i < 8 * 24 * 60; i++) {
    t.setTime(t.getTime() + 60000);
    if (inWindow(t) && !inWindow(new Date(t.getTime() - 60000))) return t;
  }
  return null;
}
