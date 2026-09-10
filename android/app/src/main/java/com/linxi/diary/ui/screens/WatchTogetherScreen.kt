package com.linxi.diary.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.linxi.diary.ui.components.LxSurface
import com.linxi.diary.ui.components.LxSurfaceTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Together Watching shares the authenticated couple room with Together
 * Listening, but stores a distinct `kind=watch` timeline. Direct MP4/WebM
 * links can play in-app; ordinary video-page links open in the system browser
 * while both phones still share the URL and authoritative timeline.
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

    LaunchedEffect(Unit) {
        WatchPlaybackManager.init(context)
        ListenSessionController.ensureStarted()
    }

    // Apply room updates to a direct local player without echoing them back to
    // the server. A remote event is state, never a new user command.
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

    // Host heartbeats keep the shared timeline close to the local player. The
    // loop is intentionally quiet and never toggles the page loading state.
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

    // Keep a destructive confirmation open while the authoritative command is
    // in flight.  A failed request stays visible in the dialog so the user can
    // retry without accidentally sending the same command twice.
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
        item {
            LxSurface(Modifier.fillMaxWidth().padding(top = 12.dp), tone = LxSurfaceTone.Raised) {
                Column(Modifier.padding(18.dp)) {
                    Text("一起看", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "同步一个 HTTPS 视频或网页链接。直接 MP4/WebM 可在应用内播放，其他网站会交给系统浏览器；房间只同步链接和时间轴。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = url,
                        onValueChange = { url = it.take(4096) },
                        label = "HTTPS 视频或网页链接",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = title,
                        onValueChange = { title = it.take(160) },
                        label = "标题（可选）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    LxButton(
                        text = if (session.loading) "同步中…" else "同步给伴侣",
                        onClick = ::publish,
                        enabled = !session.loading && ClientRuntimeConfig.listenTogetherEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            WatchRoomCard(
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

        (error ?: session.error ?: session.info ?: info)?.let { message ->
            item {
                Text(
                    message,
                    color = if (error != null || session.error != null) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
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

@Composable
private fun WatchRoomCard(
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
        Column(Modifier.padding(18.dp)) {
            Text(
                if (session.room == null) "正在进入情侣房间…"
                else "${session.roomId.uppercase()} · ${if (session.role == "host") "房主" else "成员"}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    session.room == null -> "绑定情侣后自动使用双方唯一房间"
                    session.connected -> "已连接 · ${session.room.members} 人在线"
                    else -> "连接恢复中…"
                },
                fontSize = 13.sp,
                color = if (session.connected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            if (state == null) {
                Text("还没有同步观看内容", fontWeight = FontWeight.SemiBold)
                Text("粘贴链接后，两台设备会看到同一个时间轴。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else {
                Text(state.watchTitle.ifBlank { "一起看" }, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (WatchPlaybackManager.canPlayDirect(state.watchUrl)) "可尝试在应用内播放"
                    else "网页链接 · 将使用系统浏览器",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    formatWatchPosition(if (local.url == state.watchUrl) local.positionMs else state.watchPositionMs,
                        state.watchDurationMs),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LxButton("本机播放", onClick = onPlay, modifier = Modifier.weight(1f))
                    LxButton("打开网页", onClick = onOpenExternal, variant = LxButtonVariant.Neutral, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LxButton("−10 秒", onClick = { onSeek(-10_000L) }, enabled = canControl, variant = LxButtonVariant.Neutral, modifier = Modifier.weight(1f))
                    LxButton(if (local.playing) "暂停" else "继续", onClick = onToggle, enabled = canControl, modifier = Modifier.weight(1f))
                    LxButton("+10 秒", onClick = { onSeek(10_000L) }, enabled = canControl, variant = LxButtonVariant.Neutral, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                LxButton("清除内容", onClick = onClear, enabled = canControl, variant = LxButtonVariant.Negative, modifier = Modifier.fillMaxWidth())
                if (!canControl) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "房主暂未开放成员控制。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (session.room == null && !session.loading) {
                Spacer(Modifier.height(8.dp))
                LxButton("重试", onClick = onRetry, variant = LxButtonVariant.Neutral)
            }
        }
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
