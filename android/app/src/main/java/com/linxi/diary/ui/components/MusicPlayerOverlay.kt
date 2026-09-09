package com.linxi.diary.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.data.NeteaseClient
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteasePlaybackState
import com.linxi.diary.data.NeteaseRepeatMode
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A single playback surface shared by every main tab.
 *
 * The mini surface is deliberately a sibling of the pager rather than an item in
 * MusicHomeScreen.  That makes the playback contract global and prevents a page
 * switch from destroying the user's current controls.  The expanded surface uses
 * the same state and controls; it is not a second player.
 */
@Composable
fun MusicPlayerOverlay(
    state: NeteasePlaybackState,
    onOpenLyrics: (NeteaseTrack) -> Unit,
    onOpenMusic: () -> Unit,
) {
    val track = state.track ?: return
    var expanded by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tokens = LocalLxSurfaceTokens.current

    fun toggle() {
        if (state.playing) NeteasePlaybackManager.pause() else NeteasePlaybackManager.resume()
    }

    fun adjacent(next: Boolean) {
        if (resolving) return
        val target = NeteasePlaybackManager.adjacentTrack(next) ?: return
        resolving = true
        scope.launch {
            runCatching { NeteaseClient.resolvePlaybackUrl(target.id) }
                .onSuccess { url -> NeteasePlaybackManager.play(target, url) }
            resolving = false
        }
    }

    BackHandler(enabled = expanded) { expanded = false }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !expanded,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            MiniPlaybackCard(
                state = state,
                onExpand = { expanded = true },
                onToggle = ::toggle,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.fillMaxSize(),
            // 1.2 规范：完整播放器从底部上移 280ms，收起反向 220ms。
            // 明确写出时长，避免使用 Compose 默认动画导致不同版本观感漂移。
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 280),
                initialOffsetY = { it },
            ) + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 220),
                targetOffsetY = { it },
            ) + fadeOut(animationSpec = tween(180)),
        ) {
            FullPlaybackSheet(
                state = state,
                resolving = resolving,
                onCollapse = { expanded = false },
                onOpenMusic = {
                    // Navigation belongs to the shell, but the sheet owns its
                    // visibility. Collapse first so the destination is not
                    // covered by a second full-screen player surface.
                    expanded = false
                    onOpenMusic()
                },
                onOpenLyrics = {
                    expanded = false
                    onOpenLyrics(track)
                },
                onPrevious = { adjacent(false) },
                onNext = { adjacent(true) },
                onToggle = ::toggle,
                onShuffle = { NeteasePlaybackManager.toggleShuffle() },
                onRepeat = { NeteasePlaybackManager.cycleRepeatMode() },
                canvas = tokens.canvas,
            )
        }
    }
}

@Composable
private fun MiniPlaybackCard(
    state: NeteasePlaybackState,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
) {
    val track = state.track ?: return
    val tokens = LocalLxSurfaceTokens.current
    LxSurface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 76.dp),
        tone = LxSurfaceTone.Floating,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { contentDescription = "展开播放器：${track.title}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(tokens.surface, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MiuixIcons.Music, contentDescription = "音乐封面", tint = MiuixTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, fontSize = 14.sp)
                Text(track.artist, maxLines = 1, fontSize = 12.sp, color = tokens.textSecondary)
            }
            LxButton(
                onClick = onToggle,
                modifier = Modifier.size(48.dp),
                horizontalPadding = 0,
                content = {
                    Icon(
                        if (state.playing) MiuixIcons.Pause else MiuixIcons.Play,
                        contentDescription = if (state.playing) "暂停" else "播放",
                    )
                },
            )
        }
    }
}

@Composable
private fun FullPlaybackSheet(
    state: NeteasePlaybackState,
    resolving: Boolean,
    onCollapse: () -> Unit,
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
    val context = LocalContext.current
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
            LxButton(
                onClick = onCollapse,
                variant = LxButtonVariant.Neutral,
                modifier = Modifier.size(48.dp),
                horizontalPadding = 0,
                content = {
                    Icon(MiuixIcons.Basic.Close, contentDescription = "收起播放器")
                },
            )
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
                if (maxHeight < 760.dp) 220.dp else 288.dp,
            )
            LxSurface(
                modifier = Modifier
                    .width(coverSize)
                    .height(coverSize)
                    .align(Alignment.CenterHorizontally),
                tone = LxSurfaceTone.Raised,
                shape = RoundedCornerShape(28.dp),
            ) {
                if (track.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(track.coverUrl).build(),
                        imageLoader = AppImageLoader.get(context),
                        contentDescription = "当前歌曲封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            MiuixIcons.Music,
                            contentDescription = "当前歌曲封面占位",
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
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
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(MiuixIcons.Tune, "随机播放${if (state.shuffle) "（已开启）" else ""}", onShuffle)
            PlayerIconButton(MiuixIcons.ChevronBackward, "上一首", onPrevious, enabled = !resolving)
            PlayerIconButton(
                if (state.playing) MiuixIcons.Pause else MiuixIcons.Play,
                if (state.playing) "暂停" else "播放",
                onToggle,
                primary = true,
            )
            PlayerIconButton(MiuixIcons.ChevronForward, "下一首", onNext, enabled = !resolving)
            PlayerIconButton(MiuixIcons.Recent, repeatDescription(state.repeatMode), onRepeat)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            LxButton(
                onClick = onOpenLyrics,
                variant = LxButtonVariant.Neutral,
                modifier = Modifier.weight(1f),
                content = { Icon(MiuixIcons.Messages, contentDescription = "歌词") },
            )
            LxButton(
                onClick = onOpenMusic,
                variant = LxButtonVariant.Neutral,
                modifier = Modifier.weight(1f),
                content = { Icon(MiuixIcons.Playlist, contentDescription = "播放队列") },
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
                detectTapGestures { offset ->
                    if (durationMs <= 0L || size.width <= 0f) return@detectTapGestures
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((durationMs * fraction).toLong())
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

@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    LxButton(
        onClick = onClick,
        enabled = enabled,
        variant = if (primary) LxButtonVariant.Positive else LxButtonVariant.Neutral,
        modifier = Modifier.size(if (primary) 64.dp else 48.dp),
        horizontalPadding = 0,
        content = { Icon(icon, contentDescription = description) },
    )
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
