package com.linxi.diary.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.linxi.diary.ui.components.LxSurface
import com.linxi.diary.ui.components.LxSurfaceTone
import com.linxi.diary.ui.components.NeteaseTrackCover
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Together Listening is a view over [ListenSessionController]. The controller
 * owns the WebSocket and heartbeat, so leaving this page does not stop the room
 * or make the global mini-player show a stale connection.
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
        // The server is authoritative. The controller resolves the local URL
        // only after this command succeeds, so a forbidden member never hears a
        // song that the room rejected.
        ListenSessionController.control(
            action = JSONObject().apply {
                put("action", "set_track")
                put("source", "netease")
                put("song_id", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("album", track.album)
                put("cover_url", track.coverUrl)
                put("duration_ms", track.durationMs)
                put("playing", true)
            },
            successMessage = "已开始与伴侣一起听",
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
    // A command (set track / pause / leave) may briefly use the same controller
    // loading flag, but it must not replace the whole page with the initial
    // spinner. Keep the room/search controls visible while the authoritative
    // command is in flight.
    val initialLoading = session.loading && session.room == null
    KernelScreen(title = "一起听", navigationIcon = { BackAction(onBack) }, loading = initialLoading) {
        item {
            LxSurface(Modifier.fillMaxWidth().padding(top = 12.dp), tone = LxSurfaceTone.Raised) {
                Column(Modifier.padding(18.dp)) {
                    Text("网易云一起听", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "两台设备各自使用自己的网易云账号播放同一首歌。林曦服务端只同步歌曲 ID、播放状态和进度，不接触网易云 Cookie。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!loggedIn) {
                        LxButton(
                            text = "绑定网易云账号",
                            onClick = { loginLauncher.launch(loginIntent) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("网易云账号已绑定", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(
                                value = query,
                                onValueChange = { query = it.take(80) },
                                label = "歌曲名或歌手",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            LxButton(
                                text = if (searching) "搜索中…" else "搜索",
                                onClick = ::search,
                                enabled = !searching,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        results.take(20).forEach { track ->
            item { SearchTrackRow(track, resolvingId == track.id, ::chooseTrack) }
        }

        item {
            RoomStatusCard(
                session = session,
                playerPosition = player.positionMs,
                onToggle = ::togglePlayback,
                onRetry = ListenSessionController::retry,
            )
        }

        session.error?.let { message ->
            item {
                Text(message, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
        }
        (error ?: session.info ?: info)?.let { message ->
            item {
                Text(
                    message,
                    color = if (error != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
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

@Composable
private fun RoomStatusCard(
    session: ListenSessionState,
    playerPosition: Long,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
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
            Spacer(Modifier.height(10.dp))
            val state = session.room?.state
            if (state?.isWatch == true) {
                Text(
                    state.watchTitle.ifBlank { "正在一起看" },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "当前房间同步的是观看链接，请进入“一起看”管理播放",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    state?.title?.ifBlank { "还没有选择歌曲" } ?: "还没有选择歌曲",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    state?.artist?.ifBlank { "搜索网易云歌曲开始一起听" } ?: "搜索网易云歌曲开始一起听",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            state?.takeIf { !it.isWatch && it.songId > 0L }?.let { current ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "网易云歌曲 · ${formatListenPosition(playerPosition, current.durationMs)}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                LxButton(
                    text = if (current.playing) "暂停" else "继续播放",
                    onClick = onToggle,
                    enabled = !session.loading && canControl(session),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (session.role == "member" && session.room?.settings?.allowMemberControl == false) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "房主暂未开放成员控制播放。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (session.room == null && !session.loading) {
                Spacer(Modifier.height(8.dp))
                LxButton("重试", onClick = onRetry, variant = LxButtonVariant.Neutral)
            }
        }
    }
}

@Composable
private fun SearchTrackRow(track: NeteaseTrack, resolving: Boolean, onClick: (NeteaseTrack) -> Unit) {
    LxSurface(Modifier.fillMaxWidth().padding(top = 8.dp), tone = LxSurfaceTone.Raised) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                NeteaseTrackCover(track, modifier = Modifier.size(56.dp), description = "${track.title}封面")
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(track.title, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LxButton(
                text = if (resolving) "提交中…" else "一起播放",
                onClick = { onClick(track) },
                enabled = !resolving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun canControl(session: ListenSessionState): Boolean =
    session.role == "host" || session.room?.settings?.allowMemberControl == true
