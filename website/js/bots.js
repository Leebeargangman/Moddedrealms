/* Moddedrealms — AI bots (staff) */
(function () {
  "use strict";

  var MR = window.MR;
  var state = { me: null, managers: {}, connectors: [], builds: [], events: [] };
  var selected = null; // { manager, bot }
  var pending = {}; // command id -> callback(result event)
  var modal = $("[data-modal]");

  function $(sel, root) {
    return (root || document).querySelector(sel);
  }

  function $$(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function tag(text, kind) {
    return el("span", "tag" + (kind ? " tag--" + kind : ""), text);
  }

  function button(text, cls, onClick) {
    var b = el("button", "btn btn-sm " + (cls || "btn-ghost"), text);
    b.type = "button";
    b.addEventListener("click", onClick);
    return b;
  }

  function option(value, text, selectedValue) {
    var o = el("option", null, text);
    o.value = value;
    if (value === selectedValue) o.selected = true;
    return o;
  }

  var STATE_TAGS = { online: "green", starting: "yellow", offline: "red", stopped: "grey", "host offline": "grey" };

  /* ---------- Talking to the hub ---------- */

  function command(manager, name, args, onResult) {
    return MR.api("/api/bot/command", { body: { manager: manager, command: name, args: args || {} } }).then(function (r) {
      if (!r.ok) {
        MR.toast(r.data.error || "That didn't work.");
        return r;
      }
      if (onResult) pending[r.data.id] = onResult;
      return r;
    });
  }

  function hosts() {
    return Object.keys(state.managers)
      .map(function (k) {
        return state.managers[k];
      })
      .filter(function (m) {
        return m.snapshot;
      });
  }

  function onlineHosts() {
    return hosts().filter(function (m) {
      return m.online;
    });
  }

  function findBot(sel) {
    var m = sel && state.managers[sel.manager];
    if (!m || !m.snapshot) return null;
    return m.snapshot.bots.filter(function (b) {
      return b.id === sel.bot;
    })[0];
  }

  function handleEvent(ev) {
    if (ev.type === "snapshot") {
      var m = state.managers[ev.manager] || (state.managers[ev.manager] = { name: ev.manager });
      m.snapshot = ev.snapshot;
      m.online = true;
      return render();
    }
    if (ev.type === "manager") {
      var mm = state.managers[ev.manager] || (state.managers[ev.manager] = { name: ev.manager });
      mm.online = ev.online;
      return render();
    }
    if (ev.type === "connector") {
      var known = state.connectors.filter(function (c) {
        return c.name === ev.who;
      })[0];
      if (known) {
        known.online = ev.online;
        if (ev.models) known.models = ev.models;
        if (ev.mem !== undefined) known.mem = ev.mem;
        if (ev.gpu !== undefined) known.gpu = ev.gpu;
      } else state.connectors.push({ name: ev.who, online: ev.online, models: ev.models || [], mem: ev.mem || null, gpu: ev.gpu || null, mine: ev.who === state.me.username });
      return renderConnectors();
    }
    if (ev.type === "upload") {
      state.builds.unshift({ id: ev.id, name: ev.name, uploaded_by: ev.who });
      return renderBuilds();
    }
    if (ev.type === "result" && pending[ev.id]) {
      var cb = pending[ev.id];
      if (ev.command !== "linkMicrosoft" || ev.result !== "starting Microsoft sign-in") delete pending[ev.id];
      cb(ev);
    }
    if (ev.type === "result" && !ev.ok) MR.toast(ev.error);
    if (ev.type === "linkCode" && ev.who === state.me.username) showLinkCode(ev);
    state.events.push(ev);
    if (state.events.length > 600) state.events.shift();
    if (selected && (ev.bot === selected.bot || ev.type === "team")) renderChat();
  }

  function connectStream() {
    var es = new EventSource("/api/bot/stream");
    es.onmessage = function (e) {
      try {
        handleEvent(JSON.parse(e.data));
      } catch (err) {
        /* ignore malformed */
      }
    };
    es.onerror = function () {
      // The browser reconnects by itself; refresh everything once it does.
      es.onopen = function () {
        load();
      };
    };
  }

  function load() {
    return MR.api("/api/bot/state").then(function (r) {
      if (!r.ok) return r;
      state.me = r.data.me;
      state.connectors = r.data.connectors;
      state.builds = r.data.builds;
      state.events = r.data.events;
      state.managers = {};
      r.data.managers.forEach(function (m) {
        state.managers[m.name] = m;
      });
      render();
      return r;
    });
  }

  /* ---------- Rendering ---------- */

  function render() {
    var all = [];
    // Bots on a host that's offline (PC shut down) stay listed, greyed out, so they don't seem to vanish.
    hosts().forEach(function (m) {
      m.snapshot.bots.forEach(function (b) {
        all.push({ manager: m.name, bot: m.online ? b : Object.assign({}, b, { state: "host offline", offlineReason: m.name + " is offline" }) });
      });
    });
    $('[data-stat="hosts"]').textContent = onlineHosts().length;
    $('[data-stat="running"]').textContent = all.filter(function (x) {
      return x.bot.state !== "stopped";
    }).length;
    $('[data-stat="teams"]').textContent = onlineHosts().reduce(function (n, m) {
      return n + m.snapshot.teams.filter(function (t) {
        return t.phase !== "stopped";
      }).length;
    }, 0);
    $('[data-stat="connectors"]').textContent = state.connectors.filter(function (c) {
      return c.online;
    }).length;

    var list = $("[data-bots]");
    list.replaceChildren();
    if (!onlineHosts().length) list.appendChild(offlineNotice());
    else if (!all.length) list.appendChild(el("p", "empty", "No bots yet. Add a Minecraft account, then press New bot."));
    all.forEach(function (x) {
      list.appendChild(botCard(x.manager, x.bot));
    });
    renderDetail();
    renderTeams();
    renderAccounts();
    renderConnectors();
    renderHosts();
  }

  /** No host connected: say which ones are off and how to start one. */
  function offlineNotice() {
    var box = el("div", "notice");
    var names = hosts().map(function (m) {
      return m.name + (m.lastSeen ? " (last seen " + new Date(m.lastSeen).toLocaleString() + ")" : "");
    });
    box.appendChild(el("p", null, names.length ? "Bot host offline: " + names.join(", ") + ". Bots can't be started or added until it's back." : "No bot host has connected yet."));
    var how = el("p", "muted small", "On that computer, open the ai-bot/manager folder and run ");
    how.appendChild(el("code", null, "node src/manager.js"));
    box.appendChild(how);
    return box;
  }

  function botCard(manager, b) {
    var card = el("button", "bot-card" + (selected && selected.bot === b.id ? " is-selected" : ""));
    card.type = "button";
    var head = el("div", "bot-card-head");
    head.appendChild(el("strong", null, b.name));
    head.appendChild(tag(b.state, STATE_TAGS[b.state]));
    card.appendChild(head);
    var meta = [b.account, b.server, manager].filter(Boolean).join(" · ");
    card.appendChild(el("small", null, meta));
    if (b.state === "online" && b.position) {
      var line = el("div", "bot-card-stats");
      line.appendChild(el("span", null, "♥ " + Math.round(b.health || 0)));
      line.appendChild(el("span", null, "🍗 " + (b.food != null ? b.food : "?")));
      line.appendChild(el("span", null, b.position.x + " " + b.position.y + " " + b.position.z));
      line.appendChild(el("span", null, b.queue === "queue" ? "queue #" + (b.queuePosition || "?") : b.task || (b.autonomous ? "thinking" : "idle")));
      card.appendChild(line);
    } else if (b.offlineReason) card.appendChild(el("small", "muted", b.offlineReason));
    card.setAttribute("data-manager", manager);
    card.setAttribute("data-bot", b.id);
    return card;
  }

  function kvRow(dl, k, v) {
    if (v === null || v === undefined || v === "") return;
    dl.appendChild(el("dt", null, k));
    dl.appendChild(el("dd", null, String(v)));
  }

  function renderDetail() {
    var box = $("[data-detail]");
    var b = findBot(selected);
    box.hidden = !b;
    if (!b) return;
    $("[data-detail-name]").textContent = b.name;
    $("[data-detail-tag]").replaceChildren(tag(b.state, STATE_TAGS[b.state]));
    var dl = el("dl", "kv");
    kvRow(dl, "Runs on", selected.manager + (state.managers[selected.manager] && !state.managers[selected.manager].online ? " (offline)" : ""));
    kvRow(dl, "Account", b.account);
    kvRow(dl, "Server", b.server);
    kvRow(dl, "AI", b.model);
    if (b.position) kvRow(dl, "Position", b.position.x + " " + b.position.y + " " + b.position.z + " (" + (b.dimension || "") + ")");
    if (b.health != null) kvRow(dl, "Health / food", Math.round(b.health) + " / " + b.food);
    kvRow(dl, "Doing", b.task || (b.state === "online" ? (b.autonomous ? "thinking" : "waiting for orders") : ""));
    kvRow(dl, "Thinking", b.thought);
    if (b.mode === "anarchy") kvRow(dl, "Anarchy mode", b.escape);
    if (b.queue === "queue") kvRow(dl, "Queue", "#" + (b.queuePosition || "?") + (b.queueEtaMin != null ? ", ~" + b.queueEtaMin + " min" : ""));
    if (b.goal && b.goalStep) kvRow(dl, "Toward goal", b.goalStep);
    kvRow(dl, "Standing order", b.directive);
    kvRow(dl, "Offline", b.offlineReason);
    if (b.stats) kvRow(dl, "Deaths", b.stats.deaths);
    if (b.stats && b.stats.blocksMoved != null) kvRow(dl, "Walked", b.stats.blocksMoved + " blocks");
    $("[data-detail-kv]").replaceChildren(dl);
    renderGoal(b, false); // never refill the box here: it would wipe what someone is typing

    var actions = $("[data-detail-actions]");
    var key = selected.bot + "|" + b.state;
    if (actions.getAttribute("data-key") === key) return;
    actions.setAttribute("data-key", key);
    actions.replaceChildren();
    var m = selected.manager;
    if (b.state === "stopped") actions.appendChild(button("Start", "btn-primary", function () { command(m, "startBot", { bot: b.id }); }));
    else actions.appendChild(button("Stop bot", "btn-ghost", function () { command(m, "stopBot", { bot: b.id }); }));
    if (b.state === "offline") actions.appendChild(button("Rejoin now", "btn-primary", function () { command(m, "rejoin", { bot: b.id }); }));
    if (b.state === "online") {
      actions.appendChild(button("Come to me", "btn-ghost", comeToMe));
      actions.appendChild(button("Stop task", "btn-ghost", function () { say("stop"); }));
      actions.appendChild(button("Play on its own", "btn-ghost", function () { say("auto"); }));
      actions.appendChild(button("Status", "btn-ghost", function () { say("status"); }));
    }
  }

  function comeToMe() {
    var me = state.me.minecraft;
    var coords = window.prompt(
      (me ? "The bot walks to " + me + " if it can see you. " : "Link your Minecraft account so the bot knows who you are. ") +
        "If you're far away, enter your coordinates (x z), or leave empty:",
      ""
    );
    if (coords === null) return;
    var m = coords.trim().match(/^(-?\d+)[ ,]+(-?\d+)(?:[ ,]+(-?\d+))?$/);
    if (coords.trim() && !m) return MR.toast("Use x z, e.g. 120 -340");
    say(m ? "come " + m.slice(1).filter(Boolean).join(" ") : "come");
  }

  function say(text) {
    if (!selected) return;
    command(selected.manager, "message", { bot: selected.bot, text: text });
  }

  var LINE = {
    staff: function (e) { return [e.who, e.text, "me"]; },
    reply: function (e) { return [e.botName || "bot", e.text, "bot"]; },
    said: function (e) { return [(e.to ? "whisper → " + e.to : "public chat"), e.text, "bot"]; },
    chat: function (e) { return [e.from + (e.channel === "whisper" ? " (whisper)" : ""), e.text, "game"]; },
    task: function (e) { return e.started ? null : ["task", e.name + (e.ok ? " ✓ " : " ✗ ") + (e.result || ""), "sys"]; },
    team: function (e) { return ["team", e.text, "sys"]; },
    death: function (e) { return ["death", "died at " + e.where + (e.cause ? " (" + e.cause + ")" : ""), "warn"]; },
    logoff: function (e) { return ["log-off", "left the server: " + e.reason, "warn"]; },
    disconnect: function (e) { return ["disconnected", e.reason || "", "warn"]; },
    build: function (e) { return ["build", e.file + ": " + e.status + (e.message ? " – " + e.message : ""), "sys"]; },
    command: function (e) { return [e.who, "used " + e.command, "sys"]; },
    error: function (e) { return ["error", e.text, "warn"]; },
  };

  function renderChat() {
    var log = $("[data-chat-log]");
    if (!selected) return;
    var b = findBot(selected);
    log.replaceChildren();
    state.events
      .filter(function (e) {
        return e.bot === selected.bot && LINE[e.type];
      })
      .slice(-150)
      .forEach(function (e) {
        e.botName = b && b.name;
        var parts = LINE[e.type](e);
        if (!parts) return;
        var row = el("div", "chat-line chat-line--" + parts[2]);
        row.appendChild(el("time", null, e.at ? new Date(e.at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : ""));
        row.appendChild(el("b", null, parts[0]));
        row.appendChild(el("span", null, parts[1]));
        log.appendChild(row);
      });
    log.scrollTop = log.scrollHeight;
  }

  function renderBuilds() {
    var sel = $('[data-build-form] select[name="buildId"]');
    var current = sel.value;
    sel.replaceChildren(option("", "—"));
    state.builds.forEach(function (b) {
      sel.appendChild(option(b.id, b.name + (b.uploaded_by ? " (" + b.uploaded_by + ")" : ""), current));
    });
  }

  function accountsOf(manager) {
    var m = state.managers[manager];
    return (m && m.snapshot && m.snapshot.accounts) || [];
  }

  /* ---------- Models: which ones fit where ---------- */

  // Ollama models that work with the bots (any model does, these are the tested sizes). Download size in GB.
  var MODELS = [
    ["qwen2.5:0.5b", 0.4, "tiny, weak plans"],
    ["qwen2.5:1.5b", 1.0, "very small"],
    ["qwen2.5:3b", 1.9, "best for hosts without a GPU"],
    ["qwen2.5:7b", 4.7, "recommended"],
    ["qwen2.5:14b", 9.0, "smarter, needs a big GPU"],
    ["qwen2.5:32b", 20, "smartest, needs 24 GB+"],
    ["llama3.2:1b", 1.3, "very small"],
    ["llama3.2:3b", 2.0, "small"],
    ["llama3.1:8b", 4.9, "good all-rounder"],
    ["gemma2:2b", 1.6, "small"],
    ["gemma2:9b", 5.4, "good planner"],
    ["mistral:7b", 4.1, "fast"],
    ["mistral-nemo:12b", 7.1, "smarter"],
    ["phi3.5:3.8b", 2.2, "small"],
  ];
  var CLIENT_MB = 1900; // one Minecraft client: 1400 MB heap + ~500 MB outside it
  var RESERVE_MB = 1536; // left free for the system (the manager's default)

  var gb = function (mb) { return (Math.round(mb / 102.4) / 10) + " GB"; };

  /** Where a model would run: a bot host (its Ollama) or a staff member's own computer (connector). */
  function aiPlace(source, manager) {
    if (source === "connector") {
      var c = state.connectors.filter(function (x) { return x.mine; })[0];
      if (!c || !c.online) return { name: "your computer", missing: "Your connector isn't running, so the bot would use its built-in rules." };
      return {
        name: "your computer",
        connector: true,
        freeMb: c.mem ? c.mem.freeMb : null,
        reserveMb: RESERVE_MB,
        gpu: c.gpu,
        installed: (c.models || []).map(function (n) { return { name: n }; }),
        loaded: [],
      };
    }
    var m = state.managers[manager];
    var s = m && m.snapshot;
    if (!s) return { name: manager || "the bot host", missing: "No bot host picked." };
    var o = s.ollama || {};
    return {
      name: s.host || m.name,
      offline: !m.online,
      noOllama: o.up === false,
      freeMb: s.freeMemMb,
      reserveMb: s.reserveMemMb || RESERVE_MB,
      gpu: s.gpu,
      installed: o.models || [],
      loaded: o.loaded || [],
      pulling: o.pulling || [],
    };
  }

  function modelSizeMb(name, place) {
    var have = (place.installed || []).filter(function (x) { return x.name === name && x.sizeMb; })[0];
    if (have) return have.sizeMb;
    var known = MODELS.filter(function (x) { return x[0] === name; })[0];
    if (known) return known[1] * 1024;
    var b = /[:-](\d+(?:\.\d+)?)b\b/i.exec(name);
    return b ? Number(b[1]) * 620 + 200 : null;
  }

  /**
   * Can this model run at `place`? newClient: a Minecraft client for this bot still has to start on the
   * same machine (new or stopped bot, AI on the bot host). Returns { ok: true|false|null, text }.
   */
  function modelFit(name, place, newClient) {
    if (place.missing) return { ok: null, text: place.missing };
    var size = modelSizeMb(name, place);
    var need = size ? size + 600 : null;
    var installed = (place.installed || []).some(function (x) { return x.name === name || x.name === name + ":latest"; });
    var where = place.name;
    var dl = "";
    if (!installed) {
      if (place.connector) return { ok: false, text: "Not on your computer yet: run  ollama pull " + name + "  then restart the connector." };
      dl = (place.pulling || []).indexOf(name) >= 0 ? " Downloading now." : " Not downloaded on " + where + " yet: it downloads when the bot starts" + (size ? " (" + gb(size) + ")" : "") + ".";
    }
    if (place.noOllama) return { ok: false, text: "Ollama isn't running on " + where + ", so the bot would use its built-in rules." };
    var loaded = (place.loaded || []).filter(function (x) { return x.name === name; })[0];
    if (loaded) return { ok: true, text: "✓ Already loaded on " + where + (loaded.vramMb ? " (GPU)." : ".") };
    if (!need) return { ok: null, text: "Unknown model size, can't tell if it fits on " + where + "." + dl };
    var spare = place.freeMb == null ? null : place.freeMb - place.reserveMb - (newClient ? CLIENT_MB : 0);
    if (newClient && spare != null && spare < 0) {
      return { ok: false, text: "✗ " + where + " can't take another bot: its Minecraft client needs about " + gb(CLIENT_MB) + " and only " + gb(place.freeMb) + " is free, with " + gb(place.reserveMb) + " kept for the system. Pick another computer or stop a bot there." };
    }
    var gpu = place.gpu;
    if (gpu && gpu.freeMb >= need) return { ok: true, text: "✓ Fits on " + where + "'s GPU (" + gb(need) + " of " + gb(gpu.freeMb) + " free), fast." + dl };
    var others = (place.loaded || []).filter(function (x) { return x.vramMb; });
    var swappable = others.reduce(function (n, x) { return n + x.vramMb; }, 0);
    if (gpu && gpu.freeMb + swappable >= need) {
      return { ok: true, text: "✓ Fits on " + where + "'s GPU, but only by swapping out " + others.map(function (x) { return x.name; }).join(", ") + ": slow while other bots use that one." + dl };
    }
    if (spare == null) return { ok: null, text: "Can't tell how much memory " + where + " has." + dl };
    // Too big for the free VRAM: Ollama puts what fits on the GPU and the rest in RAM (same as the manager).
    // If other models fill the GPU, Ollama unloads them first (when idle), so count their VRAM too.
    var room = gpu ? gpu.freeMb + swappable - 512 : 0;
    if (room >= 1024 && need - room + 500 <= spare) {
      var share = Math.round((room / need) * 100);
      return {
        ok: true,
        text: "✓ Splits across " + where + "'s GPU and RAM: " + gb(room) + " in VRAM (" + share + "%), " + gb(need - room + 500) + " in RAM. Works, but slower than all on the GPU" + (share < 50 ? " (mostly CPU, expect 10 s+ per decision)." : ".") +
          (others.length && gpu.freeMb - 512 < room ? " Ollama unloads " + others.map(function (x) { return x.name; }).join(", ") + " to make room, so bots using that one wait while they swap." : "") + dl,
      };
    }
    if (need <= spare) {
      var slow = !gpu && size > 3000 ? " Slow without a GPU (expect 20 s+ per decision)." : gpu ? " The GPU is full, so it runs on the CPU: slow." : "";
      return { ok: true, text: "✓ Fits in " + where + "'s RAM (" + gb(need) + " of " + gb(spare) + " spare)." + slow + dl };
    }
    return {
      ok: false,
      text: "✗ Too big for " + where + ": needs about " + gb(need) + ", only " + gb(Math.max(0, spare)) + " spare after " + (newClient ? "the bot's Minecraft client and " : "") + gb(place.reserveMb) + " kept free for the system." + (dl && " (Not downloaded either.)"),
    };
  }

  /**
   * A model dropdown that labels each model ✓/✗ for where it would run, with a hint line underneath.
   * The text input (name="model") holds the value, so forms read it as before; "Other" lets you type any name.
   */
  function modelPicker(select, inputEl, hint, getPlace) {
    function refresh() {
      var place = getPlace();
      var cur = inputEl.value.trim();
      var names = MODELS.map(function (x) { return x[0]; });
      (place.installed || []).forEach(function (x) {
        if (names.indexOf(x.name) < 0) names.push(x.name);
      });
      select.replaceChildren();
      names.forEach(function (n) {
        var fit = modelFit(n, place.place, place.newClient);
        var info = MODELS.filter(function (x) { return x[0] === n; })[0];
        var size = modelSizeMb(n, place.place);
        var mark = fit.ok === true ? "✓ " : fit.ok === false ? "✗ " : "";
        select.appendChild(option(n, mark + n + (size ? " · " + gb(size) : "") + (info ? " · " + info[2] : " · downloaded"), cur));
      });
      select.appendChild(option("__other", "Other (type a model name)…", null));
      if (cur && names.indexOf(cur) < 0) select.value = "__other";
      else if (!cur) {
        select.value = "qwen2.5:7b";
        inputEl.value = "qwen2.5:7b";
      }
      showHint();
    }
    function showHint() {
      var other = select.value === "__other";
      inputEl.hidden = !other;
      var name = inputEl.value.trim();
      var place = getPlace();
      var fit = name ? modelFit(name, place.place, place.newClient) : { ok: null, text: "Type an Ollama model name, e.g. llama3.1:8b" };
      hint.textContent = fit.text;
      hint.className = "small model-hint" + (fit.ok === false ? " is-bad" : "");
    }
    select.addEventListener("change", function () {
      if (select.value !== "__other") inputEl.value = select.value;
      else {
        inputEl.value = "";
        inputEl.focus();
      }
      showHint();
    });
    inputEl.addEventListener("input", showHint);
    return refresh;
  }

  /** Goal box: the saved goal, who set it, and the AI's latest step toward it. */
  function renderGoal(b, refill) {
    var f = $("[data-goal-form]");
    var g = b.goal;
    if (refill) f.elements.goal.value = g ? g.text : "";
    $("[data-goal-tag]").replaceChildren(g ? tag("set", "green") : tag("none", "grey"));
    var meta = $("[data-goal-meta]");
    meta.replaceChildren();
    if (g) meta.appendChild(document.createTextNode("Set" + (g.by ? " by " + g.by : "") + (g.at ? " on " + new Date(g.at).toLocaleString() : "") + "."));
    if (g && b.goalStep) meta.appendChild(el("span", "bot-goal-step", " Latest step toward it: " + b.goalStep));
    if (g && b.model === "rules only") meta.appendChild(document.createTextNode(" This bot has no AI, so its built-in rules can't follow a goal: turn the AI on in Settings."));
    $("[data-goal-clear]").hidden = !g;
  }

  function fillSettings(b, manager) {
    renderGoal(b, true);
    var f = $("[data-settings-form]");
    f.elements.server.value = b.server || "";
    var acc = f.elements.account;
    acc.replaceChildren();
    accountsOf(manager).forEach(function (a) {
      acc.appendChild(option(a.id, a.name + " (" + a.type + ")", null));
    });
    var match = accountsOf(manager).filter(function (a) {
      return a.name === b.account;
    })[0];
    if (match) acc.value = match.id;
    var model = String(b.model || "");
    f.elements.source.value = model === "rules only" ? "off" : /on .+'s PC/.test(model) ? "connector" : "vps";
    f.elements.model.value = model.split(" ")[0] === "rules" ? "" : model.split(" ")[0];
    f.elements.mode.value = b.mode === "anarchy" ? "anarchy" : "survival";
    sourceLabels(f.elements.source, manager);
    settingsModels();
  }

  /** "AI runs on" says which machine: the bot host by name, or your own computer. */
  function sourceLabels(sel, manager) {
    var mine = state.connectors.filter(function (c) { return c.mine; })[0];
    $$("option", sel).forEach(function (o) {
      if (o.value === "vps") o.textContent = "Same computer as the bot (" + (manager || "bot host") + ")";
      if (o.value === "connector") o.textContent = "My computer, through the connector" + (mine && mine.online ? "" : " (not running)");
    });
  }

  var settingsForm = $("[data-settings-form]");
  var settingsModels = modelPicker($("[data-model-pick]", settingsForm), settingsForm.elements.model, $("[data-model-hint]", settingsForm), function () {
    var b = findBot(selected) || {};
    var source = settingsForm.elements.source.value;
    var place = aiPlace(source, selected && selected.manager);
    if (source === "off") place = { missing: "No AI: the bot plays with its built-in rules only." };
    return { place: place, newClient: source === "vps" && (b.state === "stopped" || !b.state) };
  });
  settingsForm.elements.source.addEventListener("change", function () { settingsModels(); });

  function renderTeams() {
    var box = $("[data-teams]");
    var key = JSON.stringify(onlineHosts().map(function (m) {
      return m.snapshot.teams.map(function (t) { return [t.id, t.phase, t.members, t.chat, (t.feed || []).length]; });
    }));
    if (box.getAttribute("data-key") === key) return;
    box.setAttribute("data-key", key);
    box.replaceChildren();
    var any = false;
    onlineHosts().forEach(function (m) {
      m.snapshot.teams.forEach(function (t) {
        any = true;
        var card = el("div", "team-card");
        var head = el("div", "bot-card-head");
        head.appendChild(el("strong", null, t.name));
        head.appendChild(tag(t.phase, t.phase === "together" ? "green" : t.phase === "stopped" ? "grey" : "yellow"));
        card.appendChild(head);
        var names = t.members.map(function (id) {
          var b = m.snapshot.bots.filter(function (x) { return x.id === id; })[0];
          return (b ? b.name : id) + (id === t.leader ? " ★" : "");
        });
        card.appendChild(el("small", null, names.join(", ")));
        (t.feed || []).slice(-3).forEach(function (f) {
          card.appendChild(el("p", "team-feed", f.text));
        });
        var actions = el("div", "bot-actions");
        if (t.phase === "stopped") actions.appendChild(button("Start", "btn-primary", function () { command(m.name, "startTeam", { team: t.id }); }));
        else actions.appendChild(button("Stop", "btn-ghost", function () { command(m.name, "stopTeam", { team: t.id }); }));
        actions.appendChild(button(t.chat ? "Team chat: on" : "Team chat: off", "btn-ghost", function () {
          command(m.name, "updateTeam", { team: t.id, changes: { chat: !t.chat } });
        }));
        actions.appendChild(button("Remove", "btn-ghost", function () {
          if (window.confirm("Remove team " + t.name + "? The bots stay.")) command(m.name, "removeTeam", { team: t.id });
        }));
        card.appendChild(actions);
        box.appendChild(card);
      });
    });
    if (!any) box.appendChild(el("p", "muted small", "A team is 2-5 bots with one leader AI: they meet up, split the work and survive together."));
  }

  function renderAccounts() {
    var box = $("[data-accounts]");
    var key = JSON.stringify(onlineHosts().map(function (m) { return [m.name, m.snapshot.accounts]; }));
    if (box.getAttribute("data-key") === key) return;
    box.setAttribute("data-key", key);
    box.replaceChildren();
    onlineHosts().forEach(function (m) {
      m.snapshot.accounts.forEach(function (a) {
        var row = el("div", "account-row");
        row.appendChild(el("span", null, a.name));
        row.appendChild(tag(a.type === "microsoft" ? "Microsoft" : "offline", a.type === "microsoft" ? "blue" : "grey"));
        if (onlineHosts().length > 1) row.appendChild(el("small", "muted", m.name));
        var x = button("Remove", "btn-ghost", function () {
          if (window.confirm("Remove the account " + a.name + " from " + m.name + "?")) command(m.name, "removeAccount", { account: a.id });
        });
        row.appendChild(x);
        box.appendChild(row);
      });
    });
    var hostSel = $("[data-host-select]");
    var cur = hostSel.value;
    hostSel.replaceChildren();
    onlineHosts().forEach(function (m) {
      hostSel.appendChild(option(m.name, m.name, cur));
    });
    hostSel.hidden = onlineHosts().length < 2;
  }

  function renderConnectors() {
    var box = $("[data-connectors]");
    box.replaceChildren();
    state.connectors.forEach(function (c) {
      var row = el("div", "account-row");
      row.appendChild(el("span", null, c.name + (c.mine ? " (you)" : "")));
      row.appendChild(tag(c.online ? "connected" : "offline", c.online ? "green" : "grey"));
      if (c.online && c.models && c.models.length) row.appendChild(el("small", "muted", c.models.join(", ")));
      if (c.online && c.mem) row.appendChild(el("small", "muted", gb(c.mem.freeMb) + " RAM free" + (c.gpu ? ", GPU " + gb(c.gpu.freeMb) + " free" : ", no GPU")));
      box.appendChild(row);
    });
  }

  /** A Minecraft server running the test players plugin (minecraft-test-players), not a bot manager. */
  function isTestHost(m) {
    return !!(m && m.snapshot && m.snapshot.kind === "testplayers");
  }

  /** A real bot host on the VPS (not a test server whose name happens to contain "server"). */
  function hasVpsHost() {
    return hosts().some(function (m) { return !isTestHost(m) && /vps|server/i.test(m.name); });
  }

  function renderHosts() {
    var box = $("[data-hosts]");
    box.replaceChildren();
    hosts().forEach(function (m) {
      var row = el("div", "account-row");
      row.appendChild(el("span", null, m.name));
      row.appendChild(tag(m.online ? "online" : "offline", m.online ? "green" : "grey"));
      if (isTestHost(m)) row.appendChild(tag("test players", "blue"));
      box.appendChild(row);
      var s = m.snapshot;
      if (!s) return;
      if (isTestHost(m)) {
        var p = s.perf || {};
        var tb = [s.server, "TPS " + p.tps + ", " + p.mspt + " ms/tick", p.testPlayers + " test players (max " + s.maxBots + "), " + p.players + " real players",
          p.chunksHeld + " chunks held by test players, " + p.loadedChunks + " loaded", "heap " + p.heapUsedMb + " / " + p.heapMaxMb + " MB"];
        if (s.ollama) tb.push(s.ollama.up === false ? "Ollama not reachable" : "models: " + (s.ollama.models.map(function (x) { return x.name; }).join(", ") || "none"));
        box.appendChild(el("p", "muted small host-info", tb.join(" · ")));
        return;
      }
      var bits = [gb(s.freeMemMb) + " free" + (s.totalMemMb ? " of " + gb(s.totalMemMb) : ""), gb(s.reserveMemMb || RESERVE_MB) + " kept for the system", "max " + s.maxBots + " bots"];
      bits.push(s.gpu ? "GPU: " + gb(s.gpu.freeMb) + " of " + gb(s.gpu.totalMb) + " free" : "no GPU (AI runs on the CPU)");
      if (s.ollama) bits.push(s.ollama.up === false ? "Ollama not running" : "models: " + (s.ollama.models.map(function (x) { return x.name; }).join(", ") || "none"));
      box.appendChild(el("p", "muted small host-info", bits.join(" · ")));
    });
    if (!hosts().length) box.appendChild(el("p", "muted small", "Start the bot manager (ai-bot/manager) with this website's address and BOT_MANAGER_KEY."));
    box.appendChild(el("p", "muted small", "A bot runs on the host it was created on: its Minecraft client and (unless it uses a connector) its AI." +
      (hasVpsHost() ? "" : " No VPS host yet: install the bot manager on the VPS to run bots there.")));
  }

  function showLinkCode(ev) {
    var box = $("[data-link-code]");
    box.hidden = false;
    box.replaceChildren();
    box.appendChild(el("p", null, "Open "));
    var a = el("a", "text-link", ev.verificationUri);
    if (/^https:\/\//i.test(ev.verificationUri || "")) a.href = ev.verificationUri;
    a.target = "_blank";
    a.rel = "noopener";
    box.firstChild.appendChild(a);
    box.firstChild.appendChild(document.createTextNode(" and enter the code:"));
    box.appendChild(el("p", "link-code", ev.userCode));
    box.appendChild(el("p", "muted small", "Sign in with the Microsoft account that owns the Minecraft account."));
  }

  $("[data-bots]").addEventListener("click", function (e) {
    var card = e.target.closest(".bot-card");
    if (!card) return;
    selected = { manager: card.getAttribute("data-manager"), bot: card.getAttribute("data-bot") };
    render();
    renderChat();
    var b = findBot(selected);
    if (b) fillSettings(b, selected.manager);
  });

  /* ---------- Forms ---------- */

  $("[data-chat-form]").addEventListener("submit", function (e) {
    e.preventDefault();
    var input = e.target.elements.text;
    if (!input.value.trim()) return;
    say(input.value.trim());
    input.value = "";
  });

  function uploadIfNeeded(form) {
    var file = form.elements.file.files[0];
    if (!file) {
      if (!form.elements.buildId.value) return Promise.reject(new Error("Pick a .litematic file first."));
      return Promise.resolve(form.elements.buildId.value);
    }
    return fetch("/api/bot/builds?name=" + encodeURIComponent(file.name), {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/octet-stream" },
      body: file,
    })
      .then(function (res) {
        return res.json();
      })
      .then(function (data) {
        if (!data.ok) throw new Error(data.error || "Upload failed.");
        form.elements.file.value = "";
        return data.build.id;
      });
  }

  function coords(form) {
    var v = ["x", "y", "z"].map(function (k) {
      return form.elements[k].value.trim();
    });
    if (v.every(function (s) { return s === ""; })) return {};
    if (!v.every(function (s) { return /^-?\d+$/.test(s); })) throw new Error("Enter all three coordinates (whole numbers), or none.");
    return { x: Number(v[0]), y: Number(v[1]), z: Number(v[2]) };
  }

  function showMaterials(info) {
    var box = $("[data-build-info]");
    box.replaceChildren();
    box.appendChild(el("p", null, info.name + ": " + info.totalBlocks + " blocks" + (info.size ? " (" + info.size.x + "×" + info.size.y + "×" + info.size.z + ")" : "")));
    var ul = el("ul", "materials");
    Object.keys(info.materials).forEach(function (k) {
      ul.appendChild(el("li", null, info.materials[k] + " × " + k.replace(/_/g, " ")));
    });
    box.appendChild(ul);
  }

  $("[data-inspect]").addEventListener("click", function () {
    var form = $("[data-build-form]");
    uploadIfNeeded(form)
      .then(function (id) {
        return command(selected.manager, "inspectBuild", { buildId: id }, function (ev) {
          if (ev.ok) showMaterials(ev.result);
        });
      })
      .catch(function (err) {
        MR.toast(err.message);
      });
  });

  $("[data-build-form]").addEventListener("submit", function (e) {
    e.preventDefault();
    var form = e.target;
    var where;
    try {
      where = coords(form);
    } catch (err) {
      return MR.toast(err.message);
    }
    uploadIfNeeded(form)
      .then(function (id) {
        return command(selected.manager, "build", Object.assign({ bot: selected.bot, buildId: id }, where), function (ev) {
          MR.toast(ev.ok ? ev.result : ev.error);
        });
      })
      .catch(function (err) {
        MR.toast(err.message);
      });
  });

  $("[data-settings-form]").addEventListener("submit", function (e) {
    e.preventDefault();
    var f = e.target;
    var source = f.elements.source.value;
    var ai = source === "off" ? { enabled: false } : { enabled: true, source: source, model: f.elements.model.value.trim() || undefined };
    if (source === "connector") ai.connector = state.me.username;
    var changes = { server: f.elements.server.value.trim(), account: f.elements.account.value, ai: ai, behavior: { mode: f.elements.mode.value } };
    command(selected.manager, "updateBot", { bot: selected.bot, changes: changes }, function (ev) {
      if (ev.ok) MR.toast("Saved. Account changes apply the next time the bot starts.");
    });
  });

  function saveGoal(text) {
    command(selected.manager, "updateBot", { bot: selected.bot, changes: { goal: text || null } }, function (ev) {
      if (ev.ok) MR.toast(text ? "Goal saved. The bot rethinks what it's doing now." : "Goal cleared.");
    });
  }

  $("[data-goal-form]").addEventListener("submit", function (e) {
    e.preventDefault();
    var text = e.target.elements.goal.value.replace(/\s+/g, " ").trim();
    if (!text) return MR.toast("Type a goal first, or press Clear goal.");
    saveGoal(text);
  });

  $("[data-goal-clear]").addEventListener("click", function () {
    if (!window.confirm("Clear this bot's goal? It goes back to its normal survival plan.")) return;
    $("[data-goal-form]").elements.goal.value = "";
    saveGoal("");
  });

  $("[data-remove-bot]").addEventListener("click", function () {
    var b = findBot(selected);
    if (!b || !window.confirm("Remove " + b.name + "? Its memory stays on the bot host.")) return;
    command(selected.manager, "removeBot", { bot: selected.bot });
    selected = null;
  });

  $("[data-offline-form]").addEventListener("submit", function (e) {
    e.preventDefault();
    var f = e.target;
    var host = f.elements.manager.value || (onlineHosts()[0] && onlineHosts()[0].name);
    if (!host) return MR.toast("No bot host is connected. Start the bot manager on your PC or the VPS.");
    command(host, "addOffline", { name: f.elements.name.value.trim() }, function (ev) {
      if (ev.ok) {
        f.elements.name.value = "";
        MR.toast("Added " + ev.result.name);
      }
    });
  });

  $("[data-link-ms]").addEventListener("click", function () {
    var host = $("[data-host-select]").value || (onlineHosts()[0] && onlineHosts()[0].name);
    if (!host) return MR.toast("No bot host is connected. Start the bot manager on your PC or the VPS.");
    command(host, "linkMicrosoft", {}, function (ev) {
      if (ev.result && ev.result !== "starting Microsoft sign-in") {
        $("[data-link-code]").hidden = true;
        MR.toast(ev.ok ? ev.result : ev.error);
      }
    });
  });

  $("[data-token]").addEventListener("click", function () {
    MR.api("/api/bot/connector-token", { body: {} }).then(function (r) {
      if (!r.ok) return MR.toast(r.data.error || "Couldn't create a token.");
      var box = $("[data-token-box]");
      box.hidden = false;
      box.replaceChildren();
      box.appendChild(el("p", null, "Download the connector, then run (keep this token private; a new one replaces it):"));
      var dl = el("a", "btn btn-ghost btn-sm", "Download connector.js");
      dl.href = "/api/bot/connector.js";
      box.appendChild(dl);
      box.appendChild(el("pre", "token-cmd", "node connector.js --site " + location.origin + " --token " + r.data.token));
    });
  });

  /* ---------- New bot / new team dialogs ---------- */

  function openModal(title, body) {
    $("[data-modal-title]").textContent = title;
    $("[data-modal-tag]").replaceChildren();
    $("[data-modal-body]").replaceChildren(body);
    modal.showModal();
  }

  function field(label, control) {
    var f = el("div", "field");
    var l = el("label", null, label);
    control.id = "f-" + Math.random().toString(36).slice(2, 8);
    l.htmlFor = control.id;
    f.appendChild(l);
    f.appendChild(control);
    return f;
  }

  function input(name, placeholder, value) {
    var i = el("input", "input");
    i.name = name;
    if (placeholder) i.placeholder = placeholder;
    if (value) i.value = value;
    return i;
  }

  function select(name, options) {
    var s = el("select", "input");
    s.name = name;
    options.forEach(function (o) {
      s.appendChild(option(o[0], o[1]));
    });
    return s;
  }

  /** Every bot host, offline ones disabled, so it's clear which machines can run a bot right now. */
  function hostSelect() {
    var s = select("manager", []);
    hosts().forEach(function (m) {
      var snap = m.snapshot;
      var o = option(m.name, !m.online ? m.name + " (offline)" : isTestHost(m) ? m.name + " (test players on " + snap.server + ")" : m.name + " (" + gb(snap.freeMemMb) + " free" + (snap.gpu ? ", GPU" : ", no GPU") + ")");
      o.disabled = !m.online;
      s.appendChild(o);
    });
    var first = onlineHosts()[0];
    if (first) s.value = first.name;
    return s;
  }

  function hostHelp() {
    var p = el("p", "muted small", "Only computers running the bot manager are listed. ");
    if (!hasVpsHost()) {
      p.appendChild(document.createTextNode("The VPS isn't one yet, so bots can't run there: install the bot manager on it first (see ai-bot/README.md, \"Running a bot host\")."));
    }
    return p;
  }

  /** Model dropdown + "Other" text box + fit hint, appended to `form`. Returns its refresh function. */
  function modelField(form, getPlace, name, label) {
    var pickSel = el("select", "input");
    var text = input(name || "model", "model name, e.g. llama3.1:8b", "qwen2.5:7b");
    var hint = el("p", "small model-hint");
    var f = field(label || "Model", pickSel);
    f.appendChild(text);
    form.appendChild(f);
    form.appendChild(hint);
    return modelPicker(pickSel, text, hint, getPlace);
  }

  $("[data-new-bot]").addEventListener("click", function () {
    var host = onlineHosts()[0];
    if (!host) return MR.toast("No bot host is connected. Start the bot manager on your PC or the VPS.");
    var form = el("form", "form");
    var hostSel = hostSelect();
    var accSel = select("account", []);
    function fillAccounts() {
      accSel.replaceChildren();
      accountsOf(hostSel.value).forEach(function (a) {
        accSel.appendChild(option(a.id, a.name + " (" + a.type + ")"));
      });
    }
    hostSel.addEventListener("change", fillAccounts);
    fillAccounts();
    form.appendChild(field("Runs on (the computer that plays Minecraft)", hostSel));
    form.appendChild(hostHelp());
    var accField = field("Minecraft account", accSel);
    form.appendChild(accField);
    form.appendChild(field("Name (shown here)", input("name", "e.g. Builder")));
    var serverField = field("Server", input("server", "host:port", "152.53.114.44:25576"));
    form.appendChild(serverField);
    var testNote = el("p", "muted small", "A test player: a simulated player on that server for performance testing. It needs no Minecraft account and appears at the server's spawn (website.spawn-at in the plugin's config).");
    form.appendChild(testNote);
    // Test player servers spawn their own test players: no account or server address to pick.
    function showTestFields() {
      var test = isTestHost(state.managers[hostSel.value]);
      accField.hidden = serverField.hidden = test;
      testNote.hidden = !test;
    }
    hostSel.addEventListener("change", showTestFields);
    showTestFields();
    var sourceSel = select("source", [["vps", ""], ["connector", ""], ["off", "No AI (rules only)"]]);
    form.appendChild(field("AI runs on", sourceSel));
    var goalBox = el("textarea", "input");
    goalBox.name = "goal";
    goalBox.rows = 2;
    goalBox.maxLength = 500;
    goalBox.placeholder = "Optional: what it should always work toward";
    form.appendChild(field("Goal", goalBox));
    var pick = modelField(form, function () {
      var place = sourceSel.value === "off" ? { missing: "No AI: the bot plays with its built-in rules only." } : aiPlace(sourceSel.value, hostSel.value);
      return { place: place, newClient: sourceSel.value === "vps" && !isTestHost(state.managers[hostSel.value]) };
    });
    function refreshAi() {
      sourceLabels(sourceSel, hostSel.value);
      pick();
    }
    hostSel.addEventListener("change", refreshAi);
    sourceSel.addEventListener("change", pick);
    refreshAi();
    var actions = el("div", "form-actions");
    var submit = el("button", "btn btn-primary btn-sm", "Create");
    submit.type = "submit";
    actions.appendChild(submit);
    form.appendChild(actions);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var f = form.elements;
      if (!f.account.value && !isTestHost(state.managers[f.manager.value])) return MR.toast("Add a Minecraft account first.");
      var source = f.source.value;
      var ai = source === "off" ? { enabled: false } : { enabled: true, source: source, model: f.model.value.trim() || "qwen2.5:7b" };
      if (source === "connector") ai.connector = state.me.username;
      var goal = f.goal.value.replace(/\s+/g, " ").trim();
      command(f.manager.value, "createBot", { name: f.name.value.trim() || undefined, account: f.account.value, server: f.server.value.trim(), ai: ai, goal: goal || undefined }, function (ev) {
        if (ev.ok) {
          modal.close();
          selected = { manager: f.manager.value, bot: ev.result.id };
          MR.toast("Created " + ev.result.name + (isTestHost(state.managers[f.manager.value]) ? ", it's on its way into the world." : ". Press Start."));
        }
      });
    });
    openModal("New bot", form);
  });

  $("[data-new-team]").addEventListener("click", function () {
    var host = onlineHosts()[0];
    if (!host) return MR.toast("No bot host is connected. Start the bot manager on your PC or the VPS.");
    var form = el("form", "form");
    var hostSel = hostSelect();
    var membersBox = el("div", "chips");
    var leaderSel = select("leader", []);
    function fillMembers() {
      membersBox.replaceChildren();
      leaderSel.replaceChildren();
      if (!state.managers[hostSel.value]) return;
      state.managers[hostSel.value].snapshot.bots.forEach(function (b) {
        var chip = el("label", "chip");
        var cb = el("input");
        cb.type = "checkbox";
        cb.name = "members";
        cb.value = b.id;
        chip.appendChild(cb);
        chip.appendChild(el("span", null, b.name));
        membersBox.appendChild(chip);
        leaderSel.appendChild(option(b.id, b.name));
      });
    }
    hostSel.addEventListener("change", fillMembers);
    fillMembers();
    form.appendChild(field("Bot host", hostSel));
    form.appendChild(field("Team name", input("name", "e.g. Villagers")));
    var mf = el("div", "field");
    mf.appendChild(el("span", "field-label", "Members (2-5 bots on the same host)"));
    mf.appendChild(membersBox);
    form.appendChild(mf);
    form.appendChild(field("Leader", leaderSel));
    var leaderPick = modelField(form, function () {
      var leader = (state.managers[hostSel.value] ? state.managers[hostSel.value].snapshot.bots : []).filter(function (b) { return b.id === leaderSel.value; })[0];
      var source = leader && /on .+'s PC/.test(leader.model || "") ? "connector" : "vps";
      return { place: aiPlace(source, hostSel.value), newClient: false };
    }, "leaderModel", "Leader's model (smarter is better; the members keep their own)");
    hostSel.addEventListener("change", leaderPick);
    leaderSel.addEventListener("change", leaderPick);
    leaderPick();
    var chatLabel = el("label", "check");
    var chatBox = el("input");
    chatBox.type = "checkbox";
    chatBox.name = "chat";
    chatLabel.appendChild(chatBox);
    chatLabel.appendChild(el("span", null, "Team chat: members whisper each other about jobs, food and trouble (never coordinates)"));
    form.appendChild(chatLabel);
    var actions = el("div", "form-actions");
    var submit = el("button", "btn btn-primary btn-sm", "Create team");
    submit.type = "submit";
    actions.appendChild(submit);
    form.appendChild(actions);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var f = form.elements;
      var members = $$('input[name="members"]:checked', form).map(function (c) { return c.value; });
      if (members.indexOf(f.leader.value) < 0) members.push(f.leader.value);
      if (members.length < 2 || members.length > 5) return MR.toast("Pick 2 to 5 bots.");
      command(f.manager.value, "createTeam", { name: f.name.value.trim() || "Team", leader: f.leader.value, members: members, leaderModel: f.leaderModel.value.trim() || null, chat: f.chat.checked }, function (ev) {
        if (ev.ok) {
          modal.close();
          MR.toast("Team created. Press Start.");
        }
      });
    });
    openModal("New team", form);
  });

  $("[data-modal-close]").addEventListener("click", function () {
    modal.close();
  });

  /* ---------- Start ---------- */

  MR.me().then(function (info) {
    $("[data-loading]").hidden = true;
    if (!info.user || !info.user.staff) {
      $("[data-denied]").hidden = false;
      return;
    }
    $("[data-dashboard]").hidden = false;
    load().then(function () {
      renderBuilds();
      connectStream();
    });
  });
})();
