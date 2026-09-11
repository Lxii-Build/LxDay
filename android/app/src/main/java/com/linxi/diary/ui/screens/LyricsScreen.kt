package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linxi.diary.data.NeteaseClient
import com.linxi.diary.data.NeteaseLyricLine
import com.linxi.diary.data.NeteaseLyrics
import com.linxi.diary.data.NeteaseLyricSearchResult
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteaseRepeatMode
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.components.LxClickableSurface
import com.linxi.diary.ui.components.LxIcon
import com.linxi.diary.ui.components.LxIconButton
import com.linxi.diary.ui.components.LxSurface
import com.linxi.diary.ui.components.LxSurfaceTone
import com.linxi.diary.ui.components.NeteaseTrackCover
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * miuix 风格全屏歌词页。
 *
 * ## 视觉方案（为什么这样设计适合这套拟态 UI）
 *
 * 这套 UI 的语言是「同一个平面上的软光材质」：没有透明浮层、没有分割线，
 * 层级靠 `LxSurface` 的 Raised（外阴影 + 上缘高光）/ Inset（内凹）/ Flat
 * 三种色调表达。所以歌词页的改造方向不是加装饰，而是**用明暗和尺度表达
 * 「当前在唱哪一句」**：
 *
 *   1. **当前行**：`Inset` 面（内凹，像被按进去的槽位）+ 品牌蓝 + 加粗 + 放大
 *      到 1.0 倍宽度的主导字号。视觉上它是整页唯一「陷下去」的东西，
 *      注意力自然聚焦 —— 这比加一个高亮背景块更符合拟态逻辑。
 *   2. **非当前行**：不画任何面，只留文字，靠 60% 不透明度与略小的字号后退。
 *      不给每一行都套卡片，否则整页会变成「卡片糊墙」，层级反而消失。
 *   3. **过渡**：所有行的字号/透明度/缩放都走 `animateFloatAsState`，
 *      切行时是「平滑长大/缩小」而不是硬切，播放到副歌时观感尤其明显。
 *   4. **自动跟随**：当前行自动滚动居中；用户一旦手动滚动就暂停跟随，
 *      停手 3 秒后恢复 —— 这是歌词页最关键的体验细节，否则用户没法往回看。
 *   5. **顶部区收窄**：封面 56dp + 单行标题，只作为「这是哪首歌」的锚点，
 *      把纵向空间让给歌词主体（此前 64dp 封面 + 两行标题 + 4 个按钮
 *      占掉了近半屏）。
 *
 * ## 功能完整性
 *
 * 同步高亮、点击跳转、翻译歌词、歌词候选搜索、错误重试、播放/暂停/随机/循环
 * 全部保留，一个都没减。
 */
