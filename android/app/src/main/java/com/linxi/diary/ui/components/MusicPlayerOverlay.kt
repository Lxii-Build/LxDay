package com.linxi.diary.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.ListenSessionController
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteasePlaybackState
import com.linxi.diary.data.NeteaseRepeatMode
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.ui.navigation.PLAYBACK_CHROME_RESERVED_DP
import com.linxi.diary.ui.navigation.PLAYBACK_CHROME_WIDTH_DP
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import com.linxi.diary.ui.components.LxClickableSurface
import com.linxi.diary.ui.components.LxIcon
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Translate
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

/**
 * 封面的圆角形状。
 *
 * 提成常量是为了保证「外层 LxSurface 的 Raised 高光描边」「中间的可点击
 * Flat 面」「真正的封面图像」三者用**同一个** shape 实例。此前三处各写
 * `RoundedCornerShape(28.dp)`，数值相同但对象不同，加上外层还有
 * BoxWithConstraints 的尺寸取整，视觉上就会出现「封面比框小一圈」的错位感。
 */
private val CoverCornerShape = RoundedCornerShape(28.dp)

/**
 * 播放进度条的平滑过渡时长。
 *
 * ExoPlayer 的进度 tick 是 500ms 一次，直接跳变会让进度点一顿一顿。
 * 用略长于 tick 间隔的动画把它连起来，观感是匀速前进；
 * 拖动 seek 时则用更短的时长，保证「松手立刻到位」不拖沓。
 */
private const val ProgressAnimationMillis = 520
private const val ProgressSeekAnimationMillis = 160

