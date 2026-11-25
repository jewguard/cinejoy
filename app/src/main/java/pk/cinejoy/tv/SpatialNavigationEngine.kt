package pk.cinejoy.tv

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

object SpatialNavigationEngine {

    private const val NETWORK_RESILIENCE_AND_PRESET_SCRIPT = """
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

    function isLoginAuthUrl(url) {
        if (!url) {
            return false;
        }
        return String(url).toLowerCase().indexOf("/api/auth/login") !== -1;
    }

    function isTransientRetryStatus(status) {
        return status === 408 || status === 429 || (status >= 500 && status <= 599);
    }

    function isGatewayRetryStatus(status) {
        return status === 502 || status === 503 || status === 504;
    }

    function computeBackoffDelay(attempt) {
        var base = Math.min(350 * Math.pow(2, attempt), 1400);
        return base + Math.floor(Math.random() * 100);
    }

    if (!window.__cinejoyNetInstalled) {
        window.__cinejoyNetInstalled = true;

        if (typeof window.fetch === "function") {
            var origFetch = window.fetch.bind(window);
            window.fetch = function(input, init) {
                var url = (typeof input === "string") ? input : (input && input.url ? input.url : "");
                var method = ((init && init.method) || (input && input.method) || "GET").toUpperCase();
                var mediaRetry = isMediaRetryUrl(url) && (method === "GET" || method === "HEAD");
                var authLoginRetry = isLoginAuthUrl(url) && method === "POST";
                if (!mediaRetry && !authLoginRetry) {
                    return origFetch(input, init);
                }
                var maxRetries = mediaRetry ? 3 : 1;
                var signal = (init && init.signal) ? init.signal : (input && input.signal ? input.signal : null);

                function execAttempt(attempt) {
                    if (signal && signal.aborted) {
                        return origFetch(input, init);
                    }
                    return origFetch(input, init).then(function(response) {
                        var shouldRetry = mediaRetry ? isTransientRetryStatus(response.status) : isGatewayRetryStatus(response.status);
                        if (shouldRetry && attempt < maxRetries) {
                            return new Promise(function(resolve, reject) {
                                var delay = mediaRetry ? computeBackoffDelay(attempt) : 800;
                                setTimeout(function() {
                                    execAttempt(attempt + 1).then(resolve, reject);
                                }, delay);
                            });
                        }
                        return response;
                    }, function(err) {
                        if ((signal && signal.aborted) || attempt >= maxRetries || !mediaRetry) {
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
                    if (aborted || !shouldRetryRequest() || attempt >= 3) {
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
                    var retryPendingForThisInstance = false;
                    target.onreadystatechange = function(evt) {
                        if (aborted) {
                            return;
                        }
                        if (target.readyState === 4 && shouldRetryRequest() && isTransientRetryStatus(target.status) && attempt < 3) {
                            retryPendingForThisInstance = true;
                            return;
                        }
                        if (!retryPendingForThisInstance) {
                            emitEvent("readystatechange", evt);
                        }
                    };
                    target.onload = function(evt) {
                        if (aborted) {
                            return;
                        }
                        if (shouldRetryRequest() && isTransientRetryStatus(target.status) && attempt < 3) {
                            retryPendingForThisInstance = true;
                            scheduleRetry("load", evt);
                            return;
                        }
                        emitEvent("load", evt);
                    };
                    target.onerror = function(evt) {
                        if (aborted) {
                            return;
                        }
                        if (shouldRetryRequest() && attempt < 3) {
                            retryPendingForThisInstance = true;
                            scheduleRetry("error", evt);
                            return;
                        }
                        emitEvent("error", evt);
                    };
                    target.ontimeout = function(evt) {
                        if (aborted) {
                            return;
                        }
                        if (shouldRetryRequest() && attempt < 3) {
                            retryPendingForThisInstance = true;
                            scheduleRetry("timeout", evt);
                            return;
                        }
                        emitEvent("timeout", evt);
                    };
                    target.onabort = function(evt) {
                        emitEvent("abort", evt);
                    };
                    target.onprogress = function(evt) {
                        if (!retryPendingForThisInstance) {
                            emitEvent("progress", evt);
                        }
                    };
                    target.onloadstart = function(evt) {
                        if (attempt === 0) {
                            emitEvent("loadstart", evt);
                        }
                    };
                    target.onloadend = function(evt) {
                        if (!retryPendingForThisInstance) {
                            emitEvent("loadend", evt);
                        }
                    };
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
                this.getResponseHeader = function(name) {
                    return real.getResponseHeader(name);
                };
                this.getAllResponseHeaders = function() {
                    return real.getAllResponseHeaders();
                };
                this.overrideMimeType = function(mime) {
                    currentMimeType = mime;
                    return real.overrideMimeType(mime);
                };
                this.dispatchEvent = function(evt) {
                    return real.dispatchEvent(evt);
                };
                this.addEventListener = function(type, fn, opts) {
                    if (!listeners[type]) {
                        listeners[type] = [];
                    }
                    for (var i = 0; i < listeners[type].length; i++) {
                        if (listeners[type][i].fn === fn) {
                            return;
                        }
                    }
                    listeners[type].push({ fn: fn, opts: opts });
                };
                this.removeEventListener = function(type, fn) {
                    var list = listeners[type];
                    if (!list) {
                        return;
                    }
                    for (var i = list.length - 1; i >= 0; i--) {
                        if (list[i].fn === fn) {
                            list.splice(i, 1);
                        }
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
        sessionStorage.removeItem("__cj_nav_retry");
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

    private const val FIRESTICK_NAV_HEAD = """
(function() {
    function applyTvPresets() {
        try {
            localStorage.setItem("cinejoy_notice_seen", "2026-10-fixed-4k-no-ads");
            document.documentElement.setAttribute("data-low-power", "on");
            document.documentElement.setAttribute("data-flat-ui", "on");
            document.documentElement.setAttribute("data-theme-ambient-bleed", "off");
            document.documentElement.setAttribute("data-glass-refract", "off");

            var rawSettings = localStorage.getItem("cinejoy_settings");
            var settings = rawSettings ? JSON.parse(rawSettings) : {};
            settings.trailersEnabled = false;
            settings.reduceMotion = true;
            settings.autoplay = true;
            localStorage.setItem("cinejoy_settings", JSON.stringify(settings));
        } catch (e) {}
    }

    applyTvPresets();

    if (window.__cinejoySpatialNav && window.__cinejoySpatialNav.initialized) {
        window.__cinejoySpatialNav.refresh();
        return;
    }

    var FOCUS_CLASS = "cinejoy-tv-focus";
    var STYLE_ATTR = "data-cinejoy-nav";
    var WATCH_CLASS = "cinejoy-watch-active";
    var HUD_HIDDEN_CLASS = "cinejoy-hud-hidden";
    var HUD_AUTO_HIDE_MS = 3000;
    var SELECTOR = "a[href], button, input, select, textarea, summary, [role=\"button\"], [role=\"link\"], [role=\"tab\"], [role=\"menuitem\"], [role=\"menuitemradio\"], [role=\"menuitemcheckbox\"], [role=\"option\"], [role=\"slider\"], [onclick], [tabindex], iframe[src*=\"challenges.cloudflare.com\"]";

    var state = {
        initialized: true,
        spatialMap: [],
        cacheTime: 0,
        currentElement: null,
        lastBrowseElement: null,
        observer: null,
        refreshTimer: null,
        hudWakeTimer: null,
        hudRetryWakeTimer: null,
        watchGuardTimer: null,
        lastWatchReported: null,
        hudHidden: false,
        hudAutoHideTimer: null,
        lastHudElement: null,
        isNativeFullscreen: false,
        boundVideoRef: null,
        wakeStep: 0
    };

    function isWatchRoute() {
        return location.pathname.indexOf("/watch/") !== -1;
    }

    function isVideoActivelyPlayable(vid) {
        return !!(vid && !vid.error && (vid.videoWidth > 0 || vid.readyState >= 2));
    }

    function isInHeaderBar(el) {
        return !!(el && el.closest && el.closest(".header-row"));
    }

    function isTextInputControl(el) {
        if (!el) {
            return false;
        }
        if (el.tagName === "TEXTAREA") {
            return true;
        }
        if (el.tagName === "INPUT") {
            var t = (el.type || "text").toLowerCase();
            return t !== "range" && t !== "checkbox" && t !== "radio" && t !== "hidden" && t !== "button" && t !== "submit";
        }
        return false;
    }

    function reportSurfaceTransparency(onWatch) {
        if (state.lastWatchReported === onWatch) {
            return;
        }
        state.lastWatchReported = onWatch;
        try {
            if (window.__cinejoyNative && window.__cinejoyNative.setWatchSurfaceTransparent) {
                window.__cinejoyNative.setWatchSurfaceTransparent(!!onWatch);
            }
        } catch (e) {}
    }

    function dispatchPointerSweep(root) {
        state.wakeStep = (state.wakeStep || 0) + 1;
        var wx = 280 + ((state.wakeStep % 5) * 40);
        var wy = 220 + ((state.wakeStep % 3) * 30);
        var points = [[wx, wy], [wx + 32, wy + 18]];
        var targets = [root, document];
        for (var i = 0; i < targets.length; i++) {
            var t = targets[i];
            for (var p = 0; p < points.length; p++) {
                var px = points[p][0];
                var py = points[p][1];
                try {
                    t.dispatchEvent(new MouseEvent(p === 0 ? "mouseenter" : "mousemove", { bubbles: true, cancelable: true, view: window, clientX: px, clientY: py }));
                    t.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, cancelable: true, view: window, clientX: px, clientY: py }));
                } catch (e) {}
                try {
                    if (typeof PointerEvent !== "undefined") {
                        t.dispatchEvent(new PointerEvent(p === 0 ? "pointerenter" : "pointermove", { bubbles: true, cancelable: true, view: window, clientX: px, clientY: py, pointerType: "mouse", isPrimary: true }));
                        t.dispatchEvent(new PointerEvent("pointermove", { bubbles: true, cancelable: true, view: window, clientX: px, clientY: py, pointerType: "mouse", isPrimary: true }));
                    }
                } catch (e2) {}
            }
        }
        try {
            if (window.__cinejoyNative && window.__cinejoyNative.pulseMouseHover) {
                window.__cinejoyNative.pulseMouseHover();
            }
        } catch (e3) {}
    }

    function wakePlayerHud() {
        var root = document.fullscreenElement || document.webkitFullscreenElement || document.querySelector(".player-root") || document.body;
        if (!root) {
            return;
        }
        dispatchPointerSweep(root);
        if (state.hudRetryWakeTimer) {
            clearTimeout(state.hudRetryWakeTimer);
            state.hudRetryWakeTimer = null;
        }
        if (isWatchRoute()) {
            state.hudRetryWakeTimer = setTimeout(function() {
                state.hudRetryWakeTimer = null;
                var currentRoot = document.fullscreenElement || document.webkitFullscreenElement || document.querySelector(".player-root");
                if (isWatchRoute() && currentRoot && ((currentRoot.classList && currentRoot.classList.contains("cursor-none")) || currentRoot.querySelectorAll("button").length === 0)) {
                    dispatchPointerSweep(currentRoot);
                }
            }, 80);
        }
    }

    function isInFullscreen() {
        var nativeFs = false;
        try {
            nativeFs = !!(window.__cinejoyNative && window.__cinejoyNative.isFullscreenActive && window.__cinejoyNative.isFullscreenActive());
        } catch (e) {}
        return !!(state.isNativeFullscreen || document.fullscreenElement || document.webkitFullscreenElement || nativeFs);
    }

    function isFullscreenButton(el) {
        if (!el) {
            return false;
        }
        var aria = (el.getAttribute("aria-label") || "").toLowerCase();
        var title = (el.getAttribute("title") || "").toLowerCase();
        var txt = (el.innerText || "").replace(/\s+/g, " ").trim().toLowerCase();
        return aria.indexOf("fullscreen") !== -1 ||
            aria.indexOf("full screen") !== -1 ||
            title.indexOf("fullscreen") !== -1 ||
            title.indexOf("full screen") !== -1 ||
            txt === "fullscreen" ||
            txt === "exit fullscreen";
    }

    function isSeekControlElement(el) {
        if (!el) {
            return false;
        }
        if (el.getAttribute && el.getAttribute("data-cj-seekbar") === "1") {
            return true;
        }
        if (el.tagName === "INPUT" && el.type === "range") {
            return true;
        }
        if (el.getAttribute && el.getAttribute("role") === "slider") {
            return true;
        }
        var aria = el.getAttribute ? (el.getAttribute("aria-label") || "").toLowerCase() : "";
        return aria.indexOf("seek") !== -1;
    }

    function findNativeSeekControl() {
        var existing = document.querySelector('[data-cj-seekbar="1"]');
        if (existing) {
            return existing;
        }
        var seekInput = document.querySelector('input[aria-label="Seek Video"], input[type="range"]');
        if (seekInput && seekInput.parentElement) {
            return seekInput.parentElement;
        }
        return document.querySelector('[role="slider"]');
    }

    function findNativePlayPauseButton() {
        return document.querySelector('button[aria-label="Pause"], button[aria-label="Play"]');
    }

    function dispatchNativeDirectionalSeek(isRight) {
        var keyName = isRight ? "ArrowRight" : "ArrowLeft";
        var keyCodeNum = isRight ? 39 : 37;
        try {
            var kd = new KeyboardEvent("keydown", {
                key: keyName,
                code: keyName,
                keyCode: keyCodeNum,
                which: keyCodeNum,
                bubbles: true,
                cancelable: true
            });
            document.dispatchEvent(kd);
            var ku = new KeyboardEvent("keyup", {
                key: keyName,
                code: keyName,
                keyCode: keyCodeNum,
                which: keyCodeNum,
                bubbles: true,
                cancelable: true
            });
            document.dispatchEvent(ku);
        } catch (e) {}
    }

    function getPlayerRoot(vid) {
        return document.fullscreenElement ||
            document.webkitFullscreenElement ||
            document.querySelector(".player-root") ||
            (vid ? vid.parentElement : null);
    }

    function isProtectedPlayerNode(node, vid) {
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

    function markPlayerControlContainers() {
        if (!isWatchRoute()) {
            return;
        }
        var vid = findActiveVideo();
        var playerRoot = getPlayerRoot(vid);
        if (!playerRoot) {
            return;
        }
        var controls = playerRoot.querySelectorAll('button, input[type="range"], [role="slider"], [data-cj-seekbar="1"]');
        for (var i = 0; i < controls.length; i++) {
            var cur = controls[i];
            var topBar = null;
            while (cur && cur !== playerRoot && cur !== document.body && cur !== document.documentElement) {
                if (isProtectedPlayerNode(cur, vid)) {
                    break;
                }
                topBar = cur;
                cur = cur.parentElement;
            }
            if (topBar && topBar.getAttribute("data-cj-hud-chrome") !== "1") {
                topBar.setAttribute("data-cj-hud-chrome", "1");
            }
        }
        var children = playerRoot.children || [];
        for (var j = 0; j < children.length; j++) {
            var ch = children[j];
            if (isProtectedPlayerNode(ch, vid)) {
                continue;
            }
            var cls = (ch.className && typeof ch.className === "string") ? ch.className.toLowerCase() : "";
            var hasControls = !!(ch.querySelector && ch.querySelector('button, input[type="range"], [role="slider"], [data-cj-seekbar="1"]'));
            var isBarOrGradient = cls.indexOf("gradient") !== -1 || cls.indexOf("scrim") !== -1 || cls.indexOf("control") !== -1 || cls.indexOf("hud") !== -1 || cls.indexOf("bar") !== -1;
            if ((hasControls || isBarOrGradient) && ch.getAttribute("data-cj-hud-chrome") !== "1") {
                ch.setAttribute("data-cj-hud-chrome", "1");
            }
        }
    }

    function clearHudAutoHideTimer() {
        if (state.hudAutoHideTimer) {
            clearTimeout(state.hudAutoHideTimer);
            state.hudAutoHideTimer = null;
        }
    }

    function hidePlayerControls() {
        clearHudAutoHideTimer();
        if (!isWatchRoute()) {
            return false;
        }
        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;
        if (findActiveOverlay(vw, vh)) {
            return false;
        }
        var vid = findActiveVideo();
        if (!isVideoActivelyPlayable(vid) || vid.paused) {
            return false;
        }
        markPlayerControlContainers();
        state.hudHidden = true;
        if (state.currentElement && document.body.contains(state.currentElement)) {
            state.lastHudElement = state.currentElement;
        }
        clearFocusClass();
        if (document.activeElement && document.activeElement !== document.body && typeof document.activeElement.blur === "function") {
            try {
                document.activeElement.blur();
            } catch (e) {}
        }
        document.documentElement.classList.add(HUD_HIDDEN_CLASS);
        return true;
    }

    function scheduleHudAutoHide() {
        clearHudAutoHideTimer();
        if (!isWatchRoute()) {
            return;
        }
        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;
        if (findActiveOverlay(vw, vh)) {
            return;
        }
        var vid = findActiveVideo();
        if (!isVideoActivelyPlayable(vid) || vid.paused) {
            return;
        }
        state.hudAutoHideTimer = setTimeout(hidePlayerControls, HUD_AUTO_HIDE_MS);
    }

    function showPlayerControls(shouldScheduleHide) {
        clearHudAutoHideTimer();
        state.hudHidden = false;
        document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
        wakePlayerHud();
        if (shouldScheduleHide !== false) {
            scheduleHudAutoHide();
        }
    }

    function resetHudAutoHide() {
        clearHudAutoHideTimer();
        if (state.hudRetryWakeTimer) {
            clearTimeout(state.hudRetryWakeTimer);
            state.hudRetryWakeTimer = null;
        }
        state.hudHidden = false;
        state.lastHudElement = null;
        document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
        var tagged = document.querySelectorAll('[data-cj-hud-chrome="1"]');
        for (var i = 0; i < tagged.length; i++) {
            tagged[i].removeAttribute("data-cj-hud-chrome");
        }
    }

    function bindVideoPlaybackEvents(vid) {
        if (!vid || state.boundVideoRef === vid) {
            return;
        }
        state.boundVideoRef = vid;
        vid.addEventListener("loadeddata", function() {
            syncWatchPageState();
        });
        vid.addEventListener("error", function() {
            syncWatchPageState();
        });
        vid.addEventListener("play", function() {
            syncWatchPageState();
            scheduleHudAutoHide();
        });
        vid.addEventListener("playing", function() {
            syncWatchPageState();
            scheduleHudAutoHide();
        });
        vid.addEventListener("pause", function() {
            showPlayerControls(false);
        });
    }

    function onFullscreenChanged(active) {
        state.isNativeFullscreen = !!active;
        showPlayerControls(true);
        setTimeout(function() {
            syncWatchPageState();
            markPlayerControlContainers();
            scanElements(false);
            var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
            var vh = window.innerHeight || document.documentElement.clientHeight || 720;
            if (!findActiveOverlay(vw, vh) && !state.hudHidden) {
                var fsBtn = null;
                var btns = document.querySelectorAll("button");
                for (var i = 0; i < btns.length; i++) {
                    if (isFullscreenButton(btns[i]) && isVisibleElement(btns[i], vw, vh, true)) {
                        fsBtn = btns[i];
                        break;
                    }
                }
                var seekBar = findNativeSeekControl();
                if (fsBtn) {
                    applyFocus(fsBtn);
                } else if (seekBar) {
                    applyFocus(seekBar);
                } else {
                    ensureFocus();
                }
            }
            scheduleHudAutoHide();
        }, 120);
    }

    function isNodeVisiblyRendered(node, stopRoot) {
        var cur = node;
        while (cur && cur !== document.documentElement) {
            var style = window.getComputedStyle(cur);
            if (!style || style.display === "none" || style.visibility === "hidden" || parseFloat(style.opacity || "1") <= 0.05) {
                return false;
            }
            if (cur === stopRoot) {
                break;
            }
            cur = cur.parentElement;
        }
        return true;
    }

    function isHudVisible() {
        if (state.hudHidden) {
            return false;
        }
        var playerRoot = getPlayerRoot(findActiveVideo());
        if (playerRoot && playerRoot.classList && playerRoot.classList.contains("cursor-none")) {
            return false;
        }
        var nodes = document.querySelectorAll('[data-cj-seekbar="1"], button[aria-label="Pause"], button[aria-label="Play"], button[aria-label*="Fullscreen"], button[aria-label*="fullscreen"]');
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (!el.getBoundingClientRect) {
                continue;
            }
            var r = el.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) {
                continue;
            }
            if (isNodeVisiblyRendered(el, playerRoot)) {
                return true;
            }
        }
        return false;
    }

    function releaseOverlaySafely(destroyVideo) {
        if (destroyVideo) {
            try {
                if (window.__cinejoyReleaseMedia) {
                    window.__cinejoyReleaseMedia(false);
                }
            } catch (eRel) {}
        }
        try {
            if (window.__cinejoyNative && window.__cinejoyNative.releaseVideoOverlay) {
                window.__cinejoyNative.releaseVideoOverlay();
            }
        } catch (eNat) {}
    }

    function pruneSubtitleTracks(video) {
        if (!video) {
            return;
        }
        var tracks = video.querySelectorAll("track");
        if (tracks.length <= 40) {
            return;
        }
        var kept = 0;
        var toRemove = [];
        for (var i = 0; i < tracks.length; i++) {
            var t = tracks[i];
            var src = t.src || t.getAttribute("src") || "";
            var lang = (t.getAttribute("srclang") || "").toLowerCase();
            var isLoaded = src.indexOf("blob:") === 0 || (t.track && (t.track.mode === "showing" || (t.track.cues && t.track.cues.length > 0)));
            var isPreferred = isLoaded || t.hasAttribute("default") ||
                lang === "en" || lang === "eng" || lang === "es" || lang === "fr" || lang === "ar" || lang === "ur";
            if (isLoaded || (isPreferred && kept < 24)) {
                kept++;
            } else {
                toRemove.push(t);
            }
        }
        for (var j = 0; j < toRemove.length; j++) {
            var node = toRemove[j];
            if (node.parentNode) {
                node.parentNode.removeChild(node);
            }
        }
    }

    function clearTransparentSurfaceMarks() {
        document.documentElement.classList.remove(WATCH_CLASS);
        var cleaned = document.querySelectorAll('[data-cj-trans="1"]');
        for (var i = 0; i < cleaned.length; i++) {
            cleaned[i].removeAttribute("data-cj-trans");
            cleaned[i].style.removeProperty("background");
            cleaned[i].style.removeProperty("background-color");
            cleaned[i].style.removeProperty("background-image");
        }
    }

    function syncWatchGuardTimer(onWatch) {
        if (onWatch && !state.watchGuardTimer) {
            state.watchGuardTimer = setInterval(function() {
                if (isWatchRoute()) {
                    syncWatchPageState();
                } else {
                    clearInterval(state.watchGuardTimer);
                    state.watchGuardTimer = null;
                }
            }, 2500);
        } else if (!onWatch && state.watchGuardTimer) {
            clearInterval(state.watchGuardTimer);
            state.watchGuardTimer = null;
        }
    }

    function syncWatchPageState() {
        var onWatch = isWatchRoute();
        syncWatchGuardTimer(onWatch);
        if (onWatch) {
            var seekInput = document.querySelector('input[aria-label="Seek Video"], input[type="range"]');
            var seekWrapper = (seekInput && seekInput.parentElement) ? seekInput.parentElement : document.querySelector('[role="slider"]');
            if (seekWrapper && seekWrapper.getAttribute("data-cj-seekbar") !== "1") {
                seekWrapper.setAttribute("role", "slider");
                seekWrapper.setAttribute("tabindex", "0");
                seekWrapper.setAttribute("aria-label", "Timeline Seek Bar");
                seekWrapper.setAttribute("data-cj-seekbar", "1");
                state.cacheTime = 0;
            }
            var vid = findActiveVideo();
            if (vid) {
                bindVideoPlaybackEvents(vid);
                pruneSubtitleTracks(vid);
            }
            var playable = isVideoActivelyPlayable(vid);
            reportSurfaceTransparency(playable);
            if (playable) {
                document.documentElement.classList.add(WATCH_CLASS);
                var cur = vid;
                while (cur && cur !== document.documentElement) {
                    if (cur.getAttribute("data-cj-trans") !== "1") {
                        cur.setAttribute("data-cj-trans", "1");
                        cur.style.setProperty("background", "transparent", "important");
                        cur.style.setProperty("background-color", "transparent", "important");
                        cur.style.setProperty("background-image", "none", "important");
                    }
                    cur = cur.parentElement;
                }
                markPlayerControlContainers();
                if (!vid.paused && !state.hudHidden && !state.hudAutoHideTimer) {
                    scheduleHudAutoHide();
                }
            } else {
                resetHudAutoHide();
                clearTransparentSurfaceMarks();
            }
        } else {
            reportSurfaceTransparency(false);
            resetHudAutoHide();
            clearTransparentSurfaceMarks();
            var strayVideos = document.querySelectorAll("video");
            if (strayVideos.length > 0) {
                releaseOverlaySafely(true);
            }
        }
    }

    function injectStyles() {
        var existing = document.querySelector("style[" + STYLE_ATTR + "=\"true\"]");
        if (existing) {
            return;
        }
        var style = document.createElement("style");
        style.setAttribute(STYLE_ATTR, "true");
        style.type = "text/css";
        style.textContent =
            "*, *::before, *::after {" +
            " backdrop-filter: none !important;" +
            " -webkit-backdrop-filter: none !important;" +
            " transition: none !important;" +
            " transition-duration: 0ms !important;" +
            " box-shadow: none !important;" +
            " will-change: auto !important;" +
            " }" +
            "*:not(.animate-spin) {" +
            " animation: none !important;" +
            " }" +
            "html .dot-filling, html[data-flat-ui=\"on\"] .dot-filling {" +
            " animation: none !important;" +
            " transform: scaleX(1) !important;" +
            " }" +
            "[class*=\"blur-\"]:not([class*=\"backdrop-blur\"]), .ambient-bleed {" +
            " display: none !important;" +
            " }" +
            "html .lp-subtitle {" +
            " text-shadow: 0 2px 4px rgba(0, 0, 0, 0.95) !important;" +
            " background-color: rgba(0, 0, 0, 0.72) !important;" +
            " padding: 2px 8px !important;" +
            " border-radius: 4px !important;" +
            " }" +
            "html." + WATCH_CLASS + ", html." + WATCH_CLASS + " > body, html." + WATCH_CLASS + " .app-shell, html." + WATCH_CLASS + " .player-root, html." + WATCH_CLASS + " video," +
            " html." + WATCH_CLASS + " *:fullscreen, html." + WATCH_CLASS + " *:-webkit-full-screen," +
            " html." + WATCH_CLASS + " *::backdrop, html." + WATCH_CLASS + " *::-webkit-backdrop {" +
            " background: transparent !important;" +
            " background-color: transparent !important;" +
            " }" +
            "." + FOCUS_CLASS + " {" +
            " outline: 3px solid rgb(255, 255, 0) !important;" +
            " outline-offset: 2px !important;" +
            " border-radius: 6px !important;" +
            " transform: none !important;" +
            " z-index: 9999 !important;" +
            " }" +
            "[data-cj-seekbar=\"1\"]." + FOCUS_CLASS + " {" +
            " background-color: rgba(255, 255, 0, 0.22) !important;" +
            " border-radius: 8px !important;" +
            " }" +
            "[data-cj-hud-chrome=\"1\"] {" +
            " transition: opacity 180ms ease !important;" +
            " transition-duration: 180ms !important;" +
            " }" +
            "html." + WATCH_CLASS + "." + HUD_HIDDEN_CLASS + " [data-cj-hud-chrome=\"1\"] {" +
            " opacity: 0 !important;" +
            " visibility: hidden !important;" +
            " pointer-events: none !important;" +
            " }" +
            "html." + WATCH_CLASS + "." + HUD_HIDDEN_CLASS + " ." + FOCUS_CLASS + " {" +
            " outline: none !important;" +
            " }";
        var target = document.head || document.documentElement;
        if (target) {
            target.appendChild(style);
        }
    }

    function matchesSelector(el, sel) {
        if (!el || el.nodeType !== 1) {
            return false;
        }
        var fn = el.matches || el.webkitMatchesSelector || el.msMatchesSelector;
        if (!fn) {
            return false;
        }
        try {
            return fn.call(el, sel);
        } catch (e) {
            return false;
        }
    }

    function isNodeDisplayed(el) {
        if (!el || !el.getBoundingClientRect || !document.body.contains(el)) {
            return false;
        }
        var style = window.getComputedStyle(el);
        if (!style || style.display === "none" || style.visibility === "hidden" || parseFloat(style.opacity || "1") <= 0.05) {
            return false;
        }
        var r = el.getBoundingClientRect();
        return r.width >= 40 && r.height >= 40;
    }

    function findFixedOverlayAncestor(node, vw, vh) {
        var cur = node;
        while (cur && cur !== document.body && cur !== document.documentElement) {
            if (cur.nodeType === 1) {
                var style = window.getComputedStyle(cur);
                if (style && style.position === "fixed" && style.display !== "none" && style.visibility !== "hidden") {
                    var rect = cur.getBoundingClientRect();
                    if (rect.width >= vw * 0.85 && rect.height >= vh * 0.85) {
                        if (cur.querySelector(SELECTOR) !== null) {
                            return cur;
                        }
                    }
                }
            }
            cur = cur.parentElement;
        }
        return null;
    }

    function findActiveOverlay(vw, vh) {
        var preview = document.querySelector(".preview");
        if (preview && isNodeDisplayed(preview) && preview.querySelector(SELECTOR) !== null) {
            return preview;
        }

        var topModals = document.querySelectorAll(".add-menu, .login-backdrop");
        for (var tm = topModals.length - 1; tm >= 0; tm--) {
            if (isNodeDisplayed(topModals[tm]) && topModals[tm].querySelector(SELECTOR) !== null) {
                return topModals[tm];
            }
        }

        var specificModals = document.querySelectorAll(".menu-panel");
        for (var sm = specificModals.length - 1; sm >= 0; sm--) {
            if (isNodeDisplayed(specificModals[sm]) && specificModals[sm].querySelector(SELECTOR) !== null) {
                return specificModals[sm];
            }
        }

        var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"], [role="menu"]');
        var bestDialog = null;
        var bestZ = -1;
        for (var i = 0; i < dialogs.length; i++) {
            var d = dialogs[i];
            if (!isNodeDisplayed(d) || d.querySelector(SELECTOR) === null) {
                continue;
            }
            var ds = window.getComputedStyle(d);
            var dz = parseInt(ds.zIndex || "100", 10) || 100;
            if (dz >= bestZ) {
                bestZ = dz;
                bestDialog = d;
            }
        }
        if (bestDialog) {
            return bestDialog;
        }

        var fixedNodes = document.querySelectorAll('div[class*="fixed"]');
        var bestFixed = null;
        var bestFixedZ = 99;
        for (var j = 0; j < fixedNodes.length; j++) {
            var f = fixedNodes[j];
            if (!isNodeDisplayed(f)) {
                continue;
            }
            var fs = window.getComputedStyle(f);
            if (!fs || fs.position !== "fixed" || fs.pointerEvents === "none") {
                continue;
            }
            var fz = parseInt(fs.zIndex || "0", 10) || 0;
            if (fz > bestFixedZ && f.querySelector(SELECTOR) !== null) {
                bestFixedZ = fz;
                bestFixed = f;
            }
        }
        if (bestFixed) {
            return bestFixed;
        }

        var samples = [
            [8, vh - 8],
            [vw - 8, 8],
            [8, 8]
        ];
        for (var k = 0; k < samples.length; k++) {
            var hit = document.elementFromPoint(samples[k][0], samples[k][1]);
            if (hit) {
                var modal = findFixedOverlayAncestor(hit, vw, vh);
                if (modal) {
                    return modal;
                }
            }
        }
        return null;
    }

    function isVisibleElement(el, vw, vh, ignoreCull) {
        if (!el || !el.getBoundingClientRect) {
            return false;
        }
        if (el.disabled || el.getAttribute("aria-disabled") === "true" || el.getAttribute("aria-hidden") === "true") {
            return false;
        }
        if (el.tagName === "INPUT" && (el.type === "range" || el.type === "hidden")) {
            return false;
        }
        if (el.tagName === "BUTTON") {
            var bAria = el.getAttribute("aria-label") || "";
            if (bAria === "Scroll left" || bAria === "Scroll right" || bAria.indexOf("Go to slide ") === 0) {
                return false;
            }
        }
        var tabIdx = el.getAttribute("tabindex");
        if (tabIdx === "-1" && !matchesSelector(el, "a[href], button, input, select, textarea, iframe")) {
            return false;
        }
        var rect = el.getBoundingClientRect();
        var minH = isSeekControlElement(el) ? 6 : 12;
        if (rect.width < 12 || rect.height < minH) {
            return false;
        }
        if (rect.width > vw * 0.92 && rect.height > vh * 0.85) {
            return false;
        }
        if (el.tagName === "DIV") {
            if (rect.width > vw * 0.70 && rect.height > vh * 0.50 && !isSeekControlElement(el)) {
                return false;
            }
            var divTxt = (el.innerText || "").replace(/\s+/g, "").trim();
            if (!el.getAttribute("aria-label") && !el.getAttribute("title") && !divTxt &&
                el.querySelector("img, picture, svg") && !el.querySelector("button, a, input")) {
                return false;
            }
        }
        if (!ignoreCull) {
            if (rect.bottom < -360 || rect.top > vh + 360 || rect.right < -40 || rect.left > vw + 40) {
                return false;
            }
        } else {
            if (rect.bottom < -120 || rect.top > vh + 240) {
                return false;
            }
        }
        var style = window.getComputedStyle(el);
        if (!style || style.display === "none" || style.visibility === "hidden" || style.pointerEvents === "none") {
            return false;
        }
        if (parseFloat(style.opacity || "1") <= 0.05 && !isSeekControlElement(el)) {
            return false;
        }
        if (el.offsetParent === null && style.position !== "fixed" && style.position !== "sticky" && el.tagName !== "BODY") {
            return false;
        }
        return true;
    }

    function deduplicateLinks(rawList, inPreview) {
        if (rawList.length <= 1) {
            return rawList;
        }
        var skip = {};
        var hrefBest = {};
        for (var i = 0; i < rawList.length; i++) {
            var item = rawList[i];
            if (item.el.tagName !== "A") {
                continue;
            }
            var href = item.el.getAttribute("href");
            if (!href) {
                continue;
            }
            var key = href;
            var rawTxt = (item.el.innerText || "").replace(/\s+/g, " ").trim();
            var isAction = /^(resume|play|watch|more info)$/i.test(rawTxt);
            var area = item.width * item.height;
            if (!inPreview && item.el.parentElement) {
                if (!hrefBest[key]) {
                    hrefBest[key] = [];
                }
                hrefBest[key].push({ idx: i, parent: item.el.parentElement, area: area, isAction: isAction, rawTxt: rawTxt });
            } else if (inPreview) {
                if (!hrefBest[key]) {
                    hrefBest[key] = [];
                }
                hrefBest[key].push({ idx: i, isAction: isAction, rawTxt: rawTxt });
            }
        }

        for (var k in hrefBest) {
            var group = hrefBest[k];
            if (group.length < 2) {
                continue;
            }
            if (inPreview) {
                var hasNamed = false;
                for (var g1 = 0; g1 < group.length; g1++) {
                    if (group[g1].rawTxt.length > 0) {
                        hasNamed = true;
                        break;
                    }
                }
                if (hasNamed) {
                    for (var g2 = 0; g2 < group.length; g2++) {
                        if (group[g2].rawTxt.length === 0) {
                            skip[group[g2].idx] = true;
                        }
                    }
                }
            } else {
                for (var a = 0; a < group.length; a++) {
                    var ca = group[a];
                    if (ca.isAction) {
                        continue;
                    }
                    for (var b = 0; b < group.length; b++) {
                        if (a === b) {
                            continue;
                        }
                        var cb = group[b];
                        if (cb.parent === ca.parent && !cb.isAction && cb.area > ca.area * 1.25) {
                            skip[ca.idx] = true;
                            break;
                        }
                    }
                }
            }
        }

        for (var bi = 0; bi < rawList.length; bi++) {
            var outer = rawList[bi].el;
            if (skip[bi] || outer.tagName !== "BUTTON") {
                continue;
            }
            var outerAria = outer.getAttribute("aria-label") || "";
            for (var bj = 0; bj < rawList.length; bj++) {
                if (bi === bj || skip[bj] || rawList[bj].el.tagName !== "BUTTON") {
                    continue;
                }
                var inner = rawList[bj].el;
                if (outer.contains(inner) && outer !== inner) {
                    var innerAria = inner.getAttribute("aria-label") || "";
                    if (outerAria && outerAria === innerAria) {
                        skip[bi] = true;
                        break;
                    }
                }
            }
        }

        var filtered = [];
        for (var f = 0; f < rawList.length; f++) {
            if (!skip[f]) {
                filtered.push(rawList[f]);
            }
        }
        return filtered;
    }

    function scanElements(useCache) {
        var now = Date.now();
        if (useCache && state.spatialMap.length > 0 && now - state.cacheTime < 100) {
            return state.spatialMap;
        }
        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;
        var activeOverlay = findActiveOverlay(vw, vh);
        var scope = activeOverlay || document;
        var inPreview = !!(activeOverlay && activeOverlay.classList && activeOverlay.classList.contains("preview"));
        var nodes = scope.querySelectorAll(SELECTOR);
        var map = [];

        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (!isVisibleElement(el, vw, vh, !!activeOverlay)) {
                continue;
            }
            var rect = el.getBoundingClientRect();
            map.push({
                el: el,
                left: rect.left,
                top: rect.top,
                right: rect.right,
                bottom: rect.bottom,
                width: rect.width,
                height: rect.height,
                cx: rect.left + rect.width * 0.5,
                cy: rect.top + rect.height * 0.5
            });
        }
        map = deduplicateLinks(map, inPreview);
        state.spatialMap = map;
        state.cacheTime = now;
        return map;
    }

    function clearFocusClass() {
        var oldFocused = document.querySelectorAll("." + FOCUS_CLASS);
        for (var i = 0; i < oldFocused.length; i++) {
            oldFocused[i].classList.remove(FOCUS_CLASS);
        }
    }