@Composable
fun LyricsScreen(track: NeteaseTrack, onBack: () -> Unit) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val playback by NeteasePlaybackManager.stateFlow.collectAsStateWithLifecycle()
    var lyrics by remember(track.id) { mutableStateOf<NeteaseLyrics?>(null) }
    var loading by remember(track.id) { mutableStateOf(true) }
    var query by remember(track.id) { mutableStateOf("${track.title} ${track.artist}") }
    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<NeteaseLyricSearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showingSearch by remember { mutableStateOf(false) }
    var retrySearch by remember { mutableStateOf(false) }
    // 点击某行后的短暂「按压反馈」：记录刚被跳转到的行下标，用于闪一下 Inset。
    var flashIndex by remember { mutableStateOf(-1) }
    val currentTrack = playback.track?.takeIf { it.id == track.id } ?: track
    val activeIndex = lyrics?.lineAt(playback.positionMs) ?: -1

    fun loadLyrics(target: NeteaseTrack) {
        retrySearch = false
        loading = true
        error = null
        scope.launch {
            runCatching { NeteaseClient.fetchLyrics(target.id) }
                .onSuccess {
                    lyrics = it
                    NeteasePlaybackManager.cacheLyrics(target.id, it)
                }
                .onFailure { error = it.message ?: "歌词加载失败" }
            loading = false
        }
    }

    fun searchLyrics() {
        retrySearch = true
        val text = query.trim()
        if (text.isBlank()) {
            error = "请输入歌词搜索关键词"
            return
        }
        searching = true
        error = null
        scope.launch {
            runCatching { NeteaseClient.searchLyrics(text) }
                .onSuccess {
                    candidates = it
                    if (it.isEmpty()) error = "没有找到带歌词的歌曲"
                }
                .onFailure { error = it.message ?: "歌词搜索失败" }
            searching = false
        }
    }

    LaunchedEffect(track.id) { loadLyrics(track) }

    // 点击歌词行后闪一下按压反馈（400ms 后自动清除）。
    LaunchedEffect(flashIndex) {
        if (flashIndex >= 0) {
            delay(420)
            flashIndex = -1
        }
    }

    val lines = remember(lyrics, UserPrefs.musicTranslation) {
        lyrics?.let { visibleLines(it, UserPrefs.musicTranslation) }.orEmpty()
    }
    val listState = rememberLazyListState()

    // 手动滚动后暂停自动跟随：停手 3 秒才恢复。
    //
    // 为什么需要它：自动跟随会在每次切行时 `animateScrollToItem`，
    // 如果用户正在往上翻看前面的歌词，这个自动滚动会把他「拽」回当前行，
    // 体验上是完全没法回看的。所以监听用户手势 → 暂停 → 静置后恢复。
    //
    // 判定方式：`isScrollInProgress` 对程序化滚动同样为真，不能直接拿来用。
    // 这里用一个 `autoScrolling` 标志把「自己发起的滚动」排除掉：
    // 自动滚动期间置真，用户在其它任何时候产生的滚动都算手动。
    var userScrolling by remember { mutableStateOf(false) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                // 只有「不是自己发起的」滚动才算用户操作。
                if (scrolling && !autoScrolling) {
                    userScrolling = true
                }
            }
    }
    LaunchedEffect(userScrolling) {
        if (userScrolling) {
            delay(AUTO_FOLLOW_RESUME_DELAY_MS)
            userScrolling = false
        }
    }

    // 自动滚动居中。用 derivedStateOf 收敛依赖，避免每个进度 tick 都重算滚动目标。
    val followTarget by remember {
        derivedStateOf { if (userScrolling) -1 else activeIndex }
    }

    // ★ 歌词第 index 行在「外层 LazyColumn」里的绝对 item 下标 ★
    //
    // 这里**不能写死 +1**：歌词行之前还铺着若干 item，数量随界面状态变化：
    //   · 0：LyricsHeader（永远存在）
    //   · 1：搜索面板（AnimatedVisibility 为不可见时该 item 仍在，占位不塌陷）
    //   · 2..：候选卡片（仅当 showingSearch）
    //   · 再往后：错误行（仅当 error != null）
    //   · 再往后：顶部留白 spacer（仅当进入歌词主体分支）
    // 之前写死 +1 会在「展开了搜索/有候选/有错误」时稳定地滚错位置。
    // 所以把前缀 item 数按同一套条件算出来，再 +1 跳过顶部 spacer。
    val lyricItemOffset = remember(showingSearch, candidates.size, error) {
        2 + (if (showingSearch) candidates.size else 0) + (if (error != null) 1 else 0) + 1
    }
    LaunchedEffect(followTarget, lines.size, lyricItemOffset) {
        val target = followTarget
        if (target >= 0 && target < lines.size) {
            // 打上「这是程序化滚动」的标记，防止它把自己当成用户手势、
            // 从而永远无法恢复自动跟随。
            autoScrolling = true
            try {
                listState.animateScrollToItem(lyricItemOffset + target)
            } finally {
                autoScrolling = false
            }
        }
    }

    KernelScreen(
        title = "歌词",
        navigationIcon = { BackAction(onBack) },
        // 歌词页自绘加载骨架，不用 KernelScreen 的列表转圈 —— 骨架能表达
        // 「这里将会出现很多行文字」，比一个转圈更贴合内容形态。
        loading = false,
        // ★ 把 listState 交给 KernelScreen 的外层 LazyColumn ★
        //
        // 这是自动跟随能生效的关键：歌词行就是这个外层列表的 item，
        // 所以 `animateScrollToItem` 操作的正是同一个滚动容器。
        // 若这里不传，KernelScreen 会自建一个 state，自动滚动就会滚错对象。
        listState = listState,
    ) {
        item {
            LyricsHeader(
                track = currentTrack,
                playing = playback.playing,
                shuffle = playback.shuffle,
                repeatMode = playback.repeatMode,
                onTogglePlay = {
                    if (playback.playing) NeteasePlaybackManager.pause()
                    else NeteasePlaybackManager.resume()
                },
                onToggleShuffle = { NeteasePlaybackManager.toggleShuffle() },
                onCycleRepeat = { NeteasePlaybackManager.cycleRepeatMode() },
                onToggleSearch = { showingSearch = !showingSearch },
                showingSearch = showingSearch,
            )
        }

        // ---- 歌词候选搜索（功能保留，视觉重做）----
        item {
            AnimatedVisibility(
                visible = showingSearch,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
            ) {
                LyricsSearchPanel(
                    query = query,
                    onQueryChange = { query = it.take(100) },
                    searching = searching,
                    onSearch = ::searchLyrics,
                )
            }
        }

        // 候选结果：每条一张可点击卡片，而不是「文字 + 整宽按钮」的堆叠。
        if (showingSearch) {
            itemsIndexed(candidates, key = { _, c -> c.track.stableKey }) { _, candidate ->
                CandidateCard(
                    candidate = candidate,
                    onClick = {
                        lyrics = candidate.lyrics
                        NeteasePlaybackManager.cacheLyrics(candidate.track.id, candidate.lyrics)
                        query = "${candidate.track.title} ${candidate.track.artist}"
                        showingSearch = false
                    },
                )
            }
        }

        error?.let { message ->
            item {
                LyricsErrorLine(
                    message = message,
                    onRetry = { if (retrySearch) searchLyrics() else loadLyrics(track) },
                )
            }
        }

        // ---- 歌词主体 ----
        // 每一行都是外层 LazyColumn 的独立 item（与原实现一致），
        // 因此「自动滚动居中」直接作用在外层 listState 上，不需要嵌套滚动。
        if (loading) {
            items(4) { index -> LyricsSkeletonRow(index) }
        } else if (lines.isEmpty()) {
            item { LyricsEmptyState() }
        } else {
            // 顶部留白：让第一行也能滚到屏幕中央。
            item(key = "__lyric_top_spacer__") { LyricEdgeSpacer() }
            itemsIndexed(
                items = lines,
                key = { index, line -> "lyric_${index}_${line.timeMs}" },
            ) { index, line ->
                LyricLineRow(
                    text = line.text.ifBlank { "♪" },
                    active = index == activeIndex,
                    flashed = index == flashIndex,
                    index = index,
                    onClick = {
                        // 点击跳转：seek 到该行时间轴，并闪一下按压反馈。
                        NeteasePlaybackManager.seekTo(
                            (line.timeMs - (lyrics?.offsetMs ?: 0L)).coerceAtLeast(0L),
                        )
                        flashIndex = index
                    },
                )
            }
            // 底部留白：让最后一行也能滚到屏幕中央。
            item(key = "__lyric_bottom_spacer__") { LyricEdgeSpacer() }
        }
    }
}

