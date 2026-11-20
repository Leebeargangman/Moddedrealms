// One villager: a Mineflayer bot (its body) plus a Brain (when to ask the AI).
//
// The body watches the game and reports EVENTS to the brain. It also has REFLEXES that run
// instantly as plain code, without waiting for the AI: eating when hungry, fighting back or
// running away when attacked.

import mineflayer from "mineflayer";
import pf from "mineflayer-pathfinder";
import { plugin as collectBlock } from "mineflayer-collectblock";
import { config } from "../config.js";
import { log } from "../log.js";
import { Brain } from "./brain.js";
import { SKILLS, eat, flee } from "./skills.js";
import { snapshot, isHostile, isNight } from "./state.js";
import { describe } from "../ai/actions.js";

const { pathfinder, Movements } = pf;

export class CivBot {
  /** def = { name, role, personality } from config.json · village = shared { pos } object */
  constructor(def, village) {
    this.def = def;
    this.village = village;
    this.brain = new Brain(this);
    this.currentTask = null;
    this.ready = false;
    this.stopping = false;
    this.lastActivity = Date.now();
    this.lastSay = 0;
    this.flags = {}; // remembers which warnings we already reported (low food, low health...)
  }

  start() {
    const { host, port, version } = config.server;
    log.info(this.def.name, `connecting to ${host}:${port}`);
    const bot = mineflayer.createBot({ host, port, version, username: this.def.name, auth: "offline", hideErrors: true });
    this.bot = bot;
    bot.loadPlugin(pathfinder);
    bot.loadPlugin(collectBlock);

    bot.once("spawn", () => this.onFirstSpawn());
    bot.on("chat", (username, message) => this.onChat(username, message));
    bot.on("entityHurt", (entity) => { if (entity === bot.entity) this.onHurt(); });
    bot.on("health", () => this.onHealth());
    bot.on("death", () => {
      this.cancelTask();
      this.brain.notify("I died and respawned. My items were dropped where I died.");
    });
    bot.on("kicked", (reason) => log.warn(this.def.name, `kicked: ${typeof reason === "string" ? reason : JSON.stringify(reason)}`));
    bot.on("error", (err) => log.error(this.def.name, err.message));
    bot.on("end", (reason) => this.onEnd(reason));
  }

  async onFirstSpawn() {
    const bot = this.bot;
    // Wait until the world around us has loaded, or the bot can't see any blocks yet.
    try {
      await bot.waitForChunksToLoad();
    } catch {
      // carry on with what we have
    }
    const moves = new Movements(bot);
    moves.allowParkour = false;   // fewer odd jumps: looks more natural and upsets anti-cheats less
    moves.canDig = true;
    bot.pathfinder.setMovements(moves);
    if (!this.village.pos) {
      this.village.pos = (bot.spawnPoint || bot.entity.position).clone();
      log.info("village", `centre set to the world spawn ${this.village.pos.floored()}`);
    }
    this.ready = true;
    this.wasNight = isNight(bot);
    log.info(this.def.name, `joined at ${bot.entity.position.floored()}`);
    this.brain.notify(this.brain.memory ? "I logged back in." : "I just arrived in this world. Time to start the village.");

    // Cheap 5-second check for night/morning and being idle (no AI involved).
    this.ticker = setInterval(() => this.tick(), 5000);
  }

  tick() {
    if (!this.ready) return;
    const night = isNight(this.bot);
    if (night !== this.wasNight) {
      this.wasNight = night;
      this.brain.notify(night ? "Night is falling: monsters will come out." : "Morning has come.");
    }
    const idleFor = Date.now() - this.lastActivity;
    if (!this.currentTask && idleFor > config.ai.idleMs) {
      this.lastActivity = Date.now();
      this.brain.notify(`I have been idle for ${Math.round(idleFor / 60000)} min.`);
    }
  }

  snapshot() {
    return snapshot(this.bot, this.village.pos);
  }

  // --- events -----------------------------------------------------------------------------------
  onChat(username, message) {
    if (!this.ready || username === this.def.name) return;
    // Only listen when we are named or the speaker is close by: everyone else's chat is not for us.
    const speaker = this.bot.players[username]?.entity;
    const close = speaker && speaker.position.distanceTo(this.bot.entity.position) <= config.chat.hearRadius;
    const named = message.toLowerCase().includes(this.def.name.toLowerCase());
    if (!close && !named) return;
    this.brain.notify(`${username} says: "${message.slice(0, 120)}"`);
  }

