package pk.cinejoy.tv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

@OptIn(UnstableApi::class)
object ResilientNetworkClient {

    private const val MAX_RETRIES = 5
    private const val BASE_BACKOFF_MS = 300L
    private const val MAX_BACKOFF_MS = 4800L

    private val mediaFragments = arrayOf(
        ".m3u8",
        ".ts",
        ".m4s",
        ".mp4",
        ".vtt",
        ".srt",
        ".aac",
        ".key",
        "/hls/",
        "manifest",
        "segment",
        "chunk"
    )

    private class ExponentialBackoffInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!isRetryEligible(request)) {
                return chain.proceed(request)
            }

            var attempt = 0
            var lastException: IOException? = null
            var response: Response? = null

            while (attempt <= MAX_RETRIES) {
                if (chain.call().isCanceled()) {
                    throw IOException("Canceled")
                }

                try {
                    response?.close()
                    response = chain.proceed(request)
                    if (!shouldRetryStatus(response.code) || attempt == MAX_RETRIES) {
                        return response
                    }
                    response.close()
                    response = null
                } catch (ioe: IOException) {
                    if (chain.call().isCanceled() || attempt == MAX_RETRIES) {
                        throw ioe
                    }
                    lastException = ioe
                }

                val delayMs = min(BASE_BACKOFF_MS * (1L shl attempt), MAX_BACKOFF_MS) +
                    Random.nextLong(0L, 121L)
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw lastException ?: IOException("Interrupted")
                }
                attempt++
            }

            return response ?: throw (lastException ?: IOException("Request failed"))
        }

        private fun isRetryEligible(request: Request): Boolean {
            val method = request.method.uppercase(Locale.US)
            if (method == "GET" || method == "HEAD") {
                return true
            }
            val url = request.url.toString().lowercase(Locale.US)
            for (fragment in mediaFragments) {
                if (url.contains(fragment)) {
                    return true
                }
            }
            return false
        }

        private fun shouldRetryStatus(code: Int): Boolean {
            return code == 408 || code == 429 || code in 500..599
        }
    }

    class ResilientLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int {
            return 8
        }

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val ex = loadErrorInfo.exception
            if (ex is ParserException) {
                return C.TIME_UNSET
            }
            if (ex is HttpDataSource.InvalidResponseCodeException) {
                val code = ex.responseCode
                if (code == 403 || code == 404 || code == 410) {
                    return C.TIME_UNSET
                }
            }
            val exponent = min(maxOf(loadErrorInfo.errorCount - 1, 0), 4)
            return min(1000L * (1L shl exponent), 16000L)
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(ExponentialBackoffInterceptor())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