/**
 * 歌词主体。
 *
 * ★ 这里**不**嵌 `LazyColumn` ★
 *
 * 外层 `KernelScreen` 已经提供了一个 `LazyColumn`，在其中再套一个同方向
 * 可滚动列表会触发 Compose 的「infinite maximum height」异常：
 *   `IllegalStateException: Vertically scrollable component was measured with
 *    an infinity maximum height constraints`
 * （这是 Compose 里一个非常常见的崩溃，也是本页此前能跑通的关键——
 *   它原本就是把每一行直接 item {} 到外层列表里的。）
 *
 * 所以保持原结构：歌词行全部作为**外层列表的 item** 铺开，
 * 滚动与自动跟随直接作用在外层 `LazyListState` 上 ——
 * 这样「自动居中」和「手动接管」都天然成立，且没有嵌套滚动问题。
 * 首尾行要能滚到屏幕中央，靠调用方插入的 `LyricEdgeSpacer` 撑开即可。
 */
@Composable
private fun LyricEdgeSpacer() {
    // 高度约等于半屏：让第一行/最后一行也能停在视觉中央。
    Spacer(Modifier.height(LyricEdgeSpacerHeight))
}

private val LyricEdgeSpacerHeight = 180.dp

/**
 * 单行歌词。
 *
 * 动画：字号、透明度、缩放都走 `animateFloatAsState`，所以「上一行缩小、
 * 当前行放大」是连续过渡。这三个值都不依赖任何离屏图层。
 */
