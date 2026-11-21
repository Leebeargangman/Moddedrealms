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
import { installEdgeGap } from "./edgegap.js";
import { SKILLS, eat, flee, escape } from "./skills.js";
import { VILLAGE_SKILLS, murder } from "./skills-village.js";
import { snapshot, isHostile, isNight, isProtected, isVillager } from "./state.js";
import { describe } from "../ai/actions.js";
import * as board from "../village/board.js";
import * as society from "../village/society.js";
import * as crime from "../village/crime.js";
import * as builder from "../build/builder.js";
import { village, onDeath, exile, announce, positions } from "../village/village.js";

// Every plain-code skill, by action name.
const ALL_SKILLS = { ...SKILLS, ...VILLAGE_SKILLS, build: builder.build, kill: murder };

const { pathfinder, Movements } = pf;

export class CivBot {
  /** def = { name, role, personality } (from the roster) · village = the shared village object */
  constructor(def, villageObj = village) {
    this.def = def;
    this.village = villageObj;
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
    // viewDistance 4 chunks (64 blocks) instead of the default 12: the bots only look ~48 blocks around
    // them, and 20 bots decoding ~600 chunks each kept the one Node thread busy (the bots lagged).
    const bot = mineflayer.createBot({ host, port, version, username: this.def.name, auth: "offline", hideErrors: true, viewDistance: config.server.viewDistance ?? 4 });
    this.bot = bot;
    bot.loadPlugin(pathfinder);
    bot.loadPlugin(collectBlock);
    installEdgeGap(bot); // stops the bot freezing against walls on Paper (see edgegap.js)

    bot.once("spawn", () => this.onFirstSpawn());
    bot.on("chat", (username, message) => this.onChat(username, message));
    bot.on("entityHurt", (entity) => { if (entity === bot.entity) this.onHurt(); });
    bot.on("health", () => this.onHealth());
    bot.on("death", () => {
      // Permadeath: this villager is gone for good (the village records it, tells witnesses and
      // brings a newcomer later). Its items stay where it died: a clue for whoever finds them.
      const pos = this.lastPos || bot.entity?.position;
      this.cancelTask();
      log.warn(this.def.name, `died at ${pos?.floored?.()}`);
      if (pos) onDeath(this, pos, this.lastHurtBy ? `hurt by ${this.lastHurtBy}` : null);
    });
    bot.on("move", () => { if (bot.entity?.position) this.lastPos = bot.entity.position.clone(); });
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
    // Spawn protection: the server undoes any block we break or place near the world spawn, and the
    // pathfinder would try the same dig forever. So paths must not break or place blocks there.
    bot.civWorldSpawn = (bot.spawnPoint || bot.entity.position).clone();
    bot.civSpawnProtection = config.server.spawnProtection || 0;
    if (bot.civSpawnProtection > 0) {
      const forbid = (block) => (isProtected(bot, block.position) ? 1e6 : 0);
      moves.exclusionAreasBreak.push(forbid);
      moves.exclusionAreasPlace.push(forbid);
    }
    // Never dig through the village's buildings to take a shortcut.
    moves.exclusionAreasBreak.push((block) => (builder.insideBuilding(block.position) ? 1e6 : 0));
    bot.pathfinder.setMovements(moves);
    // More time to plan paths (default 5 s gave "took too long to decide path" in hilly forests).
    bot.pathfinder.thinkTimeout = 10000;
    // At most 3 ms of path planning per game tick per bot (default 40 ms). All bots share one Node
    // thread: with 20 bots planning at once, 40 ms each froze every bot's movement (the "lag").
    bot.pathfinder.tickTimeout = 3;
    if (!this.village.pos) {
      this.village.pos = (bot.spawnPoint || bot.entity.position).clone();
      log.info("village", `centre set to the world spawn ${this.village.pos.floored()}`);
    }
    this.ready = true;
    this.wasNight = isNight(bot);
    log.info(this.def.name, `joined at ${bot.entity.position.floored()}`);
    this.brain.notify(this.brain.memory ? "I logged back in." : "I just arrived in this world. Time to start the village.", "arrived");

    // Cheap 5-second check for night/morning and being idle (no AI involved).
    this.ticker = setInterval(() => this.tick(), 5000);
  }

