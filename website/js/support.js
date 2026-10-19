/* Moddedrealms — feedback & player report forms */
(function () {
  "use strict";

  var DISCORD_URL = "https://discord.gg/AA3a3H3ECk";
  var MC_NAME = /^[\w.*\- ]{2,32}$/;
  var RATING_TEXT = ["", "Poor", "Okay", "Good", "Great", "Amazing"];

  function $(sel, root) {
    return (root || document).querySelector(sel);
  }

  function $$(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  /* ---------- Tabs ---------- */
  var tabs = $$("[data-tab]");
  var panels = $$("[data-panel]");

  function selectTab(name, focus) {
    if (!panels.some(function (p) { return p.getAttribute("data-panel") === name; })) name = "feedback";
    tabs.forEach(function (tab) {
      var active = tab.getAttribute("data-tab") === name;
      tab.setAttribute("aria-selected", String(active));
      tab.tabIndex = active ? 0 : -1;
      if (active && focus) tab.focus();
    });
    panels.forEach(function (panel) {
      panel.hidden = panel.getAttribute("data-panel") !== name;
    });
    if (location.hash !== "#" + name && history.replaceState) {
      history.replaceState(null, "", "#" + name);
    }
  }

  tabs.forEach(function (tab, i) {
    tab.addEventListener("click", function () {
      selectTab(tab.getAttribute("data-tab"));
    });
    tab.addEventListener("keydown", function (e) {
      var dir = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
      if (!dir) return;
      e.preventDefault();
      var next = tabs[(i + dir + tabs.length) % tabs.length];
      selectTab(next.getAttribute("data-tab"), true);
    });
  });

  function tabFromHash() {
    selectTab((location.hash || "#feedback").slice(1));
  }
  window.addEventListener("hashchange", tabFromHash);
  tabFromHash();

  /* ---------- Character counters ---------- */
  $$("[data-counter-for]").forEach(function (counter) {
    var field = document.getElementById(counter.getAttribute("data-counter-for"));
    if (!field) return;
    var max = Number(field.getAttribute("maxlength")) || 2000;
    function update() {
      var len = field.value.length;
      counter.textContent = len + " / " + max;
      counter.classList.toggle("is-over", len >= max);
    }
    field.addEventListener("input", update);
    field.form && field.form.addEventListener("reset", function () {
      setTimeout(update, 0);
    });
    update();
  });

  /* ---------- Star rating label ---------- */
  $$("[data-stars]").forEach(function (stars) {
    var text = stars.parentNode.querySelector("[data-rating-text]");
    function update() {
      var checked = $("input:checked", stars);
      if (text) text.textContent = checked ? RATING_TEXT[Number(checked.value)] || "" : "";
    }
    stars.addEventListener("change", update);
    stars.closest("form").addEventListener("reset", function () {
      setTimeout(update, 0);
    });
  });

  /* ---------- Helpers ---------- */
  function value(form, name) {
    var field = form.elements[name];
    if (!field) return "";
    if (typeof field.value === "string") return field.value.trim();
    return "";
  }

  function checkedValue(form, name) {
    var checked = $('input[name="' + name + '"]:checked', form);
    return checked ? checked.value : "";
  }

  function isUrl(str) {
    try {
      var u = new URL(str);
      return u.protocol === "https:" || u.protocol === "http:";
    } catch (err) {
      return false;
    }
  }

  function evidenceLinks(raw) {
    return raw
      .split(/\s+/)
      .map(function (s) {
        return s.trim();
      })
      .filter(Boolean);
  }

  function setFieldError(form, name, message) {
    var errorEl = $('[data-error-for="' + name + '"]', form);
    if (errorEl) errorEl.textContent = message || "";
    var target = form.elements[name];
    var group = $('[data-group="' + name + '"]', form);
    if (group) {
      if (message) group.setAttribute("aria-invalid", "true");
      else group.removeAttribute("aria-invalid");
    } else if (target && target.setAttribute) {
      if (message) target.setAttribute("aria-invalid", "true");
      else target.removeAttribute("aria-invalid");
    }
  }

  function clearErrors(form) {
    $$("[data-error-for]", form).forEach(function (node) {
      setFieldError(form, node.getAttribute("data-error-for"), "");
    });
    setStatus(form, null);
  }

  function setStatus(form, message, withDiscordLink) {
    var status = $("[data-form-status]", form);
    if (!status) return;
    if (!message) {
      status.hidden = true;
      status.textContent = "";
      return;
    }
    status.textContent = message;
    if (withDiscordLink) {
      var link = document.createElement("a");
      link.href = DISCORD_URL;
      link.target = "_blank";
      link.rel = "noopener";
      link.textContent = "contact staff on Discord";
      status.appendChild(document.createTextNode(" You can also "));
      status.appendChild(link);
      status.appendChild(document.createTextNode("."));
    }
    status.hidden = false;
  }

  function focusFirstError(form) {
    var first = $('[aria-invalid="true"]', form);
    if (!first) return;
    if (first.matches("[data-group]")) first = $("input", first);
    if (first) first.focus();
  }

  /* ---------- Validation ---------- */
  var validators = {
    feedback: function (form) {
      var errors = {};
      var mc = value(form, "minecraftName");
      if (mc && !MC_NAME.test(mc)) errors.minecraftName = "That doesn't look like a Minecraft username.";
      if (value(form, "discordName").length > 40) errors.discordName = "Discord usernames are 40 characters or fewer.";
      if (!checkedValue(form, "category")) errors.category = "Pick a topic.";
      var msg = value(form, "message");
      if (msg.length < 10) errors.message = "Please write at least 10 characters.";
      return errors;
    },
    report: function (form) {
      var errors = {};
      var reporter = value(form, "reporterName");
      if (!reporter) errors.reporterName = "Enter your Minecraft username.";
      else if (!MC_NAME.test(reporter)) errors.reporterName = "That doesn't look like a Minecraft username.";
      if (value(form, "discordName").length > 40) errors.discordName = "Discord usernames are 40 characters or fewer.";
      var reported = value(form, "reportedName");
      if (!reported) errors.reportedName = "Enter the username of the player you're reporting.";
      else if (!MC_NAME.test(reported)) errors.reportedName = "That doesn't look like a Minecraft username.";
      if (!checkedValue(form, "reason")) errors.reason = "Choose a reason.";
      if (value(form, "description").length < 20) errors.description = "Please describe what happened (at least 20 characters).";
      var links = evidenceLinks(value(form, "evidence"));
      if (links.length > 5) errors.evidence = "Add up to 5 links.";
      else if (links.some(function (l) { return !isUrl(l); })) errors.evidence = "Each link must start with http:// or https://";
      if (!form.elements.confirm.checked) errors.confirm = "Please confirm your report is honest.";
      return errors;
    }
  };

  var payloads = {
    feedback: function (form) {
      var rating = checkedValue(form, "rating");
      return {
        minecraftName: value(form, "minecraftName"),
        discordName: value(form, "discordName"),
        category: checkedValue(form, "category"),
        rating: rating ? Number(rating) : null,
        message: value(form, "message")
      };
    },
    report: function (form) {
      var tz = "";
      try {
        tz = Intl.DateTimeFormat().resolvedOptions().timeZone || "";
      } catch (err) {
        tz = "";
      }
      return {
        reporterName: value(form, "reporterName"),
        discordName: value(form, "discordName"),
        reportedName: value(form, "reportedName"),
        reason: checkedValue(form, "reason"),
        location: value(form, "location"),
        occurredAt: value(form, "occurredAt"),
        timezone: tz,
        description: value(form, "description"),
        evidence: evidenceLinks(value(form, "evidence")),
        confirm: form.elements.confirm.checked
      };
    }
  };

  /* ---------- Signed-in players ---------- */
  // A linked Minecraft account fills in (and locks) the player's own name; the server enforces this too.
  if (window.MR && window.MR.me && location.protocol !== "file:") {
    window.MR.me().then(function (info) {
      var note = $("[data-account-note]");
      if (!info.user || !note) {
        if (note) {
          note.textContent = "Tip: sign in to follow your reports and read staff replies on your account page.";
          note.hidden = false;
        }
        return;
      }
      var mc = info.user.minecraft;
      if (mc) {
        ["rp-reporter", "fb-mc"].forEach(function (id) {
          var input = document.getElementById(id);
          if (!input) return;
          input.value = mc.name;
          input.readOnly = true;
        });
        note.textContent = "Signed in as " + info.user.username + " with your verified Minecraft account " + mc.name + ". You can follow everything you send on your account page.";
        note.classList.add("notice--ok");
      } else {
        note.textContent = "Signed in as " + info.user.username + ". Link your Minecraft account on your account page to mark your reports as verified.";
      }
      note.hidden = false;
    });
  }

  /* ---------- Submit ---------- */
  $$("[data-form]").forEach(function (form) {
    var type = form.getAttribute("data-form");
    var panel = form.closest("[data-panel]");
    var success = $("[data-success]", panel);
    var submit = $("[data-submit]", form);
    var startedAt = Date.now();

    // Clear a field's error as soon as the user fixes it.
    form.addEventListener("input", function (e) {
      var name = e.target && e.target.name;
      if (name && $('[data-error-for="' + name + '"]', form)) setFieldError(form, name, "");
    });
    form.addEventListener("change", function (e) {
      var name = e.target && e.target.name;
      if (name && $('[data-error-for="' + name + '"]', form)) setFieldError(form, name, "");
    });

    form.addEventListener("submit", function (e) {
      e.preventDefault();
      clearErrors(form);

      var errors = validators[type](form);
      var names = Object.keys(errors);
      if (names.length) {
        names.forEach(function (name) {
          setFieldError(form, name, errors[name]);
        });
        focusFirstError(form);
        return;
      }

      if (location.protocol === "file:") {
        setStatus(form, "Forms only work when the site is running on the server (see README).", true);
        return;
      }

      var body = payloads[type](form);
      body.website = value(form, "website");
      body.elapsedMs = Date.now() - startedAt;

      submit.disabled = true;
      submit.classList.add("is-busy");

      fetch("/api/" + type, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify(body)
      })
        .then(function (res) {
          return res
            .json()
            .catch(function () {
              return {};
            })
            .then(function (data) {
              return { status: res.status, ok: res.ok, data: data };
            });
        })
        .then(function (result) {
          if (result.ok && result.data && result.data.ok) {
            $("[data-ref]", success).textContent = result.data.id || "";
            var track = $("[data-track]", success);
            if (track) track.hidden = !result.data.tracked;
            form.hidden = true;
            success.hidden = false;
            success.focus();
            return;
          }
          var data = result.data || {};
          if (data.fields) {
            Object.keys(data.fields).forEach(function (name) {
              setFieldError(form, name, data.fields[name]);
            });
            focusFirstError(form);
          }
          if (result.status === 429) {
            setStatus(form, data.error || "You're sending submissions too quickly. Please wait a few minutes and try again.");
          } else if (!data.fields) {
            setStatus(form, data.error || "Something went wrong on our end. Please try again in a moment.", true);
          }
        })
        .catch(function () {
          setStatus(form, "Couldn't reach the server. Check your connection and try again.", true);
        })
        .then(function () {
          submit.disabled = false;
          submit.classList.remove("is-busy");
        });
    });

    $("[data-reset]", success).addEventListener("click", function () {
      var locked = $$("input[readonly]", form).map(function (i) {
        return [i, i.value];
      });
      form.reset();
      locked.forEach(function (pair) {
        pair[0].value = pair[1];
      });
      clearErrors(form);
      startedAt = Date.now();
      success.hidden = true;
      form.hidden = false;
      var first = $("input:not([type=hidden]), textarea", form);
      if (first) first.focus();
    });
  });
})();