@Composable
private fun LyricLineRow(
    text: String,
    active: Boolean,
    flashed: Boolean,
    onClick: () -> Unit,
    index: Int,
) {
    val primary = MiuixTheme.colorScheme.primary
    val inactive = MiuixTheme.colorScheme.onSurfaceVariantSummary

    val emphasis by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(260),
        label = "lyricEmphasis",
    )
    val fontSize = 16f + 5f * emphasis
    // 主行抬高到全不透明，其余行退到 0.6，副歌滚动时明暗流动很明显。
    val alpha = 0.55f + 0.45f * emphasis
    val scale = 0.97f + 0.03f * emphasis

    LxClickableSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        // 当前行走 Inset（内凹：像被按下去的槽位），其余行走 Flat。
        // Flat 在浅色下与 canvas 同色，等于「不画卡片」，只有当前行有实体感。
        tone = if (active || flashed) LxSurfaceTone.Inset else LxSurfaceTone.Flat,
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
        contentDescription = "跳转到第 ${index + 1} 行歌词",
        semanticRole = Role.Button,
        stateDescription = if (active) "正在播放" else null,
    ) {
        Text(
            text = text,
            fontSize = fontSize.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) primary else inactive,
            textAlign = TextAlign.Center,
            lineHeight = (fontSize * 1.5f).sp,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // 缩放与透明度都用 graphicsLayer：不创建离屏层，
                    // 但能保证与字号动画同帧生效，不会出现「字先变大、再位移」。
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .padding(horizontal = 18.dp, vertical = if (active) 12.dp else 8.dp),
        )
    }
}

/**
 * 顶部信息区：**收窄版**。
 *
 * 相比之前 64dp 封面 + 两行标题 + 两排共 4 个道具按钮，这里压到
 * 56dp 封面 + 单行标题 + 一行三个圆形控件，纵向占用减少约一半，
 * 把空间还给歌词主体。
 */
