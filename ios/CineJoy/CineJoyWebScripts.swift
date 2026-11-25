import Foundation

enum CineJoyWebScripts {

    static let documentStartSource = """
(function() {
    function isMediaRetryUrl(url) {
        if (!url) {
            return false;
        }
        var lower = String(url).toLowerCase();
        var exts = [".m3u8", ".ts", ".m4s", ".mp4", ".vtt", ".srt", ".aac", ".key", "/hls/", "manifest", "segment", "chunk"];
        for (var i = 0; i < exts.length; i++) {
            if (lower.indexOf(exts[i]) !== -1) {
                return true;
            }
        }
        return false;
    }

    function isRetryableStatus(status) {
        return status === 408 || status === 429 || (status >= 500 && status <= 599);
    }

    function computeBackoffDelay(attempt) {
        var base = Math.min(350 * Math.pow(2, attempt), 5600);
        return base + Math.floor(Math.random() * 120);
    }

    if (!window.__cinejoyNetInstalled) {
        window.__cinejoyNetInstalled = true;

        if (typeof window.fetch === "function") {
            var origFetch = window.fetch.bind(window);
            window.fetch = function(input, init) {
                var url = (typeof input === "string") ? input : (input && input.url ? input.url : "");
                var method = ((init && init.method) || (input && input.method) || "GET").toUpperCase();
                if (!isMediaRetryUrl(url) || (method !== "GET" && method !== "HEAD")) {
                    return origFetch(input, init);
                }
                var maxRetries = 5;
                var signal = (init && init.signal) ? init.signal : (input && input.signal ? input.signal : null);

                function execAttempt(attempt) {
                    if (signal && signal.aborted) {
                        return origFetch(input, init);
                    }
                    return origFetch(input, init).then(function(response) {
                        if (isRetryableStatus(response.status) && attempt < maxRetries) {
                            return new Promise(function(resolve, reject) {
                                var delay = computeBackoffDelay(attempt);
                                setTimeout(function() {
                                    execAttempt(attempt + 1).then(resolve, reject);
                                }, delay);
                            });
                        }
                        return response;
                    }, function(err) {
                        if ((signal && signal.aborted) || attempt >= maxRetries) {
                            throw err;
                        }
                        return new Promise(function(resolve, reject) {
                            var delay = computeBackoffDelay(attempt);
                            setTimeout(function() {
                                execAttempt(attempt + 1).then(resolve, reject);
                            }, delay);
                        });
                    });
                }

                return execAttempt(0);
            };
        }

        if (typeof window.XMLHttpRequest === "function") {
            var OrigXHR = window.XMLHttpRequest;
            var WrappedXHR = function() {
                var self = this;
                var real = new OrigXHR();
                var openArgs = null;
                var storedHeaders = [];
                var sendBody = null;
                var aborted = false;
                var attempt = 0;
                var retryTimer = null;
                var listeners = {};
                var propHandlers = {};
                var currentResponseType = "";
                var currentTimeout = 0;
                var currentWithCredentials = false;
                var currentMimeType = null;

                function shouldRetryRequest() {
                    if (!openArgs || openArgs.length < 2) {
                        return false;
                    }
                    var url = String(openArgs[1] || "");
                    var isAsync = openArgs.length < 3 ? true : (openArgs[2] !== false);
                    return isAsync && isMediaRetryUrl(url);
                }

                function emitEvent(type, evt) {
                    var fn = propHandlers["on" + type];
                    if (typeof fn === "function") {
                        try { fn.call(self, evt); } catch (e) {}
                    }
                    var list = listeners[type];
                    if (list && list.length > 0) {
                        var copy = list.slice();
                        for (var i = 0; i < copy.length; i++) {
                            var entry = copy[i];
                            try {
                                if (typeof entry.fn === "function") {
                                    entry.fn.call(self, evt);
                                } else if (entry.fn && typeof entry.fn.handleEvent === "function") {
                                    entry.fn.handleEvent(evt);
                                }
                            } catch (e2) {}
                        }
                    }
                }

                function scheduleRetry(fallbackEventType, evt) {
                    if (aborted || !shouldRetryRequest() || attempt >= 5) {
                        emitEvent("readystatechange", evt);
                        emitEvent(fallbackEventType, evt);
                        if (fallbackEventType !== "loadend") {
                            emitEvent("loadend", evt);
                        }
                        return true;
                    }
                    var delay = computeBackoffDelay(attempt);
                    attempt++;
                    retryTimer = setTimeout(function() {
                        retryTimer = null;
                        if (aborted) {
                            return;
                        }
                        real = new OrigXHR();
                        attachRealHandlers(real);
                        real.open.apply(real, openArgs);
                        real.responseType = currentResponseType;
                        real.timeout = currentTimeout;
                        real.withCredentials = currentWithCredentials;
                        if (currentMimeType) {
                            try { real.overrideMimeType(currentMimeType); } catch (eMime) {}
                        }
                        for (var h = 0; h < storedHeaders.length; h++) {
                            try {
                                real.setRequestHeader(storedHeaders[h][0], storedHeaders[h][1]);
                            } catch (eHdr) {}
                        }
                        real.send(sendBody);
                    }, delay);
                    return false;
                }

                function attachRealHandlers(target) {
                    var retryPending = false;
                    target.onreadystatechange = function(evt) {
                        if (aborted) { return; }
                        if (target.readyState === 4 && shouldRetryRequest() && isRetryableStatus(target.status) && attempt < 5) {
                            retryPending = true;
                            return;
                        }
                        if (!retryPending) {
                            emitEvent("readystatechange", evt);
                        }
                    };
                    target.onload = function(evt) {
                        if (aborted) { return; }
                        if (shouldRetryRequest() && isRetryableStatus(target.status) && attempt < 5) {
                            retryPending = true;
                            scheduleRetry("load", evt);
                            return;
                        }
                        emitEvent("load", evt);
                    };
                    target.onerror = function(evt) {
                        if (aborted) { return; }
                        if (shouldRetryRequest() && attempt < 5) {
                            retryPending = true;
                            scheduleRetry("error", evt);
                            return;
                        }
                        emitEvent("error", evt);
                    };
                    target.ontimeout = function(evt) {
                        if (aborted) { return; }
                        if (shouldRetryRequest() && attempt < 5) {
                            retryPending = true;
                            scheduleRetry("timeout", evt);
                            return;
                        }
                        emitEvent("timeout", evt);
                    };
                    target.onabort = function(evt) { emitEvent("abort", evt); };
                    target.onprogress = function(evt) { if (!retryPending) { emitEvent("progress", evt); } };
                    target.onloadstart = function(evt) { if (attempt === 0) { emitEvent("loadstart", evt); } };
                    target.onloadend = function(evt) { if (!retryPending) { emitEvent("loadend", evt); } };
                }

                attachRealHandlers(real);

                this.open = function() {
                    openArgs = Array.prototype.slice.call(arguments);
                    storedHeaders = [];
                    aborted = false;
                    attempt = 0;
                    if (retryTimer) {
                        clearTimeout(retryTimer);
                        retryTimer = null;
                    }
                    return real.open.apply(real, openArgs);
                };
                this.setRequestHeader = function(name, value) {
                    storedHeaders.push([name, value]);
                    return real.setRequestHeader(name, value);
                };
                this.send = function(body) {
                    sendBody = body;
                    aborted = false;
                    return real.send(body);
                };
                this.abort = function() {
                    aborted = true;
                    if (retryTimer) {
                        clearTimeout(retryTimer);
                        retryTimer = null;
                    }
                    return real.abort();
                };
                this.getResponseHeader = function(name) { return real.getResponseHeader(name); };
                this.getAllResponseHeaders = function() { return real.getAllResponseHeaders(); };
                this.overrideMimeType = function(mime) {
                    currentMimeType = mime;
                    return real.overrideMimeType(mime);
                };
                this.dispatchEvent = function(evt) {
                    return real.dispatchEvent(evt);
                };
                this.addEventListener = function(type, fn, opts) {
                    if (!listeners[type]) { listeners[type] = []; }
                    for (var i = 0; i < listeners[type].length; i++) {
                        if (listeners[type][i].fn === fn) { return; }
                    }
                    listeners[type].push({ fn: fn, opts: opts });
                };
                this.removeEventListener = function(type, fn) {
                    var list = listeners[type];
                    if (!list) { return; }
                    for (var i = list.length - 1; i >= 0; i--) {
                        if (list[i].fn === fn) { list.splice(i, 1); }
                    }
                };

                Object.defineProperties(this, {
                    readyState: { get: function() { return real.readyState; } },
                    status: { get: function() { return real.status; } },
                    statusText: { get: function() { return real.statusText; } },
                    response: { get: function() { return real.response; } },
                    responseText: { get: function() { return real.responseText; } },
                    responseXML: { get: function() { return real.responseXML; } },
                    responseURL: { get: function() { return real.responseURL; } },
                    upload: { get: function() { return real.upload; } },
                    responseType: {
                        get: function() { return currentResponseType; },
                        set: function(v) { currentResponseType = v; real.responseType = v; }
                    },
                    timeout: {
                        get: function() { return currentTimeout; },
                        set: function(v) { currentTimeout = v; real.timeout = v; }
                    },
                    withCredentials: {
                        get: function() { return currentWithCredentials; },
                        set: function(v) { currentWithCredentials = !!v; real.withCredentials = !!v; }
                    }
                });

                var events = ["readystatechange", "load", "error", "timeout", "abort", "progress", "loadstart", "loadend"];
                for (var ei = 0; ei < events.length; ei++) {
                    (function(evName) {
                        var propName = "on" + evName;
                        Object.defineProperty(self, propName, {
                            get: function() { return propHandlers[propName] || null; },
                            set: function(v) { propHandlers[propName] = v; }
                        });
                    })(events[ei]);
                }
            };
            WrappedXHR.UNSENT = OrigXHR.UNSENT;
            WrappedXHR.OPENED = OrigXHR.OPENED;
            WrappedXHR.HEADERS_RECEIVED = OrigXHR.HEADERS_RECEIVED;
            WrappedXHR.LOADING = OrigXHR.LOADING;
            WrappedXHR.DONE = OrigXHR.DONE;
            WrappedXHR.prototype = OrigXHR.prototype;
            window.XMLHttpRequest = WrappedXHR;
        }
    }

    window.__cinejoyReleaseMedia = function(preservePos) {
        try {
            var videos = document.querySelectorAll("video");
            var hadVideo = videos.length > 0;
            var savedTime = 0;
            for (var i = 0; i < videos.length; i++) {
                var v = videos[i];
                if (preservePos && !savedTime && v.currentTime && !isNaN(v.currentTime) && v.currentTime > 1) {
                    savedTime = v.currentTime;
                }
                try { v.pause(); } catch (e1) {}
                try { v.removeAttribute("src"); } catch (e2) {}
                try { v.srcObject = null; } catch (e3) {}
                try { v.load(); } catch (e4) {}
            }
            if (preservePos && hadVideo && location.pathname.indexOf("/watch/") !== -1) {
                sessionStorage.setItem("__cj_bg_resume", JSON.stringify({
                    path: location.pathname,
                    time: savedTime,
                    cleared: true
                }));
            }
        } catch (e) {}
    };

    window.__cinejoyResumeMedia = function() {
        try {
            var raw = sessionStorage.getItem("__cj_bg_resume");
            if (!raw) {
                return;
            }
            var info = JSON.parse(raw);
            if (!info || info.path !== location.pathname) {
                sessionStorage.removeItem("__cj_bg_resume");
                return;
            }
            if (info.cleared) {
                info.cleared = false;
                sessionStorage.setItem("__cj_bg_resume", JSON.stringify(info));
                location.reload();
                return;
            }
            if (info.time && info.time > 1) {
                var tries = 0;
                var restoreTimer = setInterval(function() {
                    tries++;
                    var vid = document.querySelector("video");
                    if (vid && vid.readyState >= 1) {
                        try {
                            vid.currentTime = info.time;
                            vid.play();
                        } catch (eSeek) {}
                        sessionStorage.removeItem("__cj_bg_resume");
                        clearInterval(restoreTimer);
                    } else if (tries >= 25) {
                        sessionStorage.removeItem("__cj_bg_resume");
                        clearInterval(restoreTimer);
                    }
                }, 250);
            } else {
                sessionStorage.removeItem("__cj_bg_resume");
            }
        } catch (e) {}
    };

    try {
        localStorage.setItem("cinejoy_notice_seen", "2026-10-fixed-4k-no-ads");
        var userQuality = sessionStorage.getItem("__cj_user_quality");
        var rawPlayer = localStorage.getItem("cinejoy_player_local");
        var playerCfg = rawPlayer ? JSON.parse(rawPlayer) : {};
        if (userQuality && /^[0-9]+$/.test(userQuality)) {
            playerCfg.quality = parseInt(userQuality, 10);
            playerCfg.mp4Quality = userQuality;
        } else {
            delete playerCfg.quality;
            playerCfg.mp4Quality = "auto";
        }
        localStorage.setItem("cinejoy_player_local", JSON.stringify(playerCfg));
    } catch (e) {}
})();
"""

