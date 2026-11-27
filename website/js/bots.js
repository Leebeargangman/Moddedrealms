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
        // The AI civilisation has its own tab; the bot/team/crowd parts only see normal bot hosts.
        return m.snapshot && m.snapshot.kind !== "civ";
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
    if (ev.type === "aiTest" && ev.manager === tpHost) {
      var out = $("[data-ai-test-result]");
      out.textContent = (ev.ok ? "✓ " : "✗ ") + ev.text;
      out.className = "small " + (ev.ok ? "ai-test-ok" : "ai-test-bad");
    }
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
    renderCrowds();
    renderAccounts();
    renderConnectors();
    renderHosts();
    renderTestPlayers();
    renderCiv();
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
    if (b.town) kvRow(dl, "Town / job", b.town + " · " + b.job);
    if (b.team) kvRow(dl, "Team / role", b.team + " · " + b.job);
    if (b.stats && b.stats.builds) kvRow(dl, "Built", b.stats.builds + (b.stats.builds === 1 ? " thing" : " things"));
    if (b.personality) {
      var bp = b.personality;
      kvRow(dl, "Personality", typeof bp === "string" ? bp : (bp.preset ? bp.preset + ": " : "") + bp.text + (bp.enabled === false ? " (switched off)" : "") + (bp.freeWill ? " · free will" : ""));
      if (typeof bp === "object" && (bp.likes.length || bp.dislikes.length)) kvRow(dl, "Likes / dislikes", (bp.likes.join(", ") || "nothing in particular") + " / " + (bp.dislikes.join(", ") || "nothing"));
    }
    $("[data-detail-kv]").replaceChildren(dl);
    renderGoal(b, false); // never refill the box here: it would wipe what someone is typing

    var actions = $("[data-detail-actions]");
    var key = selected.bot + "|" + b.state + "|" + !!b.free;
    if (actions.getAttribute("data-key") === key) return;
    actions.setAttribute("data-key", key);
    actions.replaceChildren();
    var m = selected.manager;
    if (b.state === "stopped") actions.appendChild(button("Start", "btn-primary", function () { command(m, "startBot", { bot: b.id }); }));
    else actions.appendChild(button("Stop bot", "btn-ghost", function () { command(m, "stopBot", { bot: b.id }); }));
    if (b.state === "offline") actions.appendChild(button("Rejoin now", "btn-primary", function () { command(m, "rejoin", { bot: b.id }); }));
    if (isTestHost(state.managers[m]) && !b.free) {
      actions.appendChild(button("Play survival", "btn-ghost", function () {
        command(m, "makeFree", { bot: b.id }, function (ev) { if (ev.ok) MR.toast(ev.result); });
      }));
    }
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
    // Test players have no account, server or mode to change, but do have a personality.
    var test = isTestHost(state.managers[manager]);
    [f.elements.server, f.elements.account, f.elements.mode].forEach(function (x) { x.closest(".field").hidden = test; });
    // AI bots also have likes, dislikes and the on/off and free-will switches.
    $$("[data-personality-extra]", f).forEach(function (x) { x.hidden = test; });
    var p = b.personality;
    f.elements.personality.value = !p ? "" : typeof p === "string" ? p : p.preset || p.text;
    f.elements.likes.value = p && p.likes ? p.likes.join(", ") : "";
    f.elements.dislikes.value = p && p.dislikes ? p.dislikes.join(", ") : "";
    f.elements.personalityOn.checked = !p || p.enabled !== false;
    f.elements.freeWill.checked = !!(p && p.freeWill);
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

  /** Crowds: many lightweight bots under a leader, each with its own personality. */
  function renderCrowds() {
    var box = $("[data-crowds]");
    // Rebuild when something you'd see changes, not every time a bot takes a step.
    var key = JSON.stringify(onlineHosts().map(function (m) {
      return (m.snapshot.crowds || []).map(function (c) {
        return [c.id, c.state, c.mode, c.online, c.leader, c.ai, (c.feed || []).length, c.members.map(function (x) {
          return [x.name, x.state, x.label, x.doing, x.personality && x.personality.enabled, x.personality && x.personality.freeWill];
        })];
      });
    }));
    if (box.getAttribute("data-key") === key) return;
    box.setAttribute("data-key", key);
    var open = {};
    $$("details[data-crowd]", box).forEach(function (d) { open[d.getAttribute("data-crowd")] = d.open; });
    box.replaceChildren();
    var any = false;
    onlineHosts().forEach(function (m) {
      (m.snapshot.crowds || []).forEach(function (c) {
        any = true;
        var card = el("div", "team-card");
        var head = el("div", "bot-card-head");
        head.appendChild(el("strong", null, c.name));
        head.appendChild(tag(c.state === "running" ? c.online + "/" + c.members.length + " online" : "stopped", c.state === "running" ? "green" : "grey"));
        card.appendChild(head);
        var leader = c.leader ? m.snapshot.bots.filter(function (b) { return b.id === c.leader; })[0] : null;
        card.appendChild(el("small", null, [m.name, c.server, leader ? "led by " + leader.name : "no leader", "told to " + c.mode, c.ai ? "AI chat: " + c.ai : "no AI chat"].join(" · ")));
        if (c.settle) card.appendChild(el("small", null, "Settlement at " + c.settle.x + " " + c.settle.z + ": " + (c.huts || 0) + " of " + c.members.length + " huts built"));
        var pOn = c.members.some(function (x) { return x.personality && x.personality.enabled; });
        var fwOn = c.members.some(function (x) { return x.personality && x.personality.freeWill; });
        var actions = el("div", "bot-actions");
        if (c.state === "running") {
          actions.appendChild(button("Stop crowd", "btn-ghost", function () { command(m.name, "stopCrowd", { crowd: c.id }); }));
          ["follow", "wander", "gather", "settle", "stop"].forEach(function (mode) {
            var label = { follow: "Follow", wander: "Wander", gather: "Gather at leader", settle: "Settle at leader", stop: "Stand still" }[mode];
            actions.appendChild(button(label, c.mode === mode ? "btn-primary" : "btn-ghost", function () {
              command(m.name, "crowdOrder", { crowd: c.id, mode: mode }, function (ev) { if (!ev.ok) MR.toast(ev.error); });
            }));
          });
        } else actions.appendChild(button("Start", "btn-primary", function () { command(m.name, "startCrowd", { crowd: c.id }, function (ev) { if (!ev.ok) MR.toast(ev.error); }); }));
        actions.appendChild(button("Personalities: " + (pOn ? "on" : "off"), "btn-ghost", function () { command(m.name, "updateCrowd", { crowd: c.id, changes: { personalities: !pOn } }); }));
        actions.appendChild(button("Free will: " + (fwOn ? "on" : "off"), "btn-ghost", function () { command(m.name, "updateCrowd", { crowd: c.id, changes: { freeWill: !fwOn } }); }));
        actions.appendChild(button("Remove", "btn-ghost", function () {
          if (window.confirm("Remove the crowd " + c.name + " (" + c.members.length + " bots)?")) command(m.name, "removeCrowd", { crowd: c.id });
        }));
        card.appendChild(actions);
        var details = el("details");
        details.setAttribute("data-crowd", c.id);
        details.open = !!open[c.id];
        details.appendChild(el("summary", "small", "Members"));
        c.members.forEach(function (x) {
          var row = el("div", "crowd-member");
          var p = x.personality;
          row.appendChild(el("span", null, x.name));
          row.appendChild(tag(x.label || "no personality", p && p.enabled ? "blue" : "grey"));
          row.appendChild(el("small", null, x.state + (x.job ? " · " + x.job : "") + (x.hut && x.hut.size ? " · hut " + Math.min(x.hut.done, x.hut.size) + "/" + x.hut.size : "") + (x.doing && x.state === "online" ? ", " + x.doing : "")));
          if (p) {
            var bits = el("div", "crowd-member-actions");
            bits.appendChild(button(p.enabled ? "Personality on" : "Personality off", "btn-ghost", function () {
              command(m.name, "updateCrowdMember", { crowd: c.id, member: x.name, personality: { enabled: !p.enabled } });
            }));
            bits.appendChild(button(p.freeWill ? "Free will on" : "Free will off", "btn-ghost", function () {
              command(m.name, "updateCrowdMember", { crowd: c.id, member: x.name, personality: { freeWill: !p.freeWill } });
            }));
            row.appendChild(bits);
          }
          details.appendChild(row);
        });
        card.appendChild(details);
        (c.feed || []).slice(-2).forEach(function (f) {
          card.appendChild(el("p", "team-feed", f.text));
        });
        box.appendChild(card);
      });
    });
    if (!any) box.appendChild(el("p", "muted small", "A crowd is many lightweight bots (about 30 MB each, no game window) on an offline-mode server you run. Each gets a personality, and they follow an AI bot as their leader."));
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

  /* ---------- Test players: AI & API, free players, teams ---------- */

  var tpHost = null; // the test player server the card shows
  var aiForm = $("[data-ai-form]");
  var freeForm = $("[data-free-form]");
  var filled = { ai: null, free: null }; // which server each form was last filled from
  var MODEL_DEFAULTS = { ollama: "qwen2.5:7b", pc: "qwen2.5:7b", lmstudio: "", openai: "gpt-4o-mini", openrouter: "meta-llama/llama-3.1-8b-instruct",
    groq: "llama-3.1-8b-instant", together: "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo", anthropic: "claude-haiku-5-5", off: "" };
  var CAPS = ["maxRequestsPerHour", "maxTokensPerDay", "maxPlayers", "minSecondsPerPlayer", "maxAnswerTokens", "maxDollarsPerDay", "pricePerMillionIn", "pricePerMillionOut"];
  var FLAGS = ["sessions", "buildBases", "teamUp", "aiByDefault", "hunger", "combat", "mobsAttack", "fightBack", "fightBackPlayers", "dropItems", "deathMessages"];

  function testHosts() {
    return onlineHosts().filter(isTestHost);
  }

  function meter(label, used, max, fmt) {
    var row = el("div", "meter");
    var f = fmt || function (n) { return Number(n).toLocaleString(); };
    row.appendChild(el("span", null, label + ": " + f(used) + (max ? " of " + f(max) : "")));
    if (max) {
      var bar = el("div", "meter-bar" + (used / max > 0.8 ? " is-high" : ""));
      var fill = el("span");
      fill.style.width = Math.min(100, Math.round((used / max) * 100)) + "%";
      bar.appendChild(fill);
      row.appendChild(bar);
    }
    return row;
  }

  function showAiFields() {
    var v = aiForm.elements.provider.value;
    $$("[data-ai-for]", aiForm).forEach(function (x) {
      x.hidden = x.getAttribute("data-ai-for").split(" ").indexOf(v) < 0;
    });
    aiForm.elements.url.placeholder = v === "lmstudio" ? "http://127.0.0.1:1234/v1" : "http://127.0.0.1:11434";
  }

  function fillAi(ai) {
    var f = aiForm.elements;
    var prov = ai.provider === "local" ? "lmstudio" : ai.provider === "none" ? "off" : ai.provider;
    if (prov === "openai") {
      // Which OpenAI-style service, from its address.
      var u = String(ai.baseUrl || "");
      prov = /openrouter/.test(u) ? "openrouter" : /groq/.test(u) ? "groq" : /together/.test(u) ? "together" : "openai";
    }
    f.provider.value = prov || "ollama";
    f.model.value = ai.model || "";
    f.url.value = prov === "lmstudio" ? ai.baseUrl || "" : prov === "ollama" ? ai.ollamaUrl || "" : "";
    f.staff.value = ai.pcStaff || (state.me && state.me.username) || "";
    f.key.value = "";
    CAPS.forEach(function (k) {
      if (ai.caps && ai.caps[k] != null) f[k].value = ai.caps[k];
    });
    aiForm.removeAttribute("data-dirty");
    showAiFields();
  }

  function fillFree(free) {
    freeForm.elements.keepOnline.value = free.keepOnline || 0;
    FLAGS.forEach(function (k) { freeForm.elements[k].checked = !!free[k]; });
    freeForm.removeAttribute("data-dirty");
  }

  function renderTestPlayers() {
    var card = $("[data-tp]");
    var list = testHosts();
    card.hidden = !list.length;
    if (!list.length) return;
    if (!tpHost || !list.some(function (m) { return m.name === tpHost; })) tpHost = list[0].name;
    var sel = $("[data-tp-host]");
    var names = list.map(function (m) { return m.name; }).join(",");
    if (sel.getAttribute("data-key") !== names) {
      sel.replaceChildren();
      list.forEach(function (m) { sel.appendChild(option(m.name, m.name, tpHost)); });
      sel.setAttribute("data-key", names);
    }
    sel.hidden = list.length < 2;
    var s = state.managers[tpHost].snapshot;
    var ai = s.ai || {};
    var free = s.free || {};
    var p = s.perf || {};
    var bots = s.bots || [];
    var freeCount = bots.filter(function (b) { return b.free; }).length;
    $("[data-tp-summary]").textContent = tpHost + ": " + (p.testPlayers || 0) + " test players in the world (" + freeCount + " playing survival), " + (p.players || 0) + " real players, TPS " + p.tps + ". "
      + (free.combat ? "They can be attacked and die." : "They can't be hurt.") + (free.keepOnline ? " Keeping " + free.keepOnline + " online." : "");

    if (!s.ai) {
      $("[data-ai-status]").textContent = "This server's plugin is older than 1.3.0: update it to set up its AI here.";
      aiForm.hidden = true;
    } else {
      aiForm.hidden = false;
      var tags = [tag(ai.ready ? "linked" : "not linked", ai.ready ? "green" : "yellow")];
      if (ai.paid) tags.push(tag(ai.enabled ? "API on" : "API paused", ai.enabled ? "blue" : "grey"));
      $("[data-ai-tag]").replaceChildren.apply($("[data-ai-tag]"), tags);
      // The summary only adds something when there's a problem ("- not answering", "- website not connected").
      $("[data-ai-status]").textContent = (ai.provider === "none" ? "No AI linked: test players play on their own routines." : "Thinking on " + ai.place + (ai.model ? " with " + ai.model : "") + ".")
        + (!ai.paid && / - /.test(ai.summary || "") ? " " + ai.summary.split(" - ").pop() + "." : "");
      var usage = $("[data-ai-usage]");
      usage.replaceChildren();
      if (ai.paid && ai.usage && ai.caps) {
        usage.appendChild(meter("Tokens today", ai.usage.tokensToday, ai.caps.maxTokensPerDay));
        usage.appendChild(meter("Requests this hour", ai.usage.requestsHour, ai.caps.maxRequestsPerHour));
        usage.appendChild(meter("Test players on the API", ai.usage.players, ai.caps.maxPlayers));
        if (ai.caps.pricePerMillionIn || ai.caps.pricePerMillionOut) {
          usage.appendChild(meter("Spent today", ai.usage.dollarsToday, ai.caps.maxDollarsPerDay, function (n) { return "$" + Number(n).toFixed(3); }));
        }
      }
      $("[data-ai-keynote]").textContent = ai.keySet ? "A key is set (hidden). Paste a new one to replace it." : "No key set yet.";
      $("[data-ai-pause]").hidden = !ai.paid;
      $("[data-ai-pause]").textContent = ai.enabled ? "Pause the API" : "Turn the API back on";
      var admin = !!(state.me && state.me.admin);
      $("button[type=submit]", aiForm).disabled = !admin;
      $("button[type=submit]", aiForm).title = admin ? "" : "Only admins can change the AI link";
      if (filled.ai !== tpHost || !aiForm.hasAttribute("data-dirty")) {
        fillAi(ai);
        filled.ai = tpHost;
      }
    }
    if (s.free && (filled.free !== tpHost || !freeForm.hasAttribute("data-dirty"))) {
      fillFree(free);
      filled.free = tpHost;
    }
    renderCamps(s);
  }

  function renderCamps(s) {
    var box = $("[data-camps]");
    var camps = s.camps || [];
    var key = JSON.stringify([tpHost, camps]);
    if (box.getAttribute("data-key") === key) return;
    box.setAttribute("data-key", key);
    box.replaceChildren();
    if (!camps.length) return box.appendChild(el("p", "muted small", "No teams yet. Players who join form them, or start one with Team in New bot."));
    camps.forEach(function (c) {
      var row = el("div", "camp-row");
      var head = el("div", "bot-card-head");
      head.appendChild(el("strong", null, c.name));
      head.appendChild(tag(c.stage, c.settled ? "green" : "yellow"));
      head.appendChild(button("Split up", "btn-ghost", function () {
        if (!window.confirm("Split up " + c.name + "? Its test players play solo; what it built stays.")) return;
        command(tpHost, "removeCamp", { team: c.name }, function (ev) { if (ev.ok) MR.toast(ev.result); });
      }));
      row.appendChild(head);
      var who = c.members.map(function (x) { return x.name + " (" + x.job + (x.online ? "" : ", offline") + ")"; }).concat(c.humans.map(function (h) { return h + " (real player)"; }));
      row.appendChild(el("small", null, who.join(", ") || "nobody"));
      row.appendChild(el("p", "muted small", c.settled ? "Base at " + c.x + " " + c.z + ": " + c.built + " of " + c.planned + " built" : "No base yet: looking for a spot"));
      box.appendChild(row);
    });
  }

  $("[data-tp-host]").addEventListener("change", function (e) {
    tpHost = e.target.value;
    filled.ai = filled.free = null;
    $("[data-ai-test-result]").textContent = "";
    renderTestPlayers();
  });
  aiForm.addEventListener("input", function () { aiForm.setAttribute("data-dirty", ""); });
  freeForm.addEventListener("input", function () { freeForm.setAttribute("data-dirty", ""); });
  aiForm.elements.provider.addEventListener("change", function () {
    var f = aiForm.elements;
    var v = f.provider.value;
    var known = Object.keys(MODEL_DEFAULTS).map(function (k) { return MODEL_DEFAULTS[k]; });
    if (!f.model.value || known.indexOf(f.model.value) >= 0) f.model.value = MODEL_DEFAULTS[v] || "";
    showAiFields();
  });
  aiForm.addEventListener("submit", function (e) {
    e.preventDefault();
    var f = aiForm.elements;
    var paidNow = ["openai", "openrouter", "groq", "together", "anthropic"].indexOf(f.provider.value) >= 0;
    var cur = ((state.managers[tpHost] || {}).snapshot || {}).ai || {};
    if (paidNow && !cur.paid && !window.confirm("Test players start using " + f.provider.options[f.provider.selectedIndex].text + " right away. It costs money, within the caps below. Go ahead?")) return;
    var args = { provider: f.provider.value, model: f.model.value.trim(), url: f.url.value.trim(), staff: f.staff.value.trim(), caps: {} };
    if (f.key.value.trim()) args.key = f.key.value.trim();
    f.key.value = ""; // never left sitting in the page
    CAPS.forEach(function (k) {
      if (f[k].value !== "") args.caps[k] = Number(f[k].value);
    });
    command(tpHost, "setAi", args, function (ev) {
      if (!ev.ok) return;
      aiForm.removeAttribute("data-dirty");
      MR.toast(ev.result);
    });
  });
  $("[data-ai-test]").addEventListener("click", function () {
    var out = $("[data-ai-test-result]");
    out.className = "small muted";
    out.textContent = "Asking…";
    command(tpHost, "testAi", {}, function (ev) {
      if (ev.ok) out.textContent = ev.result;
    });
  });
  $("[data-ai-pause]").addEventListener("click", function () {
    var ai = (state.managers[tpHost].snapshot || {}).ai || {};
    command(tpHost, "setApiPaused", { paused: !!ai.enabled }, function (ev) { if (ev.ok) MR.toast(ev.result); });
  });
  freeForm.addEventListener("submit", function (e) {
    e.preventDefault();
    var args = { keepOnline: Number(freeForm.elements.keepOnline.value) || 0 };
    FLAGS.forEach(function (k) { args[k] = freeForm.elements[k].checked; });
    command(tpHost, "setFree", args, function (ev) {
      if (!ev.ok) return;
      freeForm.removeAttribute("data-dirty");
      MR.toast("Saved.");
    });
  });
  $("[data-populate-form]").addEventListener("submit", function (e) {
    e.preventDefault();
    var n = Number(e.target.elements.count.value) || 5;
    command(tpHost, "populate", { count: n }, function (ev) { if (ev.ok) MR.toast(ev.result); });
  });

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
          p.chunksHeld + " chunks held by test players, " + p.loadedChunks + " loaded", "heap " + p.heapUsedMb + " / " + p.heapMaxMb + " MB",
          p.buildZones ? p.builds + " builds in " + p.buildZones + " build zone" + (p.buildZones === 1 ? "" : "s") : "no build zones"];
        (s.towns || []).forEach(function (t) {
          tb.push(t.name + ": " + t.stage + ", " + t.citizens + " citizens, " + t.homes + " homes, " + t.built + "/" + t.planned + " buildings");
        });
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
    var test = isTestHost(state.managers[selected.manager]);
    var changes = test
      ? { ai: ai, personality: f.elements.personality.value.trim() || null }
      : { server: f.elements.server.value.trim(), account: f.elements.account.value, ai: ai, behavior: { mode: f.elements.mode.value }, personality: personalityFrom(f.elements) };
    command(selected.manager, "updateBot", { bot: selected.bot, changes: changes }, function (ev) {
      if (ev.ok) MR.toast(test ? "Saved." : "Saved. Account changes apply the next time the bot starts.");
    });
  });

  /** An AI bot's personality from a form: preset or description, likes, dislikes, and the two switches. */
  function personalityFrom(els) {
    var text = els.personality.value.trim();
    if (!text) return null;
    var p = { text: text, enabled: els.personalityOn ? els.personalityOn.checked : true, freeWill: !!(els.freeWill && els.freeWill.checked) };
    // Empty likes/dislikes keep the preset's.
    if (els.likes && els.likes.value.trim()) p.likes = els.likes.value;
    if (els.dislikes && els.dislikes.value.trim()) p.dislikes = els.dislikes.value;
    return p;
  }

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
    var testNote = el("p", "muted small", "A test player: a simulated player on that server. It needs no Minecraft account and appears at the server's spawn (website.spawn-at in the plugin's config). Leave the name empty for a made-up player name.");
    form.appendChild(testNote);
    var playsField = field("Plays", select("plays", [["free", "Survival, like a normal player"], ["idle", "Stands still (for load tests)"]]));
    form.appendChild(playsField);
    var teamField = field("Team (optional: joins it, or starts it)", input("team", "e.g. Wolfpack"));
    form.appendChild(teamField);
    form.appendChild(field("Personality (optional)", input("personality", "builder, explorer, leader, joker, nervous, grumpy, friendly, random, or your own words")));
    var freeWillField = el("label", "check");
    var freeWillBox = el("input");
    freeWillBox.type = "checkbox";
    freeWillBox.name = "freeWill";
    freeWillField.appendChild(freeWillBox);
    freeWillField.appendChild(el("span", null, "Free will: may refuse orders it dislikes"));
    form.appendChild(freeWillField);
    // Test player servers spawn their own test players: no account or server address to pick.
    function showTestFields() {
      var test = isTestHost(state.managers[hostSel.value]);
      accField.hidden = serverField.hidden = freeWillField.hidden = test;
      testNote.hidden = playsField.hidden = teamField.hidden = !test;
      var linkOpt = $("option[value=link]", sourceSel);
      if (test && !linkOpt) sourceSel.insertBefore(option("link", "The server's AI link (see AI & API)"), sourceSel.firstChild);
      if (!test && linkOpt) linkOpt.remove();
      if (test) sourceSel.value = "link";
    }
    var sourceSel = select("source", [["vps", ""], ["connector", ""], ["off", "No AI (rules only)"]]);
    hostSel.addEventListener("change", showTestFields);
    showTestFields();
    form.appendChild(field("AI runs on", sourceSel));
    var goalBox = el("textarea", "input");
    goalBox.name = "goal";
    goalBox.rows = 2;
    goalBox.maxLength = 500;
    goalBox.placeholder = "Optional: what it should always work toward";
    form.appendChild(field("Goal", goalBox));
    var pick = modelField(form, function () {
      var place = sourceSel.value === "off" ? { missing: "No AI: the bot plays with its built-in rules only." }
        : sourceSel.value === "link" ? { missing: "Uses the server's AI link and its spending caps (AI & API in the Test players card)." } : aiPlace(sourceSel.value, hostSel.value);
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
      var args = { name: f.name.value.trim() || undefined, account: f.account.value, server: f.server.value.trim(), ai: ai, goal: goal || undefined };
      if (isTestHost(state.managers[f.manager.value])) {
        if (source === "link") delete args.ai; // the server's own AI link (and its spending caps)
        args.free = f.plays.value === "free";
        args.team = f.team.value.trim() || undefined;
      }
      args.personality = isTestHost(state.managers[f.manager.value]) ? f.personality.value.trim() || undefined : personalityFrom(f) || undefined;
      command(f.manager.value, "createBot", args, function (ev) {
        if (ev.ok) {
          modal.close();
          selected = { manager: f.manager.value, bot: ev.result.id };
          MR.toast("Created " + ev.result.name + (isTestHost(state.managers[f.manager.value]) ? ", it's on its way into the world." : ". Press Start."));
        }
      });
    });
    openModal("New bot", form);
  });

  $("[data-new-crowd]").addEventListener("click", function () {
    var managers = onlineHosts().filter(function (m) { return !isTestHost(m); });
    if (!managers.length) return MR.toast("Crowds run on a bot host (the bot manager on your PC or the VPS). None is connected.");
    var form = el("form", "form");
    var hostSel = select("manager", managers.map(function (m) { return [m.name, m.name + " (" + gb(m.snapshot.freeMemMb) + " free, up to " + (m.snapshot.maxCrowdBots || 50) + " crowd bots)"]; }));
    form.appendChild(field("Runs on", hostSel));
    form.appendChild(field("Name", input("name", "e.g. Villagers", "Villagers")));
    form.appendChild(field("Server (offline mode, one you run)", input("server", "host:port", "152.53.114.44:25576")));
    var count = input("count", "how many", "10");
    count.type = "number";
    count.min = 1;
    count.max = 50;
    form.appendChild(field("How many bots", count));
    form.appendChild(field("Name prefix (they're called Villager1, Villager2...)", input("prefix", "up to 12 letters", "Villager")));
    form.appendChild(field("Personalities", select("personalities", [["random", "A random one each"], ["off", "None"], ["builder", "All builders"], ["explorer", "All explorers"], ["leader", "All leaders"], ["joker", "All jokers"], ["nervous", "All nervous"], ["grumpy", "All grumpy"], ["friendly", "All friendly"]])));
    var fw = el("label", "check");
    var fwBox = el("input");
    fwBox.type = "checkbox";
    fwBox.name = "freeWill";
    fw.appendChild(fwBox);
    fw.appendChild(el("span", null, "Free will: they may refuse orders they dislike"));
    form.appendChild(fw);
    var leaderSel = select("leader", []);
    function fillLeaders() {
      leaderSel.replaceChildren(option("", "No leader (they wander)"));
      var m = state.managers[hostSel.value];
      (m ? m.snapshot.bots : []).forEach(function (b) { leaderSel.appendChild(option(b.id, b.name + " (" + b.server + ")")); });
    }
    hostSel.addEventListener("change", fillLeaders);
    fillLeaders();
    form.appendChild(field("Leader (an AI bot on the same server)", leaderSel));
    var sourceSel = select("source", [["off", "No AI chat (short built-in lines)"], ["vps", "AI on the bot host"], ["connector", "AI on my computer, through the connector"]]);
    form.appendChild(field("Chat", sourceSel));
    form.appendChild(field("Chat model", input("model", "e.g. qwen2.5:3b", "qwen2.5:3b")));
    form.appendChild(el("p", "muted small", "Crowd bots are lightweight clients without a game window: they walk, follow, gather and chat, but don't mine or build. They only join offline-mode servers. The leader's AI can order them around."));
    var actions = el("div", "form-actions");
    var submit = el("button", "btn btn-primary btn-sm", "Create");
    submit.type = "submit";
    actions.appendChild(submit);
    form.appendChild(actions);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var f = form.elements;
      var source = f.source.value;
      var ai = source === "off" ? { enabled: false } : { enabled: true, source: source, model: f.model.value.trim() || "qwen2.5:3b" };
      if (source === "connector") ai.connector = state.me.username;
      command(f.manager.value, "createCrowd", {
        name: f.name.value.trim(), server: f.server.value.trim(), count: Number(f.count.value), prefix: f.prefix.value.trim(),
        personalities: f.personalities.value, freeWill: f.freeWill.checked, leader: f.leader.value || undefined, ai: ai
      }, function (ev) {
        if (!ev.ok) return MR.toast(ev.error);
        modal.close();
        MR.toast("Created " + ev.result.name + " (" + ev.result.count + " bots). Press Start.");
      });
    });
    openModal("New crowd", form);
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

  /* ---------- Tabs ---------- */

  function showTab(name) {
    $$("[data-tab]").forEach(function (t) {
      t.setAttribute("aria-selected", t.getAttribute("data-tab") === name ? "true" : "false");
    });
    $$("[data-panel]").forEach(function (p) {
      p.hidden = p.getAttribute("data-panel") !== name;
    });
    try {
      localStorage.setItem("botsTab", name);
    } catch (e) {
      /* private window: fine */
    }
  }
  $$("[data-tab]").forEach(function (t) {
    t.addEventListener("click", function () {
      showTab(t.getAttribute("data-tab"));
    });
  });

  /* ---------- AI civilisation (ai-bot/civ) ---------- */

  function civHost() {
    return Object.keys(state.managers)
      .map(function (k) {
        return state.managers[k];
      })
      .filter(function (m) {
        return m.snapshot && m.snapshot.kind === "civ";
      })[0];
  }

  function ago(ms) {
    var s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 90) return s + " s ago";
    if (s < 5400) return Math.round(s / 60) + " min ago";
    return Math.round(s / 3600) + " h ago";
  }

  function clock(ms) {
    return new Date(ms).toLocaleString([], { weekday: "short", hour: "2-digit", minute: "2-digit" });
  }

  function money(n) {
    return "$" + (n || 0).toFixed(n < 1 ? 3 : 2);
  }

  function listInto(node, items, make) {
    node.textContent = "";
    if (!items.length) node.appendChild(el("li", "muted small", "Nothing yet."));
    items.forEach(function (it) {
      node.appendChild(make(it));
    });
  }

  function renderCiv() {
    var m = civHost();
    $("[data-civ-empty]").hidden = !!m;
    $("[data-civ]").hidden = !m;
    if (!m) return;
    var c = m.snapshot.civ;
    var secrets = $("[data-civ-secrets]").checked;
    var online = c.villagers.filter(function (v) {
      return v.online;
    }).length;
    $('[data-civ-stat="alive"]').textContent = c.villagers.length;
    $('[data-civ-stat="online"]').textContent = m.online ? online : 0;
    $('[data-civ-stat="day"]').textContent = c.day === null || c.day === undefined ? "-" : c.day;
    $('[data-civ-stat="cost"]').textContent = money(c.ai.spentToday);
    fillCivForm(c);

    // Session and schedule
    var tagBox = $("[data-civ-session-tag]");
    tagBox.textContent = "";
    tagBox.appendChild(!m.online ? tag("program offline", "grey") : c.session.running ? tag(c.ai.paused ? "running, AI paused" : "running", c.ai.paused ? "yellow" : "green") : tag("waiting", "grey"));
    var lines = [];
    if (!m.online) lines.push("The villagers' program isn't connected (last seen " + ago(m.lastSeen || Date.now()) + ").");
    else if (c.session.running) {
      lines.push("Session started " + ago(c.session.started) + " (" + c.session.reason + ")" + (c.session.endsAt ? ", ends " + clock(c.session.endsAt) : "") + ".");
    } else lines.push("No session running.");
    lines.push("Daily schedule: " + c.schedule.window + " " + c.schedule.timezone + (c.schedule.enabled ? "" : " (schedule off: started by hand)") + (c.schedule.nextStart && !c.session.running ? ", next " + clock(c.schedule.nextStart) : "") + ".");
    if (c.lag) lines.push("Bot lag: " + Math.round(c.lag.p99) + " ms (p99)" + (c.lag.p99 > 150 ? ": jerky" : "") + ".");
    $("[data-civ-session]").textContent = lines.join(" ");
    $$("[data-civ-cmd]").forEach(function (b) {
      var cmd = b.getAttribute("data-civ-cmd");
      b.hidden = !m.online || (cmd === "civStart" && c.session.running) || (cmd === "civStop" && !c.session.running) ||
        (cmd === "civPause" && c.ai.paused) || (cmd === "civResume" && !c.ai.paused) || (cmd === "civStart" && !state.me.admin);
    });
    $("[data-civ-reset-box]").hidden = !m.online || !state.me.admin;

    // Villagers
    var imp = {};
    (c.secret.imposters || []).forEach(function (i) {
      imp[i.name] = true;
    });
    var table = $("[data-civ-villagers]");
    table.textContent = "";
    var head = el("tr");
    var showCiv = (c.factions || []).length > 1 || (c.settings && c.settings.mode === "free");
    (showCiv ? ["Name", "Civilisation", "Role", "Health", "Doing", "Last said"] : ["Name", "Role", "Health", "Doing", "Last said"]).forEach(function (h) {
      head.appendChild(el("th", null, h));
    });
    table.appendChild(head);
    c.villagers.forEach(function (v) {
      var tr = el("tr", v.online ? "" : "civ-offline");
      var name = el("td");
      name.appendChild(el("b", null, v.name));
      if (v.leader) name.appendChild(tag("leader", "green"));
      if (secrets && imp[v.name]) name.appendChild(tag("imposter", "red"));
      name.title = v.personality;
      tr.appendChild(name);
      if (showCiv) tr.appendChild(el("td", null, v.faction || "on their own"));
      tr.appendChild(el("td", null, v.role));
      tr.appendChild(el("td", null, v.online ? v.health + " hp, " + v.food + " food" : "offline"));
      tr.appendChild(el("td", "small", v.task || (v.online ? "thinking" : "")));
      tr.appendChild(el("td", "small muted", v.lastSaid || ""));
      table.appendChild(tr);
    });
    $("[data-civ-gone]").textContent = c.gone.length ? "Gone for good: " + c.gone.map(function (g) {
      return g.name + " (" + g.status + ")";
    }).join(", ") : "";

    // News, leader, laws, buildings, board, chests, AI use
    listInto($("[data-civ-news]"), c.news.slice().reverse(), function (n) {
      var li = el("li");
      li.appendChild(el("span", "muted small", (n.day !== null && n.day !== undefined ? "Day " + n.day + " · " : "") + ago(n.at) + " "));
      li.appendChild(document.createTextNode(n.text));
      return li;
    });
    var facBox = $("[data-civ-factions]");
    facBox.textContent = "";
    var manyCivs = (c.factions || []).length > 1 || (c.settings && c.settings.mode === "free");
    (c.factions || []).forEach(function (f) {
      if (manyCivs) facBox.appendChild(el("h3", "civ-faction-name", f.name + " · " + f.alive + " alive"));
      facBox.appendChild(el("p", "small", f.leader ? f.leader.name + " leads" + (manyCivs ? "" : " the village") + " (since day " + f.leader.since_day + ")" + (f.election ? "; election on, " + f.election.votes + " votes so far." : ".") : f.election ? "Election in progress: " + f.election.votes + " votes so far." : "No leader yet."));
      if (f.relations && f.relations.length) facBox.appendChild(el("p", "small muted", f.relations.join(" · ")));
      var ol = el("ol", "civ-laws");
      listInto(ol, f.laws, function (l) {
        return el("li", null, l);
      });
      facBox.appendChild(ol);
    });
    if (!(c.factions || []).length) facBox.appendChild(el("p", "muted small", "No civilisations yet: everyone is on their own."));
    listInto($("[data-civ-buildings]"), c.buildings.slice().reverse(), function (b) {
      return el("li", null, b.blueprint + " at " + b.x + ", " + b.z + " · " + (b.status === "done" ? "done" : b.placed + "/" + b.total + " blocks") + (b.builder ? " · " + b.builder : ""));
    });
    var made = (c.routines || []).map(function (r) {
      return { text: "Routine " + r.name + " by " + r.owner + " · " + r.runs + " runs" + (r.fails ? ", " + r.fails + " failed" : "") + (r.lastError ? " · last error: " + r.lastError : "") };
    }).concat((c.designs || []).map(function (d) {
      return { text: "Design " + d.name + " by " + d.owner + " · " + d.size.x + "×" + d.size.y + "×" + d.size.z + ", " + d.blocks + " blocks" };
    }));
    listInto($("[data-civ-routines]"), made, function (x) {
      return el("li", "small", x.text);
    });
    listInto($("[data-civ-board]"), c.board, function (j) {
      return el("li", null, "#" + j.id + " " + [j.action, j.target, j.amount ? "x" + j.amount : ""].join(" ").trim() + " · " + (j.status === "open" ? "open" : "taken by " + j.claimed_by));
    });
    var chests = Object.keys(c.chests).sort(function (a, b) {
      return c.chests[b] - c.chests[a];
    });
    $("[data-civ-chests]").textContent = chests.length ? chests.slice(0, 14).map(function (k) {
      return k.replace(/_/g, " ") + " " + c.chests[k];
    }).join(", ") : "Empty, or no chest yet.";
    var ai = c.ai.model + " (" + c.ai.provider + ")" + (c.ai.mock ? ", mock" : "") + ". Today " + money(c.ai.spentToday) + " of " + money(c.ai.budget) + " (" + c.ai.callsToday + " calls).";
    if (c.stats) ai += " This session: " + c.stats.calls + " calls, " + money(c.stats.cost) + ", " + Math.round(c.stats.cacheHitRate * 100) + "% from cache; 3 h with 20 villagers would cost about " + money(c.stats.projected20Bots) + ".";
    $("[data-civ-ai]").textContent = ai;

    // Secrets (hidden unless switched on: streams and screenshots shouldn't spoil the game)
    $("[data-civ-secret-card]").hidden = !secrets;
    if (secrets) {
      var w = c.secret.killWindow || {};
      $("[data-civ-secret-summary]").textContent = "Imposters: " + ((c.secret.imposters || []).map(function (i) {
        return i.name + " (" + i.status + ")";
      }).join(", ") || "not chosen yet") + ". Kill window: " + (w.open ? "open, " + w.killsLeft + " left today" : "closed, next on day " + w.opensDay) + ".";
      listInto($("[data-civ-secret-log]"), c.secret.log.slice().reverse(), function (n) {
        var li = el("li");
        li.appendChild(el("span", "muted small", (n.day !== null && n.day !== undefined ? "Day " + n.day + " · " : "") + ago(n.at) + " "));
        li.appendChild(document.createTextNode(n.text));
        return li;
      });
    }
  }

  /* Settings form: filled from the live snapshot until someone starts editing it. */
  var civForm = $("[data-civ-settings]");
  var civDirty = false;
  var MODE_HELP = {
    village: "One village works together. With imposters on, two villagers secretly kill when nobody is watching.",
    civilisations: "Villagers are split evenly between the civilisations you name, each with its own village site, leader, laws and storage. At war, they may attack each other's villagers.",
    free: "Everyone starts on their own. Villagers decide for themselves: found a civilisation, join one or leave, and leaders choose allies and wars. Anyone outside your own civilisation can be attacked.",
  };

  function factionInputs(names) {
    var box = $("[data-civ-faction-names]");
    var n = Number(civForm.factionCount.value) || 2;
    var current = $$("input", box).map(function (i) {
      return i.value;
    });
    var defaults = ["Red", "Blue", "Green", "Gold"];
    box.textContent = "";
    for (var i = 0; i < n; i++) {
      var inp = el("input", "input");
      inp.maxLength = 20;
      inp.setAttribute("aria-label", "Civilisation " + (i + 1) + " name");
      inp.value = (names && names[i]) || current[i] || defaults[i];
      box.appendChild(inp);
    }
  }

  function civFormValues() {
    var f = civForm;
    var mode = f.mode.value;
    return {
      mode: mode,
      botCount: Number(f.botCount.value),
      budget: Number(f.budget.value),
      schedule: {
        start: f.start.value,
        end: f.end.value,
        days: $$('input[name="day"]', f)
          .filter(function (d) {
            return d.checked;
          })
          .map(function (d) {
            return Number(d.value);
          }),
      },
      factions: mode === "civilisations" ? $$("input", $("[data-civ-faction-names]")).map(function (i) {
        return i.value.trim();
      }) : ["Village"],
      war: mode === "civilisations" && f.war.checked,
      imposters: mode === "village" && f.imposters.checked,
      factionDistance: Number(f.factionDistance.value) || 150,
      instructions: f.instructions.value,
      permadeath: f.permadeath.checked,
      smartLeader: f.smartLeader.value,
    };
  }

  function showMode() {
    var mode = civForm.mode.value;
    $$("[data-civ-only]", civForm).forEach(function (n) {
      n.hidden = n.getAttribute("data-civ-only") !== mode;
    });
    $("[data-civ-mode-help]").textContent = MODE_HELP[mode] || "";
  }

  function toMinutes(t) {
    var p = String(t || "0:0").split(":");
    return Number(p[0]) * 60 + Number(p[1] || 0);
  }

  function civCost() {
    var m = civHost();
    if (!m) return;
    var c = m.snapshot.civ;
    var v = civFormValues();
    var minutes = Math.max(0, toMinutes(v.schedule.end) - toMinutes(v.schedule.start));
    var model = c.costModel || { perBotMinute: 0.00024, measuredSessions: 0, elderPerSession: 0 };
    // Smarter leader: each civilisation's leader pays the bigger model's price instead of the normal one;
    // the elder planner is one bigger-model call per civilisation every 15 minutes.
    var civs = v.mode === "civilisations" ? v.factions.length : v.mode === "free" ? 2 : 1;
    var leaderExtra = v.smartLeader === "leader" || v.smartLeader === "both" ? (model.callsPerBotMinute || 1.8) * minutes * Math.max(0, (model.smartCall || 0) - (model.normalCall || 0)) * civs : 0;
    var elderExtra = v.smartLeader === "elders" || v.smartLeader === "both" ? (minutes / (model.elderEveryMinutes || 15)) * (model.elderCall || 0) * civs : 0;
    var session = model.perBotMinute * v.botCount * minutes + leaderExtra + elderExtra;
    var week = session * v.schedule.days.length;
    var out = $("[data-civ-cost]");
    out.textContent = "Estimated AI cost: " + money(session) + " a session (" + v.botCount + " villagers × " + Math.round(minutes / 6) / 10 + " h), " +
      money(week) + " a week, about " + money(week * 4.35) + " a month. " +
      (leaderExtra + elderExtra > 0 ? "Includes " + money(leaderExtra + elderExtra) + " a session for the smarter " + (model.smartModel || "model") + ". " : "") +
      (model.measuredSessions ? "Based on your last " + model.measuredSessions + " real session(s)." : "Estimated from test runs (no real session measured yet).") +
      (session > v.budget ? " Over the daily budget of " + money(v.budget) + ": the villagers slow down at 80% and stop using the AI when it runs out." : "");
    out.className = "civ-cost small" + (session > v.budget ? " ai-test-bad" : "");
  }

  function fillCivForm(c) {
    if (civDirty || !c.settings) return;
    var s = c.settings;
    var f = civForm;
    f.mode.value = s.mode || (s.factions && s.factions.length > 1 ? "civilisations" : "village");
    f.botCount.value = s.botCount;
    f.budget.value = s.budget;
    f.start.value = s.schedule.start;
    f.end.value = s.schedule.end;
    $$('input[name="day"]', f).forEach(function (d) {
      d.checked = s.schedule.days.indexOf(Number(d.value)) !== -1;
    });
    f.factionCount.value = String(Math.max(2, Math.min(4, (s.factions || []).length)));
    factionInputs((s.factions || []).length > 1 ? s.factions.map(function (x) {
      return x.name;
    }) : null);
    f.war.checked = !!s.war;
    f.imposters.checked = !!s.imposters;
    f.factionDistance.value = s.factionDistance;
    f.instructions.value = s.instructions || "";
    f.permadeath.checked = s.permadeath !== false;
    f.smartLeader.value = s.smartLeader || "off";
    var admin = state.me && state.me.admin;
    $$("input, select, textarea, button", f).forEach(function (n) {
      n.disabled = !admin;
    });
    $("[data-civ-settings-who]").textContent = admin ? "" : "Only admins can change these.";
    showMode();
    civCost();
  }

  function onFormEdit(e) {
    civDirty = true;
    if (e.target.name === "factionCount") factionInputs();
    if (e.target.name === "mode") showMode();
    civCost();
  }
  civForm.addEventListener("input", onFormEdit);
  civForm.addEventListener("change", onFormEdit);

  function saveCivSettings(restart) {
    var m = civHost();
    if (!m) return;
    var v = civFormValues();
    var out = $("[data-civ-settings-result]");
    if (restart && !window.confirm("Save, then end the current session and start a new one with these settings?")) return;
    out.textContent = "Saving…";
    var args = Object.assign({}, v, {
      factions: v.factions.map(function (n) {
        return { name: n };
      }),
      restart: !!restart,
    });
    command(m.name, "civSettings", args, function (ev) {
      out.textContent = (ev.ok ? "✓ " : "✗ ") + (ev.text || ev.error || "");
      if (ev.ok) civDirty = false;
    });
  }
  civForm.addEventListener("submit", function (e) {
    e.preventDefault();
    saveCivSettings(false);
  });
  $("[data-civ-save-restart]").addEventListener("click", function () {
    saveCivSettings(true);
  });

  var RESET_TEXT = {
    memories: "Reset every villager's memory? They forget what they learned, who hurt them and recent events, but keep their name, role and personality. The village, leaders, laws and buildings stay.",
    everything: "Start a new village from scratch? This wipes memories, villagers (the original ones come back), leaders, laws, civilisations, the task board, chest and building records, the world log and the imposters. Settings and cost history are kept; built blocks stay in the world. A running session restarts.",
  };
  $$("[data-civ-reset]").forEach(function (b) {
    b.addEventListener("click", function () {
      var m = civHost();
      var scope = b.getAttribute("data-civ-reset");
      if (!m || !window.confirm(RESET_TEXT[scope])) return;
      var out = $("[data-civ-result]");
      out.textContent = "Resetting…";
      command(m.name, "civReset", { scope: scope }, function (ev) {
        out.textContent = (ev.ok ? "✓ " : "✗ ") + (ev.text || ev.error || "");
      });
    });
  });

  $("[data-civ-secrets]").addEventListener("change", renderCiv);
  $$("[data-civ-cmd]").forEach(function (b) {
    b.addEventListener("click", function () {
      var m = civHost();
      if (!m) return;
      var cmd = b.getAttribute("data-civ-cmd");
      if (cmd === "civStart" && !window.confirm("Start a session now? It uses API credit (about 30p an hour with 20 villagers).")) return;
      var out = $("[data-civ-result]");
      out.textContent = "Sending…";
      command(m.name, cmd, {}, function (ev) {
        out.textContent = (ev.ok ? "✓ " : "✗ ") + (ev.text || ev.error || "");
      });
    });
  });

  /* ---------- Start ---------- */

  MR.me().then(function (info) {
    $("[data-loading]").hidden = true;
    if (!info.user || !info.user.staff) {
      $("[data-denied]").hidden = false;
      return;
    }
    $("[data-dashboard]").hidden = false;
    var saved = "bots";
    try {
      saved = localStorage.getItem("botsTab") || "bots";
    } catch (e) {
      /* private window */
    }
    showTab(saved);
    load().then(function () {
      renderBuilds();
      connectStream();
    });
  });
})();