  onHurt() {
    const bot = this.bot;
    const me = bot.entity.position;
    const attacker = Object.values(bot.entities)
      .filter((e) => e !== bot.entity && e.position && e.position.distanceTo(me) < 6 && (isHostile(e) || e.type === "player"))
      .sort((a, b) => a.position.distanceTo(me) - b.position.distanceTo(me))[0];

    // Reflex (plain code): fight back if healthy and it's a mob, otherwise run.
    if (this.currentTask?.action.action !== "attack" && this.currentTask?.action.action !== "flee") {
      if (attacker && attacker.type !== "player" && bot.health > 10) this.perform({ action: "attack", target: attacker.name }, true);
      else this.perform({ action: "flee" }, true);
    }
    // Tell the brain, but at most once every 15 s (a fight is many hits).
    if (!this.flags.hurtAt || Date.now() - this.flags.hurtAt > 15000) {
      this.flags.hurtAt = Date.now();
      this.brain.notify(`I was attacked${attacker ? ` by ${attacker.username || attacker.name}` : ""}! Health ${Math.round(bot.health)}/20.`);
    }
  }

  onHealth() {
    const bot = this.bot;
    if (!this.ready) return;
    // Reflex: eat as soon as hunger drops a bit, if we carry food.
    if (bot.food <= 14 && !this.eating && this.currentTask?.action.action !== "attack") {
      this.eating = true;
      eat(bot).catch(() => {}).finally(() => { this.eating = false; });
    }
    this.flag("lowFood", bot.food <= 6, "I'm very hungry and need food.");
    this.flag("lowHealth", bot.health <= 6, `My health is low (${Math.round(bot.health)}/20).`);
  }

  /** Report a warning once when it starts being true (not on every health packet). */
  flag(key, on, text) {
    if (on && !this.flags[key]) this.brain.notify(text);
    this.flags[key] = on;
  }

  onEnd(reason) {
    this.ready = false;
    clearInterval(this.ticker);
    this.cancelTask();
    if (this.stopping) return;
    log.warn(this.def.name, `disconnected (${reason}); reconnecting in 30 s`);
    setTimeout(() => { if (!this.stopping) this.start(); }, 30000);
  }

  // --- actions ----------------------------------------------------------------------------------
  cancelTask() {
    const t = this.currentTask;
    if (!t) return;
    t.cancelled = true;
    this.currentTask = null;
    try {
      this.bot.pathfinder.setGoal(null);
      this.bot.collectBlock.cancelTask().catch(() => {});
    } catch {
      // the bot may already be disconnected
    }
  }

  say(text) {
    if (!text || Date.now() - this.lastSay < config.chat.minSayGapMs) return;
    this.lastSay = Date.now();
    this.bot.chat(text);
  }

  /** Start doing an action. `reflex` = started by code (not the AI): it doesn't wake the brain when done. */
  perform(action, reflex = false) {
    if (!this.ready) return;
    this.say(action.say);
    if (action.action === "continue" || action.action === "chat") {
      if (!this.currentTask) this.lastActivity = Date.now();
      return; // keep whatever we were doing
    }
    const skill = SKILLS[action.action];
    if (!skill) return;
    this.cancelTask();
    const task = { action, started: Date.now(), cancelled: false };
    this.currentTask = task;
    this.lastActivity = Date.now();
    const ctx = { village: this.village.pos };

    skill(this.bot, action, task, ctx)
      .catch((e) => ({ ok: false, detail: e.message }))
      .then((result) => {
        if (task.cancelled) return; // replaced by another task: nobody cares about this result
        this.currentTask = null;
        this.lastActivity = Date.now();
        const verb = result.ok ? "Task done" : "Task failed";
        log.info(this.def.name, `${verb.toLowerCase()}: ${describe(action)} -> ${result.detail}`);
        if (!reflex || !result.ok) this.brain.notify(`${verb}: ${describe(action)} (${result.detail})`);
      });
  }

  /** Leave the server cleanly. */
  quit(reason = "session over") {
    this.stopping = true;
    this.brain.stop();
    this.brain.saveMemory();
    this.cancelTask();
    clearInterval(this.ticker);
    try { this.bot.quit(reason); } catch { /* already gone */ }
  }
}
