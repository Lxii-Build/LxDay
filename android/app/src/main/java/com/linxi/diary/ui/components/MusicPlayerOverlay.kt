package com.linxi.diary.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteasePlaybackState
import com.linxi.diary.data.NeteaseRepeatMode
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import com.linxi.diary.ui.components.LxClickableSurface
import com.linxi.diary.ui.components.LxIcon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A single compact playback dock shared by every main tab.
 *
 * The mini surface is deliberately a sibling of the pager rather than an item in
 * MusicHomeScreen.  That makes the playback contract global and prevents a page
 * switch from destroying the user's current controls. The full player is a real
 * navigation destination and is rendered by [MusicPlayerScreen] below.
 */
@Composable
fun MusicPlayerOverlay(
    state: NeteasePlaybackState,
    onOpenPlayer: () -> Unit,
) {
    if (state.track == null) return
    Box(Modifier.fillMaxSize()) {
        MiniPlaybackCard(
            state = state,
            onOpen = onOpenPlayer,
            onToggle = {
                if (state.playing) NeteasePlaybackManager.pause() else NeteasePlaybackManager.resume()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Full player destination. It owns the system back action while it is on the
 * navigation stack, so returning from it goes to the page that opened it
 * instead of falling through to MainTabs' "再按一次退出" handler.
 */
@Composable
fun MusicPlayerScreen(
    state: NeteasePlaybackState,
    onBack: () -> Unit,
    onOpenLyrics: (NeteaseTrack) -> Unit,
    onOpenMusic: () -> Unit,
) {
    val track = state.track
    BackHandler { onBack() }
    if (track == null) return

    FullPlaybackSheet(
        state = state,
        resolving = state.resolving,
        onBack = onBack,
        onOpenMusic = onOpenMusic,
        onOpenLyrics = { onOpenLyrics(track) },
        onPrevious = { NeteasePlaybackManager.skipToPrevious() },
        onNext = { NeteasePlaybackManager.skipToNext() },
        onToggle = {
            if (state.playing) NeteasePlaybackManager.pause() else NeteasePlaybackManager.resume()
        },
        onShuffle = { NeteasePlaybackManager.toggleShuffle() },
        onRepeat = { NeteasePlaybackManager.cycleRepeatMode() },
        canvas = LocalLxSurfaceTokens.current.canvas,
    )
}

@Composable
private fun MiniPlaybackCard(
    state: NeteasePlaybackState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.track ?: return
    val tokens = LocalLxSurfaceTokens.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    LxClickableSurface(
        modifier = modifier
            .navigationBarsPadding()
            .widthIn(max = 312.dp)
            .fillMaxWidth()
            .padding(bottom = 84.dp)
            .height(64.dp),
        tone = LxSurfaceTone.Floating,
        shape = RoundedCornerShape(32.dp),
        onClick = onOpen,
        contentDescription = "展开播放器：${track.title}",
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeteaseTrackCover(
                track,
                modifier = Modifier
                    .size(44.dp),
                description = "音乐封面，点击打开播放器",
                shape = RoundedCornerShape(12.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp,
                )
                Text(
                    track.artist.ifBlank { "网易云音乐" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = tokens.textSecondary,
                )
            }
            LxIconButton(
                onClick = onToggle,
                variant = LxButtonVariant.Positive,
                shape = RoundedCornerShape(24.dp),
                edgeRadius = 24.dp,
                modifier = Modifier.size(48.dp),
                contentDescription = if (state.playing) "暂停" else "播放",
            ) {
                LxIcon(
                    if (state.playing) MiuixIcons.Pause else MiuixIcons.Play,
                    contentDescription = if (state.playing) "暂停" else "播放",
                )
            }
        }
    }
}

@Composable
private fun FullPlaybackSheet(
    state: NeteasePlaybackState,
    resolving: Boolean,
    onBack: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenLyrics: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    canvas: androidx.compose.ui.graphics.Color,
) {
    val track = state.track ?: return
    val tokens = LocalLxSurfaceTokens.current
    val duration = state.durationMs.coerceAtLeast(0L)
    val progress = if (duration > 0L) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(canvas)
            .systemBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackAction(onBack)
            Spacer(Modifier.weight(1f))
            LxButton(
                text = "音乐库",
                onClick = onOpenMusic,
                variant = LxButtonVariant.Neutral,
                horizontalPadding = 14,
            )
        }
        Spacer(Modifier.height(26.dp))
        // The cover is bounded by both width and height.  A fixed 260dp block
        // made the controls unreachable on 360x640 devices; the sheet now
        // scrolls and reduces the cover before sacrificing the primary action.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverSize = minOf(
                maxWidth,
                if (screenHeight < 760.dp) 220.dp else 288.dp,
            )
            LxSurface(
                modifier = Modifier
                    .width(coverSize)
                    .height(coverSize)
                    .align(Alignment.Center),
                tone = LxSurfaceTone.Raised,
                shape = RoundedCornerShape(28.dp),
            ) {
                LxClickableSurface(
                    modifier = Modifier.fillMaxSize(),
                    tone = LxSurfaceTone.Flat,
                    shape = RoundedCornerShape(28.dp),
                    onClick = onOpenLyrics,
                    contentDescription = "打开歌词：${track.title}",
                ) {
                    NeteaseTrackCover(
                        track,
                        modifier = Modifier.fillMaxSize(),
                        description = "当前歌曲封面，点击打开歌词",
                        shape = RoundedCornerShape(28.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(track.title, fontSize = 24.sp)
        Text(track.artist, fontSize = 14.sp, color = tokens.textSecondary)
        Spacer(Modifier.height(18.dp))
        MusicProgressScrubber(
            progress = progress,
            durationMs = duration,
            onSeek = { position -> NeteasePlaybackManager.seekTo(position) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${formatMusicPosition(state.positionMs)} / ${if (duration > 0) formatMusicPosition(duration) else "--:--"}",
            fontSize = 12.sp,
            color = tokens.textSecondary,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlButton(
                icon = MiuixIcons.Tune,
                label = "随机",
                description = "随机播放${if (state.shuffle) "（已开启）" else ""}",
                onClick = onShuffle,
            )
            PlayerControlButton(
                icon = MiuixIcons.ChevronBackward,
                label = "上一首",
                description = "上一首",
                onClick = onPrevious,
                enabled = !resolving,
            )
            PlayerControlButton(
                icon = if (state.playing) MiuixIcons.Pause else MiuixIcons.Play,
                label = if (state.playing) "暂停" else "播放",
                description = if (state.playing) "暂停" else "播放",
                onClick = onToggle,
                primary = true,
            )
            PlayerControlButton(
                icon = MiuixIcons.ChevronForward,
                label = "下一首",
                description = "下一首",
                onClick = onNext,
                enabled = !resolving,
            )
            PlayerControlButton(
                icon = MiuixIcons.Recent,
                label = "循环",
                description = repeatDescription(state.repeatMode),
                onClick = onRepeat,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            LxButton(
                text = "歌词",
                onClick = onOpenLyrics,
                variant = LxButtonVariant.Neutral,
                modifier = Modifier.weight(1f),
            )
            LxButton(
                text = "播放队列",
                onClick = onOpenMusic,
                variant = LxButtonVariant.Neutral,
                modifier = Modifier.weight(1f),
            )
        }
        state.error?.let {
            Text(it, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

/**
 * A deliberately small, touch-friendly scrubber.  It is drawn here instead
 * of importing a second Material slider so the player keeps the same Miuix
 * surface vocabulary as the rest of the app.  Tapping the track seeks the
 * shared ExoPlayer instance; no local position state can drift from it.
 */
@Composable
private fun MusicProgressScrubber(
    progress: Float,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val tokens = LocalLxSurfaceTokens.current
    val primary = MiuixTheme.colorScheme.primary
    val safeProgress = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragged = false
                    drag(down.id) { change ->
                        dragged = true
                        change.consume()
                        seekAtOffset(change.position.x, durationMs, size.width, onSeek)
                    }
                    // A short press is a seek as well; drag() returns without
                    // entering its callback when the pointer is released before
                    // touch slop, so taps do not need a second gesture detector.
                    if (!dragged) {
                        seekAtOffset(down.position.x, durationMs, size.width, onSeek)
                    }
                }
            }
            .semantics {
                contentDescription = "播放进度 ${formatMusicPosition((durationMs * safeProgress).toLong())}"
                progressBarRangeInfo = ProgressBarRangeInfo(safeProgress, 0f..1f)
            },
    ) {
        val centerY = size.height / 2f
        val startX = 4.dp.toPx()
        val endX = size.width - 4.dp.toPx()
        val trackWidth = (endX - startX).coerceAtLeast(1f)
        drawLine(
            color = tokens.line,
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = primary,
            start = Offset(startX, centerY),
            end = Offset(startX + trackWidth * safeProgress, centerY),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = primary,
            radius = 7.dp.toPx(),
            center = Offset(startX + trackWidth * safeProgress, centerY),
        )
    }
}

private fun seekAtOffset(
    x: Float,
    durationMs: Long,
    width: Int,
    onSeek: (Long) -> Unit,
) {
    if (durationMs <= 0L || width <= 0) return
    val fraction = (x / width.toFloat()).coerceIn(0f, 1f)
    onSeek((durationMs * fraction).toLong())
}

@Composable
private fun PlayerControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val tokens = LocalLxSurfaceTokens.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(if (primary) 64.dp else 52.dp),
    ) {
        LxButton(
            onClick = onClick,
            enabled = enabled,
            variant = if (primary) LxButtonVariant.Positive else LxButtonVariant.Neutral,
            modifier = Modifier.size(if (primary) 64.dp else 48.dp),
            horizontalPadding = 0,
            content = { LxIcon(icon, contentDescription = description) },
        )
        Text(label, fontSize = 11.sp, maxLines = 1, color = tokens.textSecondary)
    }
}

private fun repeatDescription(mode: NeteaseRepeatMode): String = when (mode) {
    NeteaseRepeatMode.OFF -> "循环关闭"
    NeteaseRepeatMode.ALL -> "列表循环"
    NeteaseRepeatMode.ONE -> "单曲循环"
}

private fun formatMusicPosition(value: Long): String {
    val seconds = (value / 1_000L).coerceAtLeast(0L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
