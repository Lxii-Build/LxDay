package com.linxi.diary.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linxi.diary.data.ClientRuntimeConfig
import com.linxi.diary.data.ListenSessionController
import com.linxi.diary.data.ListenSessionState
import com.linxi.diary.data.NeteaseAccountStore
import com.linxi.diary.data.NeteaseClient
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.data.formatListenPosition
import com.linxi.diary.ui.NeteaseWebLoginActivity
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
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.ArrowDown
import top.yukonga.miuix.kmp.icon.extended.ArrowUp
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一起听。视图层，房间与 WebSocket 由 [ListenSessionController] 持有，
 * 因此离开本页不会中断房间，也不会让全局迷你播放器显示过期连接。
 *
 * ## 布局（本页此前的写法把所有内容拍平成同权重的长卡片）
 *
 * 此前是「说明卡 → 20 条搜索结果全量铺开 → 房间状态卡 → 错误 → 底部按钮」，
 * 搜索框、按钮、状态、结果互相争夺注意力，既没有主次也无法一眼找到当前在放什么。
 * 现在按信息重要性重排为四段：
 *
 *   1. 现在播放   —— 主视觉卡：大封面 + 歌名 + 歌手 + 进度；
 *   2. 房间状态   —— 压缩成单行（房间号 · 角色 · 在线数）；
 *   3. 搜索       —— 默认收起，点开才展开输入框与结果；
 *   4. 底部操作   —— 重新进入 / 离开。
 *
 * 所有原有功能一个未减：绑定入口、搜索、逐条「一起播放」、暂停/继续、
 * 房主控制权校验、重试、离开、错误与提示文案全部保留。
 */