  tick() {
    if (!this.ready) return;
    this.checkStuck();
    const night = isNight(this.bot);
    if (night !== this.wasNight) {
      this.wasNight = night;
      this.brain.notify(night ? "Night is falling: monsters will come out." : "Morning has come.", night ? "night" : "morning");
    }
    const idleFor = Date.now() - this.lastActivity;
    if (!this.currentTask && idleFor > config.ai.idleMs) {
      this.lastActivity = Date.now();
      this.brain.notify(`I have been idle for ${Math.round(idleFor / 60000)} min.`, "idle");
    }
  }

  /** Cancel a task that has made no progress (not moved, inventory unchanged) for 45 s. */
  checkStuck() {
    const t = this.currentTask;
    const moving = t && ["gather", "goto", "explore", "flee"].includes(t.action.action);
    const pos = this.bot.entity.position;
    const items = this.bot.inventory.items().reduce((n, it) => n + it.count, 0);
    const p = this.progress;
    if (!moving || !p || p.task !== t || pos.distanceTo(p.pos) > 1.5 || items !== p.items) {
      this.progress = { task: t, pos: pos.clone(), items, at: Date.now() };
      return;
    }
    if (Date.now() - p.at < 45000) return;
    log.warn(this.def.name, `stuck on ${describe(t.action)}: no movement or new items for 45 s, trying to get free`);
    this.cancelTask();
    this.progress = null;
    escape(this.bot).catch(() => false).then((free) => {
      if (t.action.boardId) board.finish(t.action.boardId, false, "stuck");
      this.brain.notify(`Task failed: ${describe(t.action)} (I was stuck for 45 s${free ? " and got free" : " and couldn't get free"}; try another place or action)`, "task");
    });
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
    const villager = isVillager(username);
    const text = `${username} (${villager ? "villager" : "player, not a villager"}) says: "${message.slice(0, 120)}"`;
    if (!villager) return this.brain.notify(text, "chat-player");
    // Villagers talking to each other could ping-pong forever (each line = an AI call). So another
    // villager's chat wakes this bot at most chat.villagerWakesPer5Min times in 5 minutes, and only if it
    // named us or we are idle; otherwise it is just remembered for the next call.
    this.chatWakes = (this.chatWakes || []).filter((t) => Date.now() - t < 300000);
    if ((named || !this.currentTask) && this.chatWakes.length < config.chat.villagerWakesPer5Min) {
      this.chatWakes.push(Date.now());
      this.brain.notify(text, "chat-villager");
    } else {
      this.brain.note(text, "chat-villager");
    }
  }

