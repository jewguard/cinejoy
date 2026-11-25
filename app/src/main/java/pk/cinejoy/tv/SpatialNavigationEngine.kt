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
                if (method !== "GET" && method !== "HEAD" && !isMediaRetryUrl(url)) {
                    return origFetch(input, init);
                }
                var maxRetries = isMediaRetryUrl(url) ? 5 : 2;
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
                    var method = String(openArgs[0] || "GET").toUpperCase();
                    var url = String(openArgs[1] || "");
                    var isAsync = openArgs.length < 3 ? true : (openArgs[2] !== false);
                    if (!isAsync) {
                        return false;
                    }
                    return isMediaRetryUrl(url) || method === "GET" || method === "HEAD";
                }

                function getMaxRetries() {
                    var url = (openArgs && openArgs[1]) ? String(openArgs[1]) : "";
                    return isMediaRetryUrl(url) ? 5 : 2;
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
                    var maxRetries = getMaxRetries();
                    if (aborted || !shouldRetryRequest() || attempt >= maxRetries) {
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
                        if (target.readyState === 4 && shouldRetryRequest() && isRetryableStatus(target.status) && attempt < getMaxRetries()) {
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
                        if (shouldRetryRequest() && isRetryableStatus(target.status) && attempt < getMaxRetries()) {
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
                        if (shouldRetryRequest() && attempt < getMaxRetries()) {
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
                        if (shouldRetryRequest() && attempt < getMaxRetries()) {
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
            if (preservePos && location.pathname.indexOf("/watch/") !== -1) {
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
        var steppedUrl = sessionStorage.getItem("__cj_stepdown_url");
        var rawPlayer = localStorage.getItem("cinejoy_player_local");
        var playerCfg = rawPlayer ? JSON.parse(rawPlayer) : {};
        if (userQuality && /^[0-9]+$/.test(userQuality)) {
            playerCfg.quality = parseInt(userQuality, 10);
            playerCfg.mp4Quality = userQuality;
        } else if (steppedUrl === location.pathname) {
            playerCfg.quality = 720;
            playerCfg.mp4Quality = "720";
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
    var SELECTOR = "a[href], button, input, select, textarea, summary, [role=\"button\"], [role=\"link\"], [role=\"tab\"], [role=\"menuitem\"], [role=\"menuitemradio\"], [role=\"menuitemcheckbox\"], [role=\"option\"], [role=\"slider\"], [onclick], [tabindex]";
    var SERVER_ORDER = ["Light", "Nebula", "Sirius", "Rigel", "Polaris", "Warsaw", "Munich", "Paris"];

    var state = {
        initialized: true,
        spatialMap: [],
        cacheTime: 0,
        currentElement: null,
        lastBrowseElement: null,
        observer: null,
        refreshTimer: null,
        scrollTimer: null,
        hudWakeTimer: null,
        watchdogTimer: null,
        clickListenerInstalled: false,
        lastWatchReported: null,
        lastVideoRef: null,
        lastDecoded: 0,
        lastDropped: 0,
        lastPlayTime: 0,
        badDropStrikes: 0,
        stallStrikes: 0,
        noVideoTicks: 0,
        lastMoveAt: 0,
        isBurstMove: false,
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

    function wakePlayerHud() {
        state.wakeStep = (state.wakeStep || 0) + 1;
        var wx = 320 + ((state.wakeStep % 5) * 40);
        var wy = 240 + ((state.wakeStep % 3) * 30);
        var root = document.fullscreenElement || document.webkitFullscreenElement || document.querySelector(".player-root") || document.body;
        if (!root) {
            return;
        }
        var targets = [root, document];
        for (var i = 0; i < targets.length; i++) {
            var t = targets[i];
            try {
                t.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true, cancelable: true, view: window, clientX: wx, clientY: wy }));
                t.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, cancelable: true, view: window, clientX: wx, clientY: wy }));
            } catch (e) {}
            try {
                if (typeof PointerEvent !== "undefined") {
                    t.dispatchEvent(new PointerEvent("pointerenter", { bubbles: true, cancelable: true, view: window, clientX: wx, clientY: wy, pointerType: "mouse", isPrimary: true }));
                    t.dispatchEvent(new PointerEvent("pointermove", { bubbles: true, cancelable: true, view: window, clientX: wx, clientY: wy, pointerType: "mouse", isPrimary: true }));
                }
            } catch (e2) {}
        }
        try {
            if (window.__cinejoyNative && window.__cinejoyNative.pulseMouseHover) {
                window.__cinejoyNative.pulseMouseHover();
            }
        } catch (e3) {}
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
        return document.querySelector('input[aria-label="Seek Video"], input[type="range"], [data-cj-seekbar="1"], [role="slider"]');
    }

    function findNativePlayPauseButton() {
        return document.querySelector('button[aria-label="Pause"], button[aria-label="Play"]');
    }

    function dispatchNativeDirectionalSeek(isRight) {
        var keyName = isRight ? "ArrowRight" : "ArrowLeft";
        var keyCodeNum = isRight ? 39 : 37;
        var vid = findActiveVideo();
        var seekEl = findNativeSeekControl();
        var playerRoot = getPlayerRoot(vid);
        var targets = [];
        if (seekEl) { targets.push(seekEl); }
        if (playerRoot) { targets.push(playerRoot); }
        targets.push(document);
        for (var i = 0; i < targets.length; i++) {
            try {
                var kd = new KeyboardEvent("keydown", {
                    key: keyName,
                    code: keyName,
                    keyCode: keyCodeNum,
                    which: keyCodeNum,
                    bubbles: true,
                    cancelable: true
                });
                targets[i].dispatchEvent(kd);
                var ku = new KeyboardEvent("keyup", {
                    key: keyName,
                    code: keyName,
                    keyCode: keyCodeNum,
                    which: keyCodeNum,
                    bubbles: true,
                    cancelable: true
                });
                targets[i].dispatchEvent(ku);
            } catch (e) {}
        }
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
        if (!vid || vid.paused) {
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
        if (vid && vid.paused) {
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
        vid.addEventListener("play", function() {
            scheduleHudAutoHide();
        });
        vid.addEventListener("playing", function() {
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
        var nodes = document.querySelectorAll('input[aria-label="Seek Video"], input[type="range"], [data-cj-seekbar="1"], button[aria-label="Pause"], button[aria-label="Play"], button[aria-label*="Fullscreen"], button[aria-label*="fullscreen"]');
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

    function syncWatchPageState() {
        var onWatch = isWatchRoute();
        reportSurfaceTransparency(onWatch);
        if (onWatch) {
            document.documentElement.classList.add(WATCH_CLASS);
            var seekBar = findNativeSeekControl();
            if (seekBar && seekBar.getAttribute("data-cj-seekbar") !== "1") {
                seekBar.setAttribute("data-cj-seekbar", "1");
                state.cacheTime = 0;
            }
            var videos = document.querySelectorAll("video");
            var vid = videos.length > 0 ? videos[0] : findActiveVideo();
            if (vid) {
                bindVideoPlaybackEvents(vid);
                pruneSubtitleTracks(vid);
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
            }
            markPlayerControlContainers();
            if (vid && !vid.paused && !state.hudHidden && !state.hudAutoHideTimer) {
                scheduleHudAutoHide();
            }
        } else {
            resetHudAutoHide();
            document.documentElement.classList.remove(WATCH_CLASS);
            var cleaned = document.querySelectorAll('[data-cj-trans="1"]');
            for (var i = 0; i < cleaned.length; i++) {
                cleaned[i].removeAttribute("data-cj-trans");
                cleaned[i].style.removeProperty("background");
                cleaned[i].style.removeProperty("background-color");
                cleaned[i].style.removeProperty("background-image");
            }
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
            "[data-cj-seekbar=\"1\"]." + FOCUS_CLASS + ", input[type=\"range\"]." + FOCUS_CLASS + " {" +
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

        var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"], [role="menu"], .menu-panel');
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
        var tabIdx = el.getAttribute("tabindex");
        if (tabIdx === "-1" && !matchesSelector(el, "a[href], button, input, select, textarea")) {
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
            if (rect.width > vw * 0.70 && rect.height > vh * 0.50) {
                return false;
            }
            var divTxt = (el.innerText || "").replace(/\s+/g, "").trim();
            if (!el.getAttribute("aria-label") && !el.getAttribute("title") && !divTxt &&
                el.querySelector("img, picture, svg") && !el.querySelector("button, a, input")) {
                return false;
            }
        }
        if (!ignoreCull) {
            if (rect.bottom < -160 || rect.top > vh + 260 || rect.right < -200 || rect.left > vw + 480) {
                return false;
            }
        } else {
            if (rect.bottom < -120 || rect.top > vh + 240) {
                return false;
            }
        }
        var cy = rect.top + rect.height * 0.5;
        if (!ignoreCull && cy < 0) {
            return false;
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

        var skip = {};
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
        if (useCache && state.spatialMap.length > 0 && now - state.cacheTime < 140) {
            return state.spatialMap;
        }
        var vw = window.innerWidth || document.documentElement.clientWidth || 1280;
        var vh = window.innerHeight || document.documentElement.clientHeight || 720;
        var activeOverlay = findActiveOverlay(vw, vh);
        var scope = activeOverlay || document;
        var inPreview = !!(activeOverlay && activeOverlay.classList && activeOverlay.classList.contains("preview"));
        var nodes = scope.querySelectorAll(SELECTOR);

        var scrollX = window.pageXOffset || document.documentElement.scrollLeft || 0;
        var scrollY = window.pageYOffset || document.documentElement.scrollTop || 0;
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
                cy: rect.top + rect.height * 0.5,
                docX: rect.left + scrollX + rect.width * 0.5,
                docY: rect.top + scrollY + rect.height * 0.5
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
        } else if (!isWatchRoute()) {
            var inFixedChrome = !!(el.closest && el.closest("header, nav, [class*='fixed']"));
            if (!inFixedChrome) {
                var r = el.getBoundingClientRect();
                var offCenterY = Math.abs((r.top + r.height * 0.5) - (vh * 0.5)) > (vh * 0.18);
                var offEdges = r.top < 96 || r.bottom > vh - 72 || r.left < 40 || r.right > vw - 40;
                if (offCenterY || offEdges) {
                    try {
                        el.scrollIntoView({
                            behavior: state.isBurstMove ? "auto" : "smooth",
                            block: "center",
                            inline: "center"
                        });
                    } catch (e3) {
                        try {
                            var targetY = (window.pageYOffset || 0) + r.top - (vh * 0.45);
                            window.scrollTo({ top: Math.max(0, targetY), behavior: state.isBurstMove ? "auto" : "smooth" });
                        } catch (e4) {}
                    }
                }
            }
        }
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
            var inView = item.bottom > 0 && item.top < vh && item.right > 0 && item.left < vw;
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

    function move(direction) {
        var nowMove = Date.now();
        state.isBurstMove = (nowMove - state.lastMoveAt) < 180;
        state.lastMoveAt = nowMove;
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

        scanElements(true);
        if (state.spatialMap.length === 0) {
            scanElements(false);
        }
        if (state.spatialMap.length === 0) {
            return;
        }
        if (!state.currentElement || !document.body.contains(state.currentElement) || !findEntryForElement(state.currentElement)) {
            var activeOverlayNow = findActiveOverlay(vw, vh);
            var first = pickInitialEntry(activeOverlayNow);
            if (first) {
                applyFocus(first.el);
            }
            return;
        }

        var curr = findEntryForElement(state.currentElement);
        if (!curr) {
            var r = state.currentElement.getBoundingClientRect();
            curr = {
                el: state.currentElement,
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

        var bestCandidate = null;
        var bestScore = 1e12;

        for (var i = 0; i < state.spatialMap.length; i++) {
            var cand = state.spatialMap[i];
            if (cand.el === curr.el) {
                continue;
            }

            var primary = 0;
            var perp = 0;
            var score = 1e12;

            if (direction === "right") {
                if (cand.cx <= curr.cx + 2) {
                    continue;
                }
                primary = cand.cx - curr.cx;
                perp = Math.abs(cand.cy - curr.cy);
                var overlapY = Math.max(0, Math.min(curr.bottom, cand.bottom) - Math.max(curr.top, cand.top));
                var sameRow = overlapY > Math.min(curr.height, cand.height) * 0.25;
                score = primary + perp * (sameRow ? 1.5 : 3.5) + (sameRow ? 0 : 10000);
            } else if (direction === "left") {
                if (cand.cx >= curr.cx - 2) {
                    continue;
                }
                primary = curr.cx - cand.cx;
                perp = Math.abs(cand.cy - curr.cy);
                var overlapYLeft = Math.max(0, Math.min(curr.bottom, cand.bottom) - Math.max(curr.top, cand.top));
                var sameRowLeft = overlapYLeft > Math.min(curr.height, cand.height) * 0.25;
                score = primary + perp * (sameRowLeft ? 1.5 : 3.5) + (sameRowLeft ? 0 : 10000);
            } else if (direction === "down") {
                if (cand.cy <= curr.cy + 2) {
                    continue;
                }
                primary = cand.cy - curr.cy;
                perp = Math.abs(cand.cx - curr.cx);
                var overlapX = Math.max(0, Math.min(curr.right, cand.right) - Math.max(curr.left, cand.left));
                score = primary + perp * 1.8 - (overlapX > 0 ? 200 : 0);
            } else if (direction === "up") {
                if (cand.cy >= curr.cy - 2) {
                    continue;
                }
                primary = curr.cy - cand.cy;
                perp = Math.abs(cand.cx - curr.cx);
                var overlapXUp = Math.max(0, Math.min(curr.right, cand.right) - Math.max(curr.left, cand.left));
                score = primary + perp * 1.8 - (overlapXUp > 0 ? 200 : 0);
            } else {
                continue;
            }

            if (score < bestScore) {
                bestScore = score;
                bestCandidate = cand;
            }
        }

        if (bestCandidate) {
            state.cacheTime = 0;
            applyFocus(bestCandidate.el);
            return;
        }

        if (direction === "down" || direction === "up") {
            var curOverlay = findActiveOverlay(vw, vh);
            if (curOverlay) {
                var scrollers = curOverlay.querySelectorAll("div, ul, section");
                var scrolled = false;
                var stepY = direction === "down" ? 200 : -200;
                for (var si = 0; si < scrollers.length; si++) {
                    var sc = scrollers[si];
                    if (sc.scrollHeight > sc.clientHeight + 8) {
                        sc.scrollTop += stepY;
                        scrolled = true;
                        break;
                    }
                }
                if (!scrolled && curOverlay.scrollHeight > curOverlay.clientHeight + 8) {
                    curOverlay.scrollTop += stepY;
                    scrolled = true;
                }
                if (scrolled) {
                    state.cacheTime = 0;
                    setTimeout(function() { scanElements(false); }, 60);
                    return;
                }
            } else if (!isWatchRoute()) {
                var delta = direction === "down" ? 360 : -360;
                try {
                    window.scrollBy(0, delta);
                } catch (e) {}
                if (state.scrollTimer) {
                    clearTimeout(state.scrollTimer);
                }
                state.scrollTimer = setTimeout(function() {
                    scanElements(false);
                }, 90);
            }
        }
    }

    function dispatchPointerEvent(el, type, cx, cy) {
        try {
            var evt = new MouseEvent(type, {
                bubbles: true,
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
                el.scrollIntoView({ block: "center", inline: "center" });
            } catch (e) {
                try {
                    el.scrollIntoView(false);
                } catch (e2) {}
            }
            rect = el.getBoundingClientRect();
        }
        var cx = Math.max(1, Math.min(vw - 1, rect.left + rect.width * 0.5));
        var cy = Math.max(1, Math.min(vh - 1, rect.top + rect.height * 0.5));
        if (el.tagName === "INPUT" || el.tagName === "TEXTAREA") {
            try {
                el.focus();
            } catch (e3) {}
        }
        return (cx / vw).toFixed(5) + "," + (cy / vh).toFixed(5);
    }

    function clickNativePlayPause() {
        var btn = findNativePlayPauseButton();
        if (btn && typeof btn.click === "function") {
            btn.click();
            return true;
        }
        return toggleVideo();
    }

    function activateOrTap() {
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

        var needsTap = (el.tagName === "INPUT" && el.type !== "range") || el.tagName === "TEXTAREA";
        if (needsTap) {
            return "tap:" + prepareTapCoords(el);
        }

        var rect = el.getBoundingClientRect();
        var cx = rect.left + rect.width * 0.5;
        var cy = rect.top + rect.height * 0.5;

        dispatchPointerEvent(el, "pointerdown", cx, cy);
        dispatchPointerEvent(el, "mousedown", cx, cy);
        dispatchPointerEvent(el, "pointerup", cx, cy);
        dispatchPointerEvent(el, "mouseup", cx, cy);

        if (typeof el.click === "function") {
            el.click();
        } else {
            dispatchPointerEvent(el, "click", cx, cy);
        }

        setTimeout(function() {
            syncWatchPageState();
            ensureFocus();
            if (isWatchRoute()) {
                scheduleHudAutoHide();
            }
        }, 120);
        return "clicked";
    }

    function activate() {
        activateOrTap();
    }

    function dismissActiveOverlay() {
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

    function findButtonByTextOrAria(targetText) {
        var btns = document.querySelectorAll("button");
        for (var i = 0; i < btns.length; i++) {
            var txt = (btns[i].innerText || "").replace(/\s+/g, " ").trim();
            var aria = btns[i].getAttribute("aria-label") || "";
            if (txt === targetText || aria === targetText) {
                return btns[i];
            }
        }
        return null;
    }

    function installClickInterceptor() {
        if (state.clickListenerInstalled) {
            return;
        }
        state.clickListenerInstalled = true;
        document.addEventListener("click", function(e) {
            if (!isWatchRoute()) {
                return;
            }
            var target = e.target;
            while (target && target !== document.body && target.tagName !== "BUTTON" && target.tagName !== "A" && target.getAttribute("role") !== "menuitem" && target.getAttribute("role") !== "menuitemradio") {
                target = target.parentElement;
            }
            if (!target) {
                return;
            }
            var txt = (target.innerText || "").replace(/\s+/g, " ").trim();
            var aria = target.getAttribute("aria-label") || "";
            if (txt === "2160p" || txt === "4K" || txt.indexOf("4K ") === 0 || txt.indexOf("2160p") === 0) {
                try {
                    sessionStorage.setItem("__cj_user_quality", "2160");
                    sessionStorage.removeItem("__cj_stepdown_url");
                } catch (e1) {}
                releaseOverlaySafely(false);
            } else if (txt === "1080p" || txt.indexOf("1080p") === 0 || txt === "720p" || txt.indexOf("720p") === 0 || txt === "480p" || txt === "360p") {
                var numQ = txt.replace(/[^0-9]/g, "");
                try {
                    sessionStorage.setItem("__cj_user_quality", numQ || "720");
                    sessionStorage.setItem("__cj_stepdown_url", location.pathname);
                } catch (e2) {}
                releaseOverlaySafely(false);
            } else if (txt === "Auto" || txt.indexOf("Auto (") === 0) {
                try {
                    sessionStorage.setItem("__cj_user_quality", "auto");
                    sessionStorage.removeItem("__cj_stepdown_url");
                } catch (e3) {}
                releaseOverlaySafely(false);
            } else if (SERVER_ORDER.indexOf(txt) !== -1) {
                releaseOverlaySafely(false);
            } else if (aria === "Back" || txt === "Go Back" || txt === "Go back" || aria.indexOf("View details") === 0) {
                releaseOverlaySafely(true);
            }
        }, true);
    }

    function attemptServerFallback() {
        var key = "__cj_srv_tries_" + location.pathname;
        var tries = 0;
        try {
            tries = parseInt(sessionStorage.getItem(key) || "0", 10) || 0;
        } catch (e) {}
        if (tries >= 3) {
            return false;
        }
        try { sessionStorage.setItem(key, String(tries + 1)); } catch (e2) {}

        var serversBtn = findButtonByTextOrAria("Servers");
        if (serversBtn) {
            var nextServer = SERVER_ORDER[Math.min(tries + 1, SERVER_ORDER.length - 1)];
            releaseOverlaySafely(true);
            serversBtn.click();
            setTimeout(function() {
                var candidateBtn = findButtonByTextOrAria(nextServer);
                if (candidateBtn) {
                    candidateBtn.click();
                }
            }, 350);
            return true;
        }

        var tryAgainBtn = findButtonByTextOrAria("Try Again");
        if (tryAgainBtn && tries === 0) {
            releaseOverlaySafely(true);
            tryAgainBtn.click();
            return true;
        }
        return false;
    }

    function triggerOverlayRecovery() {
        var lastRecover = 0;
        try {
            lastRecover = parseInt(sessionStorage.getItem("__cj_overlay_recover") || "0", 10) || 0;
        } catch (e) {}
        if (Date.now() - lastRecover < 20000) {
            return;
        }
        try { sessionStorage.setItem("__cj_overlay_recover", String(Date.now())); } catch (e2) {}
        releaseOverlaySafely(true);
        setTimeout(function() {
            location.reload();
        }, 350);
    }

    function runPlaybackWatchdog() {
        syncWatchPageState();
        if (!isWatchRoute()) {
            state.badDropStrikes = 0;
            state.stallStrikes = 0;
            state.noVideoTicks = 0;
            state.lastVideoRef = null;
            return;
        }

        var bodyText = document.body ? (document.body.innerText || "") : "";
        if (bodyText.indexOf("Server error") !== -1 || bodyText.indexOf("Oops! We couldn't find this") !== -1) {
            attemptServerFallback();
            return;
        }

        var vid = findActiveVideo();
        if (!vid || vid.error) {
            state.noVideoTicks++;
            if ((vid && vid.error) || state.noVideoTicks >= 10) {
                state.noVideoTicks = 0;
                attemptServerFallback();
            }
            return;
        }

        if (vid.readyState === 0 && !vid.paused) {
            state.noVideoTicks++;
            if (state.noVideoTicks >= 10) {
                state.noVideoTicks = 0;
                attemptServerFallback();
            }
            return;
        }

        state.noVideoTicks = 0;
        var decoded = vid.webkitDecodedFrameCount || 0;
        var dropped = vid.webkitDroppedFrameCount || 0;

        if (!vid.paused && vid.readyState >= 2) {
            if (state.lastVideoRef === vid) {
                var dDec = decoded - state.lastDecoded;
                var dDrop = dropped - state.lastDropped;
                if (dDec >= 20 && dDrop >= dDec * 0.75) {
                    state.badDropStrikes++;
                    if (state.badDropStrikes >= 2) {
                        state.badDropStrikes = 0;
                        triggerOverlayRecovery();
                        return;
                    }
                } else {
                    state.badDropStrikes = 0;
                }

                if (vid.videoWidth >= 3000 && vid.readyState < 3 && Math.abs((vid.currentTime || 0) - state.lastPlayTime) < 0.05) {
                    state.stallStrikes++;
                    if (state.stallStrikes >= 6) {
                        state.stallStrikes = 0;
                        try {
                            if (sessionStorage.getItem("__cj_stepdown_url") !== location.pathname) {
                                sessionStorage.setItem("__cj_stepdown_url", location.pathname);
                                sessionStorage.setItem("__cj_user_quality", "720");
                                var rawPlayer = localStorage.getItem("cinejoy_player_local");
                                var playerCfg = rawPlayer ? JSON.parse(rawPlayer) : {};
                                playerCfg.quality = 720;
                                playerCfg.mp4Quality = "720";
                                localStorage.setItem("cinejoy_player_local", JSON.stringify(playerCfg));
                                triggerOverlayRecovery();
                                return;
                            }
                        } catch (e) {}
                    }
                } else {
                    state.stallStrikes = 0;
                }
            }
            state.lastVideoRef = vid;
            state.lastDecoded = decoded;
            state.lastDropped = dropped;
            state.lastPlayTime = vid.currentTime || 0;
        }
    }

    function installWatchdog() {
        if (state.watchdogTimer) {
            return;
        }
        state.watchdogTimer = setInterval(runPlaybackWatchdog, 2500);
    }

    function installObserver() {
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
            }, 300);
        });
        state.observer.observe(document.body, { childList: true, subtree: true });
    }

    function refresh() {
        applyTvPresets();
        injectStyles();
        syncWatchPageState();
        ensureFocus();
        installObserver();
        installClickInterceptor();
        installWatchdog();
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
        activate: activate,
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
        getHudState: function() {
            return { hidden: state.hudHidden, fullscreen: isInFullscreen() };
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
    var boundVid = null;
    var lastReportedWatch = null;

    function isWatchRoute() {
        return location.pathname.indexOf("/watch/") !== -1;
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

    function wakeHud() {
        document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
        scheduleHide();
    }

    function refresh() {
        injectMobileStyles();
        var onWatch = isWatchRoute();
        reportTransparency(onWatch);
        if (onWatch) {
            document.documentElement.classList.add(WATCH_CLASS);
            markHudChrome();
            var vid = document.querySelector("video");
            if (vid && boundVid !== vid) {
                boundVid = vid;
                vid.addEventListener("play", scheduleHide);
                vid.addEventListener("playing", scheduleHide);
                vid.addEventListener("pause", function() {
                    clearTimer();
                    document.documentElement.classList.remove(HUD_HIDDEN_CLASS);
                });
            }
            if (vid && !vid.paused && !hudTimer && !document.documentElement.classList.contains(HUD_HIDDEN_CLASS)) {
                scheduleHide();
            }
        } else {
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

    document.addEventListener("touchstart", wakeHud, { passive: true, capture: true });
    document.addEventListener("pointerdown", wakeHud, { passive: true, capture: true });
    document.addEventListener("click", wakeHud, true);

    window.__cinejoyMobileHud = {
        initialized: true,
        refresh: refresh
    };

    refresh();
    setInterval(refresh, 2000);
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

    fun handleBackKey(webView: WebView, onNotDismissed: () -> Unit) {
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
                if (cleaned != "dismissed") {
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
        isFullscreenProvider: () -> Boolean = { false }
    ) {
        if (!BuildConfig.IS_TV_PLATFORM) {
            return
        }
        runOnUiImmediate(webView) {
            safeEvaluateJs(
                webView,
                "window.__cinejoySpatialNav ? window.__cinejoySpatialNav.activateOrTap() : 'none'"
            ) { rawResult ->
                val cleaned = rawResult?.trim()?.trim('"') ?: "none"
                val isFullscreenTap = cleaned.startsWith("tap-fs:")
                if (cleaned.startsWith("tap:") || isFullscreenTap) {
                    val rawCoords = if (isFullscreenTap) cleaned.removePrefix("tap-fs:") else cleaned.removePrefix("tap:")
                    val coords = rawCoords.split(",")
                    val rx = coords.getOrNull(0)?.toFloatOrNull()
                    val ry = coords.getOrNull(1)?.toFloatOrNull()
                    if (rx != null && ry != null && rx in 0.0f..1.0f && ry in 0.0f..1.0f && webView.width > 0 && webView.height > 0) {
                        val isFullscreen = isFullscreenProvider()
                        val targetView = if (isFullscreen && activeCustomView != null) activeCustomView else webView
                        val tw = if (targetView.width > 0) targetView.width.toFloat() else webView.width.toFloat()
                        val th = if (targetView.height > 0) targetView.height.toFloat() else webView.height.toFloat()
                        dispatchTrustedTap(targetView, rx * tw, ry * th)
                        if (isFullscreenTap) {
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
