package pk.cinejoy.tv

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object AdBlockerFilter {

    private val blockedHosts: HashSet<String> = hashSetOf(
        "a.wing.st",
        "plausible.io",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "revcontent.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "smartadserver.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "exoclick.com",
        "exosrv.com",
        "juicyads.com",
        "trafficjunky.com",
        "trafficstars.com",
        "adsterra.com",
        "highperformanceformat.com",
        "highcpmgate.com",
        "profitablegatecpm.com",
        "effectivegatecpm.com",
        "topcreativeformat.com",
        "acscdn.com",
        "onclickalgo.com",
        "onclickmax.com",
        "onclkds.com",
        "adcash.com",
        "admaven.com",
        "hilltopads.net",
        "clickadu.com",
        "monetag.com",
        "alwingulla.com",
        "dolohen.com",
        "histats.com",
        "mc.yandex.ru",
        "connect.facebook.net",
        "hotjar.com",
        "clarity.ms",
        "sentry.io",
        "mixpanel.com",
        "segment.io",
        "amplitude.com",
        "scorecardresearch.com",
        "quantserve.com",
        "chartbeat.com"
    )

    private val blockedPathFragments = arrayOf(
        "/pagead/",
        "/popunder",
        "/adserver/",
        "popads.js"
    )

    private val mediaExtensions = arrayOf(
        ".m3u8",
        ".mp4",
        ".ts",
        ".vtt",
        ".srt"
    )

    fun isCineJoyHost(host: String?): Boolean {
        if (host.isNullOrBlank()) {
            return false
        }
        val normalized = host.lowercase(Locale.US)
        return normalized == "cinejoy.pk" || normalized.endsWith(".cinejoy.pk")
    }

    fun isBlocked(uri: Uri?): Boolean {
        if (uri == null) {
            return false
        }
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (isCineJoyHost(host) || host == "tmdb.org" || host.endsWith(".tmdb.org")) {
            return false
        }
        val path = uri.path?.lowercase(Locale.US) ?: ""
        for (ext in mediaExtensions) {
            if (path.endsWith(ext)) {
                return false
            }
        }
        if (matchesBlockedDomain(host)) {
            return true
        }
        val fullTarget = uri.toString().lowercase(Locale.US)
        for (fragment in blockedPathFragments) {
            if (fullTarget.contains(fragment)) {
                return true
            }
        }
        return false
    }

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun matchesBlockedDomain(host: String): Boolean {
        var current = host
        while (current.isNotEmpty()) {
            if (blockedHosts.contains(current)) {
                return true
            }
            val dotIndex = current.indexOf('.')
            if (dotIndex < 0) {
                break
            }
            current = current.substring(dotIndex + 1)
        }
        return false
    }
}
