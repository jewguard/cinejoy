package pk.cinejoy.tv

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import kotlin.math.min

@OptIn(UnstableApi::class)
class CineJoyExoPlayerController(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val isInPipProvider: () -> Boolean,
    private val onStateChanged: (Boolean) -> Unit
) : DefaultLifecycleObserver {

    companion object {
        private const val CONTROLLER_TIMEOUT_MS = 3000
        private const val MAX_PLAYBACK_RETRIES = 4
        private const val SEEK_INCREMENT_MS = 10_000L
    }

    private var exoPlayer: ExoPlayer? = null
    private var activeUrl: String? = null
    private var activeHeaders: Map<String, String> = emptyMap()
    private var savedPositionMs: Long = 0L
    private var resumeAfterForeground = false
    private var retryCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRetryRunnable: Runnable? = null

    init {
        playerView.controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
        playerView.controllerAutoShow = true
        playerView.controllerHideOnTouch = true
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retryCount = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = activeUrl ?: run {
                closePlayer()
                return
            }
            if (retryCount >= MAX_PLAYBACK_RETRIES) {
                closePlayer()
                return
            }
            val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: savedPositionMs
            savedPositionMs = pos
            val delayMs = 500L shl min(retryCount, 3)
            retryCount++
            cancelPendingRetry()
            val retryTask = Runnable {
                if (activeUrl == url && !activity.isDestroyed && !activity.isFinishing) {
                    prepareAndPlay(url, activeHeaders, savedPositionMs)
                }
            }
            pendingRetryRunnable = retryTask
            mainHandler.postDelayed(retryTask, delayMs)
        }
    }

    fun isVisible(): Boolean = playerView.visibility == View.VISIBLE

    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    fun openStream(url: String, headers: Map<String, String>, startPositionMs: Long = 0L) {
        if (url.isBlank()) {
            return
        }
        cancelPendingRetry()
        activeUrl = url
        activeHeaders = headers
        savedPositionMs = startPositionMs.coerceAtLeast(0L)
        retryCount = 0
        resumeAfterForeground = true
        playerView.visibility = View.VISIBLE
        playerView.requestFocus()
        playerView.showController()
        prepareAndPlay(url, headers, savedPositionMs)
        onStateChanged(true)
    }

    fun closePlayer() {
        cancelPendingRetry()
        activeUrl = null
        activeHeaders = emptyMap()
        savedPositionMs = 0L
        retryCount = 0
        resumeAfterForeground = false
        releasePlayerInstance()
        playerView.visibility = View.GONE
        onStateChanged(false)
    }

    fun releaseForBackground() {
        if (isInPipProvider()) {
            return
        }
        cancelPendingRetry()
        val player = exoPlayer
        if (player != null) {
            savedPositionMs = player.currentPosition.coerceAtLeast(0L)
            resumeAfterForeground = activeUrl != null
            releasePlayerInstance()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val url = activeUrl
        if (resumeAfterForeground && !url.isNullOrBlank() && exoPlayer == null) {
            playerView.visibility = View.VISIBLE
            prepareAndPlay(url, activeHeaders, savedPositionMs)
            onStateChanged(true)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        releaseForBackground()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        closePlayer()
    }

    fun handleKeyEvent(keyCode: Int): Boolean {
        if (!isVisible()) {
            return false
        }
        val player = exoPlayer
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    closePlayer()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player != null) {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                }
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (player != null) {
                    val target = (player.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0L)
                    player.seekTo(target)
                }
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (player != null) {
                    val duration = player.duration
                    val rawTarget = player.currentPosition + SEEK_INCREMENT_MS
                    val target = if (duration > 0L) min(rawTarget, duration) else rawTarget
                    player.seekTo(target)
                }
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                playerView.showController()
                return true
            }
            else -> return false
        }
    }

    private fun prepareAndPlay(url: String, headers: Map<String, String>, positionMs: Long) {
        releasePlayerInstance()

        val renderersFactory = DefaultRenderersFactory(activity)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        val bandwidthMeter = DefaultBandwidthMeter.Builder(activity).build()
        val trackSelector = DefaultTrackSelector(activity)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .setBackBuffer(10_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpFactory = OkHttpDataSource.Factory(ResilientNetworkClient.okHttpClient)
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(ResilientNetworkClient.ResilientLoadErrorPolicy())

        val player = ExoPlayer.Builder(activity, renderersFactory)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exoPlayer = player
        player.addListener(playerListener)
        playerView.player = player
        playerView.controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
        playerView.showController()

        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem, positionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
    }

    private fun releasePlayerInstance() {
        val player = exoPlayer ?: return
        exoPlayer = null
        player.removeListener(playerListener)
        try {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
        } catch (_: Throwable) {
        }
        playerView.player = null
        try {
            player.release()
        } catch (_: Throwable) {
        }
    }

    private fun cancelPendingRetry() {
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }
}
