package com.linxi.diary.data

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WatchPlaybackState(
    val url: String = "",
    val title: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Optional local playback for Together Watching.
 *
 * The room never receives a playback credential from this class. A user may
 * paste a direct HTTPS media URL, in which case Media3 can play it locally;
 * page URLs (YouTube, Bilibili, and similar sites) are opened in the system
 * browser by the screen instead. Keeping this boundary explicit avoids
 * pretending that a web page URL is a downloadable video.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object WatchPlaybackManager {
    private val state = MutableStateFlow(WatchPlaybackState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var initialized = false

    val stateFlow: StateFlow<WatchPlaybackState> = state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        player = ExoPlayer.Builder(context.applicationContext).build().also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    state.value = state.value.copy(playing = isPlaying, loading = false, error = null)
                    if (isPlaying) startProgressTicker() else progressJob?.cancel()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val duration = exo.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                        ?.coerceAtLeast(0L) ?: state.value.durationMs
                    state.value = state.value.copy(
                        loading = playbackState == Player.STATE_BUFFERING,
                        durationMs = duration,
                    )
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    state.value = state.value.copy(
                        playing = false,
                        loading = false,
                        error = "视频无法在本机播放，可尝试用系统浏览器打开",
                    )
                }
            })
        }
        initialized = true
    }

    fun canPlayDirect(url: String): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("https://", ignoreCase = true)) return false
        val path = runCatching { android.net.Uri.parse(clean).path.orEmpty().lowercase() }.getOrDefault("")
        return listOf(".mp4", ".m4v", ".webm", ".mov", ".mkv").any { path.substringBefore('?').endsWith(it) }
    }

    fun play(url: String, title: String, positionMs: Long = 0L) {
        ensureInitialized()
        require(canPlayDirect(url)) { "请输入可直接播放的 HTTPS 视频地址" }
        // Do not let a direct video and the global music player produce two
        // overlapping audio streams. The user's music queue remains intact.
        if (NeteasePlaybackManager.stateFlow.value.playing) {
            runCatching { NeteasePlaybackManager.pause() }
        }
        val clean = url.trim()
        val exo = player ?: return
        if (state.value.url != clean) {
            val item = MediaItem.Builder()
                .setMediaId(clean)
                .setUri(clean)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder().setTitle(title.ifBlank { "一起看" }).build(),
                )
                .build()
            val source = ProgressiveMediaSource.Factory(
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(false)
                    .setUserAgent("LinxiDiary/Watch"),
            ).createMediaSource(item)
            exo.setMediaSource(source, positionMs.coerceAtLeast(0L))
            exo.prepare()
        } else if (kotlin.math.abs(exo.currentPosition - positionMs) > 1_500L) {
            exo.seekTo(positionMs.coerceAtLeast(0L))
        }
        state.value = state.value.copy(url = clean, title = title, loading = true, error = null)
        exo.play()
        startProgressTicker()
    }

    fun pause() {
        if (!initialized) return
        player?.pause()
        refreshProgress()
    }

    fun resume() {
        if (!initialized) return
        if (state.value.url.isNotBlank()) player?.play()
    }

    fun seekTo(positionMs: Long) {
        if (!initialized) return
        player?.seekTo(positionMs.coerceAtLeast(0L))
        refreshProgress()
    }

    fun currentPosition(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: state.value.positionMs

    fun syncRemote(snapshot: ListenState) {
        if (!snapshot.isWatch || snapshot.watchUrl.isBlank()) return
        val local = state.value
        if (local.url != snapshot.watchUrl) {
            player?.stop()
            state.value = local.copy(
                url = snapshot.watchUrl,
                title = snapshot.watchTitle,
                playing = false,
                loading = false,
                positionMs = snapshot.watchPositionMs,
                durationMs = snapshot.watchDurationMs,
            )
            return
        }
        if (kotlin.math.abs(currentPosition() - snapshot.watchPositionMs) > 2_000L) {
            player?.seekTo(snapshot.watchPositionMs)
        }
        if (snapshot.watchPlaying && !state.value.playing) resume()
        if (!snapshot.watchPlaying && state.value.playing) pause()
    }

    fun stopAndClear() {
        progressJob?.cancel()
        player?.stop()
        state.value = WatchPlaybackState()
    }

    private fun ensureInitialized() {
        check(initialized) { "WatchPlaybackManager.init() must be called first" }
    }

    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                refreshProgress()
                delay(500L)
            }
        }
    }

    private fun refreshProgress() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET }
            ?.coerceAtLeast(0L) ?: state.value.durationMs
        state.value = state.value.copy(
            playing = exo.isPlaying,
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
        )
    }
}
