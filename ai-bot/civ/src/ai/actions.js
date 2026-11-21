// The JSON action system: what the AI is allowed to answer, and how its answer is checked.
//
// The AI replies with ONE small JSON object, for example:
//   {"action":"gather","target":"oak_log","amount":16,"say":"Getting wood for the hall"}
// We never trust it blindly: validate() checks every field and anything wrong falls back to a safe
// default action. We never re-ask the AI in a loop (that would cost money).

import { config } from "../config.js";

// Every action a bot can do as plain code. Keep this list short: it is part of every prompt.
export const ACTIONS = [
  "gather",   // collect blocks: target = block or item name (oak_log, stone, coal_ore, sand, "wood"), amount
  "craft",    // craft an item: target = item name (oak_planks, stick, crafting_table, wooden_pickaxe...), amount
  "eat",      // eat the best food in the inventory
  "goto",     // walk somewhere: target = "x,z", "x,y,z", "village" or a player name
  "follow",   // follow a player: target = player name, amount = seconds (default 60)
  "explore",  // wander 30-60 blocks in a new direction to find resources
  "attack",   // fight the nearest mob of a type: target = zombie, skeleton, spider... (or "hostile")
  "flee",     // run away from nearby danger
  "sleep",    // sleep in a nearby bed (only works at night)
  "chat",     // only talk (put the message in "say")
  "post",     // put a job on the village task board: target = "gather oak_log" / "craft stone_pickaxe" / "explore", amount
  "claim",    // take a job from the task board and do it: target = the job number, e.g. "4"
  "wait",     // stand still: amount = seconds (default 30)
  "continue", // keep doing the current task
];

// Words the AI sometimes uses instead of the real action names.
const ALIASES = { mine: "gather", chop: "gather", collect: "gather", dig: "gather", walk: "goto", move: "goto",
  go: "goto", talk: "chat", say: "chat", fight: "attack", run: "flee", rest: "wait", idle: "wait", wander: "explore" };

// The JSON schema sent with each request (structured outputs), so the API itself guarantees valid JSON.
export const ACTION_SCHEMA = {
  type: "object",
  properties: {
    action: { type: "string", enum: ACTIONS },
    target: { type: "string" },
    amount: { type: "integer" },
    say: { type: "string" },
  },
  required: ["action"],
  additionalProperties: false,
};

const DEFAULT_AMOUNT = { gather: 8, craft: 1, follow: 60, wait: 30 };
// Which actions use a target / an amount (anything else the AI sends for them is dropped).
const USES_TARGET = new Set(["gather", "craft", "goto", "follow", "attack", "post", "claim"]);

/**
 * Parse and check the AI's reply. Returns { ok: true, action } or { ok: false, error }.
 * `text` is the raw text the model wrote.
 */
export function parseAction(text) {
  let obj;
  try {
    // Be forgiving about stray text around the object (only needed if structured outputs are off).
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start < 0 || end < start) return { ok: false, error: "no JSON object" };
    obj = JSON.parse(text.slice(start, end + 1));
  } catch (e) {
    return { ok: false, error: `invalid JSON (${e.message})` };
  }
  if (!obj || typeof obj !== "object") return { ok: false, error: "not an object" };

  let name = String(obj.action || "").toLowerCase().trim();
  name = ALIASES[name] || name;
  if (!ACTIONS.includes(name)) return { ok: false, error: `unknown action "${obj.action}"` };

  const action = { action: name };
  if (USES_TARGET.has(name) && typeof obj.target === "string" && obj.target.trim()) {
    action.target = obj.target.trim().toLowerCase().replace(/^minecraft:/, "").slice(0, 48);
  }
  const amount = !(name in DEFAULT_AMOUNT) && !(name === "post" && Number.isFinite(obj.amount)) ? undefined : Number.isFinite(obj.amount) ? Math.round(obj.amount) : DEFAULT_AMOUNT[name];
  if (amount !== undefined) action.amount = Math.max(1, Math.min(name === "follow" || name === "wait" ? 300 : 64, amount));
  const say = cleanSay(obj.say);
  if (say) action.say = say;
  if (name === "chat" && !action.say) return { ok: false, error: "chat without a message" };
  if ((name === "post" || name === "claim") && !action.target) return { ok: false, error: `${name} without a target` };
  if (name === "claim") action.target = action.target.replace(/[^0-9]/g, "");
  return { ok: true, action };
}

/** Make chat text safe: one short line, and never a /command (a bot must not run server commands). */
export function cleanSay(s) {
  if (typeof s !== "string") return "";
  return s.replace(/[\r\n]+/g, " ").replace(/§./g, "").replace(/^[\s/]+/, "").trim().slice(0, config.chat.maxSayLength);
}

/** The safe default when the AI can't be asked (budget, errors) or its answer was unusable. Plain code. */
export function fallbackAction(s) {
  if (s.food <= 14 && s.hasFood) return { action: "eat" };
  if (s.dangerNear) return { action: "flee" };
  if (s.isNight) return s.bedNear ? { action: "sleep" } : { action: "goto", target: "village" };
  if (s.logs < 16) return { action: "gather", target: "wood", amount: 8 };
  return { action: "explore" };
}

/** Short text for logs, e.g. `gather oak_log x16 "Getting wood"`. */
export function describe(a) {
  return [a.action, a.target, a.amount !== undefined && a.action !== "eat" ? `x${a.amount}` : "", a.say ? `"${a.say}"` : ""]
    .filter(Boolean).join(" ");
}