    static let documentEndSource = """
(function() {
    if (window.__cinejoyIosController && window.__cinejoyIosController.initialized) {
        window.__cinejoyIosController.refresh();
        return;
    }

    var WATCH_CLASS = "cinejoy-watch-active";
    var HUD_HIDDEN_CLASS = "cinejoy-hud-hidden";
    var STYLE_ATTR = "data-cinejoy-ios-style";
    var HUD_AUTO_HIDE_MS = 3000;
    var hudTimer = null;
    var refreshTimer = null;
    var watchGuardTimer = null;
    var boundVid = null;
    var lastReportedWatch = null;
    var contentReadySent = false;

    function ensureViewportMeta() {
        var existing = document.querySelector('meta[name="viewport"]');
        if (!existing) {
            var meta = document.createElement("meta");
            meta.name = "viewport";
            meta.setAttribute("content", "width=device-width, initial-scale=1, viewport-fit=cover");
            (document.head || document.documentElement).appendChild(meta);
        }
    }

    function injectSafeAreaAndHudStyles() {
        if (document.querySelector("style[" + STYLE_ATTR + "=\"true\"]")) {
            return;
        }
        var style = document.createElement("style");
        style.setAttribute(STYLE_ATTR, "true");
        style.type = "text/css";
        style.textContent =
            "html { overflow-x: clip !important; -webkit-text-size-adjust: 100% !important; }" +
            "html, body { background-color: rgb(5, 5, 5) !important; -webkit-tap-highlight-color: transparent; touch-action: manipulation; }" +
            "button, a, input, textarea, select { touch-action: manipulation; }" +
            "input, textarea, select { font-size: 16px !important; }" +
            "[data-cj-hud-chrome=\"1\"] { transition: opacity 180ms ease !important; }" +
            "html." + WATCH_CLASS + "." + HUD_HIDDEN_CLASS + " [data-cj-hud-chrome=\"1\"] { opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; }";
        var target = document.head || document.documentElement;
        if (target) {
            target.appendChild(style);
        }
    }

    function repositionAddMenuForKeyboard() {
        var menu = document.querySelector(".add-menu");
        if (!menu) {
            return;
        }
        var active = document.activeElement;
        var inMenuInput = !!(active && menu.contains(active) && (active.tagName === "INPUT" || active.tagName === "TEXTAREA"));
        var vv = window.visualViewport;
        if (inMenuInput && vv && vv.height < window.innerHeight * 0.78) {
            var r = menu.getBoundingClientRect();
            var topPx = Math.max(16, (vv.height - r.height) / 2) + (vv.offsetTop || 0);
            menu.style.setProperty("top", Math.round(topPx) + "px", "important");
            menu.style.setProperty("transform", "translateX(-50%)", "important");
        } else {
            menu.style.removeProperty("top");
            menu.style.removeProperty("transform");
        }
    }

    function isWatchRoute() {
        return location.pathname.indexOf("/watch/") !== -1;
    }

    function postBridgeMessage(payload) {
        try {
            if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.cinejoyNative) {
                window.webkit.messageHandlers.cinejoyNative.postMessage(payload);
            }
        } catch (e) {}
    }

    function reportRouteState(onWatch) {
        if (lastReportedWatch === onWatch) {
            return;
        }
        lastReportedWatch = onWatch;
        postBridgeMessage({
            type: "routeChanged",
            url: location.href,
            isWatch: !!onWatch
        });
    }

    function isProtectedNode(node, vid) {
        if (!node || node.nodeType !== 1) {
            return true;
        }
        if (node === vid || (vid && node.contains(vid))) {
            return true;
        }
        if (node.classList && (node.classList.contains("lp-subtitle") || node.classList.contains("animate-spin") || node.classList.contains("settings-popover"))) {
            return true;
        }
        if (node.getAttribute && (node.getAttribute("role") === "dialog" || node.getAttribute("aria-modal") === "true")) {
            return true;
        }
        if (node.querySelector && node.querySelector('.lp-subtitle, .animate-spin, [role="dialog"], [aria-modal="true"], .settings-popover')) {
            return true;
        }
        return false;
    }

    function hasActiveDialog() {
        var nodes = document.querySelectorAll('[role="dialog"], [aria-modal="true"], [role="menu"], .menu-panel');
        for (var i = 0; i < nodes.length; i++) {
            var style = window.getComputedStyle(nodes[i]);
            if (style && style.display !== "none" && style.visibility !== "hidden") {
                var r = nodes[i].getBoundingClientRect();
                if (r.width >= 40 && r.height >= 40) {
                    return true;
                }
            }
        }
        return false;
    }

    function hasMountedPlayerControls() {
        var root = document.fullscreenElement || document.webkitFullscreenElement || document.querySelector(".player-root");
        if (!root) {
            return false;
        }
        if (root.classList && root.classList.contains("cursor-none")) {
            return false;
        }
        return root.querySelectorAll("button").length > 0;
    }

    function markHudChrome() {
        var vid = document.querySelector("video");
        var root = document.fullscreenElement || document.webkitFullscreenElement || document.querySelector(".player-root") || (vid ? vid.parentElement : null);
        if (!root) {
            return;
        }
        var controls = root.querySelectorAll('button, input[type="range"], [role="slider"]');
        for (var i = 0; i < controls.length; i++) {
            var cur = controls[i];
            var topBar = null;
            while (cur && cur !== root && cur !== document.body && cur !== document.documentElement) {
                if (isProtectedNode(cur, vid)) {
                    break;
                }
                topBar = cur;
                cur = cur.parentElement;
            }
            if (topBar && topBar.getAttribute("data-cj-hud-chrome") !== "1") {
                topBar.setAttribute("data-cj-hud-chrome", "1");
            }
        }
    }

    function clearTimer() {
        if (hudTimer) {
            clearTimeout(hudTimer);
            hudTimer = null;
        }
    }

    function hideHud() {
        clearTimer();
        if (!isWatchRoute() || hasActiveDialog()) {
            return;
        }
        var vid = document.querySelector("video");
        if (!vid || vid.paused) {
            return;
        }
        markHudChrome();
        document.documentElement.classList.add(HUD_HIDDEN_CLASS);
    }

    function scheduleHide() {
        clearTimer();
        if (!isWatchRoute() || hasActiveDialog()) {
            return;
        }
        var vid = document.querySelector("video");
        if (vid && vid.paused) {
            return;
        }
        hudTimer = setTimeout(hideHud, HUD_AUTO_HIDE_MS);
    }

    function applyVideoHardwareAttributes(vid) {
        if (!vid) {
            return;
        }
        vid.setAttribute("playsinline", "");
        vid.setAttribute("webkit-playsinline", "");
        vid.setAttribute("x-webkit-airplay", "allow");
        vid.playsInline = true;
        if (boundVid !== vid) {
            boundVid = vid;
            vid.addEventListener("play", scheduleHide);
            vid.addEventListener("playing", scheduleHide);
            vid.addEventListener("pause", function() {
                clearTimer();
                document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
            });
        }
    }

    function syncWatchGuardTimer(onWatch) {
        if (onWatch && !watchGuardTimer) {
            watchGuardTimer = setInterval(function() {
                if (isWatchRoute()) {
                    refresh();
                }
            }, 3000);
        } else if (!onWatch && watchGuardTimer) {
            clearInterval(watchGuardTimer);
            watchGuardTimer = null;
        }
    }

    function refresh() {
        ensureViewportMeta();
        injectSafeAreaAndHudStyles();
        repositionAddMenuForKeyboard();
        var onWatch = isWatchRoute();
        reportRouteState(onWatch);
        syncWatchGuardTimer(onWatch);
        if (onWatch) {
            document.documentElement.classList.add(WATCH_CLASS);
            markHudChrome();
            var vid = document.querySelector("video");
            if (vid) {
                applyVideoHardwareAttributes(vid);
                if (!vid.paused && !hudTimer && !document.documentElement.classList.contains(HUD_HIDDEN_CLASS)) {
                    scheduleHide();
                }
            }
        } else {
            clearTimer();
            document.documentElement.classList.remove(WATCH_CLASS);
            document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
        }
        if (!contentReadySent) {
            var readyNodes = document.querySelectorAll('a[href*="/movie/"], a[href*="/tv/"], a[href*="/watch/"], button');
            if (readyNodes.length >= 4) {
                contentReadySent = true;
                postBridgeMessage({ type: "contentReady" });
            }
        }
    }

    function scheduleDebouncedRefresh() {
        if (refreshTimer) {
            clearTimeout(refreshTimer);
        }
        refreshTimer = setTimeout(refresh, 160);
    }

    document.addEventListener("click", function(e) {
        if (!isWatchRoute()) {
            return;
        }
        if (document.documentElement.classList.contains(HUD_HIDDEN_CLASS)) {
            var mounted = hasMountedPlayerControls();
            document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
            scheduleHide();
            if (mounted) {
                e.preventDefault();
                e.stopPropagation();
            }
            return;
        }
        scheduleHide();
    }, true);

    document.addEventListener("focusin", function() {
        setTimeout(repositionAddMenuForKeyboard, 80);
    }, true);
    document.addEventListener("focusout", function() {
        setTimeout(repositionAddMenuForKeyboard, 120);
    }, true);

    if (window.visualViewport) {
        window.visualViewport.addEventListener("resize", repositionAddMenuForKeyboard);
        window.visualViewport.addEventListener("scroll", repositionAddMenuForKeyboard);
    }

    if (window.history) {
        var origPushState = window.history.pushState;
        if (typeof origPushState === "function") {
            window.history.pushState = function() {
                var ret = origPushState.apply(this, arguments);
                scheduleDebouncedRefresh();
                return ret;
            };
        }
        var origReplaceState = window.history.replaceState;
        if (typeof origReplaceState === "function") {
            window.history.replaceState = function() {
                var ret = origReplaceState.apply(this, arguments);
                scheduleDebouncedRefresh();
                return ret;
            };
        }
    }
    window.addEventListener("popstate", scheduleDebouncedRefresh);

    if (typeof MutationObserver !== "undefined" && document.body) {
        var observer = new MutationObserver(scheduleDebouncedRefresh);
        observer.observe(document.body, { childList: true, subtree: true });
    }

    window.__cinejoyIosController = {
        initialized: true,
        refresh: refresh
    };

    refresh();
})();
"""
}
