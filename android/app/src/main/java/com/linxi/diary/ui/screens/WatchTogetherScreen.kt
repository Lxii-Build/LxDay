package com.linxi.diary.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.linxi.diary.data.WatchPlaybackManager
import com.linxi.diary.data.WatchPlaybackState
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.components.LxConfirmDialog
import com.linxi.diary.ui.components.LxIcon
import com.linxi.diary.ui.components.LxIconButton
import com.linxi.diary.ui.components.LxSurface
import com.linxi.diary.ui.components.LxSurfaceTone
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一起看。与「一起听」共用同一对情侣房间，但存储的是 `kind=watch` 时间轴。
 * 直链 MP4/WebM 可在应用内播放；普通视频页链接交给系统浏览器，两台手机仍然
 * 共享 URL 与权威时间轴。
 *
 * ## 布局（与「一起听」保持一致的信息层级）
 *
 * 此前「同步表单」和「房间状态 + 全部控制按钮」是两张同权重的长卡片，
 * 打开页面先看到的是一个空输入框，而正在同步什么、能按哪些键反而要往下翻。
 * 现在改为：
 *
 *   1. 当前同步内容 —— 主卡：标题 / 类型 / 进度 + 播放控制；
 *   2. 房间状态     —— 压缩成单行；
 *   3. 同步新链接   —— 默认收起，点开才出现两个输入框与提交按钮；
 *   4. 底部操作     —— 重新连接 / 离开。
 *
 * 全部功能保留：本机播放、打开网页、±10 秒、暂停/继续、清除内容（含二次确认
 * 与进行中状态）、房主控制权校验、重试、离开、错误与提示。
 */
