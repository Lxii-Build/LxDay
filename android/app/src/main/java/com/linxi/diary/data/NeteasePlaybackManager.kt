package com.linxi.diary.data

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import com.linxi.diary.util.UserPrefs

data class NeteasePlaybackState(
    val track: NeteaseTrack? = null,
    val queue: List<NeteaseTrack> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: NeteaseRepeatMode = NeteaseRepeatMode.ALL,
    val shuffle: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 与 NeriPlayer 相同的职责边界：播放器留在设备上，房间只同步逻辑曲目和时间轴。
 * 这里不把播放 URL 放进房间状态，也不把 URL 写入持久化存储。
 */
@OptIn(UnstableApi::class)
object NeteasePlaybackManager {
    private val state = MutableStateFlow(NeteasePlaybackState())
    private lateinit var player: ExoPlayer
    private var initialized = false
    private var currentUrl: String? = null
    private var progressJob: Job? = null
    private val lyricsCache = LinkedHashMap<Long, NeteaseLyrics>(16, 0.75f, true)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val stateFlow: StateFlow<NeteasePlaybackState> = state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        val repeatMode = when (UserPrefs.musicRepeatMode.lowercase()) {
            "one" -> NeteaseRepeatMode.ONE
            "off" -> NeteaseRepeatMode.OFF
            else -> NeteaseRepeatMode.ALL
        }
        state.value = state.value.copy(repeatMode = repeatMode, shuffle = UserPrefs.musicShuffle)
        player = ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                UserPrefs.musicAutoPause,
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    state.value = state.value.copy(
                        playing = isPlaying,
                        loading = false,
                        error = null,
                    )
                    if (isPlaying) startProgressTicker() else progressJob?.cancel()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    state.value = state.value.copy(
                        loading = playbackState == Player.STATE_BUFFERING,
                        durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L)
                            ?: state.value.durationMs,
                    )
                    if (playbackState == Player.STATE_ENDED) advanceAfterEnd()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    state.value = state.value.copy(
                        playing = false,
                        loading = false,
                        error = "网易云音频播放失败，请检查账号权限或网络",
                    )
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    state.value = state.value.copy(positionMs = 0L)
                }
            })
        }
        MusicNotificationController.init(context)
        playbackScope.launch {
            state.collect { MusicNotificationController.refresh(it) }
        }
        initialized = true
    }

    /** 歌词页加载成功后缓存少量近期曲目，用于播放胶囊显示当前行。 */
    fun cacheLyrics(trackId: Long, lyrics: NeteaseLyrics) {
        if (trackId <= 0L) return
        synchronized(lyricsCache) {
            lyricsCache[trackId] = lyrics
            while (lyricsCache.size > 16) lyricsCache.remove(lyricsCache.entries.first().key)
        }
        MusicNotificationController.refresh(state.value)
    }

    fun currentLyric(): String? {
        val track = state.value.track ?: return null
        val lyrics = synchronized(lyricsCache) { lyricsCache[track.id] } ?: return null
        val index = lyrics.lineAt(state.value.positionMs)
        return lyrics.original.getOrNull(index)?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }
    }

    fun play(track: NeteaseTrack, url: String, positionMs: Long = 0L) {
        ensureInitialized()
        require(url.startsWith("https://", ignoreCase = true)) { "播放地址必须使用 HTTPS" }
        val current = state.value
        val queue = if (current.queue.any { it.stableKey == track.stableKey }) {
            current.queue
        } else {
            current.queue + track
        }
        val queueIndex = queue.indexOfFirst { it.stableKey == track.stableKey }
        val changed = current.track?.stableKey != track.stableKey || currentUrl != url
        if (changed) {
            currentUrl = url
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
            track.coverUrl.takeIf(String::isNotBlank)?.let { cover ->
                metadata.setArtworkUri(android.net.Uri.parse(cover))
            }
            val mediaItem = MediaItem.Builder()
                    .setMediaId(track.stableKey)
                    .setUri(url)
                    .setMediaMetadata(metadata.build())
                    .build()
            val cookies = NeteaseAccountStore.cookies()
                .filterValues(String::isNotBlank)
                .entries
                .joinToString("; ") { (key, value) -> "$key=$value" }
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(mapOf("Cookie" to cookies, "Referer" to "https://music.163.com/"))
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, positionMs.coerceAtLeast(0L))
            player.prepare()
        } else if (positionMs > 0L && kotlin.math.abs(player.currentPosition - positionMs) > 1500L) {
            player.seekTo(positionMs)
        }
        state.value = state.value.copy(
            track = track,
            queue = queue,
            queueIndex = queueIndex,
            loading = true,
            error = null,
        )
        player.play()
        startProgressTicker()
        refreshProgress()
    }

    /** 把搜索/收藏结果放入播放队列，保留当前歌曲位置。 */
    fun setQueue(tracks: List<NeteaseTrack>, startIndex: Int = 0) {
        ensureInitialized()
        val distinct = tracks.distinctBy { it.stableKey }
        if (distinct.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, distinct.lastIndex)
        val current = state.value
        state.value = current.copy(
            queue = distinct,
            queueIndex = safeIndex,
            track = distinct[safeIndex],
        )
    }

    fun setRepeatMode(mode: NeteaseRepeatMode) {
        state.value = state.value.copy(repeatMode = mode)
        UserPrefs.musicRepeatMode = mode.name.lowercase()
    }

    fun cycleRepeatMode(): NeteaseRepeatMode {
        val next = when (state.value.repeatMode) {
            NeteaseRepeatMode.OFF -> NeteaseRepeatMode.ALL
            NeteaseRepeatMode.ALL -> NeteaseRepeatMode.ONE
            NeteaseRepeatMode.ONE -> NeteaseRepeatMode.OFF
        }
        setRepeatMode(next)
        return next
    }

    fun setShuffle(enabled: Boolean) {
        state.value = state.value.copy(shuffle = enabled)
        UserPrefs.musicShuffle = enabled
    }

    fun setAutoPause(enabled: Boolean) {
        UserPrefs.musicAutoPause = enabled
        if (initialized) {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                enabled,
            )
            MusicNotificationController.refresh(state.value)
        }
    }

    fun toggleShuffle(): Boolean {
        val next = !state.value.shuffle
        setShuffle(next)
        return next
    }

    /** 返回队列中上一首/下一首；地址解析和实际播放由页面协程完成。 */
    fun adjacentTrack(next: Boolean): NeteaseTrack? {
        val current = state.value
        if (current.queue.isEmpty() || current.queueIndex !in current.queue.indices) return null
        if (!next && current.queueIndex == 0) return null
        if (next && current.repeatMode == NeteaseRepeatMode.OFF && current.queueIndex == current.queue.lastIndex) return null
        if (current.repeatMode == NeteaseRepeatMode.ONE && next) return current.track
        val index = if (next) {
            if (current.shuffle) {
                current.queue.indices.filterNot { it == current.queueIndex }.randomOrNull()
                    ?: current.queueIndex
            } else {
                (current.queueIndex + 1) % current.queue.size
            }
        } else {
            (current.queueIndex - 1 + current.queue.size) % current.queue.size
        }
        state.value = current.copy(queueIndex = index, track = current.queue[index])
        return current.queue[index]
    }

    fun currentPosition(): Long = if (!initialized) 0L else player.currentPosition.coerceAtLeast(0L)

    private fun advanceAfterEnd() {
        val current = state.value
        if (current.repeatMode == NeteaseRepeatMode.ONE && current.track != null) {
            player.seekTo(0L)
            player.play()
            state.value = current.copy(playing = true, positionMs = 0L, loading = false)
            return
        }
        val next = adjacentTrack(next = true) ?: return
        playbackScope.launch {
            runCatching { NeteaseClient.resolvePlaybackUrl(next.id) }
                .onSuccess { url -> play(next, url) }
                .onFailure { error ->
                    state.value = state.value.copy(
                        playing = false,
                        loading = false,
                        error = error.message ?: "下一首歌曲解析失败",
                    )
                }
        }
    }

    fun pause() {
        ensureInitialized()
        player.pause()
        progressJob?.cancel()
        refreshProgress()
    }

    fun resume() {
        ensureInitialized()
        if (state.value.track != null) {
            player.play()
            startProgressTicker()
        }
    }

    /** 退出网易云账号时清掉正在播放的第三方地址与媒体通知。 */
    fun stopAndClear() {
        if (!initialized) return
        progressJob?.cancel()
        player.stop()
        currentUrl = null
        val current = state.value
        state.value = current.copy(
            track = null,
            queue = emptyList(),
            queueIndex = -1,
            playing = false,
            positionMs = 0L,
            durationMs = 0L,
            loading = false,
            error = null,
        )
    }

    fun seekTo(positionMs: Long) {
        ensureInitialized()
        player.seekTo(positionMs.coerceAtLeast(0L))
        refreshProgress()
    }

    fun refreshProgress(): NeteasePlaybackState {
        if (!initialized) return state.value
        state.value = state.value.copy(
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L)
                ?: state.value.durationMs,
        )
        return state.value
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = playbackScope.launch {
            while (isActive && initialized && player.isPlaying) {
                refreshProgress()
                delay(500L)
            }
        }
    }

    private fun ensureInitialized() {
        check(initialized) { "NeteasePlaybackManager.init must be called from Application" }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
}