@Composable
private fun LyricsHeader(
    track: NeteaseTrack,
    playing: Boolean,
    shuffle: Boolean,
    repeatMode: NeteaseRepeatMode,
    onTogglePlay: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleSearch: () -> Unit,
    showingSearch: Boolean,
) {
    val tokens = LocalLxSurfaceTokens.current
    LxSurface(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        tone = LxSurfaceTone.Raised,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeteaseTrackCover(
                track,
                modifier = Modifier.size(56.dp),
                description = "${track.title}封面",
                shape = RoundedCornerShape(14.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    track.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist.ifBlank { "未知歌手" },
                    fontSize = 12.sp,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(6.dp))
            // 三个控件用圆形 LxIconButton，语义靠图标 + contentDescription 承载，
            // 比原来两排整宽文字按钮紧凑得多，也不会和歌词区抢注意力。
            //
            // ★ 尺寸走 buttonSize 而不是 Modifier.size ★
            // LxIconButton 内部用 `sizeIn` 固定正方形，且其 max 是会与传入约束
            // 求交集的，所以 `Modifier.size(40.dp)` 会被悄悄钳回 48dp —— 表面上
            // 「设了 40dp」，真机上却和 44dp 的播放键一样大，紧凑布局的意图落空。
            // 改用 buttonSize 后：随机/循环请求 40dp，但组件的无障碍下限
            // （MIN_TOUCH_DP = 48dp）会把它兜到 48dp —— 这是**预期行为**，
            // 因为这个头部的三个按钮彼此间隔 6dp，48dp 的触达区不会互相重叠，
            // 也不会把顶部信息区撑高。
            LxIconButton(
                onClick = onToggleShuffle,
                variant = if (shuffle) LxButtonVariant.Positive else LxButtonVariant.Neutral,
                shape = CircleShape,
                buttonSize = 40.dp,
                contentDescription = if (shuffle) "已开启随机播放，点击关闭" else "开启随机播放",
            ) {
                LxIcon(
                    MiuixIcons.Tune,
                    contentDescription = null,
                    tint = if (shuffle) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.size(6.dp))
            LxIconButton(
                onClick = onCycleRepeat,
                variant = if (repeatMode == NeteaseRepeatMode.OFF) LxButtonVariant.Neutral
                else LxButtonVariant.Positive,
                shape = CircleShape,
                buttonSize = 40.dp,
                contentDescription = repeatDescription(repeatMode) + "，点击切换",
            ) {
                LxIcon(
                    MiuixIcons.Recent,
                    contentDescription = null,
                    tint = if (repeatMode == NeteaseRepeatMode.OFF) MiuixTheme.colorScheme.onBackground
                    else MiuixTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.size(6.dp))
            LxIconButton(
                onClick = onTogglePlay,
                variant = LxButtonVariant.Positive,
                shape = CircleShape,
                buttonSize = 44.dp,
                contentDescription = if (playing) "暂停" else "播放",
            ) {
                LxIcon(
                    if (playing) MiuixIcons.Pause else MiuixIcons.Play,
                    contentDescription = null,
                )
            }
        }
        // 搜索入口单独一行、贴底、低对比：它是次要操作，不该占据主行。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LxIcon(
                imageVector = MiuixIcons.Translate,
                contentDescription = null,
                tint = if (showingSearch) MiuixTheme.colorScheme.primary else tokens.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                if (UserPrefs.musicTranslation) "已开启翻译歌词" else "歌词搜索与翻译",
                fontSize = 12.sp,
                color = if (showingSearch) MiuixTheme.colorScheme.primary else tokens.textSecondary,
                modifier = Modifier.weight(1f),
            )
            LxButton(
                text = if (showingSearch) "收起搜索" else "搜索歌词",
                onClick = onToggleSearch,
                variant = LxButtonVariant.Neutral,
                horizontalPadding = 12,
            )
        }
    }
}

/** 搜索面板：外层 LxSurface 让 miuix 的 TextField 融入拟态材质。 */
@Composable
private fun LyricsSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
) {
    LxSurface(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        tone = LxSurfaceTone.Inset,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                label = "歌曲名 / 歌手",
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            LxButton(
                text = if (searching) "搜索中…" else "搜索",
                onClick = onSearch,
                enabled = !searching,
                horizontalPadding = 14,
            )
        }
    }
}

/**
 * 候选歌词卡片：整卡可点，点哪儿都能用这份歌词。
 * 相比原来「文字 + 一个整宽按钮」，点击热区更大、视觉更干净。
 */
