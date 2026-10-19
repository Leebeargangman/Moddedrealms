/* Moddedrealms — sign in, create account and password reset pages */
(function () {
  "use strict";

  var MR = window.MR;

  function $(sel, root) {
    return (root || document).querySelector(sel);
  }

  function $$(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  /** Where to go after signing in: ?next=/some/path on this site, otherwise the account page. */
  function nextUrl() {
    var next = new URLSearchParams(location.search).get("next") || "";
    return /^\/(?!\/)[\w\-./#?=&]*$/.test(next) ? next : "account.html";
  }

  function value(form, name) {
    return form.elements[name] ? form.elements[name].value.trim() : "";
  }

  /* ---------- Tabs (sign in / create account) ---------- */
  var tabs = $$("[data-tab]");
  function selectTab(name, focus) {
    if (!tabs.some(function (t) { return t.getAttribute("data-tab") === name; })) name = "signin";
    tabs.forEach(function (tab) {
      var active = tab.getAttribute("data-tab") === name;
      tab.setAttribute("aria-selected", String(active));
      tab.tabIndex = active ? 0 : -1;
      if (active && focus) tab.focus();
    });
    $$("[data-panel]").forEach(function (panel) {
      panel.hidden = panel.getAttribute("data-panel") !== name;
    });
    if (history.replaceState) history.replaceState(null, "", location.search + (name === "register" ? "#register" : ""));
  }
  tabs.forEach(function (tab, i) {
    tab.addEventListener("click", function () {
      selectTab(tab.getAttribute("data-tab"));
    });
    tab.addEventListener("keydown", function (e) {
      var dir = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
      if (!dir) return;
      e.preventDefault();
      selectTab(tabs[(i + dir + tabs.length) % tabs.length].getAttribute("data-tab"), true);
    });
  });
  if (tabs.length) selectTab(location.hash === "#register" ? "register" : "signin");

  /* ---------- Features + already signed in ---------- */
  MR.me().then(function (info) {
    if (info.user && $("[data-auth-form=login]")) {
      location.replace(nextUrl());
      return;
    }
    var email = Boolean(info.features && info.features.email);
    $$("[data-email-only]").forEach(function (n) {
      n.hidden = !email;
    });
    $$("[data-no-email]").forEach(function (n) {
      n.hidden = email;
    });
  });

  var loginForm = $("[data-auth-form=login]");
  var forgotForm = $("[data-auth-form=forgot]");

  function showForgot(show) {
    if (!loginForm || !forgotForm) return;
    loginForm.hidden = show;
    forgotForm.hidden = !show;
    var focus = show ? forgotForm.elements.email : loginForm.elements.username;
    if (focus) focus.focus();
  }
  $$("[data-show-forgot]").forEach(function (b) {
    b.addEventListener("click", function () {
      showForgot(true);
    });
  });
  $$("[data-hide-forgot]").forEach(function (b) {
    b.addEventListener("click", function () {
      showForgot(false);
    });
  });

  /* ---------- Submit handlers ---------- */
  function wire(form, validate, send) {
    if (!form) return;
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      MR.clearErrors(form);
      var errors = validate(form);
      if (Object.keys(errors).length) {
        MR.showErrors(form, errors);
        return;
      }
      var button = $("[data-submit]", form);
      MR.busy(button, true);
      send(form).then(function (result) {
        MR.busy(button, false);
        if (!result) return;
        if (!result.ok) {
          var data = result.data || {};
          if (data.fields) MR.showErrors(form, data.fields);
          MR.setStatus(form, data.fields && !data.error ? "" : data.error || "Something went wrong. Please try again.");
        }
      });
    });
  }

  wire(
    loginForm,
    function (form) {
      var errors = {};
      if (!value(form, "username")) errors.username = "Enter your username.";
      if (!form.elements.password.value) errors.password = "Enter your password.";
      return errors;
    },
    function (form) {
      return MR.api("/api/auth/login", { body: { username: value(form, "username"), password: form.elements.password.value } }).then(function (r) {
        if (r.ok) location.assign(nextUrl());
        return r;
      });
    }
  );

  wire(
    $("[data-auth-form=register]"),
    function (form) {
      var errors = {};
      if (!/^[A-Za-z0-9_.-]{3,24}$/.test(value(form, "username"))) errors.username = "Use 3-24 letters, numbers, dots, dashes or underscores.";
      if (form.elements.password.value.length < 8) errors.password = "Use at least 8 characters.";
      var email = value(form, "email");
      if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = "That doesn't look like an email address.";
      return errors;
    },
    function (form) {
      return MR.api("/api/auth/register", {
        body: { username: value(form, "username"), password: form.elements.password.value, email: value(form, "email") },
      }).then(function (r) {
        var next = nextUrl();
        if (r.ok) location.assign(/^\/?account(\.html)?$/.test(next) ? "account.html?welcome=1" : next);
        return r;
      });
    }
  );

  wire(
    forgotForm,
    function (form) {
      return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value(form, "email")) ? {} : { email: "Enter the email address on your account." };
    },
    function (form) {
      return MR.api("/api/auth/forgot", { body: { email: value(form, "email") } }).then(function (r) {
        if (r.ok) $("[data-form-ok]", form).hidden = false;
        return r;
      });
    }
  );

  var resetForm = $("[data-auth-form=reset]");
  var resetToken = new URLSearchParams(location.search).get("token") || "";
  if (resetForm && !resetToken) {
    MR.setStatus(resetForm, "This link is missing its reset code. Open the link from your email again.");
  }
  wire(
    resetForm,
    function (form) {
      var errors = {};
      if (form.elements.password.value.length < 8) errors.password = "Use at least 8 characters.";
      else if (form.elements.password.value !== form.elements.confirm.value) errors.confirm = "The passwords don't match.";
      return errors;
    },
    function (form) {
      return MR.api("/api/auth/reset", { body: { token: resetToken, password: form.elements.password.value } }).then(function (r) {
        if (r.ok) location.assign("account.html?password=reset");
        return r;
      });
    }
  );
})();