@Composable
private fun MiniPlaybackCard(
    state: NeteasePlaybackState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.track ?: return
    val tokens = LocalLxSurfaceTokens.current
    LxClickableSurface(
        modifier = modifier
            .navigationBarsPadding()
            .widthIn(max = PLAYBACK_CHROME_WIDTH_DP.dp)
            .fillMaxWidth()
            .padding(bottom = PLAYBACK_CHROME_RESERVED_DP.dp)
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
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val duration = state.durationMs.coerceAtLeast(0L)
    val rawProgress = if (duration > 0L) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    // ★ 进度平滑 ★
    //
    // 拖动 seek 时必须「跟手」：如果这里也走 520ms 的动画，用户手指已经到
    // 80% 了、进度条还在半路追，观感是拖不动的。所以用一个 isScrubbing 标志
    // 区分两种来源——拖动期间直接把状态同步过去（snap），松手后再回到平滑。
    var scrubbing by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(if (scrubbing) ProgressSeekAnimationMillis else ProgressAnimationMillis),
        label = "playerProgress",
    )
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
            // ★ 封面呼吸动画 ★
            //
            // 播放时做一次 1.0 → 1.025 的极轻微缩放循环（2200ms 来回），
            // 视觉上像「封面在轻轻起伏」。幅度刻意压得很小：一是大尺寸封面
            // 稍微放大就会溢出/顶到相邻控件，二是这套拟态 UI 的观感是克制的，
            // 大幅脉动会显得廉价。暂停时停在一个固定缩放，不残留动画。
            val breathe = if (state.playing) {
                val transition = rememberInfiniteTransition(label = "coverBreathe")
                val value by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.025f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "coverBreatheScale",
                )
                value
            } else {
                1f
            }
            LxSurface(
                modifier = Modifier
                    .width(coverSize)
                    .height(coverSize)
                    .align(Alignment.Center)
                    // 用 graphicsLayer 缩放而不是动画尺寸：不触发重新测量，
                    // 也不会给封面节点创建离屏图层（RenderEffect 图层在部分
                    // GPU 上会让相邻文本重影，这条路径必须避开）。
                    .graphicsLayer {
                        scaleX = breathe
                        scaleY = breathe
                    },
                tone = LxSurfaceTone.Raised,
                // 圆角与内部 NeteaseTrackCover 必须完全一致，
                // 否则外层 Raised 的高光描边会与封面图像边缘错开一圈
                // （管理员反馈的「封面和中间那个框没对齐」就是这个原因）。
                shape = CoverCornerShape,
            ) {
                LxClickableSurface(
                    modifier = Modifier.fillMaxSize(),
                    tone = LxSurfaceTone.Flat,
                    shape = CoverCornerShape,
                    onClick = onOpenLyrics,
                    contentDescription = "打开歌词：${track.title}",
                ) {
                    NeteaseTrackCover(
                        track,
                        modifier = Modifier.fillMaxSize(),
                        description = "当前歌曲封面，点击打开歌词",
                        shape = CoverCornerShape,
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
            onScrubStart = { scrubbing = true },
            // 松手 = 一次拖动的终点：把最终位置广播进一起听房间（网易云式
            // 「我拖进度，对方立即跟」）。拖动过程中的连续 onSeek 只更新
            // 本地播放器，不会打进房间（否则一次拖动会洪泛几十条命令）；
            // 没有一起听会话时 routeSeek 直接返回 false，零开销。
            onScrubEnd = {
                scrubbing = false
                ListenSessionController.routeSeek(NeteasePlaybackManager.stateFlow.value.positionMs)
            },
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
                // 标签直接带状态：用户不必从图标形状反推「随机开没开」。
                label = if (state.shuffle) "随机开" else "随机",
                description = "随机播放${if (state.shuffle) "（已开启）" else ""}",
                onClick = onShuffle,
                active = state.shuffle,
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
                // 三种循环模式各自成词，不再是一个笼统的「循环」。
                label = when (state.repeatMode) {
                    NeteaseRepeatMode.OFF -> "不循环"
                    NeteaseRepeatMode.ALL -> "列表循环"
                    NeteaseRepeatMode.ONE -> "单曲循环"
                },
                description = repeatDescription(state.repeatMode),
                onClick = onRepeat,
                active = state.repeatMode != NeteaseRepeatMode.OFF,
            )
        }
        Spacer(Modifier.height(18.dp))
        // 底部两个次要入口补上图标 + **清晰文字**。
        //
        // 管理员截图里这两个是「两个白色胶囊 + 一个看不懂的图标」——因为原实现
        // 只放了图标没有文字。这里改成 LxButton 的 text + 前置图标组合，
        // 文字是主信息，图标只做辅助定位。
        //
        // ★ 注意：不要再手动加 Spacer 当间距 ★
        // LxButton 的内容式重载内部已经把 content() 包在
        // `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` 里了
        // （见 LxButton.kt 的注释：此前多个子元素会被画在同一起点，加 Row 是修复）。
        // 如果这里再插一个 `Spacer(Modifier.width(6.dp))`，实际间距会变成
        // 8 + 6 = 14dp，图标和文字离得过远、看起来像两个独立元素。
        // 所以这里只放「图标 + 文字」两个子元素，8dp 间距交给组件。
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            LxButton(
                onClick = onOpenLyrics,
                variant = LxButtonVariant.Neutral,
                modifier = Modifier.weight(1f),
                horizontalPadding = 12,
            ) {
                LxIcon(
                    imageVector = MiuixIcons.Translate,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.size(18.dp),
                )
                Text("歌词", fontSize = 14.sp, maxLines = 1)
            }
            LxButton(
                onClick = onOpenMusic,
                variant = LxButtonVariant.Neutral,
                modifier = Modifier.weight(1f),
                horizontalPadding = 12,
            ) {
                LxIcon(
                    imageVector = MiuixIcons.Playlist,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.size(18.dp),
                )
                Text("播放队列", fontSize = 14.sp, maxLines = 1)
            }
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
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
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
                    // 按下即视为开始拖动：让进度动画立刻切到「跟手」档，
                    // 否则手指已经划出去了、进度点还在慢慢爬。
                    onScrubStart()
                    var dragged = false
                    try {
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
                    } finally {
                        // finally 保证被打断（手势取消/页面滚走）时也能复位，
                        // 否则 scrubbing 会永久卡在 true，进度条再也不平滑。
                        onScrubEnd()
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

/**
 * 单个播放控制按钮 = 圆形图标按钮 + **始终可见的文字标签**。
 *
 * 管理员反馈「有些按钮看不懂有什么用」——根因是这套 miuix 图标库里**没有**
 * 随机/上一首/下一首/循环这几个音乐语义图标（已核对 miuix-icons 0.9.3 的
 * 完整图标清单：不存在 Shuffle / SkipNext / SkipPrevious / Repeat / RepeatOnce）。
 * 此前用 `Tune` 当随机、`Recent` 当循环、`ChevronBackward/Forward` 当上一首/
 * 下一首，图标语义与功能对不上，用户自然看不懂。
 *
 * 既然图标库里没有合适的字形，就不硬找一个「差不多」的图标，而是：
 *   1. 保留语义最接近的字形（ChevronBackward/Forward 表示前后、Recent 表示回绕）；
 *   2. **把文字标签做实**——从 11sp 提到 12sp、用主文字色而非次要色、
 *      给足够的行高，保证一眼能读；
 *   3. 状态类按钮（随机/循环）把**当前状态写进标签**（「随机开」/「单曲循环」），
 *      用户不需要从图标形状反推开没开。
 *
 * 这样即使图标不够精确，功能也不会被误解——文字是最终兜底。
 */
@Composable
private fun PlayerControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    /** 状态是否处于激活态；激活时图标按钮用品牌蓝，标签也跟着变色。 */
    active: Boolean = false,
) {
    val tokens = LocalLxSurfaceTokens.current
    val labelColor = when {
        !enabled -> tokens.textSecondary.copy(alpha = 0.5f)
        active -> MiuixTheme.colorScheme.primary
        primary -> MiuixTheme.colorScheme.primary
        else -> tokens.text
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 宽度从 52dp 放宽到 56dp，保证「下一首」这类三字标签不被截断。
        modifier = Modifier.width(if (primary) 68.dp else 56.dp),
    ) {
        LxButton(
            onClick = onClick,
            enabled = enabled,
            variant = if (primary || active) LxButtonVariant.Positive else LxButtonVariant.Neutral,
            modifier = Modifier.size(if (primary) 64.dp else 48.dp),
            horizontalPadding = 0,
            content = { LxIcon(icon, contentDescription = description) },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            color = labelColor,
        )
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
