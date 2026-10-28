/* Moddedrealms — shared site script (no dependencies) */
(function () {
  "use strict";

  var doc = document.documentElement;
  var reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function $(sel, root) {
    return (root || document).querySelector(sel);
  }

  function $$(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        if (key === "text") node.textContent = attrs[key];
        else node.setAttribute(key, attrs[key]);
      });
    }
    (children || []).forEach(function (child) {
      node.appendChild(typeof child === "string" ? document.createTextNode(child) : child);
    });
    return node;
  }

  function svgUse(id, className) {
    var ns = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(ns, "svg");
    if (className) svg.setAttribute("class", className);
    svg.setAttribute("aria-hidden", "true");
    var use = document.createElementNS(ns, "use");
    use.setAttribute("href", "#" + id);
    svg.appendChild(use);
    return svg;
  }

  /* ---------- Scroll reveal ---------- */
  var revealItems = $$(".reveal, [data-telemetry]");
  revealItems.forEach(function (item) {
    // Anything already on screen is shown immediately so it never flickers.
    if (item.getBoundingClientRect().top < window.innerHeight) item.classList.add("is-visible");
  });
  doc.classList.add("js");

  var revealObserver = null;
  if ("IntersectionObserver" in window && !reduceMotion) {
    revealObserver = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            revealObserver.unobserve(entry.target);
          }
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 }
    );
  }

  function observeReveal(items) {
    items.forEach(function (item) {
      if (item.classList.contains("is-visible")) return;
      if (revealObserver) revealObserver.observe(item);
      else item.classList.add("is-visible");
    });
  }
  observeReveal(revealItems);

  /* ---------- Header ---------- */
  var header = $("[data-header]");
  var ticking = false;
  function updateHeader() {
    ticking = false;
    if (header) header.classList.toggle("is-scrolled", window.scrollY > 24);
  }
  window.addEventListener(
    "scroll",
    function () {
      if (!ticking) {
        ticking = true;
        window.requestAnimationFrame(updateHeader);
      }
    },
    { passive: true }
  );
  updateHeader();

  /* ---------- Mobile navigation ---------- */
  var navToggle = $("[data-nav-toggle]");
  var nav = $("[data-nav]");
  function setNav(open) {
    if (!navToggle || !nav) return;
    navToggle.setAttribute("aria-expanded", String(open));
    nav.classList.toggle("is-open", open);
    if (header) header.classList.toggle("menu-open", open);
  }
  if (navToggle && nav) {
    navToggle.addEventListener("click", function () {
      setNav(navToggle.getAttribute("aria-expanded") !== "true");
    });
    $$("a", nav).forEach(function (link) {
      link.addEventListener("click", function () {
        setNav(false);
      });
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && navToggle.getAttribute("aria-expanded") === "true") {
        setNav(false);
        navToggle.focus();
      }
    });
    document.addEventListener("click", function (e) {
      if (header && !header.contains(e.target)) setNav(false);
    });
    window.addEventListener("resize", function () {
      if (window.innerWidth > 980) setNav(false);
    });
  }

  /* ---------- Toast ---------- */
  var toastEl = $("[data-toast]");
  var toastTimer = null;
  function toast(message) {
    if (!toastEl) return;
    toastEl.replaceChildren(svgUse("i-check", "icon"), document.createTextNode(message));
    toastEl.classList.add("is-visible");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toastEl.classList.remove("is-visible");
    }, 2600);
  }
  window.MR = window.MR || {};
  window.MR.toast = toast;

  /* ---------- API + account helpers (used by the account pages) ---------- */
  var online = location.protocol !== "file:";

  /** Call the website API. Resolves { status, ok, data } and never rejects (network errors give status 0). */
  function api(path, options) {
    options = options || {};
    var init = { method: options.method || "GET", headers: { Accept: "application/json" }, credentials: "same-origin" };
    if (options.body !== undefined) {
      init.method = options.method || "POST";
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(options.body);
    }
    if (!online) return Promise.resolve({ status: 0, ok: false, data: { error: "Run the website server to use accounts." } });
    return fetch(path, init).then(
      function (res) {
        return res
          .json()
          .catch(function () {
            return {};
          })
          .then(function (data) {
            return { status: res.status, ok: res.ok && data.ok !== false, data: data };
          });
      },
      function () {
        return { status: 0, ok: false, data: { error: "Couldn't reach the server. Check your connection and try again." } };
      }
    );
  }

  var mePromise = null;
  /** The signed-in user (or null) and which optional features the server has switched on. */
  function me(refresh) {
    if (!mePromise || refresh) {
      mePromise = api("/api/auth/me").then(function (r) {
        return { user: (r.data && r.data.user) || null, features: (r.data && r.data.features) || {} };
      });
    }
    return mePromise;
  }

  function formatDate(iso, withTime) {
    var d = new Date(iso);
    if (!iso || isNaN(d.getTime())) return "";
    var opts = { month: "short", day: "numeric", year: "numeric" };
    if (withTime) {
      opts.hour = "numeric";
      opts.minute = "2-digit";
    }
    return d.toLocaleString(undefined, opts);
  }

  /** Show server-side field errors next to their inputs (elements with data-error-for="name"). */
  function showErrors(form, fields) {
    var first = null;
    Object.keys(fields || {}).forEach(function (name) {
      var holder = $('[data-error-for="' + name + '"]', form);
      if (holder) holder.textContent = fields[name];
      var input = form.elements[name];
      if (input && input.setAttribute) {
        input.setAttribute("aria-invalid", "true");
        if (!first) first = input;
      }
    });
    if (first) first.focus();
  }

  function clearErrors(form) {
    $$("[data-error-for]", form).forEach(function (n) {
      n.textContent = "";
    });
    $$('[aria-invalid="true"]', form).forEach(function (n) {
      n.removeAttribute("aria-invalid");
    });
    setStatus(form, "");
  }

  function setStatus(form, message) {
    var status = $("[data-form-status]", form);
    if (!status) return;
    status.textContent = message || "";
    status.hidden = !message;
  }

  function busy(button, isBusy) {
    if (!button) return;
    button.disabled = isBusy;
    button.classList.toggle("is-busy", isBusy);
  }

  window.MR.api = api;
  window.MR.me = me;
  window.MR.formatDate = formatDate;
  window.MR.showErrors = showErrors;
  window.MR.clearErrors = clearErrors;
  window.MR.setStatus = setStatus;
  window.MR.busy = busy;

  /* ---------- Nav: "Sign in" becomes "Account" (+ "Staff") when signed in ---------- */
  var accountLink = $("[data-account-link]");
  if (accountLink && online) {
    me().then(function (info) {
      if (!info.user) return;
      var absolute = (accountLink.getAttribute("href") || "").charAt(0) === "/";
      accountLink.textContent = "Account";
      accountLink.setAttribute("href", absolute ? "/account" : "account.html");
      if (info.user.staff) {
        var staffLink = el("a", { href: absolute ? "/admin" : "admin.html", text: "Staff" });
        if (/admin\.html$|\/admin$/.test(location.pathname)) staffLink.setAttribute("aria-current", "page");
        accountLink.parentNode.insertBefore(staffLink, accountLink);
        var botsLink = el("a", { href: absolute ? "/bots" : "bots.html", text: "Bots" });
        if (/bots(\.html)?$/.test(location.pathname)) botsLink.setAttribute("aria-current", "page");
        accountLink.parentNode.insertBefore(botsLink, accountLink);
      }
      if (/account(\.html)?$/.test(location.pathname)) accountLink.setAttribute("aria-current", "page");
    });
  }

  /* ---------- Copy server IP ---------- */
  function fallbackCopy(text) {
    var ta = el("textarea", { readonly: "", class: "sr-only" });
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try {
      ok = document.execCommand("copy");
    } catch (err) {
      ok = false;
    }
    ta.remove();
    return ok;
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(
        function () {
          return true;
        },
        function () {
          return fallbackCopy(text);
        }
      );
    }
    return Promise.resolve(fallbackCopy(text));
  }

  $$("[data-copy]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var text = btn.getAttribute("data-copy");
      var label = $("[data-copy-label]", btn);
      copyText(text).then(function (ok) {
        if (ok) {
          toast("IP copied! See you on the battlefield.");
          if (label) {
            var original = label.getAttribute("data-original") || label.textContent;
            label.setAttribute("data-original", original);
            label.textContent = "Copied!";
            setTimeout(function () {
              label.textContent = original;
            }, 2000);
          }
        } else {
          toast("Server IP: " + text);
        }
      });
    });
  });

  /* ---------- Footer year ---------- */
  $$("[data-year]").forEach(function (node) {
    node.textContent = String(new Date().getFullYear());
  });

  /* ---------- Live server + Discord status ---------- */
  var statusPill = $("[data-status]");
  var statusText = $("[data-status-text]");
  var discordMeta = $("[data-discord-meta]");

  function plural(n, word) {
    return n + " " + word + (n === 1 ? "" : "s");
  }

  function renderStatus(data) {
    var mc = data && data.minecraft;
    if (statusPill && statusText && mc && typeof mc.online === "boolean") {
      statusPill.setAttribute("data-state", mc.online ? "online" : "offline");
      if (mc.online) {
        var players = typeof mc.players === "number" ? mc.players : null;
        var detail = players === null ? "Join now" : mc.max ? players + " / " + mc.max + " players" : plural(players, "player");
        statusText.replaceChildren(el("strong", { text: "Online" }), document.createTextNode(" · " + detail));
      } else {
        statusText.replaceChildren(el("strong", { text: "Offline" }), document.createTextNode(" · check Discord for updates"));
      }
    }

    var dc = data && data.discord;
    if (discordMeta && dc && typeof dc.members === "number") {
      var parts = [];
      if (typeof dc.online === "number") {
        parts.push(el("span", null, [el("i", { class: "dot", "aria-hidden": "true" }), dc.online + " online"]));
      }
      parts.push(el("span", null, [el("i", { class: "dot dot--muted", "aria-hidden": "true" }), plural(dc.members, "member")]));
      discordMeta.replaceChildren.apply(discordMeta, parts);
    }
  }

  function getJSON(url) {
    return fetch(url, { headers: { Accept: "application/json" } }).then(function (res) {
      if (!res.ok) throw new Error("HTTP " + res.status);
      return res.json();
    });
  }

  if ((statusPill || discordMeta) && location.protocol !== "file:") {
    getJSON("/api/status")
      .then(renderStatus)
      .catch(function () {
        /* Static hosting or API down: keep the default text. */
      });
  }

  /* ---------- YouTube facades ---------- */
  var VIDEO_ID = /^[\w-]{11}$/;

  function playVideo(btn) {
    var id = btn.getAttribute("data-video-id");
    if (!VIDEO_ID.test(id || "")) return;
    var wrap = btn.closest("[data-video-wrap]") || btn.parentNode;
    var iframe = el("iframe", {
      src: "https://www.youtube-nocookie.com/embed/" + id + "?autoplay=1&rel=0",
      title: btn.getAttribute("data-video-title") || "YouTube video",
      allow: "accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share",
      referrerpolicy: "strict-origin-when-cross-origin",
      allowfullscreen: ""
    });
    wrap.replaceChildren(iframe);
    iframe.focus();
  }

  document.addEventListener("click", function (e) {
    var btn = e.target.closest && e.target.closest(".video-facade");
    if (btn) playVideo(btn);
  });

  // maxresdefault thumbnails don't exist for every video; YouTube serves a 120px placeholder instead.
  $$("img[data-thumb-fallback]").forEach(function (img) {
    function check() {
      if (img.naturalWidth && img.naturalWidth <= 120) img.src = img.getAttribute("data-thumb-fallback");
    }
    function fail() {
      img.removeEventListener("error", fail);
      img.src = img.getAttribute("data-thumb-fallback");
    }
    img.addEventListener("load", check);
    img.addEventListener("error", fail);
    if (img.complete) check();
  });

  var videosGrid = $("[data-videos]");

  function videoCard(video, index) {
    var meta = el("span", { class: "video-meta" });
    var date = formatDate(video.published);
    if (date) meta.appendChild(el("small", { text: date }));
    meta.appendChild(document.createTextNode(video.title));

    var playBtn = el("span", { class: "play-btn" });
    playBtn.appendChild(svgUse("i-play"));

    var button = el("button", {
      class: "video-facade",
      type: "button",
      "data-video-id": video.id,
      "data-video-title": video.title
    }, [
      el("img", { src: "https://i.ytimg.com/vi/" + video.id + "/hqdefault.jpg", width: "480", height: "360", alt: "", loading: "lazy" }),
      playBtn,
      meta,
      el("span", { class: "sr-only", text: "Play video" })
    ]);

    var wrap = el("div", { class: "video reveal is-visible" + (index ? " reveal-d" + index : ""), "data-video-wrap": "" }, [button]);
    return wrap;
  }

  if (videosGrid && location.protocol !== "file:") {
    var exclude = (videosGrid.getAttribute("data-exclude") || "").split(",");
    getJSON("/api/videos")
      .then(function (data) {
        var list = ((data && data.videos) || []).filter(function (v) {
          return v && VIDEO_ID.test(v.id) && typeof v.title === "string" && exclude.indexOf(v.id) === -1;
        });
        if (!list.length) return;
        videosGrid.replaceChildren.apply(videosGrid, list.slice(0, 3).map(videoCard));
      })
      .catch(function () {
        /* Keep the built-in list. */
      });
  }

  /* ---------- Gallery lightbox ---------- */
  var lightbox = $("[data-lightbox]");
  var shots = $$("[data-gallery] .shot");
  if (lightbox && shots.length) {
    var lbImg = $("[data-lb-img]", lightbox);
    var lbCaption = $("[data-lb-caption]", lightbox);
    var lbCount = $("[data-lb-count]", lightbox);
    var current = 0;
    var supportsDialog = typeof lightbox.showModal === "function";

    var show = function (i) {
      current = (i + shots.length) % shots.length;
      var shot = shots[current];
      var thumb = $("img", shot);
      lbImg.src = shot.getAttribute("data-full");
      lbImg.alt = thumb ? thumb.alt : "";
      lbCaption.textContent = shot.getAttribute("data-caption") || "";
      lbCount.textContent = current + 1 + " / " + shots.length;
    };

    shots.forEach(function (shot, i) {
      shot.addEventListener("click", function () {
        if (!supportsDialog) {
          window.open(shot.getAttribute("data-full"), "_blank", "noopener");
          return;
        }
        show(i);
        lightbox.showModal();
      });
    });

    $("[data-lb-close]", lightbox).addEventListener("click", function () {
      lightbox.close();
    });
    $("[data-lb-prev]", lightbox).addEventListener("click", function () {
      show(current - 1);
    });
    $("[data-lb-next]", lightbox).addEventListener("click", function () {
      show(current + 1);
    });
    lightbox.addEventListener("keydown", function (e) {
      if (e.key === "ArrowLeft") show(current - 1);
      if (e.key === "ArrowRight") show(current + 1);
    });
    lightbox.addEventListener("click", function (e) {
      if (e.target === lightbox) lightbox.close();
    });
  }
})();
