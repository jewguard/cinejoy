package pk.cinejoy.tv

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

class CineJoyWebViewClient(
    private val onPageStart: (WebView, String) -> Unit,
    private val onLoadError: () -> Unit,
    private val onPageReady: (WebView, String) -> Unit,
    private val onRouteChanged: (WebView, String) -> Unit
) : WebViewClient() {

    companion object {
        private const val TAG = "CineJoyWebView"
        private const val HOME_URL = "https://cinejoy.pk"
    }

    private var lastVisitedUrl: String = ""

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        if (AdBlockerFilter.isBlocked(url)) {
            return AdBlockerFilter.createEmptyResponse()
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return handleNavigation(request?.url, request?.isForMainFrame ?: true)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return true
        }
        return handleNavigation(Uri.parse(url), true)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (view != null && url != null && url != "about:blank") {
            lastVisitedUrl = url
            onPageStart(view, url)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view != null && url != null && url != "about:blank") {
            Log.i(TAG, "Page finished: $url (blocked ads=${AdBlockerFilter.getBlockedCount()})")
            lastVisitedUrl = url
            onPageStart(view, url)
            onPageReady(view, url)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (view != null && url != null && url != "about:blank") {
            val wasWatch = lastVisitedUrl.contains("/watch/")
            val isWatch = url.contains("/watch/")
            lastVisitedUrl = url
            if (wasWatch || isWatch) {
                SpatialNavigationEngine.notifyRouteTransition(view)
            }
            onRouteChanged(view, url)
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        Log.w(TAG, "Proceeding past SSL error url=${error?.url} code=${error?.primaryError}")
        handler?.proceed()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request?.isForMainFrame == true) {
            val failedUrl = request.url?.toString() ?: ""
            if (failedUrl != "about:blank") {
                Log.e(TAG, "Main frame load error code=${error?.errorCode} desc=${error?.description} url=$failedUrl")
                onLoadError()
                loadErrorScreen(view, error?.description?.toString() ?: "Network unavailable")
            }
        }
    }

    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && failingUrl != "about:blank") {
            Log.e(TAG, "Legacy main frame load error code=$errorCode desc=$description url=$failingUrl")
            onLoadError()
            loadErrorScreen(view, description ?: "Network unavailable")
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.e(TAG, "WebView render process gone didCrash=${detail?.didCrash()}")
        } else {
            Log.e(TAG, "WebView render process gone")
        }
        return true
    }

    private fun handleNavigation(uri: Uri?, isMainFrame: Boolean): Boolean {
        if (uri == null) {
            return true
        }
        val rawUrl = uri.toString()
        if (rawUrl == "about:blank") {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: ""
        if (scheme != "http" && scheme != "https") {
            Log.d(TAG, "Blocked non-HTTP scheme navigation: $rawUrl")
            return true
        }
        if (AdBlockerFilter.isBlocked(uri)) {
            Log.d(TAG, "Blocked ad navigation: $rawUrl")
            return true
        }
        val host = uri.host
        if (AdBlockerFilter.isCineJoyHost(host)) {
            return false
        }
        if (!isMainFrame) {
            return false
        }
        Log.d(TAG, "Blocked external main-frame navigation: $rawUrl")
        return true
    }

    private fun loadErrorScreen(view: WebView?, message: String) {
        if (view == null) {
            return
        }
        val safeMessage = message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val html = """
            <!doctype html>
            <html>
            <head>
                <meta charset="utf-8" />
                <style>
                    body {
                        margin: 0;
                        padding: 48px;
                        background-color: rgb(10, 12, 14);
                        color: rgb(235, 240, 245);
                        font-family: sans-serif;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 80vh;
                        text-align: center;
                    }
                    h1 {
                        color: rgb(255, 255, 0);
                        font-size: 32px;
                        margin-bottom: 16px;
                    }
                    p {
                        color: rgb(180, 190, 200);
                        font-size: 18px;
                        margin-bottom: 28px;
                    }
                    button {
                        background-color: rgb(255, 255, 0);
                        color: rgb(10, 12, 14);
                        border: none;
                        border-radius: 8px;
                        padding: 14px 32px;
                        font-size: 18px;
                        font-weight: bold;
                        cursor: pointer;
                    }
                </style>
            </head>
            <body>
                <h1>CineJoy TV Connection Issue</h1>
                <p>$safeMessage</p>
                <button onclick="window.location.replace('$HOME_URL')">Retry Connection</button>
            </body>
            </html>
        """.trimIndent()
        view.loadDataWithBaseURL(HOME_URL, html, "text/html", "utf-8", null)
    }
}