@Composable
private fun CandidateCard(
    candidate: NeteaseLyricSearchResult,
    onClick: () -> Unit,
) {
    val tokens = LocalLxSurfaceTokens.current
    LxClickableSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        tone = LxSurfaceTone.Raised,
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
        contentDescription = "使用 ${candidate.track.title} 的歌词",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeteaseTrackCover(
                candidate.track,
                modifier = Modifier.size(44.dp),
                description = null,
                shape = RoundedCornerShape(12.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    candidate.track.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    candidate.track.artist,
                    fontSize = 12.sp,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "使用",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** 错误行：错误文案 + 重试，包在一张 Inset 面里而不是裸文字。 */
@Composable
private fun LyricsErrorLine(message: String, onRetry: () -> Unit) {
    LxSurface(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        tone = LxSurfaceTone.Inset,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                color = MiuixTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            LxButton(
                text = "重试",
                onClick = onRetry,
                variant = LxButtonVariant.Neutral,
                horizontalPadding = 14,
            )
        }
    }
}

/**
 * 加载骨架：一组宽度递减的呼吸条，而不是一句「加载中」文字。
 *
 * 用 `rememberInfiniteTransition` 做一个 0.35↔0.75 的 alpha 循环；
 * 骨架条的宽度按行下标阶梯变化，视觉上就像「各行的字数不一样」，
 * 比等高等宽的灰条更像真实歌词。
 */
@Composable
private fun LyricsSkeletonRow(index: Int) {
    val tokens = LocalLxSurfaceTokens.current
    val transition = rememberInfiniteTransition(label = "lyricSkeleton")
    val breathe by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lyricSkeletonAlpha",
    )
    // 阶梯宽度：让骷髅条看起来像不同长度的句子，而不是四条等长灰块。
    val widthFraction = when (index % 4) {
        0 -> 0.78f
        1 -> 0.62f
        2 -> 0.86f
        else -> 0.52f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(widthFraction)
                .height(if (index == 0) 22.dp else 16.dp)
                // 呼吸用 graphicsLayer 的 alpha：与 Modifier.alpha() 一样不创建离屏层，
                // 但能保证它与下方背景同帧合成，避免「先变淡、再变色」的双重动画。
                .graphicsLayer { alpha = breathe }
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.line),
        )
    }
}

/** 空态：明确告诉用户「这首歌没有歌词」，并暗示可以去搜别的。 */
@Composable
private fun LyricsEmptyState() {
    val tokens = LocalLxSurfaceTokens.current
    LxSurface(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        tone = LxSurfaceTone.Inset,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(tokens.surface),
                contentAlignment = Alignment.Center,
            ) {
                LxIcon(
                    imageVector = MiuixIcons.Translate,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("这首歌暂时没有歌词", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "可以点上方「搜索歌词」手动匹配一份",
                fontSize = 12.sp,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun repeatDescription(mode: NeteaseRepeatMode): String = when (mode) {
    NeteaseRepeatMode.OFF -> "循环关闭"
    NeteaseRepeatMode.ALL -> "列表循环"
    NeteaseRepeatMode.ONE -> "单曲循环"
}

/** 手动滚动后，静置多久恢复自动跟随。3 秒是「看完这一句就回来」的常用阈值。 */
private const val AUTO_FOLLOW_RESUME_DELAY_MS = 3_000L

private fun visibleLines(lyrics: NeteaseLyrics, includeTranslation: Boolean): List<NeteaseLyricLine> {
    if (lyrics.original.isEmpty()) return lyrics.translated
    if (!includeTranslation || lyrics.translated.isEmpty()) return lyrics.original
    return lyrics.original.map { original ->
        val translation = lyrics.translated.minByOrNull { kotlin.math.abs(it.timeMs - original.timeMs) }
            ?.takeIf { kotlin.math.abs(it.timeMs - original.timeMs) <= 1_000L }
        if (translation == null || translation.text.isBlank()) original
        else original.copy(text = "${original.text}\n${translation.text}")
    }
}
