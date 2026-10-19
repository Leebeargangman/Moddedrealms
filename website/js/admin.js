/* Moddedrealms — staff dashboard */
(function () {
  "use strict";

  var MR = window.MR;
  var statuses = { reports: {}, feedback: {} };
  var view = "reports";
  var items = [];
  var searchTimer = null;

  var rows = $("[data-rows]");
  var statusSelect = $("[data-filter-status]");
  var searchInput = $("[data-filter-q]");
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

  function cell(strong, small) {
    var div = el("div", "row-main");
    div.appendChild(el("strong", null, strong));
    if (small) div.appendChild(el("small", null, small));
    return div;
  }

  /* ---------- Summary ---------- */
  function loadSummary() {
    return MR.api("/api/admin/summary").then(function (r) {
      if (!r.ok) return r;
      var d = r.data;
      statuses = d.statuses;
      $('[data-stat="open"]').textContent = d.reports.open || 0;
      $('[data-stat="investigating"]').textContent = d.reports.investigating || 0;
      $('[data-stat="newFeedback"]').textContent = d.feedback.new || 0;
      $('[data-stat="linked"]').textContent = d.users.linked;
      return r;
    });
  }

  /* ---------- Toolbar ---------- */
  function fillStatusFilter() {
    statusSelect.replaceChildren();
    statusSelect.hidden = view === "users";
    if (view === "users") return;
    var defaults = { reports: "active", feedback: "new" };
    var options = [["all", "All statuses"]];
    if (view === "reports") options.unshift(["active", "Open + investigating"]);
    Object.keys(statuses[view] || {}).forEach(function (key) {
      options.push([key, statuses[view][key]]);
    });
    options.forEach(function (o) {
      var opt = el("option", null, o[1]);
      opt.value = o[0];
      statusSelect.appendChild(opt);
    });
    statusSelect.value = defaults[view];
  }

  $$("[data-view]").forEach(function (tab) {
    tab.addEventListener("click", function () {
      view = tab.getAttribute("data-view");
      $$("[data-view]").forEach(function (t) {
        var active = t === tab;
        t.setAttribute("aria-selected", String(active));
        t.tabIndex = active ? 0 : -1;
      });
      searchInput.value = "";
      fillStatusFilter();
      load();
    });
  });

  statusSelect.addEventListener("change", load);
  searchInput.addEventListener("input", function () {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(load, 250);
  });

  /* ---------- Lists ---------- */
  function load() {
    var params = new URLSearchParams();
    var status = statusSelect.value;
    if (status && status !== "active") params.set("status", status);
    if (searchInput.value.trim()) params.set("q", searchInput.value.trim());
    rows.replaceChildren(el("p", "loading", "Loading…"));
    MR.api("/api/admin/" + view + "?" + params.toString()).then(function (r) {
      if (!r.ok) {
        rows.replaceChildren(el("p", "empty", (r.data && r.data.error) || "Couldn't load."));
        return;
      }
      items = r.data[view] || [];
      if (view === "reports" && status === "active") {
        items = items.filter(function (i) {
          return i.status === "open" || i.status === "investigating";
        });
      }
      render();
    });
  }

  function render() {
    rows.replaceChildren();
    if (!items.length) {
      rows.appendChild(el("p", "empty", "Nothing here right now."));
      return;
    }
    items.forEach(function (item) {
      rows.appendChild(view === "reports" ? reportRow(item) : view === "feedback" ? feedbackRow(item) : userRow(item));
    });
  }

  function reportRow(r) {
    var row = el("button", "row");
    row.type = "button";
    row.appendChild(el("span", "item-id", r.id));
    var who = r.reportedName + (r.priorReports ? "  (" + r.priorReports + " other report" + (r.priorReports === 1 ? "" : "s") + ")" : "");
    row.appendChild(cell(who, r.reasonLabel + " · " + r.location));
    row.appendChild(cell("by " + r.reporterName + (r.reporterVerified ? " ✓" : ""), r.flagged ? "⚠ " + r.flagged : r.reporterVerified ? "Verified account" : "Not verified"));
    row.appendChild(el("small", null, MR.formatDate(r.createdAt, true)));
    row.appendChild(tag((statuses.reports[r.status] || r.status).split(":")[0], r.status));
    row.addEventListener("click", function () {
      openReport(r);
    });
    return row;
  }

  function feedbackRow(f) {
    var row = el("button", "row");
    row.type = "button";
    row.appendChild(el("span", "item-id", f.id));
    var excerpt = f.message.length > 90 ? f.message.slice(0, 90) + "…" : f.message;
    row.appendChild(cell(f.categoryLabel + (f.rating ? " · " + "★".repeat(f.rating) : ""), excerpt));
    row.appendChild(cell(f.minecraftName || "Anonymous", f.hasAccount ? "Has an account" : f.discordName ? "Discord: " + f.discordName : ""));
    row.appendChild(el("small", null, MR.formatDate(f.createdAt, true)));
    row.appendChild(tag(statuses.feedback[f.status] || f.status, f.status));
    row.addEventListener("click", function () {
      openFeedback(f);
    });
    return row;
  }

  function userRow(u) {
    var row = el("div", "row");
    row.appendChild(el("span", "item-id", u.username));
    row.appendChild(cell(u.minecraftName || "Not linked", u.rank ? "Rank: " + u.rank : ""));
    row.appendChild(cell(u.emailVerified ? "Email confirmed" : "No email", "Last sign in " + (MR.formatDate(u.lastLoginAt) || "never")));
    row.appendChild(el("small", null, "Joined " + MR.formatDate(u.createdAt)));
    row.appendChild(u.staff ? tag(u.role === "admin" ? "Admin" : "Staff", "staff") : tag("Player", "grey"));
    return row;
  }

  /* ---------- Detail modal ---------- */
  function section(title, content) {
    var div = el("div");
    div.appendChild(el("h3", null, title));
    if (typeof content === "string") div.appendChild(el("p", "quote", content));
    else div.appendChild(content);
    return div;
  }

  function kv(pairs) {
    var dl = el("dl", "kv");
    pairs.forEach(function (p) {
      if (!p[1]) return;
      dl.appendChild(el("dt", null, p[0]));
      dl.appendChild(el("dd", null, p[1]));
    });
    return dl;
  }

  function field(id, label, control, hint) {
    var div = el("div", "field");
    var lab = el("label", null, label);
    lab.htmlFor = id;
    control.id = id;
    div.appendChild(lab);
    div.appendChild(control);
    if (hint) {
      var foot = el("div", "field-foot");
      foot.appendChild(el("span", null, hint));
      div.appendChild(foot);
    }
    return div;
  }

  function statusControl(kind, current) {
    var select = el("select", "input");
    select.name = "status";
    Object.keys(statuses[kind]).forEach(function (key) {
      var opt = el("option", null, statuses[kind][key]);
      opt.value = key;
      select.appendChild(opt);
    });
    select.value = current;
    return select;
  }

  function textControl(name, value) {
    var ta = el("textarea", "input");
    ta.name = name;
    ta.rows = 4;
    ta.maxLength = name === "staffNote" ? 4000 : 2000;
    ta.value = value || "";
    return ta;
  }

  function openModal(title, statusTag, body) {
    $("[data-modal-title]").textContent = title;
    $("[data-modal-tag]").replaceChildren(statusTag);
    $("[data-modal-body]").replaceChildren(body);
    if (typeof modal.showModal === "function") modal.showModal();
    else modal.setAttribute("open", "");
  }

  function saveForm(form, url, payload, onSaved) {
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var button = $("[data-submit]", form);
      MR.busy(button, true);
      MR.api(url, { body: payload() }).then(function (r) {
        MR.busy(button, false);
        if (!r.ok) {
          MR.setStatus(form, (r.data && r.data.error) || "Couldn't save.");
          return;
        }
        onSaved(r.data);
        modal.close();
        MR.toast("Saved.");
        loadSummary();
        load();
      });
    });
  }

  function openReport(r) {
    var body = el("div", "modal-stack");
    body.appendChild(
      section(
        "Report",
        kv([
          ["Reported", r.reportedName + (r.reportedUuid ? " (" + r.reportedUuid + ")" : "")],
          ["Reason", r.reasonLabel],
          ["Where", r.location],
          ["When", r.occurredAt ? r.occurredAt.replace("T", " ") + (r.timezone ? " (" + r.timezone + ")" : "") : "Not given"],
          ["Reporter", r.reporterName + (r.reporterVerified ? " ✓ verified Minecraft account" : r.reporterHasAccount ? " (has an account, not linked)" : " (not verified)")],
          ["Discord", r.discordName],
          ["Submitted", MR.formatDate(r.createdAt, true)],
          ["Other reports", r.priorReports ? String(r.priorReports) + " about this player" : ""],
          ["Handled by", r.handledBy],
          ["Flag", r.flagged],
        ])
      )
    );
    body.appendChild(section("What happened", r.description));
    if (r.evidence.length) {
      var list = el("ul", "evidence");
      r.evidence.forEach(function (url) {
        var li = el("li");
        var a = el("a", null, url);
        a.href = url;
        a.target = "_blank";
        a.rel = "noopener noreferrer nofollow";
        li.appendChild(a);
        list.appendChild(li);
      });
      body.appendChild(section("Evidence", list));
    }

    var form = el("form", "form");
    form.noValidate = true;
    form.appendChild(field("m-status", "Status", statusControl("reports", r.status), "Closing a report (resolved or no action) shows it to the reported player, without the reporter's name."));
    form.appendChild(field("m-response", "Reply to the reporter", textControl("staffResponse", r.staffResponse), "The reporter sees this on their account page" + (r.reporterHasAccount ? " and by email if they turned it on." : ". They don't have an account, so only Discord will reach them.")));
    form.appendChild(field("m-note", "Internal note", textControl("staffNote", r.staffNote), "Only staff can see this."));
    var status = el("p", "form-status");
    status.setAttribute("data-form-status", "");
    status.hidden = true;
    form.appendChild(status);
    var actions = el("div", "form-actions");
    var save = el("button", "btn btn-primary", "Save");
    save.type = "submit";
    save.setAttribute("data-submit", "");
    actions.appendChild(save);
    form.appendChild(actions);
    body.appendChild(section("Staff action", form));

    saveForm(
      form,
      "/api/admin/reports/" + r.id,
      function () {
        return { status: form.elements.status.value, staffResponse: form.elements.staffResponse.value, staffNote: form.elements.staffNote.value };
      },
      function () {}
    );
    openModal(r.id + " · " + r.reportedName, tag(statuses.reports[r.status] || r.status, r.status), body);
  }

  function openFeedback(f) {
    var body = el("div", "modal-stack");
    body.appendChild(
      section(
        "Feedback",
        kv([
          ["Topic", f.categoryLabel],
          ["Rating", f.rating ? "★".repeat(f.rating) + "☆".repeat(5 - f.rating) : "Not rated"],
          ["Minecraft", f.minecraftName],
          ["Discord", f.discordName],
          ["Account", f.hasAccount ? "Yes, they can read your reply" : "No account"],
          ["Submitted", MR.formatDate(f.createdAt, true)],
          ["Handled by", f.handledBy],
          ["Flag", f.flagged],
        ])
      )
    );
    body.appendChild(section("Message", f.message));

    var form = el("form", "form");
    form.noValidate = true;
    form.appendChild(field("m-fstatus", "Status", statusControl("feedback", f.status)));
    form.appendChild(field("m-reply", "Reply", textControl("staffReply", f.staffReply), f.hasAccount ? "Shown on their account page, and emailed if they turned it on." : "They don't have an account, so they won't see this."));
    var status = el("p", "form-status");
    status.setAttribute("data-form-status", "");
    status.hidden = true;
    form.appendChild(status);
    var actions = el("div", "form-actions");
    var save = el("button", "btn btn-primary", "Save");
    save.type = "submit";
    save.setAttribute("data-submit", "");
    actions.appendChild(save);
    form.appendChild(actions);
    body.appendChild(section("Staff action", form));

    saveForm(
      form,
      "/api/admin/feedback/" + f.id,
      function () {
        return { status: form.elements.status.value, staffReply: form.elements.staffReply.value };
      },
      function () {}
    );
    openModal(f.id + " · " + f.categoryLabel, tag(statuses.feedback[f.status] || f.status, f.status), body);
  }

  $("[data-modal-close]").addEventListener("click", function () {
    modal.close();
  });
  modal.addEventListener("click", function (e) {
    if (e.target === modal) modal.close();
  });

  /* ---------- Start ---------- */
  MR.me().then(function (info) {
    if (!info.user) {
      location.replace("signin.html?next=" + encodeURIComponent("/admin"));
      return;
    }
    $("[data-loading]").hidden = true;
    if (!info.user.staff) {
      $("[data-denied]").hidden = false;
      return;
    }
    $("[data-dashboard]").hidden = false;
    loadSummary().then(function () {
      fillStatusFilter();
      load();
    });
  });
})();
