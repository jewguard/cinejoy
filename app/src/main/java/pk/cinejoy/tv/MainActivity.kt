package pk.cinejoy.tv

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.json.JSONObject

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    companion object {
        private const val NATIVE_BRIDGE_NAME = "__cinejoyNative"
        private const val SPLASH_FALLBACK_TIMEOUT_MS = 7000L
        private const val DESKTOP_TV_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MOBILE_ANDROID_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var cinejoyWebView: WebView
    private lateinit var nativePlayerView: PlayerView
    private lateinit var exoPlayerController: CineJoyExoPlayerController
    private lateinit var fullscreenChromeClient: FullscreenWebChromeClient
    private var splashOverlayView: FrameLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isWebViewDestroyed = false
    private var isWatchSurfaceTransparent = false
    private var isInitialContentReady = false
    private var isForegroundPausedForBackground = false
    private var hoverStep = 0
    private val splashFallbackRunnable = Runnable { markInitialContentReady() }

    private inner class CineJoyNativeBridge {
        @JavascriptInterface
        fun releaseVideoOverlay() {
            Thread {
                Runtime.getRuntime().gc()
                try {
                    Thread.sleep(120L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                Runtime.getRuntime().gc()
            }.start()
        }

        @JavascriptInterface
        fun setWatchSurfaceTransparent(transparent: Boolean) {
            this@MainActivity.setWatchSurfaceTransparent(transparent)
        }

        @JavascriptInterface
        fun onWebContentRendered() {
            this@MainActivity.markInitialContentReady()
        }

        @JavascriptInterface
        fun isFullscreenActive(): Boolean {
            return ::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive()
        }

        @JavascriptInterface
        fun exitFullscreen() {
            runOnUiThread {
                if (!isWebViewDestroyed && ::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive()) {
                    fullscreenChromeClient.onHideCustomView()
                }
            }
        }

        @JavascriptInterface
        fun openNativePlayer(streamUrl: String?, headersJson: String?, startPositionMs: Long) {
            if (streamUrl.isNullOrBlank()) {
                return
            }
            val parsedHeaders = mutableMapOf<String, String>()
            if (!headersJson.isNullOrBlank()) {
                try {
                    val obj = JSONObject(headersJson)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.optString(key, "")
                        if (key.isNotBlank() && value.isNotBlank()) {
                            parsedHeaders[key] = value
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            runOnUiThread {
                if (!isWebViewDestroyed && ::exoPlayerController.isInitialized) {
                    exoPlayerController.openStream(streamUrl, parsedHeaders, startPositionMs)
                }
            }
        }

        @JavascriptInterface
        fun pulseMouseHover() {
            runOnUiThread {
                if (!isWebViewDestroyed && ::cinejoyWebView.isInitialized && cinejoyWebView.width > 0 && cinejoyWebView.height > 0) {
                    hoverStep = (hoverStep + 1) % 7
                    val activeView = if (::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive()) {
                        fullscreenChromeClient.getActiveCustomView()
                    } else {
                        null
                    }
                    val targets = listOfNotNull(activeView, cinejoyWebView)
                    val now = SystemClock.uptimeMillis()
                    for (target in targets) {
                        val tw = if (target.width > 0) target.width.toFloat() else cinejoyWebView.width.toFloat()
                        val th = if (target.height > 0) target.height.toFloat() else cinejoyWebView.height.toFloat()
                        val hx = (tw * 0.45f) + (hoverStep * 7f)
                        val hy = (th * 0.45f) + (hoverStep * 5f)
                        val enterEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_ENTER, hx, hy, 0).apply {
                            source = InputDevice.SOURCE_MOUSE
                        }
                        val hoverEvent1 = MotionEvent.obtain(now, now + 4L, MotionEvent.ACTION_HOVER_MOVE, hx, hy, 0).apply {
                            source = InputDevice.SOURCE_MOUSE
                        }
                        val hoverEvent2 = MotionEvent.obtain(now, now + 10L, MotionEvent.ACTION_HOVER_MOVE, hx + 24f, hy + 12f, 0).apply {
                            source = InputDevice.SOURCE_MOUSE
                        }
                        try {
                            target.dispatchGenericMotionEvent(enterEvent)
                            target.dispatchGenericMotionEvent(hoverEvent1)
                            target.dispatchGenericMotionEvent(hoverEvent2)
                        } finally {
                            enterEvent.recycle()
                            hoverEvent1.recycle()
                            hoverEvent2.recycle()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isInitialContentReady
        }
        splashScreen.setOnExitAnimationListener { splashViewProvider ->
            splashViewProvider.view.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    splashViewProvider.remove()
                }
                .start()
        }
        mainHandler.postDelayed(splashFallbackRunnable, SPLASH_FALLBACK_TIMEOUT_MS)

        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        cinejoyWebView = findViewById(R.id.cinejoyWebView)
        nativePlayerView = findViewById(R.id.nativePlayerView)

        val opaqueDark = Color.rgb(5, 5, 5)
        rootContainer.setBackgroundColor(opaqueDark)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            attachBrandedSplashOverlay(opaqueDark)
        }

        exoPlayerController = CineJoyExoPlayerController(
            activity = this,
            playerView = nativePlayerView,
            isInPipProvider = { isInPictureInPictureModeCompat() },
            onStateChanged = { nativeActive ->
                if (!isWebViewDestroyed && ::cinejoyWebView.isInitialized) {
                    cinejoyWebView.visibility = if (nativeActive) View.GONE else View.VISIBLE
                }
                if (!BuildConfig.IS_TV_PLATFORM) {
                    requestedOrientation = if (nativeActive || (::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive())) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                    }
                }
                updateSystemUiForRoute(isWatchOrFullscreenActive())
            }
        )
        lifecycle.addObserver(exoPlayerController)

        attachWebViewComponents(cinejoyWebView)

        if (BuildConfig.IS_TV_PLATFORM) {
            configurePreferred4kDisplayMode()
        }

        val initialUrl = CineJoyApplication.consumeRecoveryUrl(this)
        updateSystemUiForRoute(initialUrl.contains("/watch/"))
        cinejoyWebView.loadUrl(initialUrl)
    }

    private fun attachWebViewComponents(targetWebView: WebView) {
        cinejoyWebView = targetWebView
        configureWebViewEngine(targetWebView)

        fullscreenChromeClient = FullscreenWebChromeClient(
            rootContainer = rootContainer,
            webView = targetWebView,
            onEnterFullscreen = {
                setWatchSurfaceTransparent(true)
                if (!BuildConfig.IS_TV_PLATFORM) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                updateSystemUiForRoute(true)
                SpatialNavigationEngine.onFullscreenChanged(targetWebView, true)
            },
            onExitFullscreen = {
                val onWatch = targetWebView.url?.contains("/watch/") == true
                setWatchSurfaceTransparent(onWatch)
                if (!BuildConfig.IS_TV_PLATFORM && !exoPlayerController.isVisible()) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
                updateSystemUiForRoute(isWatchOrFullscreenActive())
                SpatialNavigationEngine.onFullscreenChanged(targetWebView, false)
            }
        )

        targetWebView.webViewClient = CineJoyWebViewClient(
            onPageStart = { webView, url ->
                setWatchSurfaceTransparent(url.contains("/watch/"))
                SpatialNavigationEngine.applyNetworkAndPresets(webView)
            },
            onLoadError = {
                markInitialContentReady()
            },
            onPageReady = { webView, url ->
                setWatchSurfaceTransparent(url.contains("/watch/"))
                SpatialNavigationEngine.injectIntoWebView(webView)
            },
            onRouteChanged = { webView, url ->
                setWatchSurfaceTransparent(url.contains("/watch/"))
                SpatialNavigationEngine.refreshMap(webView)
            },
            onRenderProcessCrashed = {
                recoverFromWebViewCrash()
            }
        )
        targetWebView.webChromeClient = fullscreenChromeClient
    }

    private fun recoverFromWebViewCrash() {
        runOnUiThread {
            if (isDestroyed || isFinishing || !::rootContainer.isInitialized) {
                return@runOnUiThread
            }
            if (::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive()) {
                fullscreenChromeClient.onHideCustomView()
            }
            val oldWebView = cinejoyWebView
            try {
                oldWebView.removeJavascriptInterface(NATIVE_BRIDGE_NAME)
                rootContainer.removeView(oldWebView)
                oldWebView.stopLoading()
                oldWebView.webChromeClient = null
                oldWebView.webViewClient = WebViewClient()
                oldWebView.removeAllViews()
                oldWebView.destroy()
            } catch (_: Throwable) {
            }

            val freshWebView = WebView(this).apply {
                id = R.id.cinejoyWebView
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.rgb(5, 5, 5))
                isFocusable = true
                isFocusableInTouchMode = true
            }
            rootContainer.addView(freshWebView, 0)
            isWatchSurfaceTransparent = false
            attachWebViewComponents(freshWebView)
            freshWebView.loadUrl(CineJoyApplication.consumeRecoveryUrl(this))
        }
    }

    private fun attachBrandedSplashOverlay(backgroundColor: Int) {
        val density = resources.displayMetrics.density
        val logoSizePx = (180f * density).toInt()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(backgroundColor)
            isClickable = false
            isFocusable = false
        }
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val lp = FrameLayout.LayoutParams(logoSizePx, logoSizePx, Gravity.CENTER)
        overlay.addView(logoView, lp)
        rootContainer.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        splashOverlayView = overlay
    }

    private fun markInitialContentReady() {
        val complete = {
            if (!isInitialContentReady) {
                isInitialContentReady = true
                mainHandler.removeCallbacks(splashFallbackRunnable)
                if (!isWatchSurfaceTransparent) {
                    window.setBackgroundDrawable(ColorDrawable(Color.rgb(5, 5, 5)))
                }
                val overlay = splashOverlayView
                if (overlay != null) {
                    splashOverlayView = null
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(220L)
                        .withEndAction {
                            if (::rootContainer.isInitialized) {
                                rootContainer.removeView(overlay)
                            }
                        }
                        .start()
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            complete()
        } else {
            runOnUiThread(complete)
        }
    }

    private fun setWatchSurfaceTransparent(transparent: Boolean) {
        val applyBlock = {
            if (!isWebViewDestroyed && ::rootContainer.isInitialized && ::cinejoyWebView.isInitialized) {
                if (isWatchSurfaceTransparent != transparent) {
                    isWatchSurfaceTransparent = transparent
                    if (transparent) {
                        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        rootContainer.setBackgroundColor(Color.TRANSPARENT)
                        cinejoyWebView.setBackgroundColor(Color.TRANSPARENT)
                    } else {
                        val opaqueDark = Color.rgb(5, 5, 5)
                        window.setBackgroundDrawable(ColorDrawable(opaqueDark))
                        rootContainer.setBackgroundColor(opaqueDark)
                        cinejoyWebView.setBackgroundColor(opaqueDark)
                    }
                    updateSystemUiForRoute(isWatchOrFullscreenActive())
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyBlock()
        } else {
            runOnUiThread(applyBlock)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateSystemUiForRoute(isWatchOrFullscreenActive())
        }
    }

    private fun configurePreferred4kDisplayMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val activeDisplay = ContextCompat.getDisplayOrDefault(this)
        val modes = activeDisplay.supportedModes
        if (modes.isEmpty()) {
            return
        }

        var bestMode = modes[0]
        var bestPixels = bestMode.physicalWidth.toLong() * bestMode.physicalHeight.toLong()
        for (mode in modes) {
            val pixels = mode.physicalWidth.toLong() * mode.physicalHeight.toLong()
            val betterResolution = pixels > bestPixels
            val sameResBetterRefresh = pixels == bestPixels &&
                ((mode.refreshRate >= 59.0f && bestMode.refreshRate < 59.0f) ||
                    (mode.refreshRate >= 59.0f && bestMode.refreshRate >= 59.0f && mode.refreshRate > bestMode.refreshRate))
            if (betterResolution || sameResBetterRefresh) {
                bestMode = mode
                bestPixels = pixels
            }
        }

        val params = window.attributes
        params.preferredDisplayModeId = bestMode.modeId
        window.attributes = params
    }

    private fun configureWebViewEngine(targetWebView: WebView) {
        val settings = targetWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = if (BuildConfig.IS_TV_PLATFORM) {
            DESKTOP_TV_USER_AGENT
        } else {
            MOBILE_ANDROID_USER_AGENT
        }
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowContentAccess = true
        settings.allowFileAccess = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.offscreenPreRaster = false
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(targetWebView, true)

        targetWebView.addJavascriptInterface(CineJoyNativeBridge(), NATIVE_BRIDGE_NAME)
        targetWebView.setLayerType(View.LAYER_TYPE_NONE, null)
        targetWebView.setBackgroundColor(Color.rgb(5, 5, 5))
        targetWebView.isVerticalScrollBarEnabled = false
        targetWebView.isHorizontalScrollBarEnabled = false
        targetWebView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun isInPictureInPictureModeCompat(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (BuildConfig.IS_TV_PLATFORM || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val shouldPip = exoPlayerController.isPlaying() ||
            (cinejoyWebView.url?.contains("/watch/") == true) ||
            (::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive())
        if (shouldPip) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && !isWebViewDestroyed) {
            updateSystemUiForRoute(isWatchOrFullscreenActive())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isWebViewDestroyed || !::cinejoyWebView.isInitialized) {
            return super.dispatchKeyEvent(event)
        }
        val keyCode = event.keyCode
        val action = event.action

        if (::exoPlayerController.isInitialized && exoPlayerController.isVisible()) {
            if (isHandledRemoteKey(keyCode)) {
                if (action == KeyEvent.ACTION_DOWN) {
                    exoPlayerController.handleKeyEvent(keyCode)
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (!BuildConfig.IS_TV_PLATFORM) {
            if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
                if (::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive()) {
                    fullscreenChromeClient.onHideCustomView()
                } else if (cinejoyWebView.canGoBack()) {
                    SpatialNavigationEngine.navigateBackCleanly(cinejoyWebView)
                } else {
                    finish()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (isHandledRemoteKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN) {
                val shouldDispatch = isRepeatableRemoteKey(keyCode) || event.repeatCount == 0
                if (shouldDispatch) {
                    handleRemoteKeyDown(keyCode)
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun isRepeatableRemoteKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND -> true
            else -> false
        }
    }

    private fun isHandledRemoteKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_BACK -> true
            else -> false
        }
    }

    private fun handleRemoteKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> SpatialNavigationEngine.moveFocus(cinejoyWebView, "up")
            KeyEvent.KEYCODE_DPAD_DOWN -> SpatialNavigationEngine.moveFocus(cinejoyWebView, "down")
            KeyEvent.KEYCODE_DPAD_LEFT -> SpatialNavigationEngine.moveFocus(cinejoyWebView, "left")
            KeyEvent.KEYCODE_DPAD_RIGHT -> SpatialNavigationEngine.moveFocus(cinejoyWebView, "right")
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> SpatialNavigationEngine.activateFocusedElement(
                cinejoyWebView,
                fullscreenChromeClient.getActiveCustomView()
            ) { fullscreenChromeClient.isFullscreenActive() }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> SpatialNavigationEngine.toggleVideoPlayback(cinejoyWebView)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SpatialNavigationEngine.seekVideo(cinejoyWebView, 10)
            KeyEvent.KEYCODE_MEDIA_REWIND -> SpatialNavigationEngine.seekVideo(cinejoyWebView, -10)
            KeyEvent.KEYCODE_BACK -> {
                if (fullscreenChromeClient.isFullscreenActive()) {
                    SpatialNavigationEngine.handleBackKey(cinejoyWebView) {
                        fullscreenChromeClient.onHideCustomView()
                    }
                } else {
                    SpatialNavigationEngine.handleBackKey(cinejoyWebView) {
                        if (cinejoyWebView.canGoBack()) {
                            SpatialNavigationEngine.navigateBackCleanly(cinejoyWebView)
                        } else {
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun isWatchOrFullscreenActive(): Boolean {
        return isWatchSurfaceTransparent ||
            (::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive()) ||
            (::exoPlayerController.isInitialized && exoPlayerController.isVisible()) ||
            (::cinejoyWebView.isInitialized && cinejoyWebView.url?.contains("/watch/") == true)
    }

    private fun updateSystemUiForRoute(isWatchOrFullscreen: Boolean) {
        if (BuildConfig.IS_TV_PLATFORM || isWatchOrFullscreen) {
            enterFullscreenImmersiveMode()
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, rootContainer)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun enterFullscreenImmersiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, rootContainer)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreenImmersiveMode() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, rootContainer)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() {
        super.onStart()
        if (!isWebViewDestroyed && isForegroundPausedForBackground) {
            isForegroundPausedForBackground = false
            cinejoyWebView.resumeTimers()
            cinejoyWebView.onResume()
            SpatialNavigationEngine.resumeMediaAfterForeground(cinejoyWebView)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isWebViewDestroyed) {
            cinejoyWebView.resumeTimers()
            cinejoyWebView.onResume()
            updateSystemUiForRoute(isWatchOrFullscreenActive())
        }
    }

    override fun onPause() {
        if (!isInPictureInPictureModeCompat()) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isWebViewDestroyed) {
                cinejoyWebView.onPause()
            }
        }
        super.onPause()
    }

    override fun onStop() {
        if (!isInPictureInPictureModeCompat() && !isWebViewDestroyed) {
            isForegroundPausedForBackground = true
            exoPlayerController.releaseForBackground()
            SpatialNavigationEngine.releaseMediaForBackground(cinejoyWebView)
            cinejoyWebView.onPause()
            cinejoyWebView.pauseTimers()
        }
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(splashFallbackRunnable)
        if (::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive()) {
            fullscreenChromeClient.onHideCustomView()
        }
        if (::exoPlayerController.isInitialized) {
            exoPlayerController.closePlayer()
        }
        exitFullscreenImmersiveMode()
        if (::cinejoyWebView.isInitialized && !isWebViewDestroyed) {
            isWebViewDestroyed = true
            cinejoyWebView.removeJavascriptInterface(NATIVE_BRIDGE_NAME)
            rootContainer.removeView(cinejoyWebView)
            cinejoyWebView.stopLoading()
            cinejoyWebView.webChromeClient = null
            cinejoyWebView.webViewClient = WebViewClient()
            cinejoyWebView.loadUrl("about:blank")
            cinejoyWebView.removeAllViews()
            cinejoyWebView.destroy()
        }
        super.onDestroy()
    }
}
