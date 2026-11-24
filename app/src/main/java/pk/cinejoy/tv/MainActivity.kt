package pk.cinejoy.tv

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CineJoyMain"
        private const val TARGET_URL = "https://cinejoy.pk"
        private const val NATIVE_BRIDGE_NAME = "__cinejoyNative"
        private const val SPLASH_FALLBACK_TIMEOUT_MS = 7000L
        private const val DESKTOP_TV_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var cinejoyWebView: WebView
    private lateinit var fullscreenChromeClient: FullscreenWebChromeClient
    private var splashOverlayView: FrameLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isWebViewDestroyed = false
    private var isWatchSurfaceTransparent = false
    private var isInitialContentReady = false
    private var hoverStep = 0
    private val splashFallbackRunnable = Runnable { markInitialContentReady("timeout") }

    private inner class CineJoyNativeBridge {
        @JavascriptInterface
        fun releaseVideoOverlay() {
            Log.i(TAG, "Recycling WebView video overlay handles")
            Thread {
                runFinalizationPass()
                try {
                    Thread.sleep(120L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runFinalizationPass()
            }.start()
        }

        @JavascriptInterface
        fun setWatchSurfaceTransparent(transparent: Boolean) {
            this@MainActivity.setWatchSurfaceTransparent(transparent)
        }

        @JavascriptInterface
        fun onWebContentRendered() {
            this@MainActivity.markInitialContentReady("web_hydrated")
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
                    val now = android.os.SystemClock.uptimeMillis()
                    for (target in targets) {
                        val tw = if (target.width > 0) target.width.toFloat() else cinejoyWebView.width.toFloat()
                        val th = if (target.height > 0) target.height.toFloat() else cinejoyWebView.height.toFloat()
                        val hx = (tw * 0.45f) + (hoverStep * 7f)
                        val hy = (th * 0.45f) + (hoverStep * 5f)
                        val enterEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_ENTER, hx, hy, 0).apply {
                            source = InputDevice.SOURCE_MOUSE
                        }
                        val hoverEvent = MotionEvent.obtain(now, now + 4L, MotionEvent.ACTION_HOVER_MOVE, hx, hy, 0).apply {
                            source = InputDevice.SOURCE_MOUSE
                        }
                        try {
                            target.dispatchGenericMotionEvent(enterEvent)
                            target.dispatchGenericMotionEvent(hoverEvent)
                        } finally {
                            enterEvent.recycle()
                            hoverEvent.recycle()
                        }
                    }
                }
            }
        }

        private fun runFinalizationPass() {
            Runtime.getRuntime().gc()
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

        val opaqueDark = Color.rgb(5, 5, 5)
        rootContainer.setBackgroundColor(opaqueDark)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            attachBrandedSplashOverlay(opaqueDark)
        }
        enterFullscreenImmersiveMode()
        configureWebViewEngine()

        fullscreenChromeClient = FullscreenWebChromeClient(
            rootContainer = rootContainer,
            webView = cinejoyWebView,
            onEnterFullscreen = {
                setWatchSurfaceTransparent(true)
                enterFullscreenImmersiveMode()
                SpatialNavigationEngine.onFullscreenChanged(cinejoyWebView, true)
            },
            onExitFullscreen = {
                setWatchSurfaceTransparent(cinejoyWebView.url?.contains("/watch/") == true)
                enterFullscreenImmersiveMode()
                SpatialNavigationEngine.onFullscreenChanged(cinejoyWebView, false)
            }
        )

        cinejoyWebView.webViewClient = CineJoyWebViewClient(
            onPageStart = { webView, url ->
                setWatchSurfaceTransparent(url.contains("/watch/"))
                SpatialNavigationEngine.applyFast4kPresets(webView)
            },
            onLoadError = {
                markInitialContentReady("error_fallback")
            },
            onPageReady = { webView, url ->
                setWatchSurfaceTransparent(url.contains("/watch/"))
                SpatialNavigationEngine.injectIntoWebView(webView)
            },
            onRouteChanged = { webView, url ->
                setWatchSurfaceTransparent(url.contains("/watch/"))
                SpatialNavigationEngine.refreshMap(webView)
            }
        )
        cinejoyWebView.webChromeClient = fullscreenChromeClient

        configurePreferred4kDisplayMode()
        Log.i(TAG, "Loading target streaming platform: $TARGET_URL")
        cinejoyWebView.loadUrl(TARGET_URL)
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

    private fun markInitialContentReady(reason: String) {
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
                Log.i(TAG, "Splash screen dismissed cleanly ($reason)")
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
                        Log.i(TAG, "Surface switched to TRANSPARENT for watch playback")
                    } else {
                        val opaqueDark = Color.rgb(5, 5, 5)
                        window.setBackgroundDrawable(ColorDrawable(opaqueDark))
                        rootContainer.setBackgroundColor(opaqueDark)
                        cinejoyWebView.setBackgroundColor(opaqueDark)
                        Log.i(TAG, "Surface switched to OPAQUE for browsing")
                    }
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
            enterFullscreenImmersiveMode()
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
        Log.i(
            TAG,
            "Preferred display mode selected: ${bestMode.physicalWidth}x${bestMode.physicalHeight}@${bestMode.refreshRate}Hz (id=${bestMode.modeId})"
        )
    }

    private fun configureWebViewEngine() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val settings = cinejoyWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = DESKTOP_TV_USER_AGENT
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
        cookieManager.setAcceptThirdPartyCookies(cinejoyWebView, true)

        cinejoyWebView.addJavascriptInterface(CineJoyNativeBridge(), NATIVE_BRIDGE_NAME)
        cinejoyWebView.setLayerType(View.LAYER_TYPE_NONE, null)
        cinejoyWebView.setBackgroundColor(Color.rgb(5, 5, 5))
        cinejoyWebView.isVerticalScrollBarEnabled = false
        cinejoyWebView.isHorizontalScrollBarEnabled = false
        cinejoyWebView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isWebViewDestroyed || !::cinejoyWebView.isInitialized) {
            return super.dispatchKeyEvent(event)
        }
        val keyCode = event.keyCode
        val action = event.action

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

    override fun onResume() {
        super.onResume()
        if (!isWebViewDestroyed) {
            cinejoyWebView.onResume()
            enterFullscreenImmersiveMode()
        }
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isWebViewDestroyed) {
            cinejoyWebView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(splashFallbackRunnable)
        if (::fullscreenChromeClient.isInitialized && fullscreenChromeClient.isFullscreenActive()) {
            fullscreenChromeClient.onHideCustomView()
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
