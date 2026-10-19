/* Moddedrealms — account page */
(function () {
  "use strict";

  var MR = window.MR;
  var user = null;
  var features = {};
  var codeTimer = null;
  var pollTimer = null;

  function $(sel, root) {
    return (root || document).querySelector(sel);
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

  function flash(message, ok) {
    var box = $("[data-flash]");
    box.textContent = message;
    box.classList.toggle("notice--ok", Boolean(ok));
    box.hidden = !message;
  }

  /* ---------- Profile ---------- */
  function renderProfile() {
    $("[data-username]").textContent = user.username;
    var tags = $("[data-profile-tags]");
    tags.replaceChildren();
    if (user.minecraft) {
      tags.appendChild(tag(user.minecraft.name, "green"));
      if (user.minecraft.rank) tags.appendChild(tag(user.minecraft.rank));
    } else {
      tags.appendChild(tag("Minecraft not linked", "grey"));
    }
    if (user.staff) tags.appendChild(tag("Staff", "staff"));
    $("[data-staff-only]").hidden = !user.staff;

    var avatar = $("[data-avatar]");
    if (user.minecraft && /^[0-9a-f-]{36}$/i.test(user.minecraft.uuid)) {
      avatar.src = "https://mc-heads.net/avatar/" + user.minecraft.uuid + "/72";
    } else {
      avatar.src = "image/web/logo-160.webp";
    }
  }

  /* ---------- Minecraft link ---------- */
  function stopCode() {
    clearInterval(codeTimer);
    clearInterval(pollTimer);
    codeTimer = pollTimer = null;
    $("[data-code-box]").hidden = true;
  }

  function renderMinecraft() {
    var linked = Boolean(user.minecraft);
    $("[data-mc-disabled]").hidden = linked || features.linking !== false;
    $("[data-mc-linked]").hidden = !linked;
    $("[data-mc-unlinked]").hidden = linked || features.linking === false;
    $("[data-mc-tag]").replaceChildren(linked ? tag("Verified", "green") : tag("Not linked", "grey"));
    if (linked) {
      stopCode();
      $("[data-mc-name]").textContent = user.minecraft.name;
      $("[data-mc-rank]").textContent = user.minecraft.rank || "None";
      $("[data-mc-uuid]").textContent = user.minecraft.uuid;
      $("[data-mc-linked-at]").textContent = MR.formatDate(user.minecraft.linkedAt, true);
    }
  }

  function startCode(code, expiresAt) {
    var box = $("[data-code-box]");
    var cmd = "/link " + code;
    $("[data-code-cmd]").textContent = cmd;
    box.hidden = false;
    var end = new Date(expiresAt).getTime();

    clearInterval(codeTimer);
    codeTimer = setInterval(tick, 1000);
    tick();
    function tick() {
      var left = Math.max(0, Math.round((end - Date.now()) / 1000));
      $("[data-code-timer]").textContent = left
        ? "Waiting for you to type it in-game… expires in " + Math.floor(left / 60) + ":" + String(left % 60).padStart(2, "0")
        : "This code has expired. Get a new one.";
      if (!left) {
        clearInterval(codeTimer);
        clearInterval(pollTimer);
      }
    }

    // Notice as soon as the player runs /link in-game.
    clearInterval(pollTimer);
    pollTimer = setInterval(function () {
      MR.me(true).then(function (info) {
        if (info.user && info.user.minecraft) {
          user = info.user;
          renderAll();
          MR.toast("Minecraft account linked: " + user.minecraft.name);
          loadActivity();
        }
      });
    }, 4000);
  }

  $("[data-get-code]").addEventListener("click", function () {
    var button = this;
    var error = $("[data-link-error]");
    error.hidden = true;
    MR.busy(button, true);
    MR.api("/api/account/link-code", { body: {} }).then(function (r) {
      MR.busy(button, false);
      if (!r.ok) {
        error.textContent = (r.data && r.data.error) || "Couldn't get a code. Please try again.";
        error.hidden = false;
        return;
      }
      button.textContent = "Get a new code";
      startCode(r.data.code, r.data.expiresAt);
    });
  });

  $("[data-copy-code]").addEventListener("click", function () {
    var text = $("[data-code-cmd]").textContent;
    var done = function () {
      MR.toast("Copied " + text);
    };
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(text).then(done, done);
    else done();
  });

  $("[data-unlink]").addEventListener("click", function () {
    if (!window.confirm("Unlink your Minecraft account from this website account?")) return;
    MR.api("/api/account/unlink", { body: {} }).then(function (r) {
      if (!r.ok) return MR.toast((r.data && r.data.error) || "Couldn't unlink.");
      user = r.data.user;
      renderAll();
      loadActivity();
    });
  });

  /* ---------- Activity ---------- */
  function renderList(name, items, render, emptyText) {
    var list = $('[data-list="' + name + '"]');
    $('[data-count="' + name + '"]').textContent = String(items.length);
    list.replaceChildren();
    if (!items.length) {
      var empty = el("li", "empty", emptyText);
      list.appendChild(empty);
      return;
    }
    items.forEach(function (item) {
      list.appendChild(render(item));
    });
  }

  function itemShell(id, status, statusLabel, date) {
    var li = el("li", "item");
    var head = el("div", "item-head");
    head.appendChild(el("span", "item-id", id));
    head.appendChild(tag(statusLabel, status));
    head.appendChild(el("span", "item-date", MR.formatDate(date)));
    li.appendChild(head);
    return li;
  }

  function staffReply(label, text) {
    var box = el("div", "staff-reply");
    box.appendChild(el("strong", null, label));
    box.appendChild(document.createTextNode(text));
    return box;
  }

  function loadActivity() {
    MR.api("/api/account/activity").then(function (r) {
      if (!r.ok) return;
      var d = r.data;
      renderList(
        "reports",
        d.reports,
        function (rep) {
          var li = itemShell(rep.id, rep.status, rep.statusLabel, rep.createdAt);
          li.appendChild(el("p", "item-title", rep.reportedName + " · " + rep.reason));
          li.appendChild(el("p", "item-meta", rep.location));
          if (rep.staffResponse) li.appendChild(staffReply("Staff reply", rep.staffResponse));
          return li;
        },
        "You haven't made any reports while signed in."
      );
      renderList(
        "aboutMe",
        d.aboutMe,
        function (rep) {
          var li = itemShell(rep.id, rep.status, rep.statusLabel, rep.createdAt);
          li.appendChild(el("p", "item-title", rep.reason));
          li.appendChild(el("p", "item-meta", rep.location + " · reviewed " + MR.formatDate(rep.reviewedAt)));
          return li;
        },
        user.minecraft ? "No reviewed reports about you. Keep it up!" : "Link your Minecraft account to see reports about you."
      );
      renderList(
        "feedback",
        d.feedback,
        function (f) {
          var li = itemShell(f.id, f.status, f.statusLabel, f.createdAt);
          li.appendChild(el("p", "item-title", f.category + (f.rating ? " · " + "★".repeat(f.rating) : "")));
          var text = el("p", "item-text", f.message.length > 400 ? f.message.slice(0, 400) + "…" : f.message);
          li.appendChild(text);
          if (f.staffReply) li.appendChild(staffReply("Staff reply", f.staffReply));
          return li;
        },
        "You haven't sent any feedback while signed in."
      );
    });
  }

  /* ---------- Email ---------- */
  var emailForm = $("[data-email-form]");

  function renderEmail() {
    var card = $("[data-email-card]");
    card.hidden = !features.email;
    if (!features.email) return;
    emailForm.elements.email.value = user.email || "";
    var state = $("[data-email-state]");
    var resend = $("[data-resend]");
    if (user.email && user.emailVerified) {
      state.textContent = "Confirmed. You'll get emails at this address.";
      state.classList.add("notice--ok");
      state.hidden = false;
      resend.hidden = true;
    } else if (user.email) {
      state.textContent = "Check your inbox and click the link to confirm this address.";
      state.classList.remove("notice--ok");
      state.hidden = false;
      resend.hidden = false;
    } else {
      state.hidden = true;
      resend.hidden = true;
    }
    $("[data-notify-row]").hidden = !(user.email && user.emailVerified);
    $("[data-notify]").checked = user.emailNotify;
  }

  emailForm.addEventListener("submit", function (e) {
    e.preventDefault();
    MR.clearErrors(emailForm);
    var button = $("[data-submit]", emailForm);
    MR.busy(button, true);
    MR.api("/api/account/email", { body: { email: emailForm.elements.email.value.trim() } }).then(function (r) {
      MR.busy(button, false);
      if (!r.ok) {
        if (r.data.fields) MR.showErrors(emailForm, r.data.fields);
        else MR.setStatus(emailForm, r.data.error || "Couldn't save your email.");
        return;
      }
      user = r.data.user;
      renderEmail();
      MR.toast(user.email ? "Saved. Check your inbox to confirm it." : "Email removed.");
    });
  });

  $("[data-resend]").addEventListener("click", function () {
    MR.api("/api/account/email/resend", { body: {} }).then(function (r) {
      MR.toast(r.ok ? "Confirmation email sent." : (r.data && r.data.error) || "Couldn't send the email.");
    });
  });

  $("[data-notify]").addEventListener("change", function () {
    MR.api("/api/account/notify", { body: { enabled: this.checked } }).then(function (r) {
      if (r.ok) {
        user = r.data.user;
        MR.toast(user.emailNotify ? "Email updates on." : "Email updates off.");
      }
    });
  });

  /* ---------- Password ---------- */
  var passwordForm = $("[data-password-form]");
  passwordForm.addEventListener("submit", function (e) {
    e.preventDefault();
    MR.clearErrors(passwordForm);
    if (passwordForm.elements.password.value.length < 8) {
      MR.showErrors(passwordForm, { password: "Use at least 8 characters." });
      return;
    }
    var button = $("[data-submit]", passwordForm);
    MR.busy(button, true);
    MR.api("/api/account/password", {
      body: { current: passwordForm.elements.current.value, password: passwordForm.elements.password.value },
    }).then(function (r) {
      MR.busy(button, false);
      if (!r.ok) {
        if (r.data.fields) MR.showErrors(passwordForm, r.data.fields);
        else MR.setStatus(passwordForm, r.data.error || "Couldn't change your password.");
        return;
      }
      passwordForm.reset();
      MR.toast("Password updated. Other devices were signed out.");
    });
  });

  /* ---------- Sign out ---------- */
  $("[data-logout]").addEventListener("click", function () {
    MR.api("/api/auth/logout", { body: {} }).then(function () {
      location.assign("index.html");
    });
  });

  /* ---------- Start ---------- */
  function renderAll() {
    renderProfile();
    renderMinecraft();
    renderEmail();
  }

  MR.me().then(function (info) {
    if (!info.user) {
      location.replace("signin.html?next=" + encodeURIComponent("/account"));
      return;
    }
    user = info.user;
    features = info.features || {};
    $("[data-loading]").hidden = true;
    $("[data-account]").hidden = false;
    renderAll();
    loadActivity();

    var params = new URLSearchParams(location.search);
    if (params.get("email") === "verified") flash("Your email address is confirmed.", true);
    else if (params.get("email") === "invalid") flash("That confirmation link is invalid or has expired. You can send a new one below.");
    else if (params.get("welcome")) flash("Welcome! Your account is ready. Link your Minecraft account below.", true);
    else if (params.get("password") === "reset") flash("Your password has been changed.", true);
    if (params.toString() && history.replaceState) history.replaceState(null, "", location.pathname);
  });
})();
