package com.linxi.diary.data

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
// onPlayerCommandRequest 的返回值常量定义在 SessionResult 上（Player 上没有
// RESULT_SUCCESS —— 编译期即报 Unresolved reference，CI 已验证过一次）。
import androidx.media3.session.SessionResult
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedHashMap
import com.linxi.diary.util.UserPrefs
import com.linxi.diary.util.Logs

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
    val resolving: Boolean = false,
    val error: String? = null,
)

/**
 * 与 NeriPlayer 相同的职责边界：播放器留在设备上，房间只同步逻辑曲目和时间轴。
 * 这里不把播放 URL 放进房间状态，也不把 URL 写入持久化存储。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object NeteasePlaybackManager {
    private val state = MutableStateFlow(NeteasePlaybackState())
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var musicCache: SimpleCache
    private var initialized = false
    private var currentUrl: String? = null
    private var progressJob: Job? = null
    private var skipJob: Job? = null
    private var skipGeneration = 0L
    private val lyricsCache = LinkedHashMap<Long, NeteaseLyrics>(16, 0.75f, true)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val stateFlow: StateFlow<NeteasePlaybackState> = state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        val repeatMode = when (UserPrefs.musicRepeatMode.lowercase()) {
            "one" -> NeteaseRepeatMode.ONE
            "off" -> NeteaseRepeatMode.OFF
            else -> NeteaseRepeatMode.ALL
        }
        state.value = state.value.copy(repeatMode = repeatMode, shuffle = UserPrefs.musicShuffle)
        musicCache = SimpleCache(
            File(appContext.cacheDir, MUSIC_CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(MUSIC_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(appContext),
        )

        player = ExoPlayer.Builder(appContext).build().apply {
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
                        resolving = false,
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
                    val host = runCatching { currentUrl?.let { java.net.URI(it).host } }.getOrNull()
                    Logs.e(
                        "NeteasePlayback",
                        "ExoPlayer error code=${error.errorCode} name=${error.errorCodeName} host=${host ?: "-"}",
                        error,
                    )
                    state.value = state.value.copy(
                        playing = false,
                        loading = false,
                        resolving = false,
                        error = "网易云音频播放失败，请检查账号权限或网络",
                    )
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    state.value = state.value.copy(positionMs = 0L)
                }
            })
        }
        // Use the platform media session so Android, not app-specific “island”
        // settings, owns playback controls and real-time media presentation.
        // The queue is resolved lazily (网易云 URLs expire), so ExoPlayer only
        // has the current MediaItem and would otherwise advertise no next/prev
        // commands to the lock screen, desktop and headset controllers.
        // Advertise those standard commands and route them through the same
        // async resolver used by the in-app controls.
        val transportButtons = listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .setDisplayName("上一首")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .setDisplayName("下一首")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )
        // ★ 媒体按钮的真正执行入口是 Player，而不是 Session callback ★
        //
        // 厂商流体云/锁屏/耳机上的「上一首/下一首」按下后，MediaButton 的
        // KeyEvent 会被 media3 默认解析成 `player.seekToPreviousMediaItem()`
        // **直接打在 Player 上**，`onPlayerCommandRequest` 只拦得住 controller
        // 显式发起的命令，拦不住这条默认路径。而本项目播放队列是懒解析的
        // （网易云 URL 会过期，Player 里始终只有当前一首），ExoPlayer 收到
        // seekToNext/Previous 时既没有下一项也没有上一项 → 静默 no-op，
        // 这正是管理员「按钮看得见、点了没反应」的根因。
        //
        // 官方推荐做法：用 ForwardingPlayer 包住真 player 交给 Session，
        // 把这几个 seek 命令统一重定向到异步解析器（skipToNext/Previous）。
        // 无论命令来自 MediaButton、controller 还是应用内控件，都收敛到
        // 同一条链路；onPlayerCommandRequest 因此只负责放行（声明支持），
        // 不再自己执行——否则 ForwardingPlayer 会再执行一次造成双跳。
        val sessionPlayer = object : ForwardingPlayer(player) {
            // 注：WINDOW 版命令常量（COMMAND_SEEK_TO_PREVIOUS_WINDOW 等）在
            // media3 1.10 已从 Player.Commands 的合法集合移除（lint WrongConstant
            // 直接报错），Player 接口上对应方法也没了。MediaButton/流体云的
            // 上一首/下一首语义走 MEDIA_ITEM 命令，下面的集合已完整覆盖。
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                super.isCommandAvailable(command) || when (command) {
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT -> true

                    else -> false
                }

            // 队列懒解析：只有当前 MediaItem，原生 seek 到前后曲目必然 no-op。
            // 全部改走异步 URL 解析（skipToNext/Previous 内部自行取歌单）。
            override fun seekToPreviousMediaItem() {
                skipToPrevious()
            }

            override fun seekToNextMediaItem() {
                skipToNext()
            }

            override fun seekToPrevious() {
                skipToPrevious()
            }

            override fun seekToNext() {
                skipToNext()
            }
            // 注：seekToPreviousWindow/seekToNextWindow 两个方法在 media3 1.10
            // 的 Player 接口里已随 WINDOW 命令一起移除（只剩 deprecated 常量），
            // 不能也不需要 override —— 框架内不会再把命令路由到它们。
        }
        mediaSession = MediaSession.Builder(appContext, sessionPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val base = super.onConnect(session, controller)
                    val commands = base.availablePlayerCommands.buildUpon()
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .build()
                    return MediaSession.ConnectionResult.accept(
                        base.availableSessionCommands,
                        commands,
                    )
                }

                // 只放行、不执行：真正执行发生在 ForwardingPlayer 的重定向里
                // （见上）。此前在这里直接 skipToPrevious 并返回 SUCCESS，
                // media3 收到 SUCCESS 后还会继续对 player 执行一次同名命令，
                // 接入 ForwardingPlayer 后会变成「跳两首」。
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int = when (playerCommand) {
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT -> SessionResult.RESULT_SUCCESS

                    else -> super.onPlayerCommandRequest(session, controller, playerCommand)
                }
            })
            // Some vendor desktop surfaces read media-button preferences rather
            // than the compact NotificationCompat action list.
            .setMediaButtonPreferences(transportButtons)
            // ★ OPPO / 一加「流体云」(灵动岛) 补上「下一首」按钮 ★
            //
            // 部分厂商的流体云/媒体卡片读取的不是 MediaButtonPreferences，
            // 而是 MediaSession 的 **custom layout**（media3 1.2+ 新增的自定义
            // 布局槽位）。此前只配了 preferences + 通知紧凑视图，
            // 于是流体云上只出现「上一首/播放」，独独缺「下一首」——
            // 这正是管理员反馈的现象。
            //
            // 这里显式声明 3 个槽位：上一首 / 播放暂停 / 下一首。
            // 播放暂停用 `COMMAND_PLAY_PAUSE`（会随 ExoPlayer 状态自动在
            // 播放/暂停图标间切换），前后用既有的异步解析命令，
            // 与 onPlayerCommandRequest 的分发保持一致。
            .setCustomLayout(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .setDisplayName("上一首")
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    // 注意：media3 没有 ICON_PLAY_PAUSE 这个字形，中间槽位用
                    // ICON_PLAY + COMMAND_PLAY_PAUSE —— 厂商渲染时会依播放状态
                    // 自动切换成播放/暂停，这也正是官方示例的写法。
                    // 槽位常量是 SLOT_CENTRAL（不是 SLOT_CENTER）。
                    CommandButton.Builder(CommandButton.ICON_PLAY)
                        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                        .setDisplayName("播放/暂停")
                        .setSlots(CommandButton.SLOT_CENTRAL)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .setDisplayName("下一首")
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build(),
                ),
            )
            .build()
        MusicNotificationController.init(context, mediaSession)
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

    fun play(
        track: NeteaseTrack,
        url: String,
        positionMs: Long = 0L,
        autoplay: Boolean = true,
        queue: List<NeteaseTrack>? = null,
        queueIndex: Int? = null,
    ) {
        ensureInitialized()
        // A direct play request (for example selecting a new search result or
        // receiving a room-sync correction) supersedes an in-flight next/prev
        // URL lookup. The lookup checks this generation before touching ExoPlayer.
        skipGeneration++
        skipJob?.cancel()
        skipJob = null
        require(url.startsWith("https://", ignoreCase = true)) { "播放地址必须使用 HTTPS" }
        val current = state.value
        val requestedQueue = queue
            ?.distinctBy { it.stableKey }
            ?.filter { it.id > 0L }
            ?.takeIf { it.isNotEmpty() }
        val effectiveQueue = requestedQueue ?: if (current.queue.any { it.stableKey == track.stableKey }) {
            current.queue
        } else {
            current.queue + track
        }
        val effectiveQueueIndex = queueIndex
            ?.takeIf { it in effectiveQueue.indices && effectiveQueue[it].stableKey == track.stableKey }
            ?: effectiveQueue.indexOfFirst { it.stableKey == track.stableKey }
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
                    // 网易云播放地址带短时签名参数，不能直接拿完整 URL 当缓存键。
                    // 逻辑歌曲 + 音质是稳定键；签名地址只作为当前缺失分片的上游地址。
                    .setCustomCacheKey(musicCacheKey(track))
                    .setUri(url)
                    .setMediaMetadata(metadata.build())
                    .build()
            val cookies = NeteaseAccountStore.cookies()
                .filterValues(String::isNotBlank)
                .entries
                .joinToString("; ") { (key, value) -> "$key=$value" }
            val upstreamFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(mapOf("Cookie" to cookies, "Referer" to "https://music.163.com/"))
            val mediaSource = ProgressiveMediaSource.Factory(
                CacheDataSource.Factory()
                    .setCache(musicCache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(musicCache))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            )
                .createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, positionMs.coerceAtLeast(0L))
            player.prepare()
        } else if (positionMs > 0L && kotlin.math.abs(player.currentPosition - positionMs) > 1500L) {
            player.seekTo(positionMs)
        }
        state.value = state.value.copy(
            track = track,
            queue = effectiveQueue,
            queueIndex = effectiveQueueIndex,
            durationMs = track.durationMs.coerceAtLeast(0L),
            playing = autoplay,
            loading = true,
            resolving = false,
            error = null,
        )
        if (autoplay) {
            player.play()
            startProgressTicker()
        } else {
            player.pause()
            progressJob?.cancel()
        }
        refreshProgress()
    }

    /** True only while the current track still has a usable local media source. */
    fun hasSource(trackId: Long): Boolean =
        initialized &&
            state.value.track?.id == trackId &&
            state.value.error == null &&
            !currentUrl.isNullOrBlank() &&
            player.currentMediaItem?.mediaId == state.value.track?.stableKey

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
            durationMs = distinct[safeIndex].durationMs.coerceAtLeast(0L),
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

    /** 返回队列中上一首/下一首；调用方若要实际播放请使用 [skipToNext]/[skipToPrevious]。 */
    fun adjacentTrack(next: Boolean): NeteaseTrack? {
        val current = state.value
        val target = adjacentTrack(current, next) ?: return null
        val index = current.queue.indexOfFirst { it.stableKey == target.stableKey }
        state.value = current.copy(queueIndex = index, track = target)
        return target
    }

    /** Read the next/previous queue item without mutating local playback state. */
    fun peekAdjacentTrack(next: Boolean): NeteaseTrack? = adjacentTrack(state.value, next)

    private fun adjacentTrack(current: NeteasePlaybackState, next: Boolean): NeteaseTrack? {
        if (current.queue.isEmpty() || current.queueIndex !in current.queue.indices) return null
        if (!next && current.queueIndex == 0) return null
        if (next && current.repeatMode == NeteaseRepeatMode.OFF && current.queueIndex == current.queue.lastIndex) return null
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
        return current.queue[index]
    }

    /**
     * 让 UI、系统媒体通知和耳机按键共用同一条切歌路径。
     *
     * 播放地址解析是网络操作，不能在 BroadcastReceiver 或点击回调里同步执行；
     * 这里把它放进播放器自己的监督作用域，并以 resolving 状态抑制重复点击。
     */
    fun skipToNext(): Boolean {
        if (ListenSessionController.controlAdjacentTrack(next = true)) return true
        return skipTo(next = true)
    }

    fun skipToPrevious(): Boolean {
        if (ListenSessionController.controlAdjacentTrack(next = false)) return true
        return skipTo(next = false)
    }

    private fun skipTo(next: Boolean): Boolean {
        ensureInitialized()
        if (state.value.resolving) return false
        val current = state.value
        val target = adjacentTrack(current, next) ?: return false
        val generation = ++skipGeneration
        state.value = current.copy(
            loading = true,
            resolving = true,
            error = null,
        )
        skipJob?.cancel()
        skipJob = playbackScope.launch {
            try {
                val url = NeteaseClient.resolvePlaybackUrl(target.id)
                // CancellationException must not be converted into a visible
                // playback error, and a newer direct-play request must win.
                if (!isActive || generation != skipGeneration) return@launch
                skipJob = null
                play(target, url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != skipGeneration) return@launch
                state.value = state.value.copy(
                    // Resolving a neighbouring track must not pause the song
                    // that is still on the player when the lookup fails.
                    playing = player.isPlaying,
                    loading = false,
                    resolving = false,
                    error = error.message ?: "歌曲解析失败，请重试",
                )
            }
        }
        return true
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
        skipToNext()
    }

    fun pause() {
        if (ListenSessionController.controlPlaybackToggle(playing = false)) return
        pauseLocal()
    }

    /** Apply a room-authoritative pause without sending a command back. */
    internal fun pauseLocal() {
        ensureInitialized()
        player.pause()
        progressJob?.cancel()
        refreshProgress()
    }

    fun resume() {
        if (ListenSessionController.controlPlaybackToggle(playing = true)) return
        resumeLocal()
    }

    /** Apply a room-authoritative play without sending a command back. */
    internal fun resumeLocal() {
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
        skipGeneration++
        skipJob?.cancel()
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
            resolving = false,
            error = null,
        )
    }

    /** 最近一次「用户主动 seek」（拖进度条/点歌词）的时间戳。
     *
     *  一起听用它实现网易云式「拖动防拽回」：拖动进行中/刚拖完的短窗口里，
     *  远程快照不再把进度强行拉回对方位置——否则手指还没离开进度条就被
     *  5 秒一次的 host 心跳拽回去，拖动根本没法完成。远程自动对齐走
     *  [seekToLocal]，不会点亮这个时间戳。 */
    @Volatile
    var lastUserSeekAtMs: Long = 0L
        private set

    fun seekTo(positionMs: Long) {
        // Seeking is emitted repeatedly while the full-player scrubber is
        // dragged.  Keep it local here; the settled position is published by
        // ListenSessionController.routeSeek when the gesture ends (网易云式：
        // 拖动全程本地跟手，松手才广播一次最终位置，不洪泛控制端点).
        lastUserSeekAtMs = System.currentTimeMillis()
        seekToLocal(positionMs)
    }

    internal fun seekToLocal(positionMs: Long) {
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

    private const val MUSIC_CACHE_DIR = "music_cache"
    // 音频是可再生的第三方缓存，给出明确上限并交给 LRU 淘汰，避免长期播放无限占用磁盘。
    private const val MUSIC_CACHE_MAX_BYTES = 128L * 1024L * 1024L

    private fun musicCacheKey(track: NeteaseTrack): String =
        "netease:${track.id}:${UserPrefs.musicQuality.ifBlank { "exhigh" }}"
}
