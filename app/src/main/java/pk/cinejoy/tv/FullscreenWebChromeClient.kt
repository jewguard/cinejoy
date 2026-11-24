package pk.cinejoy.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

class FullscreenWebChromeClient(
    private val rootContainer: FrameLayout,
    private val webView: WebView,
    private val onEnterFullscreen: () -> Unit,
    private val onExitFullscreen: () -> Unit
) : WebChromeClient() {

    companion object {
        private const val TAG_FULLSCREEN = "CineJoyFullscreen"
        private const val TAG_CONSOLE = "CineJoyConsole"
    }

    private var activeCustomView: View? = null
    private var activeCustomViewCallback: CustomViewCallback? = null

    private val defaultPosterBitmap: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
    }

    fun isFullscreenActive(): Boolean = activeCustomView != null

    fun getActiveCustomView(): View? = activeCustomView

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }

        if (activeCustomView != null) {
            onHideCustomView()
        }

        activeCustomView = view
        activeCustomViewCallback = callback

        clearViewBackgrounds(view)
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootContainer.addView(view, layoutParams)
        webView.visibility = View.VISIBLE
        webView.alpha = 0f
        webView.isFocusable = false

        onEnterFullscreen()
        view.requestFocus()
        Log.i(TAG_FULLSCREEN, "Entered fullscreen video mode")
    }

    override fun onHideCustomView() {
        val currentView = activeCustomView
        val currentCallback = activeCustomViewCallback

        activeCustomView = null
        activeCustomViewCallback = null

        if (currentView == null && currentCallback == null) {
            return
        }

        if (currentView != null) {
            (currentView.parent as? ViewGroup)?.removeView(currentView)
        }

        webView.alpha = 1f
        webView.isFocusable = true
        webView.visibility = View.VISIBLE
        webView.requestFocus()

        currentCallback?.onCustomViewHidden()
        onExitFullscreen()
        Log.i(TAG_FULLSCREEN, "Exited fullscreen video mode and restored WebView")
    }

    override fun getDefaultVideoPoster(): Bitmap = defaultPosterBitmap

    private fun clearViewBackgrounds(target: View) {
        if (target !is android.view.SurfaceView) {
            target.setBackgroundColor(Color.TRANSPARENT)
            target.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        if (target is ViewGroup) {
            for (i in 0 until target.childCount) {
                clearViewBackgrounds(target.getChildAt(i))
            }
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        val targetUrl = view?.hitTestResult?.extra
        if (!targetUrl.isNullOrBlank()) {
            val uri = android.net.Uri.parse(targetUrl)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "https" || scheme == "http") && AdBlockerFilter.isCineJoyHost(uri.host)) {
                Log.d(TAG_FULLSCREEN, "Redirecting same-origin popup into main WebView: $targetUrl")
                webView.loadUrl(targetUrl)
            } else {
                Log.d(TAG_FULLSCREEN, "Blocked external popup window attempt: $targetUrl")
            }
        } else {
            Log.d(TAG_FULLSCREEN, "Blocked blank popup window attempt")
        }
        return false
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (consoleMessage != null) {
            val msg = consoleMessage.message()
            val line = "$msg (line ${consoleMessage.lineNumber()})"
            when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG_CONSOLE, line)
                ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG_CONSOLE, line)
                else -> {
                    if (msg.startsWith("[CineJoyNav]")) {
                        Log.i(TAG_CONSOLE, line)
                    } else {
                        Log.d(TAG_CONSOLE, line)
                    }
                }
            }
        }
        return true
    }
}