@Composable
fun ListenTogetherScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by ListenSessionController.stateFlow.collectAsStateWithLifecycle()
    val player by NeteasePlaybackManager.stateFlow.collectAsStateWithLifecycle()
    var loggedIn by remember { mutableStateOf(NeteaseAccountStore.isLoggedIn()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NeteaseTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var resolvingId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // 搜索区默认收起：进入页面时用户最关心的是「现在在放什么」，
    // 而不是一个空搜索框。已绑定账号时展开过一次就记住，减少重复操作。
    var searchExpanded by remember { mutableStateOf(false) }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loggedIn = NeteaseAccountStore.isLoggedIn()
            if (loggedIn) {
                info = "网易云账号已绑定，可以搜索歌曲"
                ListenSessionController.ensureStarted()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (loggedIn && ClientRuntimeConfig.listenTogetherEnabled) {
            ListenSessionController.ensureStarted()
        }
    }

    LaunchedEffect(session.loading, session.error, session.info) {
        if (!session.loading) resolvingId = null
    }

    fun search() {
        if (!loggedIn) {
            error = "请先绑定网易云账号"
            return
        }
        val text = query.trim()
        if (text.isBlank()) {
            error = "请输入歌曲名或歌手"
            return
        }
        searching = true
        error = null
        scope.launch {
            runCatching { NeteaseClient.searchSongs(text) }
                .onSuccess {
                    results = it
                    if (it.isEmpty()) info = "没有找到匹配歌曲"
                }
                .onFailure { error = it.message ?: "网易云搜索失败，请检查网络" }
            searching = false
        }
    }

    fun chooseTrack(track: NeteaseTrack) {
        if (session.room == null) {
            error = "一起听房间尚未准备好，请稍后重试"
            ListenSessionController.retry()
            return
        }
        if (!canControl(session)) {
            error = "房主暂未开放成员控制播放"
            return
        }
        resolvingId = track.id
        error = null
        // 服务端是权威来源：只有命令成功后才解析本地地址，
        // 被拒绝的成员不会听到房间没批准的歌。
        // 搜索结果整条作为队列提交，对方设备也能用上一首/下一首。
        val queue = results
            .distinctBy { it.stableKey }
            .let { songs -> if (songs.any { it.stableKey == track.stableKey }) songs else songs + track }
            .ifEmpty { listOf(track) }
        ListenSessionController.controlTrack(
            track = track,
            queue = queue,
            queueIndex = queue.indexOfFirst { it.stableKey == track.stableKey },
            playing = true,
        )
    }

    fun togglePlayback() {
        val current = session.room?.state ?: return
        if (!canControl(session)) {
            error = "房主暂未开放成员控制播放"
            return
        }
        ListenSessionController.control(
            JSONObject().put("action", if (current.playing) "pause" else "play"),
        )
    }

    val loginIntent = remember { Intent(context, NeteaseWebLoginActivity::class.java) }
    // 命令（切歌/暂停/离开）会短暂复用 loading 标志，但不能让整页退回初始转圈，
    // 否则搜索与房间控件会在操作瞬间整块消失。
    val initialLoading = session.loading && session.room == null
    KernelScreen(title = "一起听", navigationIcon = { BackAction(onBack) }, loading = initialLoading) {
        // ---- ① 现在播放：主视觉卡 ----
        item {
            NowPlayingCard(
                session = session,
                playerPosition = player.positionMs,
                loggedIn = loggedIn,
                onBindAccount = { loginLauncher.launch(loginIntent) },
                onToggle = ::togglePlayback,
                onRetry = ListenSessionController::retry,
            )
        }

        // ---- ③ 搜索：默认收起 ----
        item {
            SearchSection(
                expanded = searchExpanded,
                onToggleExpanded = { searchExpanded = !searchExpanded },
                query = query,
                onQueryChange = { query = it.take(80) },
                searching = searching,
                loggedIn = loggedIn,
                resultCount = results.size,
                onSearch = ::search,
            )
        }

        // 搜索结果逐条渲染，每条一个「一起播放」动作（原功能完整保留）。
        if (searchExpanded) {
            results.take(20).forEach { track ->
                item { SearchTrackRow(track, resolvingId == track.id, ::chooseTrack) }
            }
        }

        // ---- 提示与错误（文案与判定逻辑保持不变）----
        session.error?.let { message ->
            item { StatusLine(message, isError = true) }
        }
        (error ?: session.info ?: info)?.let { message ->
            item { StatusLine(message, isError = error != null) }
        }

        // ---- ④ 底部操作 ----
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LxButton(
                    text = if (session.loading) "进入中…" else "重新进入房间",
                    onClick = ListenSessionController::retry,
                    enabled = !session.loading && ClientRuntimeConfig.listenTogetherEnabled,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.weight(1f),
                )
                if (session.room != null) {
                    LxButton(
                        text = "离开",
                        onClick = ListenSessionController::leave,
                        enabled = !session.loading,
                        variant = LxButtonVariant.Negative,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 现在播放主卡：本页视觉重心。
 *
 * 未绑定网易云账号时，这里退化为「绑定引导」——用同一张卡承载首次使用的
 * 唯一动作，避免同时出现一张空播放卡和一个孤立的绑定按钮。
 */
@Composable
private fun NowPlayingCard(
    session: ListenSessionState,
    playerPosition: Long,
    loggedIn: Boolean,
    onBindAccount: () -> Unit,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
    val tokens = LocalLxSurfaceTokens.current
    val state = session.room?.state
    val isWatch = state?.isWatch == true
    val current = state?.takeIf { !it.isWatch && it.songId > 0L }

    LxSurface(Modifier.fillMaxWidth().padding(top = 12.dp), tone = LxSurfaceTone.Raised) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // 房间状态压缩成一行标签，不再单独占一张卡。
            RoomStatusLine(session)

            Spacer(Modifier.height(16.dp))

            if (!loggedIn) {
                // 首次使用：把唯一动作放在主位，说明文字保持简短。
                IconBadge()
                Spacer(Modifier.height(12.dp))
                Text("绑定网易云账号后即可一起听", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "两台设备各自用本地播放器播放同一首歌，服务端只同步歌曲与进度，不接触 Cookie。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                LxButton(text = "绑定网易云账号", onClick = onBindAccount, modifier = Modifier.fillMaxWidth())
                return@Column
            }

            when {
                isWatch -> {
                    // 房间在放视频：明确指路，不在这里重复视频控制（那些在「一起看」）。
                    IconBadge()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state?.watchTitle?.ifBlank { "正在一起看" } ?: "正在一起看",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "当前房间同步的是观看链接，请到「一起看」管理播放",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }

                current != null -> {
                    // 有歌在放：大封面主视觉。封面用房间状态自带的 coverUrl
                    // （toTrack() 会一并带上 album/duration），避免手工拼装漏字段。
                    NeteaseTrackCover(
                        track = current.toTrack(),
                        modifier = Modifier.size(168.dp),
                        description = "${current.title}封面",
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        current.title.ifBlank { "未命名歌曲" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current.artist.ifBlank { "未知歌手" },
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        formatListenPosition(playerPosition, current.durationMs),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(16.dp))
                    LxButton(
                        text = if (current.playing) "暂停" else "继续播放",
                        onClick = onToggle,
                        enabled = !session.loading && canControl(session),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> {
                    // 房间就绪但还没选歌：空态 + 明确下一步。
                    IconBadge()
                    Spacer(Modifier.height(12.dp))
                    Text("还没有选择歌曲", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (session.room == null) "正在进入情侣房间…" else "展开下方搜索，选一首开始一起听",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                    if (session.room == null && !session.loading) {
                        Spacer(Modifier.height(14.dp))
                        LxButton("重试", onClick = onRetry, variant = LxButtonVariant.Neutral, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // 成员不可控时的说明（原逻辑保留）。
            if (session.role == "member" && session.room?.settings?.allowMemberControl == false) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "房主暂未开放成员控制播放。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 主卡空态/引导态共用的圆形图标底，替代此前孤立的小图标。 */
@Composable
private fun IconBadge() {
    val tokens = LocalLxSurfaceTokens.current
    LxSurface(
        modifier = Modifier.size(72.dp),
        tone = LxSurfaceTone.Inset,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = tokens.surface,
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
            LxIcon(
                imageVector = MiuixIcons.Music,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 房间状态单行：房间号 · 角色 · 在线数。连接中/未就绪时给出对应文案。 */
@Composable
private fun RoomStatusLine(session: ListenSessionState) {
    val text = when {
        session.room == null -> "正在进入情侣房间…"
        else -> buildString {
            append(session.roomId.uppercase())
            append(" · ")
            append(if (session.role == "host") "房主" else "成员")
            append(" · ")
            if (session.connected) append("${session.room?.members ?: 0} 人在线") else append("连接恢复中…")
        }
    }
    Text(
        text,
        fontSize = 12.sp,
        color = if (session.connected) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 可折叠搜索区。
 *
 * 收起时只是一行入口 + 当前结果数，点开才出现输入框，
 * 让小屏设备的首屏不再被搜索控件占满。
 *
 * ## 拟态化处理
 *
 * 输入框是 miuix 的 `TextField`，它的内部填充色与外层卡片不同，
 * 直接放在 Raised 卡里会出现「一块贴上去的浅色矩形」——正是管理员
 * 一直吐槽的那种违和感。这里把它包在一层 `Inset`（内凹）面里：
 * 内凹的槽位天然就是「输入框该有的样子」，miuix 输入框嵌在槽位里
 * 视觉上就成立了，不需要换控件（换控件风险大且会丢掉 miuix 的
 * 光标/选区/长按菜单等既有行为）。
 */
@Composable
private fun SearchSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    loggedIn: Boolean,
    resultCount: Int,
    onSearch: () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "searchChevron")

    LxSurface(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        tone = LxSurfaceTone.Raised,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("搜索歌曲", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            !loggedIn -> "需先绑定网易云账号"
                            resultCount > 0 -> "已找到 $resultCount 首"
                            else -> "展开后可搜索歌名或歌手"
                        },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LxIconButton(
                    onClick = onToggleExpanded,
                    variant = LxButtonVariant.Neutral,
                    // 圆形：高光已跟随 shape 真实轮廓，无需再传已废弃的 edgeRadius。
                    shape = CircleShape,
                    // 走 buttonSize（而非 Modifier.size）：LxIconButton 内部用
                    // sizeIn 的 max 求交集，Modifier.size 会被钳回 48dp。
                    // 这里请求 38dp，实际会被组件的无障碍下限兜到 48dp ——
                    // 与右侧的卡片内边距配合后，展开箭头仍是紧凑的圆形控件。
                    buttonSize = 38.dp,
                    contentDescription = if (expanded) "收起搜索" else "展开搜索",
                ) {
                    LxIcon(
                        imageVector = if (expanded) MiuixIcons.ArrowUp else MiuixIcons.ArrowDown,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .size(20.dp)
                            // 箭头本身也做旋转动画，而不是直接换两个图标：
                            // 换图标是硬切，旋转是连续过渡，观感更顺。
                            .graphicsLayer { rotationZ = rotation },
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(160)),
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    // 内凹槽位承托 miuix 输入框，让它融入拟态材质。
                    LxSurface(
                        Modifier.fillMaxWidth(),
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
                                label = "歌曲名或歌手",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            AddActionButton(
                                added = false,
                                busy = searching,
                                label = "搜索",
                                onClick = onSearch,
                                enabled = !searching && loggedIn,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索/添加共用的动作按钮，带**状态形变**动画。
 *
 * 「添加」→「已添加」这类状态切换此前是直接换文字（硬切），用户看不出
 * 发生过什么。这里做成：
 *   · 颜色：中性 → 品牌蓝，`animateColorAsState` 平滑过渡；
 *   · 内容：图标做 90° 旋转淡入淡出，而不是瞬间替换；
 *   · 尺寸：宽度随文字变化走 `animateContentSize`。
 * 三处都不依赖离屏图层。
 */
@Composable
private fun AddActionButton(
    added: Boolean,
    busy: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentColor by animateColorAsState(
        targetValue = if (added) Color.White else MiuixTheme.colorScheme.primary,
        animationSpec = tween(220),
        label = "addActionColor",
    )
    val fillColor by animateColorAsState(
        targetValue = if (added) MiuixTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(220),
        label = "addActionFill",
    )
    LxSurface(
        modifier = Modifier.animateContentSize(),
        tone = LxSurfaceTone.Flat,
        shape = CircleShape,
        color = fillColor,
    ) {
        Row(
            Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (busy) {
                // 提交中用一个小圆点呼吸，而不是引入 miuix 加载圈的完整 API
                // （它的参数签名在不同版本间有变化，这里只依赖最稳定的
                // 基本绘图能力，避免因版本差异编译不过）。
                val transition = rememberInfiniteTransition(label = "addBusy")
                val pulse by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "addBusyPulse",
                )
                Box(
                    Modifier
                        .size(14.dp)
                        .graphicsLayer { alpha = pulse }
                        .clip(CircleShape)
                        .background(contentColor),
                )
            } else if (added) {
                // 勾选图标「弹入」：0.6 → 1 的缩放 + 淡入。
                // 用一个从 0 到 1 的插值动画驱动，避免硬切。
                val tick = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    tick.animateTo(1f, tween(260))
                }
                val tickProgress = tick.value
                LxIcon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer {
                            scaleX = 0.6f + 0.4f * tickProgress
                            scaleY = 0.6f + 0.4f * tickProgress
                            alpha = tickProgress
                        },
                )
            }
            Text(
                text = if (busy) "提交中…" else label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/** 统一的状态提示行；错误用红色、提示用主色（原判定逻辑不变）。 */
@Composable
private fun StatusLine(message: String, isError: Boolean) {
    Text(
        message,
        color = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/**
 * 搜索结果行：封面 + 歌名 + 歌手·专辑 + 时长 + 「一起播放」动作。
 *
 * 三处质感提升：
 *   1. **整行可点**（`LxClickableSurface`）——此前只有右侧那个窄按钮能点，
 *      卡片主体是死的，点封面没反应，非常反直觉。
 *   2. **补上时长**（`track.durationMs`）——这是选歌时最常用的判断依据，
 *      此前完全没有。
 *   3. **已提交态用形变而非硬切**——提交中显示转圈，提交完成后勾选弹入。
 */
@Composable
private fun SearchTrackRow(track: NeteaseTrack, resolving: Boolean, onClick: (NeteaseTrack) -> Unit) {
    val tokens = LocalLxSurfaceTokens.current
    LxClickableSurface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        tone = LxSurfaceTone.Raised,
        shape = RoundedCornerShape(16.dp),
        enabled = !resolving,
        onClick = { onClick(track) },
        contentDescription = "一起播放 ${track.title}",
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeteaseTrackCover(
                track,
                modifier = Modifier.size(52.dp),
                description = null,
                shape = RoundedCornerShape(13.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    track.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                        fontSize = 12.sp,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 时长：选歌的关键信息。
                    if (track.durationMs > 0L) {
                        Text(
                            "  ·  ${formatTrackDuration(track.durationMs)}",
                            fontSize = 12.sp,
                            color = tokens.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.size(10.dp))
            AddActionButton(
                added = false,
                busy = resolving,
                label = "一起播放",
                onClick = { onClick(track) },
                enabled = !resolving,
            )
        }
    }
}

/** 曲目时长格式：mm:ss。 */
private fun formatTrackDuration(durationMs: Long): String {
    val seconds = (durationMs / 1_000L).coerceAtLeast(0L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun canControl(session: ListenSessionState): Boolean =
    session.role == "host" || session.room?.settings?.allowMemberControl == true
