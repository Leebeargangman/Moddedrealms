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

  var STATE_TAGS = { online: "green", starting: "yellow", offline: "red", stopped: "grey" };

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
      } else state.connectors.push({ name: ev.who, online: ev.online, models: ev.models || [], mine: ev.who === state.me.username });
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
    onlineHosts().forEach(function (m) {
      m.snapshot.bots.forEach(function (b) {
        all.push({ manager: m.name, bot: b });
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
    if (!all.length) list.appendChild(el("p", "empty", onlineHosts().length ? "No bots yet. Add a Minecraft account, then press New bot." : "No bot host is connected. Start the bot manager on the VPS or your PC."));
    all.forEach(function (x) {
      list.appendChild(botCard(x.manager, x.bot));
    });
    renderDetail();
    renderTeams();
    renderAccounts();
    renderConnectors();
    renderHosts();
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
    kvRow(dl, "Account", b.account);
    kvRow(dl, "Server", b.server);
    kvRow(dl, "AI", b.model);
    if (b.position) kvRow(dl, "Position", b.position.x + " " + b.position.y + " " + b.position.z + " (" + (b.dimension || "") + ")");
    if (b.health != null) kvRow(dl, "Health / food", Math.round(b.health) + " / " + b.food);
    kvRow(dl, "Doing", b.task || (b.state === "online" ? (b.autonomous ? "thinking" : "waiting for orders") : ""));
    kvRow(dl, "Thinking", b.thought);
    if (b.mode === "anarchy") kvRow(dl, "Anarchy mode", b.escape);
    if (b.queue === "queue") kvRow(dl, "Queue", "#" + (b.queuePosition || "?") + (b.queueEtaMin != null ? ", ~" + b.queueEtaMin + " min" : ""));
    kvRow(dl, "Standing order", b.directive);
    kvRow(dl, "Offline", b.offlineReason);
    if (b.stats) kvRow(dl, "Deaths", b.stats.deaths);
    $("[data-detail-kv]").replaceChildren(dl);

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

  function fillSettings(b, manager) {
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
  }

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
      box.appendChild(row);
    });
  }

  function renderHosts() {
    var box = $("[data-hosts]");
    box.replaceChildren();
    hosts().forEach(function (m) {
      var row = el("div", "account-row");
      row.appendChild(el("span", null, m.name));
      row.appendChild(tag(m.online ? "online" : "offline", m.online ? "green" : "grey"));
      if (m.snapshot) row.appendChild(el("small", "muted", m.snapshot.freeMemMb + " MB free · max " + m.snapshot.maxBots + " bots"));
      box.appendChild(row);
    });
    if (!hosts().length) box.appendChild(el("p", "muted small", "Start the bot manager (ai-bot/manager) with this website's address and BOT_MANAGER_KEY."));
  }

  function showLinkCode(ev) {
    var box = $("[data-link-code]");
    box.hidden = false;
    box.replaceChildren();
    box.appendChild(el("p", null, "Open "));
    var a = el("a", "text-link", ev.verificationUri);
    a.href = ev.verificationUri;
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
    if (!host) return MR.toast("No bot host is connected.");
    command(host, "addOffline", { name: f.elements.name.value.trim() }, function (ev) {
      if (ev.ok) {
        f.elements.name.value = "";
        MR.toast("Added " + ev.result.name);
      }
    });
  });

  $("[data-link-ms]").addEventListener("click", function () {
    var host = $("[data-host-select]").value || (onlineHosts()[0] && onlineHosts()[0].name);
    if (!host) return MR.toast("No bot host is connected.");
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

  $("[data-new-bot]").addEventListener("click", function () {
    var host = onlineHosts()[0];
    if (!host) return MR.toast("No bot host is connected.");
    var form = el("form", "form");
    var hostSel = select("manager", onlineHosts().map(function (m) { return [m.name, m.name + " (" + m.snapshot.freeMemMb + " MB free)"]; }));
    var accSel = select("account", []);
    function fillAccounts() {
      accSel.replaceChildren();
      accountsOf(hostSel.value).forEach(function (a) {
        accSel.appendChild(option(a.id, a.name + " (" + a.type + ")"));
      });
    }
    hostSel.addEventListener("change", fillAccounts);
    fillAccounts();
    form.appendChild(field("Bot host", hostSel));
    form.appendChild(field("Minecraft account", accSel));
    form.appendChild(field("Name (shown here)", input("name", "e.g. Builder")));
    form.appendChild(field("Server", input("server", "host:port", "152.53.114.44:25576")));
    form.appendChild(field("AI runs on", select("source", [["vps", "The bot host"], ["connector", "My computer (connector)"], ["off", "No AI (rules only)"]])));
    form.appendChild(field("Model", input("model", "qwen2.5:7b", "qwen2.5:7b")));
    var actions = el("div", "form-actions");
    var submit = el("button", "btn btn-primary btn-sm", "Create");
    submit.type = "submit";
    actions.appendChild(submit);
    form.appendChild(actions);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var f = form.elements;
      if (!f.account.value) return MR.toast("Add a Minecraft account first.");
      var source = f.source.value;
      var ai = source === "off" ? { enabled: false } : { enabled: true, source: source, model: f.model.value.trim() || "qwen2.5:7b" };
      command(f.manager.value, "createBot", { name: f.name.value.trim() || undefined, account: f.account.value, server: f.server.value.trim(), ai: ai }, function (ev) {
        if (ev.ok) {
          modal.close();
          selected = { manager: f.manager.value, bot: ev.result.id };
          MR.toast("Created " + ev.result.name + ". Press Start.");
        }
      });
    });
    openModal("New bot", form);
  });

  $("[data-new-team]").addEventListener("click", function () {
    var host = onlineHosts()[0];
    if (!host) return MR.toast("No bot host is connected.");
    var form = el("form", "form");
    var hostSel = select("manager", onlineHosts().map(function (m) { return [m.name, m.name]; }));
    var membersBox = el("div", "chips");
    var leaderSel = select("leader", []);
    function fillMembers() {
      membersBox.replaceChildren();
      leaderSel.replaceChildren();
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
    form.appendChild(field("Leader's model (smarter is better)", input("leaderModel", "qwen2.5:7b", "qwen2.5:7b")));
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