@Composable
fun WatchTogetherScreen(onBack: () -> Unit) {
    BackHandler {
        WatchPlaybackManager.pause()
        onBack()
    }
    val context = LocalContext.current
    val session by ListenSessionController.stateFlow.collectAsStateWithLifecycle()
    val local by WatchPlaybackManager.stateFlow.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var clearRequested by remember { mutableStateOf(false) }
    // 同步表单默认收起：优先呈现「现在在看什么」。
    var syncExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        WatchPlaybackManager.init(context)
        ListenSessionController.ensureStarted()
    }

    // 把房间更新应用到本地播放器，且不回传给服务端：远端事件是状态，不是新命令。
    val remoteWatch = session.room?.state?.takeIf { it.isWatch }
    LaunchedEffect(remoteWatch?.watchUrl, remoteWatch?.watchPositionMs, remoteWatch?.watchPlaying) {
        val remote = remoteWatch ?: return@LaunchedEffect
        if (remote.watchUrl.isBlank()) return@LaunchedEffect
        url = remote.watchUrl
        title = remote.watchTitle
        WatchPlaybackManager.syncRemote(remote)
        if (remote.watchPlaying && WatchPlaybackManager.canPlayDirect(remote.watchUrl)) {
            runCatching { WatchPlaybackManager.play(remote.watchUrl, remote.watchTitle, remote.watchPositionMs) }
                .onFailure { error = it.message }
        }
    }

    // 房主心跳保持共享时间轴贴近本地播放器。该循环刻意静默，从不改动页面加载态。
    LaunchedEffect(session.room?.roomId, session.role) {
        while (isActive) {
            delay(5_000L)
            val current = ListenSessionController.stateFlow.value
            val playback = WatchPlaybackManager.stateFlow.value
            if (current.room?.state?.isWatch == true && canWatchControl(current)) {
                ListenSessionController.heartbeatWatch(playback.positionMs, playback.playing)
            }
        }
    }

    // 权威命令进行中时保持确认弹窗打开；失败的请求把错误留在弹窗里，
    // 用户可以重试，而不会误发同一条命令两次。
    LaunchedEffect(session.loading, session.info, session.error) {
        if (clearRequested && !session.loading) {
            when {
                session.error == null && session.info == "已清除一起看的内容" -> {
                    clearRequested = false
                    showClearDialog = false
                }
                session.error != null -> clearRequested = false
            }
        }
    }

    fun publish() {
        val clean = url.trim()
        if (!clean.startsWith("https://", ignoreCase = true)) {
            error = "观看链接必须以 https:// 开头"
            return
        }
        if (session.room == null) {
            error = "一起看房间尚未准备好，请稍后重试"
            ListenSessionController.retry()
            return
        }
        if (!canWatchControl(session)) {
            error = "房主暂未开放成员控制"
            return
        }
        error = null
        ListenSessionController.setWatch(clean, title.trim())
    }

    fun openExternal() {
        val target = session.room?.state?.takeIf { it.isWatch }?.watchUrl ?: url.trim()
        if (target.isBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.onFailure { error = "系统没有可打开此链接的应用" }
    }

    fun playLocal() {
        val remote = session.room?.state?.takeIf { it.isWatch } ?: return
        if (!WatchPlaybackManager.canPlayDirect(remote.watchUrl)) {
            info = "这是网页链接，将使用系统浏览器打开"
            openExternal()
            return
        }
        runCatching { WatchPlaybackManager.play(remote.watchUrl, remote.watchTitle, remote.watchPositionMs) }
            .onFailure { error = it.message ?: "视频暂时无法播放" }
    }

    val initialLoading = session.loading && session.room == null
    KernelScreen(title = "一起看", navigationIcon = { BackAction(onBack) }, loading = initialLoading) {
        // ---- ① 当前同步内容：主卡 ----
        item {
            WatchNowCard(
                session = session,
                local = local,
                onPlay = ::playLocal,
                onOpenExternal = ::openExternal,
                onToggle = {
                    if (local.playing) {
                        ListenSessionController.control(org.json.JSONObject().put("action", "watch_pause"))
                    } else {
                        ListenSessionController.control(org.json.JSONObject().put("action", "watch_play"))
                    }
                },
                onSeek = { delta ->
                    val next = (local.positionMs + delta).coerceAtLeast(0L)
                    ListenSessionController.control(
                        org.json.JSONObject().apply {
                            put("action", "watch_seek")
                            put("watch_position_ms", next)
                        },
                    )
                },
                canControl = canWatchControl(session) && !session.loading,
                onClear = { showClearDialog = true },
                onRetry = ListenSessionController::retry,
            )
        }

        // ---- ③ 同步新链接：默认收起 ----
        item {
            SyncSection(
                expanded = syncExpanded,
                onToggleExpanded = { syncExpanded = !syncExpanded },
                url = url,
                onUrlChange = { url = it.take(4096) },
                title = title,
                onTitleChange = { title = it.take(160) },
                submitting = session.loading,
                onPublish = ::publish,
            )
        }

        // ---- 提示与错误（判定逻辑与文案保持不变）----
        (error ?: session.error ?: session.info ?: info)?.let { message ->
            item {
                Text(
                    message,
                    color = if (error != null || session.error != null) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // ---- ④ 底部操作 ----
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LxButton(
                    text = "重新连接",
                    onClick = ListenSessionController::retry,
                    enabled = !session.loading && ClientRuntimeConfig.listenTogetherEnabled,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.weight(1f),
                )
                if (session.room != null) {
                    LxButton(
                        text = "离开",
                        onClick = {
                            WatchPlaybackManager.stopAndClear()
                            ListenSessionController.leave()
                        },
                        enabled = !session.loading,
                        variant = LxButtonVariant.Negative,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    LxConfirmDialog(
        show = showClearDialog,
        title = "清除一起看内容？",
        message = "清除后双方房间里的视频链接、标题和观看进度都会移除；之后仍可重新同步新的链接。",
        confirmText = "清除内容",
        onConfirm = {
            if (!clearRequested) {
                clearRequested = true
                ListenSessionController.clearWatch()
            }
        },
        onDismiss = {
            if (!session.loading) {
                clearRequested = false
                showClearDialog = false
            }
        },
        destructive = true,
        busy = clearRequested,
        busyText = "清除中…",
        extraContent = {
            session.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
        },
    )
}

/**
 * 当前同步内容主卡。
 * 有内容时展示标题、来源类型、进度与完整播放控制；没有内容时给出明确空态，
 * 并引导到下方「同步新链接」，而不是留一张只有状态文字的空卡。
 */
@Composable
private fun WatchNowCard(
    session: ListenSessionState,
    local: WatchPlaybackState,
    onPlay: () -> Unit,
    onOpenExternal: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    canControl: Boolean,
    onClear: () -> Unit,
    onRetry: () -> Unit,
) {
    val state = session.room?.state?.takeIf { it.isWatch }

    LxSurface(Modifier.fillMaxWidth().padding(top = 12.dp), tone = LxSurfaceTone.Raised) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // 房间状态压缩成单行（与「一起听」一致）。
            WatchRoomStatusLine(session)

            Spacer(Modifier.height(16.dp))

            if (state == null) {
                WatchIconBadge()
                Spacer(Modifier.height(12.dp))
                Text("还没有同步观看内容", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (session.room == null) "正在进入情侣房间…" else "展开下方「同步新链接」，两台设备会看到同一个时间轴",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                if (session.room == null && !session.loading) {
                    Spacer(Modifier.height(14.dp))
                    LxButton("重试", onClick = onRetry, variant = LxButtonVariant.Neutral, modifier = Modifier.fillMaxWidth())
                }
                return@Column
            }

            Text(
                state.watchTitle.ifBlank { "一起看" },
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (WatchPlaybackManager.canPlayDirect(state.watchUrl)) "可直接在应用内播放"
                else "网页链接 · 将使用系统浏览器打开",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                formatWatchPosition(
                    if (local.url == state.watchUrl) local.positionMs else state.watchPositionMs,
                    state.watchDurationMs,
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(18.dp))
            // 播放控制：一行圆形图标按钮（高频、拇指区）。
            WatchTransportRow(
                playing = local.playing,
                canControl = canControl,
                onSeek = onSeek,
                onToggle = onToggle,
            )

            Spacer(Modifier.height(16.dp))
            // 入口型动作：明确文字 + 均分宽度。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LxButton("本机播放", onClick = onPlay, modifier = Modifier.weight(1f))
                LxButton("打开网页", onClick = onOpenExternal, variant = LxButtonVariant.Neutral, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            LxButton("清除内容", onClick = onClear, enabled = canControl, variant = LxButtonVariant.Negative, modifier = Modifier.fillMaxWidth())

            if (!canControl) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "房主暂未开放成员控制。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 主卡空态用的圆形图标底。 */
@Composable
private fun WatchIconBadge() {
    val tokens = LocalLxSurfaceTokens.current
    LxSurface(
        modifier = Modifier.size(72.dp),
        tone = LxSurfaceTone.Inset,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = tokens.surface,
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
            LxIcon(
                imageVector = MiuixIcons.Play,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 房间状态单行：房间号 · 角色 · 在线数。 */
@Composable
private fun WatchRoomStatusLine(session: ListenSessionState) {
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
 * 可折叠的「同步新链接」区。
 *
 * 与「一起听」的搜索区用同一套处理：外层 Raised 卡 + 内凹槽位承托输入框，
 * 让 miuix 的 `TextField` 融入拟态材质，而不是在卡片上贴两块矩形。
 * 展开/收起走 `AnimatedVisibility` 的淡入 + 纵向扩展，不是硬切。
 */
@Composable
private fun SyncSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    url: String,
    onUrlChange: (String) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    submitting: Boolean,
    onPublish: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "syncChevron")

    LxSurface(Modifier.fillMaxWidth().padding(top = 10.dp), tone = LxSurfaceTone.Raised) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("同步新链接", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (expanded) "粘贴 HTTPS 链接后同步给伴侣" else "展开可粘贴视频或网页链接",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LxIconButton(
                    onClick = onToggleExpanded,
                    variant = LxButtonVariant.Neutral,
                    shape = CircleShape,
                    // 同「一起听」：走 buttonSize，Modifier.size 会被 sizeIn 钳回 48dp。
                    buttonSize = 38.dp,
                    contentDescription = if (expanded) "收起同步表单" else "展开同步表单",
                ) {
                    LxIcon(
                        imageVector = MiuixIcons.ExpandMore,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .size(20.dp)
                            // 旋转而不是换 ArrowUp/ArrowDown 两个图标：
                            // 连续过渡比硬切更顺，也和「一起听」保持一致。
                            .graphicsLayer { rotationZ = chevronRotation },
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
                    // 两个输入框共用一层内凹槽位：视觉上它们是「同一组同步参数」。
                    LxSurface(
                        Modifier.fillMaxWidth(),
                        tone = LxSurfaceTone.Inset,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextField(
                                value = url,
                                onValueChange = onUrlChange,
                                label = "HTTPS 视频或网页链接",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextField(
                                value = title,
                                onValueChange = onTitleChange,
                                label = "标题（可选）",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LxButton(
                        text = if (submitting) "同步中…" else "同步给伴侣",
                        onClick = onPublish,
                        enabled = !submitting && ClientRuntimeConfig.listenTogetherEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 一起看主卡里的「播放控制条」。
 *
 * 此前是 2 + 3 + 1 三行共 6 个整宽按钮，纵向吃掉大半屏，且「本机播放」
 * 「打开网页」这类**入口型**动作和「±10 秒」「暂停」这类**播放型**动作
 * 混在同一个视觉层级里，用户很难一眼分清。
 *
 * 现在分成两层：
 *   · 上层：圆形图标按钮组（后退 10s / 播放暂停 / 前进 10s）—— 高频、拇指区；
 *   · 下层：两个入口 + 一个破坏性动作 —— 低频、明确文字。
 */
@Composable
private fun WatchTransportRow(
    playing: Boolean,
    canControl: Boolean,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 用纯文字 + 圆形底面承载「±10 秒」：miuix 图标库没有
        // replay/forward-10 这类字形，硬凑图标不如把数字写清楚。
        WatchRoundAction(
            label = "−10",
            hint = "后退 10 秒",
            enabled = canControl,
            onClick = { onSeek(-10_000L) },
        )
        LxIconButton(
            onClick = onToggle,
            enabled = canControl,
            variant = LxButtonVariant.Positive,
            shape = CircleShape,
            // 主按钮：64dp。★ 必须走 buttonSize 而不是 Modifier.size(64.dp) ★
            // LxIconButton 内部用 sizeIn 固定尺寸，Modifier.size 会被求交集钳回
            // 48dp；传 buttonSize 才能让「暂停/播放」这个最高频的动作真正成为
            // 视觉主位（两侧的 ±10 秒是 48dp 次级按钮）。
            buttonSize = 64.dp,
            contentDescription = if (playing) "暂停" else "继续播放",
        ) {
            LxIcon(
                if (playing) MiuixIcons.Pause else MiuixIcons.Play,
                contentDescription = null,
            )
        }
        WatchRoundAction(
            label = "+10",
            hint = "前进 10 秒",
            enabled = canControl,
            onClick = { onSeek(10_000L) },
        )
    }
}

/** 圆形文字动作按钮（用于 −10s / +10s）。 */
@Composable
private fun WatchRoundAction(
    label: String,
    hint: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalLxSurfaceTokens.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LxIconButton(
            onClick = onClick,
            enabled = enabled,
            variant = LxButtonVariant.Neutral,
            shape = CircleShape,
            // 次级按钮：48dp（不传即默认，显式写出来是为了让「比主按钮小一圈」
            // 这件事在代码里是可读的）。「秒」字标签单独放在按钮下方，
            // 不占用按钮内部空间，避免圆形按钮里的文字被 10dp padding 挤掉。
            buttonSize = 48.dp,
            contentDescription = hint,
        ) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MiuixTheme.colorScheme.onBackground
                else tokens.textSecondary.copy(alpha = 0.5f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("秒", fontSize = 11.sp, color = tokens.textSecondary, maxLines = 1)
    }
}

private fun canWatchControl(session: ListenSessionState): Boolean =
    session.role == "host" || session.room?.settings?.allowMemberControl == true

private fun formatWatchPosition(position: Long, duration: Long): String {
    fun format(value: Long): String {
        val seconds = (value / 1_000L).coerceAtLeast(0L)
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }
    return "${format(position)} / ${if (duration > 0L) format(duration) else "--:--"}"
}