  onHurt() {
    const bot = this.bot;
    const me = bot.entity.position;
    const attacker = Object.values(bot.entities)
      .filter((e) => e !== bot.entity && e.position && e.position.distanceTo(me) < 6 && (isHostile(e) || e.type === "player"))
      .sort((a, b) => a.position.distanceTo(me) - b.position.distanceTo(me))[0];

    if (attacker?.type === "player") this.lastHurtBy = attacker.username;
    else if (attacker) this.lastHurtBy = `a ${attacker.name}`;
    // Reflex (plain code): fight back if healthy and it's a mob, otherwise run.
    if (!["attack", "flee", "kill"].includes(this.currentTask?.action.action)) {
      if (attacker && attacker.type !== "player" && bot.health > 10) this.perform({ action: "attack", target: attacker.name }, true);
      else this.perform({ action: "flee", reflex: true }, true);
    }
    // Tell the brain, but at most once every 15 s (a fight is many hits).
    if (!this.flags.hurtAt || Date.now() - this.flags.hurtAt > 15000) {
      this.flags.hurtAt = Date.now();
      if (attacker?.type === "player") this.brain.grudge(attacker.username); // remembered in every prompt
      this.brain.notify(`I was attacked${attacker ? ` by ${attacker.username || attacker.name}` : ""}! Health ${Math.round(bot.health)}/20.`, "attacked");
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
    if (on && !this.flags[key]) this.brain.notify(text, "health");
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
    // Skip lines that are nearly the same as something we said in the last 5 minutes
    // (stops "Creepers are near. I'll keep clear." every 20 seconds).
    const words = new Set(text.toLowerCase().match(/[a-z']+/g) || []);
    this.said = (this.said || []).filter((s) => Date.now() - s.at < 300000);
    const similar = this.said.some((s) => {
      const shared = [...words].filter((w) => s.words.has(w)).length;
      return shared / Math.max(1, new Set([...words, ...s.words]).size) >= 0.5;
    });
    if (similar) return;
    this.said.push({ at: Date.now(), words });
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
    if (action.action === "post") {
      // "gather oak_log" -> job { action: gather, target: oak_log }. Posting doesn't stop the current task.
      const [jobAction, ...rest] = action.target.split(/\s+/);
      const res = board.post({ by: this.def.name, action: jobAction, target: rest.join("_") || null, amount: action.amount ?? null });
      const text = res.ok ? `I posted job #${res.id}: ${action.target}${action.amount ? ` x${action.amount}` : ""}.` : `Couldn't post the job: ${res.detail}.`;
      if (this.currentTask) this.brain.note(text, "board"); else this.brain.notify(text, "board");
      return;
    }
    if (action.action === "claim") {
      const res = board.claim(Number(action.target), this.def.name);
      if (!res.ok) return this.brain.notify(`Couldn't take the job: ${res.detail}.`, "board");
      const t = res.task;
      action = { action: t.action, target: t.target || undefined, amount: t.amount || undefined, boardId: t.id, say: undefined };
    }
    // Government: quick actions that don't stop the current task.
    const me = this.def.name;
    const alive = () => [...village.bots.values()].filter((b) => b.ready).map((b) => b.def.name);
    const quick = (text) => (this.currentTask ? this.brain.note(text, "society") : this.brain.notify(text, "society"));
    if (action.action === "vote") {
      const r = society.vote(me, action.target, alive());
      return quick(r.ok ? `I ${r.detail}.` : `My vote didn't count: ${r.detail}.`);
    }
    if (action.action === "law") {
      const r = society.law(me, action.target, village.day);
      if (r.ok && r.announce) announce(r.announce, "society");
      return quick(r.ok ? `I ${r.detail}.` : `No law: ${r.detail}.`);
    }
    if (action.action === "exile") {
      const r = exile(me, action.target, action.say);
      return quick(r.ok ? `I ${r.detail}.` : `Couldn't exile: ${r.detail}.`);
    }
    if (action.action === "kill") {
      // Imposters only (the schema already hides it from everyone else). The rules are checked in plain code.
      const name = [...village.bots.keys()].find((n) => n.toLowerCase() === String(action.target).toLowerCase()) || action.target;
      const r = crime.canKill(me, name, village.day, positions());
      if (!r.ok) return this.brain.notify(`I didn't attack ${name}: ${r.detail}.`, "task");
      crime.attackStarted(me, name);
      action = { ...action, target: name };
    }
    const skill = ALL_SKILLS[action.action];
    if (!skill) return;
    // Asked to do exactly what we are already doing (e.g. "hide" while hiding): just carry on.
    const cur = this.currentTask?.action;
    if (cur && cur.action === action.action && (cur.target || "") === (action.target || "")) return;
    this.cancelTask();
    const task = { action, started: Date.now(), cancelled: false };
    this.currentTask = task;
    this.lastActivity = Date.now();
    const ctx = {
      village: this.village.pos,
      name: me,
      day: village.day,
      post: (job) => board.post({ by: me, ...job }),
      withdraw: (target, amount) => VILLAGE_SKILLS.withdraw(this.bot, { target, amount }, task, ctx),
      announce: (text) => announce(text, "news"),
    };

    skill(this.bot, action, task, ctx)
      .catch((e) => ({ ok: false, detail: e.message }))
      .then((result) => {
        if (task.cancelled) return; // replaced by another task: nobody cares about this result
        this.currentTask = null;
        this.lastActivity = Date.now();
        const verb = result.ok ? "Task done" : "Task failed";
        if (action.boardId) board.finish(action.boardId, result.ok, result.detail);
        log.info(this.def.name, `${verb.toLowerCase()}: ${describe(action)} -> ${result.detail}`);
        const job = action.boardId ? ` [job #${action.boardId} ${result.ok ? "done" : "back on the board"}]` : "";
        if (!reflex || !result.ok) this.brain.notify(`${verb}: ${describe(action)} (${result.detail})${job}`, "task");
      });
  }

  /** Leave the village for good (died or exiled): log off and never reconnect. */
  leave(why) {
    log.info(this.def.name, `leaves the village for good (${why})`);
    setTimeout(() => this.quit(why), 1500); // give the death screen / exile message a moment
  }

  /** Leave the server cleanly. */
  quit(reason = "session over") {
    this.stopping = true;
    board.release(this.def.name);
    this.brain.stop();
    this.brain.saveMemory();
    this.cancelTask();
    clearInterval(this.ticker);
    try { this.bot.quit(reason); } catch { /* already gone */ }
  }
}
