package pk.cinejoy.tv

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
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
    private val onRouteChanged: (WebView, String) -> Unit,
    private val onRenderProcessCrashed: () -> Unit
) : WebViewClient() {

    companion object {
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
            CineJoyApplication.saveLastUrl(view.context, url)
            onPageStart(view, url)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view != null && url != null && url != "about:blank") {
            lastVisitedUrl = url
            CineJoyApplication.saveLastUrl(view.context, url)
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
            CineJoyApplication.saveLastUrl(view.context, url)
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
                onLoadError()
                loadErrorScreen(view, error?.description?.toString() ?: "Network unavailable", failedUrl)
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
            onLoadError()
            loadErrorScreen(view, description ?: "Network unavailable", failingUrl ?: HOME_URL)
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onRenderProcessCrashed()
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
            return true
        }
        if (AdBlockerFilter.isBlocked(uri)) {
            return true
        }
        val host = uri.host
        if (AdBlockerFilter.isCineJoyHost(host)) {
            return false
        }
        if (!isMainFrame) {
            return false
        }
        return true
    }

    private fun loadErrorScreen(view: WebView?, message: String, retryUrl: String) {
        if (view == null) {
            return
        }
        val targetRetryUrl = if (retryUrl.startsWith("https://") || retryUrl.startsWith("http://")) {
            retryUrl
        } else {
            HOME_URL
        }
        val safeMessage = message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val safeTargetUrl = targetRetryUrl
            .replace("\\", "")
            .replace("'", "")
            .replace("\"", "")
        val html = """
            <!doctype html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
                <style>
                    body {
                        margin: 0;
                        padding: 36px;
                        background-color: rgb(5, 5, 5);
                        color: rgb(235, 240, 245);
                        font-family: sans-serif;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 85vh;
                        text-align: center;
                    }
                    h1 {
                        color: rgb(255, 255, 0);
                        font-size: 28px;
                        margin-bottom: 14px;
                    }
                    p {
                        color: rgb(180, 190, 200);
                        font-size: 16px;
                        margin-bottom: 12px;
                    }
                    #retryStatus {
                        color: rgb(140, 150, 160);
                        font-size: 14px;
                        margin-bottom: 24px;
                    }
                    button {
                        background-color: rgb(255, 255, 0);
                        color: rgb(5, 5, 5);
                        border: none;
                        border-radius: 8px;
                        padding: 14px 32px;
                        font-size: 17px;
                        font-weight: bold;
                        cursor: pointer;
                    }
                    button:focus {
                        outline: 3px solid rgb(255, 255, 255);
                        outline-offset: 3px;
                    }
                </style>
            </head>
            <body>
                <h1>CineJoy Connection Issue</h1>
                <p>$safeMessage</p>
                <p id="retryStatus">Retrying automatically...</p>
                <button id="retryBtn" autofocus onclick="window.location.replace('$safeTargetUrl')">Retry Now</button>
                <script>
                    (function() {
                        var attempt = 0;
                        try {
                            attempt = parseInt(sessionStorage.getItem("__cj_nav_retry") || "0", 10) || 0;
                        } catch (e) {}
                        var delayMs = Math.min(3000 * Math.pow(2, Math.min(attempt, 2)), 12000);
                        try {
                            sessionStorage.setItem("__cj_nav_retry", String(attempt + 1));
                        } catch (e2) {}
                        var statusEl = document.getElementById("retryStatus");
                        if (statusEl) {
                            statusEl.textContent = "Retrying in " + Math.round(delayMs / 1000) + "s...";
                        }
                        var btn = document.getElementById("retryBtn");
                        if (btn && typeof btn.focus === "function") {
                            btn.focus();
                        }
                        setTimeout(function() {
                            window.location.replace('$safeTargetUrl');
                        }, delayMs);
                    })();
                </script>
            </body>
            </html>
        """.trimIndent()
        view.loadDataWithBaseURL(HOME_URL, html, "text/html", "utf-8", null)
    }
}