"""

    private const val FIRESTICK_NAV_TAIL = """
    function applyFocus(el) {
        if (!el || !document.body.contains(el)) {
            return;
        }
        injectStyles();
        clearFocusClass();
        state.currentElement = el;
        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;
        if (!isWatchRoute() && !findActiveOverlay(vw, vh)) {
            state.lastBrowseElement = el;
        }
        el.classList.add(FOCUS_CLASS);
        try {
            el.focus({ preventScroll: true });
        } catch (e) {
            try {
                el.focus();
            } catch (e2) {}
        }
        var overlayNow = findActiveOverlay(vw, vh);
        if (overlayNow) {
            var ovR = overlayNow.getBoundingClientRect();
            var elR = el.getBoundingClientRect();
            if (elR.top < ovR.top + 8 || elR.bottom > ovR.bottom - 8) {
                try {
                    el.scrollIntoView({ behavior: "auto", block: "nearest", inline: "nearest" });
                } catch (e5) {}
            }
        } else if (!isWatchRoute() && !isInHeaderBar(el)) {
            var r = el.getBoundingClientRect();
            var offEdges = r.top < 84 || r.bottom > vh - 48 || r.left < 24 || r.right > vw - 24;
            if (offEdges) {
                try {
                    el.scrollIntoView({
                        behavior: "auto",
                        block: "center",
                        inline: "nearest"
                    });
                } catch (e3) {
                    try {
                        var targetY = (window.pageYOffset || 0) + r.top - (vh * 0.42);
                        window.scrollTo({ top: Math.max(0, targetY), behavior: "auto" });
                    } catch (e4) {}
                }
            }
        }
        state.cacheTime = 0;
    }

    function findEntryForElement(el) {
        for (var i = 0; i < state.spatialMap.length; i++) {
            if (state.spatialMap[i].el === el) {
                return state.spatialMap[i];
            }
        }
        return null;
    }

    function pickInitialEntry(activeOverlay) {
        if (state.spatialMap.length === 0) {
            return null;
        }
        if (activeOverlay) {
            for (var p = 0; p < state.spatialMap.length; p++) {
                var pTxt = (state.spatialMap[p].el.innerText || "").replace(/\s+/g, " ").trim();
                if (/^(resume|play|watch now|watch)$/i.test(pTxt)) {
                    return state.spatialMap[p];
                }
            }
            for (var t = 0; t < state.spatialMap.length; t++) {
                if (isTextInputControl(state.spatialMap[t].el)) {
                    return state.spatialMap[t];
                }
            }
            for (var q = 0; q < state.spatialMap.length; q++) {
                var qEl = state.spatialMap[q].el;
                var qTxt = (qEl.innerText || "").replace(/\s+/g, " ").trim();
                var qAria = (qEl.getAttribute("aria-label") || "").toLowerCase();
                if (qAria.indexOf("close") === -1 && (qTxt.length > 0 || qAria.indexOf("back") === -1)) {
                    return state.spatialMap[q];
                }
            }
            return state.spatialMap[0];
        }
        if (isWatchRoute()) {
            var seekBar = findNativeSeekControl();
            if (seekBar) {
                for (var s = 0; s < state.spatialMap.length; s++) {
                    if (state.spatialMap[s].el === seekBar) {
                        return state.spatialMap[s];
                    }
                }
            }
        }
        var vh = window.innerHeight || 720;
        var vw = window.innerWidth || 1280;
        var best = state.spatialMap[0];
        var bestScore = 1e12;
        for (var i = 0; i < state.spatialMap.length; i++) {
            var item = state.spatialMap[i];
            if (isInHeaderBar(item.el)) {
                continue;
            }
            var inView = item.bottom > 80 && item.top < vh && item.right > 0 && item.left < vw;
            var score = (inView ? 0 : 50000) + Math.max(0, item.top) * 2 + Math.max(0, item.left);
            if (score < bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    function ensureFocus() {
        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;
        var activeOverlay = findActiveOverlay(vw, vh);
        if (isWatchRoute() && state.hudHidden && !activeOverlay) {
            return false;
        }
        scanElements(false);

        var activeEl = document.activeElement;
        if (activeEl && isTextInputControl(activeEl) && document.body.contains(activeEl)) {
            var activeInOverlay = !activeOverlay || activeOverlay.contains(activeEl);
            if (activeInOverlay && findEntryForElement(activeEl) && isVisibleElement(activeEl, vw, vh, true)) {
                if (state.currentElement !== activeEl || !activeEl.classList.contains(FOCUS_CLASS)) {
                    applyFocus(activeEl);
                }
                return true;
            }
        }

        if (state.currentElement && document.body.contains(state.currentElement)) {
            var inOverlay = !activeOverlay || activeOverlay.contains(state.currentElement);
            var inFilteredMap = !!findEntryForElement(state.currentElement);
            if (inOverlay && inFilteredMap && isVisibleElement(state.currentElement, vw, vh, true)) {
                if (!state.currentElement.classList.contains(FOCUS_CLASS)) {
                    applyFocus(state.currentElement);
                }
                return true;
            }
        }

        if (!activeOverlay && state.lastBrowseElement && document.body.contains(state.lastBrowseElement) &&
            findEntryForElement(state.lastBrowseElement) && isVisibleElement(state.lastBrowseElement, vw, vh, false)) {
            applyFocus(state.lastBrowseElement);
            return true;
        }

        var initial = pickInitialEntry(activeOverlay);
        if (initial) {
            applyFocus(initial.el);
            return true;
        }
        return false;
    }

    function findHorizontalScroller(el) {
        var cur = el ? el.parentElement : null;
        while (cur && cur !== document.body && cur !== document.documentElement) {
            var st = window.getComputedStyle(cur);
            if (st && (st.overflowX === "auto" || st.overflowX === "scroll") && cur.scrollWidth > cur.clientWidth + 8) {
                return cur;
            }
            cur = cur.parentElement;
        }
        return null;
    }

    function isSameRowCandidate(curr, cand) {
        var overlapY = Math.max(0, Math.min(curr.bottom, cand.bottom) - Math.max(curr.top, cand.top));
        if (overlapY > Math.min(curr.height, cand.height) * 0.2) {
            return true;
        }
        return Math.abs(cand.cy - curr.cy) <= Math.max(curr.height, cand.height) * 0.65;
    }

    function pickBestHorizontalCandidate(curr, direction, allowCrossRow) {
        var best = null;
        var bestScore = 1e12;
        for (var i = 0; i < state.spatialMap.length; i++) {
            var cand = state.spatialMap[i];
            if (cand.el === curr.el) {
                continue;
            }
            var primary = direction === "right" ? (cand.cx - curr.cx) : (curr.cx - cand.cx);
            if (primary <= 2) {
                continue;
            }
            var sameRow = isSameRowCandidate(curr, cand);
            if (!sameRow && !allowCrossRow) {
                continue;
            }
            var perp = Math.abs(cand.cy - curr.cy);
            var score = primary + perp * (sameRow ? 1.5 : 4.0) + (sameRow ? 0 : 10000);
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return best;
    }

    function pickBestVerticalCandidate(curr, direction, excludeHeader, onlyHeader) {
        var best = null;
        var bestScore = 1e12;
        for (var i = 0; i < state.spatialMap.length; i++) {
            var cand = state.spatialMap[i];
            if (cand.el === curr.el) {
                continue;
            }
            var inHdr = isInHeaderBar(cand.el);
            if (excludeHeader && inHdr) {
                continue;
            }
            if (onlyHeader && !inHdr) {
                continue;
            }
            if (isSameRowCandidate(curr, cand)) {
                continue;
            }
            var primary = direction === "down" ? (cand.cy - curr.cy) : (curr.cy - cand.cy);
            if (primary <= 4) {
                continue;
            }
            var overlapX = Math.max(0, Math.min(curr.right, cand.right) - Math.max(curr.left, cand.left));
            var perp = Math.abs(cand.cx - curr.cx);
            var score = primary * 3.0 + perp * 0.8 - (overlapX > 0 ? 120 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return best;
    }

    function measureCurr(el) {
        var found = findEntryForElement(el);
        if (found) {
            return found;
        }
        var r = el.getBoundingClientRect();
        return {
            el: el,
            left: r.left,
            top: r.top,
            right: r.right,
            bottom: r.bottom,
            width: r.width,
            height: r.height,
            cx: r.left + r.width * 0.5,
            cy: r.top + r.height * 0.5
        };
    }

    function move(direction) {
        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;

        if (isWatchRoute()) {
            var activePlayerOverlay = findActiveOverlay(vw, vh);
            if (!activePlayerOverlay) {
                var hudWasVisible = isHudVisible();
                showPlayerControls(true);
                if (!hudWasVisible) {
                    if (direction === "left" || direction === "right") {
                        dispatchNativeDirectionalSeek(direction === "right");
                    }
                    if (state.hudWakeTimer) {
                        clearTimeout(state.hudWakeTimer);
                    }
                    state.hudWakeTimer = setTimeout(function() {
                        syncWatchPageState();
                        scanElements(false);
                        var target = (state.lastHudElement && document.body.contains(state.lastHudElement) && isVisibleElement(state.lastHudElement, vw, vh, true))
                            ? state.lastHudElement
                            : (findNativeSeekControl() || findNativePlayPauseButton());
                        if (target) {
                            applyFocus(target);
                        } else if (!ensureFocus()) {
                            setTimeout(function() {
                                syncWatchPageState();
                                scanElements(false);
                                var retryTarget = findNativeSeekControl() || findNativePlayPauseButton();
                                if (retryTarget) {
                                    applyFocus(retryTarget);
                                } else {
                                    ensureFocus();
                                }
                            }, 120);
                        }
                    }, 50);
                    return;
                }
                if (state.currentElement && isSeekControlElement(state.currentElement)) {
                    if (direction === "left" || direction === "right") {
                        dispatchNativeDirectionalSeek(direction === "right");
                        return;
                    }
                }
            }
        }

        scanElements(false);
        if (state.spatialMap.length === 0) {
            return;
        }
        var activeOverlayNow = findActiveOverlay(vw, vh);
        if (!state.currentElement || !document.body.contains(state.currentElement) || !findEntryForElement(state.currentElement)) {
            var first = pickInitialEntry(activeOverlayNow);
            if (first) {
                applyFocus(first.el);
            }
            return;
        }

        var curr = measureCurr(state.currentElement);

        if (direction === "left" || direction === "right") {
            var candHoriz = pickBestHorizontalCandidate(curr, direction, false);
            if (candHoriz) {
                applyFocus(candHoriz.el);
                return;
            }
            var scroller = findHorizontalScroller(curr.el);
            if (scroller) {
                var prevScrollLeft = scroller.scrollLeft;
                var deltaX = direction === "right" ? 480 : -480;
                var maxScroll = Math.max(0, scroller.scrollWidth - scroller.clientWidth);
                scroller.scrollLeft = Math.max(0, Math.min(maxScroll, prevScrollLeft + deltaX));
                if (Math.abs(scroller.scrollLeft - prevScrollLeft) > 2) {
                    scanElements(false);
                    curr = measureCurr(curr.el);
                    var nextInCarousel = null;
                    var bestInCarouselScore = 1e12;
                    for (var hi = 0; hi < state.spatialMap.length; hi++) {
                        var item = state.spatialMap[hi];
                        if (item.el === curr.el || !scroller.contains(item.el)) {
                            continue;
                        }
                        var hp = direction === "right" ? (item.cx - curr.cx) : (curr.cx - item.cx);
                        if (hp <= 2 || !isSameRowCandidate(curr, item)) {
                            continue;
                        }
                        if (hp < bestInCarouselScore) {
                            bestInCarouselScore = hp;
                            nextInCarousel = item;
                        }
                    }
                    if (nextInCarousel) {
                        applyFocus(nextInCarousel.el);
                    }
                    return;
                }
            }
            if (activeOverlayNow) {
                var fallbackHoriz = pickBestHorizontalCandidate(curr, direction, true);
                if (fallbackHoriz) {
                    applyFocus(fallbackHoriz.el);
                }
            }
            return;
        }

        if (direction === "down") {
            var currInHdr = isInHeaderBar(curr.el);
            var downCand = pickBestVerticalCandidate(curr, "down", currInHdr, false);
            if (downCand) {
                applyFocus(downCand.el);
                return;
            }
            if (activeOverlayNow) {
                var scrollersDown = activeOverlayNow.querySelectorAll("div, ul, section");
                for (var sdi = 0; sdi < scrollersDown.length; sdi++) {
                    if (scrollersDown[sdi].scrollHeight > scrollersDown[sdi].clientHeight + 8) {
                        scrollersDown[sdi].scrollTop += 200;
                        setTimeout(function() { scanElements(false); }, 60);
                        return;
                    }
                }
                if (activeOverlayNow.scrollHeight > activeOverlayNow.clientHeight + 8) {
                    activeOverlayNow.scrollTop += 200;
                    setTimeout(function() { scanElements(false); }, 60);
                }
            } else if (!isWatchRoute()) {
                window.scrollBy(0, 360);
                scanElements(false);
                curr = measureCurr(curr.el);
                var revealedDown = pickBestVerticalCandidate(curr, "down", true, false);
                if (revealedDown) {
                    applyFocus(revealedDown.el);
                }
            }
            return;
        }

        if (direction === "up") {
            if (activeOverlayNow || isWatchRoute()) {
                var upOverlayCand = pickBestVerticalCandidate(curr, "up", false, false);
                if (upOverlayCand) {
                    applyFocus(upOverlayCand.el);
                    return;
                }
                if (activeOverlayNow) {
                    var scrollersUp = activeOverlayNow.querySelectorAll("div, ul, section");
                    for (var sui = 0; sui < scrollersUp.length; sui++) {
                        if (scrollersUp[sui].scrollHeight > scrollersUp[sui].clientHeight + 8) {
                            scrollersUp[sui].scrollTop -= 200;
                            setTimeout(function() { scanElements(false); }, 60);
                            return;
                        }
                    }
                    if (activeOverlayNow.scrollHeight > activeOverlayNow.clientHeight + 8) {
                        activeOverlayNow.scrollTop -= 200;
                        setTimeout(function() { scanElements(false); }, 60);
                    }
                }
                return;
            }

            if (isInHeaderBar(curr.el)) {
                var upInHdr = pickBestVerticalCandidate(curr, "up", false, true);
                if (upInHdr) {
                    applyFocus(upInHdr.el);
                }
                return;
            }

            var scrollYNow = window.pageYOffset || document.documentElement.scrollTop || 0;
            var upContentCand = pickBestVerticalCandidate(curr, "up", true, false);
            if (upContentCand) {
                applyFocus(upContentCand.el);
                return;
            }
            if (scrollYNow > 80) {
                window.scrollBy(0, -360);
                scanElements(false);
                curr = measureCurr(curr.el);
                var revealedUp = pickBestVerticalCandidate(curr, "up", true, false);
                if (revealedUp) {
                    applyFocus(revealedUp.el);
                    return;
                }
                if ((window.pageYOffset || document.documentElement.scrollTop || 0) > 80) {
                    return;
                }
            }
            var headerCand = pickBestVerticalCandidate(curr, "up", false, true);
            if (headerCand) {
                applyFocus(headerCand.el);
            }
        }
    }

    function dispatchPointerEvent(el, type, cx, cy, shouldBubble) {
        try {
            var evt = new MouseEvent(type, {
                bubbles: shouldBubble !== false,
                cancelable: true,
                view: window,
                clientX: cx,
                clientY: cy
            });
            el.dispatchEvent(evt);
        } catch (e) {}
    }

    function prepareTapCoords(el) {
        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;
        var rect = el.getBoundingClientRect();
        if (rect.top < 0 || rect.bottom > vh || rect.left < 0 || rect.right > vw) {
            try {
                el.scrollIntoView({ behavior: "auto", block: "center", inline: "center" });
            } catch (e) {
                try {
                    el.scrollIntoView(false);
                } catch (e2) {}
            }
            rect = el.getBoundingClientRect();
        }
        var cx = Math.max(1, Math.min(vw - 1, rect.left + rect.width * 0.5));
        var cy = Math.max(1, Math.min(vh - 1, rect.top + rect.height * 0.5));
        if (isTextInputControl(el)) {
            try {
                el.focus();
            } catch (e3) {}
        }
        return (cx / vw).toFixed(5) + "," + (cy / vh).toFixed(5);
    }

    function submitParentFormAction(inputEl) {
        var container = inputEl.closest ? inputEl.closest(".add-menu, .login-backdrop, form") : null;
        if (!container) {
            var cur = inputEl.parentElement;
            while (cur && cur !== document.body) {
                if (cur.querySelectorAll && cur.querySelectorAll("button").length >= 1) {
                    var btnsCheck = cur.querySelectorAll("button");
                    for (var bc = 0; bc < btnsCheck.length; bc++) {
                        var bTxt = (btnsCheck[bc].innerText || "").replace(/\s+/g, " ").trim().toLowerCase();
                        var bTitle = (btnsCheck[bc].getAttribute("title") || "").toLowerCase();
                        if (bTitle === "create & add" || bTxt === "create" || bTxt === "sign in" || bTxt === "submit") {
                            container = cur;
                            break;
                        }
                    }
                    if (container) {
                        break;
                    }
                }
                cur = cur.parentElement;
            }
        }
        if (!container) {
            return false;
        }
        var allBtns = container.querySelectorAll("button");
        var addMenuSubmit = container.querySelector('button[title="Create & Add"]');
        if (addMenuSubmit && !addMenuSubmit.disabled) {
            addMenuSubmit.click();
            return true;
        }
        var matched = [];
        for (var i = 0; i < allBtns.length; i++) {
            var b = allBtns[i];
            if (b.disabled) {
                continue;
            }
            var txt = (b.innerText || "").replace(/\s+/g, " ").trim().toLowerCase();
            if (txt === "create" || txt === "sign in" || txt === "submit") {
                matched.push(b);
            }
        }
        if (matched.length > 0) {
            matched[matched.length - 1].click();
            return true;
        }
        return false;
    }

    function clickNativePlayPause() {
        var btn = findNativePlayPauseButton();
        if (btn && typeof btn.click === "function") {
            btn.click();
            return true;
        }
        return toggleVideo();
    }

    function activateOrTap(isEnterKey) {
        if (isWatchRoute()) {
            var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
            var vh = window.innerHeight || document.documentElement.clientHeight || 720;
            var hudWasVisible = isHudVisible();
            var watchOverlay = findActiveOverlay(vw, vh);
            if (!hudWasVisible && !watchOverlay) {
                showPlayerControls(true);
                clickNativePlayPause();
                if (state.hudWakeTimer) {
                    clearTimeout(state.hudWakeTimer);
                }
                state.hudWakeTimer = setTimeout(function() {
                    syncWatchPageState();
                    scanElements(false);
                    var target = (state.lastHudElement && document.body.contains(state.lastHudElement) && isVisibleElement(state.lastHudElement, vw, vh, true))
                        ? state.lastHudElement
                        : (findNativeSeekControl() || findNativePlayPauseButton());
                    if (target) {
                        applyFocus(target);
                    } else {
                        ensureFocus();
                    }
                }, 50);
                return "clicked";
            }
            if (!watchOverlay) {
                showPlayerControls(true);
            }
        }

        if (!state.currentElement || !document.body.contains(state.currentElement)) {
            if (!ensureFocus()) {
                return "none";
            }
        }
        var el = state.currentElement;
        if (!el) {
            return "none";
        }

        if (isSeekControlElement(el)) {
            showPlayerControls(true);
            clickNativePlayPause();
            return "clicked";
        }

        if (isFullscreenButton(el)) {
            if (isInFullscreen()) {
                state.isNativeFullscreen = false;
                try {
                    if (document.exitFullscreen) {
                        document.exitFullscreen();
                    } else if (document.webkitExitFullscreen) {
                        document.webkitExitFullscreen();
                    }
                } catch (eFs) {}
                try {
                    if (window.__cinejoyNative && window.__cinejoyNative.exitFullscreen) {
                        window.__cinejoyNative.exitFullscreen();
                    }
                } catch (eFs2) {}
                showPlayerControls(true);
                return "clicked";
            }
            showPlayerControls(true);
            return "tap-fs:" + prepareTapCoords(el);
        }

        if (isTextInputControl(el)) {
            if (isEnterKey && document.activeElement === el && (el.value || "").trim().length > 0) {
                try {
                    el.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true }));
                } catch (eKey) {}
                submitParentFormAction(el);
                try {
                    el.blur();
                } catch (eBlur) {}
                setTimeout(function() {
                    scanElements(false);
                    ensureFocus();
                }, 140);
                return "hide-ime";
            }
            return "input-tap:" + prepareTapCoords(el);
        }

        if (el.tagName === "IFRAME") {
            return "tap:" + prepareTapCoords(el);
        }

        var hasParentButton = !!(el.parentElement && el.parentElement.closest && el.parentElement.closest("button"));
        var rect = el.getBoundingClientRect();
        var cx = rect.left + rect.width * 0.5;
        var cy = rect.top + rect.height * 0.5;

        dispatchPointerEvent(el, "pointerdown", cx, cy, !hasParentButton);
        dispatchPointerEvent(el, "mousedown", cx, cy, !hasParentButton);
        dispatchPointerEvent(el, "pointerup", cx, cy, !hasParentButton);
        dispatchPointerEvent(el, "mouseup", cx, cy, !hasParentButton);

        if (typeof el.click === "function") {
            if (hasParentButton) {
                var stopNestedClick = function(ev) {
                    ev.stopPropagation();
                };
                el.addEventListener("click", stopNestedClick, false);
                try {
                    el.click();
                } finally {
                    el.removeEventListener("click", stopNestedClick, false);
                }
            } else {
                el.click();
            }
        } else {
            dispatchPointerEvent(el, "click", cx, cy, !hasParentButton);
        }

        setTimeout(function() {
            syncWatchPageState();
            scanElements(false);
            ensureFocus();
            if (isWatchRoute()) {
                scheduleHudAutoHide();
            }
        }, 140);
        return "clicked";
    }

    function dismissActiveOverlay() {
        var activeEl = document.activeElement;
        if (isTextInputControl(activeEl)) {
            try {
                activeEl.blur();
            } catch (e) {}
            return "blur-ime";
        }

        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;

        var preview = document.querySelector(".preview");
        if (preview && isNodeDisplayed(preview)) {
            var closePreviewBtn = preview.querySelector('button[aria-label="Close preview"], button[aria-label*="Close"]');
            if (closePreviewBtn) {
                closePreviewBtn.click();
            } else {
                var scrim = document.querySelector('div[class*="fixed"][class*="inset-0"]');
                if (scrim) {
                    scrim.click();
                }
            }
            setTimeout(function() {
                state.cacheTime = 0;
                ensureFocus();
                if (isWatchRoute()) {
                    scheduleHudAutoHide();
                }
            }, 90);
            return "dismissed";
        }

        var overlay = findActiveOverlay(vw, vh);
        if (overlay) {
            var closeBtn = overlay.querySelector('button[aria-label*="Close"], button[aria-label*="Back"]');
            if (!closeBtn) {
                var allOverlayBtns = overlay.querySelectorAll("button, [role=\"button\"]");
                for (var bi = 0; bi < allOverlayBtns.length; bi++) {
                    var bTxt = (allOverlayBtns[bi].innerText || "").replace(/\s+/g, " ").trim().toLowerCase();
                    if (bTxt === "close" || bTxt === "done" || bTxt === "cancel" || bTxt === "back" || bTxt === "dismiss") {
                        closeBtn = allOverlayBtns[bi];
                        break;
                    }
                }
            }
            if (!closeBtn && overlay.parentElement) {
                var parentBtns = overlay.parentElement.querySelectorAll("button, [role=\"button\"]");
                for (var pi = 0; pi < parentBtns.length; pi++) {
                    var pTxt = (parentBtns[pi].innerText || "").replace(/\s+/g, " ").trim().toLowerCase();
                    var pAria = (parentBtns[pi].getAttribute("aria-label") || "").toLowerCase();
                    if (pTxt === "close" || pAria.indexOf("close") !== -1) {
                        closeBtn = parentBtns[pi];
                        break;
                    }
                }
            }
            if (closeBtn) {
                closeBtn.click();
            } else {
                try {
                    var escEvt = new KeyboardEvent("keydown", { key: "Escape", code: "Escape", keyCode: 27, which: 27, bubbles: true });
                    window.dispatchEvent(escEvt);
                    document.dispatchEvent(escEvt);
                } catch (e) {}
            }
            setTimeout(function() {
                state.cacheTime = 0;
                ensureFocus();
                if (isWatchRoute()) {
                    scheduleHudAutoHide();
                }
            }, 90);
            return "dismissed";
        }

        return "none";
    }

    function findActiveVideo() {
        var videos = document.querySelectorAll("video");
        if (videos.length > 0) {
            return videos[0];
        }
        var frames = document.querySelectorAll("iframe");
        for (var i = 0; i < frames.length; i++) {
            try {
                var doc = frames[i].contentDocument || frames[i].contentWindow.document;
                if (doc) {
                    var innerVideos = doc.querySelectorAll("video");
                    if (innerVideos.length > 0) {
                        return innerVideos[0];
                    }
                }
            } catch (e) {}
        }
        return null;
    }

    function toggleVideo() {
        var vid = findActiveVideo();
        if (!vid) {
            return false;
        }
        try {
            if (vid.paused) {
                vid.play();
                showPlayerControls(true);
            } else {
                vid.pause();
                showPlayerControls(false);
            }
            return true;
        } catch (e) {
            return false;
        }
    }

    function seekVideo(deltaSeconds) {
        if (!isWatchRoute()) {
            return false;
        }
        showPlayerControls(true);
        dispatchNativeDirectionalSeek(deltaSeconds >= 0);
        return true;
    }

    function installObserver() {
        if (!state.focusListenerInstalled) {
            state.focusListenerInstalled = true;
            document.addEventListener("focusin", function(ev) {
                var target = ev && ev.target;
                if (!target || target === state.currentElement || !isTextInputControl(target) || !document.body || !document.body.contains(target)) {
                    return;
                }
                var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
                var vh = window.innerHeight || document.documentElement.clientHeight || 720;
                var overlay = findActiveOverlay(vw, vh);
                if (overlay && !overlay.contains(target)) {
                    return;
                }
                scanElements(false);
                if (findEntryForElement(target) && isVisibleElement(target, vw, vh, true)) {
                    applyFocus(target);
                }
            }, true);
        }
        if (state.observer || !document.body || typeof MutationObserver === "undefined") {
            return;
        }
        state.observer = new MutationObserver(function() {
            if (state.refreshTimer) {
                clearTimeout(state.refreshTimer);
            }
            state.refreshTimer = setTimeout(function() {
                state.cacheTime = 0;
                syncWatchPageState();
                var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
                var vh = window.innerHeight || document.documentElement.clientHeight || 720;
                var overlay = findActiveOverlay(vw, vh);
                if (isWatchRoute() && state.hudHidden && !overlay) {
                    return;
                }
                var needRefocus = !state.currentElement ||
                    !document.body.contains(state.currentElement) ||
                    (overlay && !overlay.contains(state.currentElement));
                if (needRefocus) {
                    ensureFocus();
                }
                var navReadyNodes = document.querySelectorAll('a[href*="/movie/"], a[href*="/tv/"], a[href*="/watch/"], button');
                if (navReadyNodes.length >= 4 && state.spatialMap.length >= 6) {
                    try {
                        if (window.__cinejoyNative && window.__cinejoyNative.onWebContentRendered) {
                            window.__cinejoyNative.onWebContentRendered();
                        }
                    } catch (e) {}
                }
            }, 250);
        });
        state.observer.observe(document.body, { childList: true, subtree: true });
    }

    function refresh() {
        applyTvPresets();
        injectStyles();
        syncWatchPageState();
        ensureFocus();
        installObserver();
        var renderedNodes = document.querySelectorAll('a[href*="/movie/"], a[href*="/tv/"], a[href*="/watch/"], button');
        if (renderedNodes.length >= 4 && state.spatialMap.length >= 6) {
            try {
                if (window.__cinejoyNative && window.__cinejoyNative.onWebContentRendered) {
                    window.__cinejoyNative.onWebContentRendered();
                }
            } catch (e) {}
        }
    }

    window.__cinejoySpatialNav = {
        initialized: true,
        refresh: refresh,
        move: move,
        activateOrTap: activateOrTap,
        dismissActiveOverlay: dismissActiveOverlay,
        toggleVideo: toggleVideo,
        seekVideo: seekVideo,
        onFullscreenChanged: onFullscreenChanged,
        getFullscreenTapCoords: function() {
            var btns = document.querySelectorAll("button");
            for (var i = 0; i < btns.length; i++) {
                if (isFullscreenButton(btns[i])) {
                    var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
                    var vh = window.innerHeight || document.documentElement.clientHeight || 720;
                    var r = btns[i].getBoundingClientRect();
                    if (r.width >= 12 && r.height >= 12) {
                        var cx = Math.max(1, Math.min(vw - 1, r.left + r.width * 0.5));
                        var cy = Math.max(1, Math.min(vh - 1, r.top + r.height * 0.5));
                        return (cx / vw).toFixed(5) + "," + (cy / vh).toFixed(5);
                    }
                }
            }
            return "";
        },
        releaseOverlay: releaseOverlaySafely,
        getCount: function() { return state.spatialMap.length; }
    };

    refresh();
})();
"""

    private const val MOBILE_PLAYBACK_SCRIPT = """
(function() {
    if (window.__cinejoyMobileHud && window.__cinejoyMobileHud.initialized) {
        window.__cinejoyMobileHud.refresh();
        return;
    }

    var WATCH_CLASS = "cinejoy-watch-active";
    var HUD_HIDDEN_CLASS = "cinejoy-hud-hidden";
    var STYLE_ATTR = "data-cinejoy-mobile-hud";
    var HUD_AUTO_HIDE_MS = 3000;
    var hudTimer = null;
    var refreshTimer = null;
    var watchGuardTimer = null;
    var boundVid = null;
    var lastReportedWatch = null;

    function isWatchRoute() {
        return location.pathname.indexOf("/watch/") !== -1;
    }

    function isVideoActivelyPlayable(vid) {
        return !!(vid && !vid.error && (vid.videoWidth > 0 || vid.readyState >= 2));
    }

    function reportTransparency(onWatch) {
        if (lastReportedWatch === onWatch) {
            return;
        }
        lastReportedWatch = onWatch;
        try {
            if (window.__cinejoyNative && window.__cinejoyNative.setWatchSurfaceTransparent) {
                window.__cinejoyNative.setWatchSurfaceTransparent(!!onWatch);
            }
        } catch (e) {}
    }

    function injectMobileStyles() {
        if (document.querySelector("style[" + STYLE_ATTR + "=\"true\"]")) {
            return;
        }
        var style = document.createElement("style");
        style.setAttribute(STYLE_ATTR, "true");
        style.type = "text/css";
        style.textContent =
            "html." + WATCH_CLASS + ", html." + WATCH_CLASS + " > body, html." + WATCH_CLASS + " .app-shell, html." + WATCH_CLASS + " .player-root, html." + WATCH_CLASS + " video {" +
            " background: transparent !important;" +
            " background-color: transparent !important;" +
            " }" +
            "[data-cj-hud-chrome=\"1\"] {" +
            " transition: opacity 180ms ease !important;" +
            " }" +
            "html." + WATCH_CLASS + "." + HUD_HIDDEN_CLASS + " [data-cj-hud-chrome=\"1\"] {" +
            " opacity: 0 !important;" +
            " visibility: hidden !important;" +
            " pointer-events: none !important;" +
            " }";
        var target = document.head || document.documentElement;
        if (target) {
            target.appendChild(style);
        }
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
        if (!isVideoActivelyPlayable(vid) || vid.paused) {
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
        if (!isVideoActivelyPlayable(vid) || vid.paused) {
            return;
        }
        hudTimer = setTimeout(hideHud, HUD_AUTO_HIDE_MS);
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
        injectMobileStyles();
        var onWatch = isWatchRoute();
        syncWatchGuardTimer(onWatch);
        if (onWatch) {
            var vid = document.querySelector("video");
            if (vid && boundVid !== vid) {
                boundVid = vid;
                vid.addEventListener("loadeddata", refresh);
                vid.addEventListener("error", refresh);
                vid.addEventListener("play", function() {
                    refresh();
                    scheduleHide();
                });
                vid.addEventListener("playing", function() {
                    refresh();
                    scheduleHide();
                });
                vid.addEventListener("pause", function() {
                    clearTimer();
                    document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
                });
            }
            var playable = isVideoActivelyPlayable(vid);
            reportTransparency(playable);
            if (playable) {
                document.documentElement.classList.add(WATCH_CLASS);
                markHudChrome();
                if (!vid.paused && !hudTimer && !document.documentElement.classList.contains(HUD_HIDDEN_CLASS)) {
                    scheduleHide();
                }
            } else {
                clearTimer();
                document.documentElement.classList.remove(WATCH_CLASS);
                document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
            }
        } else {
            reportTransparency(false);
            clearTimer();
            document.documentElement.classList.remove(WATCH_CLASS);
            document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
        }
        var readyNodes = document.querySelectorAll('a[href*="/movie/"], a[href*="/tv/"], a[href*="/watch/"], button');
        if (readyNodes.length >= 4) {
            try {
                if (window.__cinejoyNative && window.__cinejoyNative.onWebContentRendered) {
                    window.__cinejoyNative.onWebContentRendered();
                }
            } catch (e) {}
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

    window.__cinejoyMobileHud = {
        initialized: true,
        refresh: refresh
    };

    refresh();
})();
"""

    private val firestickScript: String by lazy {
        FIRESTICK_NAV_HEAD + "\n" + FIRESTICK_NAV_TAIL
    }

    private fun safeEvaluateJs(
        webView: WebView,
        script: String,
        callback: ((String?) -> Unit)? = null
    ) {
        if (!webView.isAttachedToWindow) {
            callback?.invoke(null)
            return
        }
        try {
            webView.evaluateJavascript(script, callback)
        } catch (_: Throwable) {
            callback?.invoke(null)
        }
    }

    private fun runOnUiImmediate(webView: WebView, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            webView.post(action)
        }
    }

    fun applyNetworkAndPresets(webView: WebView) {
        runOnUiImmediate(webView) {
            safeEvaluateJs(webView, NETWORK_RESILIENCE_AND_PRESET_SCRIPT)
        }
    }

    fun injectIntoWebView(webView: WebView) {
        runOnUiImmediate(webView) {
            safeEvaluateJs(webView, NETWORK_RESILIENCE_AND_PRESET_SCRIPT)
            val platformScript = if (BuildConfig.IS_TV_PLATFORM) firestickScript else MOBILE_PLAYBACK_SCRIPT
            safeEvaluateJs(webView, platformScript)
        }
    }

    fun refreshMap(webView: WebView) {
        runOnUiImmediate(webView) {
            if (BuildConfig.IS_TV_PLATFORM) {
                safeEvaluateJs(
                    webView,
                    "if (window.__cinejoySpatialNav) { window.__cinejoySpatialNav.refresh(); }"
                )
            } else {
                safeEvaluateJs(
                    webView,
                    "if (window.__cinejoyMobileHud) { window.__cinejoyMobileHud.refresh(); }"
                )
            }
        }
    }

    fun notifyRouteTransition(webView: WebView) {
        runOnUiImmediate(webView) {
            if (BuildConfig.IS_TV_PLATFORM) {
                safeEvaluateJs(
                    webView,
                    "if (window.__cinejoySpatialNav) { window.__cinejoySpatialNav.releaseOverlay(false); window.__cinejoySpatialNav.refresh(); }"
                )
            } else {
                safeEvaluateJs(
                    webView,
                    "if (window.__cinejoyMobileHud) { window.__cinejoyMobileHud.refresh(); }"
                )
            }
        }
    }

    fun releaseMediaForBackground(webView: WebView) {
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "if (window.__cinejoyReleaseMedia) { window.__cinejoyReleaseMedia(true); }"
            )
        }
    }

    fun resumeMediaAfterForeground(webView: WebView) {
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "if (window.__cinejoyResumeMedia) { window.__cinejoyResumeMedia(); }"
            )
        }
    }

    fun handleBackKey(
        webView: WebView,
        onBlurIme: () -> Unit,
        onNotDismissed: () -> Unit
    ) {
        if (!BuildConfig.IS_TV_PLATFORM) {
            onNotDismissed()
            return
        }
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "window.__cinejoySpatialNav ? window.__cinejoySpatialNav.dismissActiveOverlay() : 'none'"
            ) { rawResult ->
                val cleaned = rawResult?.trim()?.trim('"') ?: "none"
                if (cleaned == "blur-ime") {
                    onBlurIme()
                } else if (cleaned != "dismissed") {
                    onNotDismissed()
                }
            }
        }
    }

    fun navigateBackCleanly(webView: WebView) {
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "if (window.__cinejoySpatialNav) { window.__cinejoySpatialNav.releaseOverlay(true); } else if (window.__cinejoyReleaseMedia) { window.__cinejoyReleaseMedia(false); }"
            ) {
                webView.postDelayed({
                    if (webView.isAttachedToWindow && webView.canGoBack()) {
                        webView.goBack()
                    }
                }, 90L)
            }
        }
    }

    fun onFullscreenChanged(webView: WebView, active: Boolean) {
        runOnUiImmediate(webView) {
            if (BuildConfig.IS_TV_PLATFORM) {
                safeEvaluateJs(
                    webView,
                    "if (window.__cinejoySpatialNav && window.__cinejoySpatialNav.onFullscreenChanged) { window.__cinejoySpatialNav.onFullscreenChanged($active); }"
                )
            } else {
                safeEvaluateJs(
                    webView,
                    "if (window.__cinejoyMobileHud) { window.__cinejoyMobileHud.refresh(); }"
                )
            }
        }
    }

    fun moveFocus(webView: WebView, direction: String) {
        if (!BuildConfig.IS_TV_PLATFORM) {
            return
        }
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "if (window.__cinejoySpatialNav) { window.__cinejoySpatialNav.move('$direction'); }"
            )
        }
    }

    fun activateFocusedElement(
        webView: WebView,
        activeCustomView: View? = null,
        isEnterKey: Boolean = false,
        onShowIme: () -> Unit = {},
        onHideIme: () -> Unit = {},
        isFullscreenProvider: () -> Boolean = { false }
    ) {
        if (!BuildConfig.IS_TV_PLATFORM) {
            return
        }
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "window.__cinejoySpatialNav ? window.__cinejoySpatialNav.activateOrTap($isEnterKey) : 'none'"
            ) { rawResult ->
                val cleaned = rawResult?.trim()?.trim('"') ?: "none"
                if (cleaned == "hide-ime") {
                    onHideIme()
                    return@safeEvaluateJs
                }
                val isInputTap = cleaned.startsWith("input-tap:")
                val isFullscreenTap = cleaned.startsWith("tap-fs:")
                if (cleaned.startsWith("tap:") || isFullscreenTap || isInputTap) {
                    val rawCoords = when {
                        isFullscreenTap -> cleaned.removePrefix("tap-fs:")
                        isInputTap -> cleaned.removePrefix("input-tap:")
                        else -> cleaned.removePrefix("tap:")
                    }
                    val coords = rawCoords.split(",")
                    val rx = coords.getOrNull(0)?.toFloatOrNull()
                    val ry = coords.getOrNull(1)?.toFloatOrNull()
                    if (rx != null && ry != null && rx in 0.0f..1.0f && ry in 0.0f..1.0f && webView.width > 0 && webView.height > 0) {
                        val isFullscreen = isFullscreenProvider()
                        val targetView = if (isFullscreen && activeCustomView != null) activeCustomView else webView
                        val tw = if (targetView.width > 0) targetView.width.toFloat() else webView.width.toFloat()
                        val th = if (targetView.height > 0) targetView.height.toFloat() else webView.height.toFloat()
                        dispatchTrustedTap(targetView, rx * tw, ry * th)
                        if (isInputTap) {
                            onShowIme()
                        } else if (isFullscreenTap) {
                            webView.postDelayed({
                                if (!isFullscreenProvider()) {
                                    safeEvaluateJs(
                                        webView,
                                        "window.__cinejoySpatialNav ? window.__cinejoySpatialNav.getFullscreenTapCoords() : ''"
                                    ) { retryRaw ->
                                        val retryCoords = retryRaw?.trim()?.trim('"').orEmpty().split(",")
                                        val rrx = retryCoords.getOrNull(0)?.toFloatOrNull()
                                        val rry = retryCoords.getOrNull(1)?.toFloatOrNull()
                                        if (rrx != null && rry != null && rrx in 0.0f..1.0f && rry in 0.0f..1.0f && webView.width > 0 && webView.height > 0) {
                                            dispatchTrustedTap(webView, rrx * webView.width.toFloat(), rry * webView.height.toFloat())
                                        }
                                    }
                                }
                                refreshMap(webView)
                            }, 380L)
                        } else {
                            webView.postDelayed({ refreshMap(webView) }, 350L)
                        }
                    }
                }
            }
        }
    }

    fun toggleVideoPlayback(webView: WebView) {
        if (!BuildConfig.IS_TV_PLATFORM) {
            return
        }
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "if (window.__cinejoySpatialNav) { window.__cinejoySpatialNav.toggleVideo(); }"
            )
        }
    }

    fun seekVideo(webView: WebView, deltaSeconds: Int) {
        if (!BuildConfig.IS_TV_PLATFORM) {
            return
        }
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "if (window.__cinejoySpatialNav) { window.__cinejoySpatialNav.seekVideo($deltaSeconds); }"
            )
        }
    }

    private fun dispatchTrustedTap(targetView: View, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            targetView.dispatchTouchEvent(downEvent)
        } finally {
            downEvent.recycle()
        }
        targetView.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(
                downTime,
                upTime,
                MotionEvent.ACTION_UP,
                x,
                y,
                0
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                if (targetView.isAttachedToWindow) {
                    targetView.dispatchTouchEvent(upEvent)
                }
            } finally {
                upEvent.recycle()
            }
        }, 40L)
    }
}
