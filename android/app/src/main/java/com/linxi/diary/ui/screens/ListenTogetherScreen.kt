package com.linxi.diary.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.linxi.diary.data.ApiException
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.ClientRuntimeConfig
import com.linxi.diary.data.NeteaseAccountStore
import com.linxi.diary.data.NeteaseClient
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.ui.NeteaseWebLoginActivity
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 网易云一起听：登录、搜索、解析和播放都在每台设备本地完成。
 * 房间只携带 source/song_id/元数据/时间轴，永远不携带第三方 Cookie 或播放 URL。
 */
@Composable
fun ListenTogetherScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roomPlayer by NeteasePlaybackManager.stateFlow.collectAsStateWithLifecycle()
    var loggedIn by remember { mutableStateOf(NeteaseAccountStore.isLoggedIn()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NeteaseTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var resolvingId by remember { mutableStateOf<Long?>(null) }
    var roomId by remember { mutableStateOf("") }
    var sessionToken by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf("") }
    var room by remember { mutableStateOf<ListenRoomSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            loggedIn = NeteaseAccountStore.isLoggedIn()
            if (loggedIn) info = "网易云账号已绑定，可以搜索歌曲"
        }
    }

    fun showFailure(t: Throwable, fallback: String = "操作失败，请稍后重试") {
        error = when (t) {
            is ApiException -> t.message
            else -> t.message?.takeIf(String::isNotBlank) ?: fallback
        }
    }

    fun applyRoomResponse(response: JSONObject) {
        response.optJSONObject("room")?.let { json ->
            val snapshot = ListenRoomSnapshot.fromJson(json)
            room = snapshot
            if (snapshot.roomId.isNotBlank()) roomId = snapshot.roomId
            syncRoomToLocalPlayer(snapshot, scope) { showFailure(it, "无法解析这首网易云歌曲") }
        }
        response.optString("role").takeIf(String::isNotBlank)?.let { role = it }
        response.optString("token").takeIf(String::isNotBlank)?.let { sessionToken = it }
        response.optString("room_id").takeIf(String::isNotBlank)?.let { roomId = it }
    }

    fun launchRequest(block: suspend () -> JSONObject, success: (JSONObject) -> Unit = {}) {
        if (loading) return
        loading = true
        error = null
        info = null
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    applyRoomResponse(it)
                    success(it)
                }
                .onFailure { showFailure(it) }
            loading = false
        }
    }

    suspend fun enterOrCreateRoom(): JSONObject {
        return try {
            ApiClient.joinCurrentListenRoom()
        } catch (cause: ApiException) {
            if (cause.bizCode == 1035) ApiClient.createListenRoom() else throw cause
        }
    }

    LaunchedEffect(ClientRuntimeConfig.listenTogetherEnabled) {
        if (!ClientRuntimeConfig.listenTogetherEnabled || room != null) return@LaunchedEffect
        loading = true
        runCatching { enterOrCreateRoom() }
            .onSuccess(::applyRoomResponse)
            .onFailure { showFailure(it, "进入一起听失败，请稍后重试") }
        loading = false
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            if (!NeteaseClient.verifyLogin()) {
                loggedIn = false
                error = "网易云账号已失效，请重新绑定"
            }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000L)
            NeteasePlaybackManager.refreshProgress()
        }
    }

    DisposableEffect(roomId, sessionToken) {
        val token = sessionToken
        var socket: WebSocket? = null
        if (roomId.isNotBlank() && !token.isNullOrBlank()) {
            socket = ApiClient.openListenSocket(roomId, token, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch { connected = true; error = null }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch {
                        runCatching {
                            val event = JSONObject(text)
                            event.optJSONObject("room")?.let {
                                val snapshot = ListenRoomSnapshot.fromJson(it)
                                room = snapshot
                                syncRoomToLocalPlayer(snapshot, scope) {
                                    showFailure(it, "无法解析伴侣正在播放的网易云歌曲")
                                }
                            }
                            if (event.optString("type") == "room_closed") {
                                sessionToken = null
                                room = null
                                info = "一起听房间已关闭"
                            }
                        }.onFailure { Logs.w("Listen", "invalid room event", it) }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scope.launch { connected = false }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scope.launch {
                        connected = false
                        error = "一起听连接中断，正在使用状态恢复"
                    }
                    Logs.w("Listen", "room websocket failed", t)
                }
            })
        }
        onDispose {
            socket?.close(1000, "screen closed")
            connected = false
        }
    }

    // 监听端和后台切回页面时用 REST 修复 WS 丢包；播放中的房主每 5 秒锚定一次时间轴。
    LaunchedEffect(roomId, sessionToken, connected, role) {
        if (roomId.isBlank() || sessionToken.isNullOrBlank()) return@LaunchedEffect
        while (isActive) {
            delay(if (role == "host") 5_000L else 30_000L)
            val local = NeteasePlaybackManager.refreshProgress()
            if (role == "host" && local.track != null && room?.state?.songId == local.track.id) {
                runCatching {
                    ApiClient.controlListenRoom(
                        roomId,
                        JSONObject().apply {
                            put("action", "heartbeat")
                            put("playing", local.playing)
                            put("position_ms", local.positionMs)
                        },
                    )
                }
            } else {
                runCatching { ApiClient.listenRoomState(roomId) }
                    .onSuccess { state ->
                        val snapshot = ListenRoomSnapshot.fromJson(state)
                        room = snapshot
                        syncRoomToLocalPlayer(snapshot, scope) {
                            showFailure(it, "无法解析伴侣正在播放的网易云歌曲")
                        }
                    }
            }
        }
    }

    fun search() {
        if (!loggedIn) {
            error = "请先绑定网易云账号"
            return
        }
        if (query.trim().isBlank()) {
            error = "请输入歌曲名或歌手"
            return
        }
        searching = true
        error = null
        scope.launch {
            runCatching { NeteaseClient.searchSongs(query) }
                .onSuccess { results = it; if (it.isEmpty()) info = "没有找到匹配歌曲" }
                .onFailure { showFailure(it, "网易云搜索失败，请检查网络") }
            searching = false
        }
    }

    fun chooseTrack(track: NeteaseTrack) {
        if (room == null) {
            error = "一起听房间尚未准备好，请稍后重试"
            return
        }
        if (!canControl(role, room)) {
            error = "房主暂未允许成员控制播放"
            return
        }
        resolvingId = track.id
        error = null
        scope.launch {
            runCatching {
                val url = NeteaseClient.resolvePlaybackUrl(track.id)
                NeteasePlaybackManager.play(track, url)
                ApiClient.controlListenRoom(
                    roomId,
                    JSONObject().apply {
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
                )
            }.onSuccess { info = "已开始与伴侣一起听" }
                .onFailure { showFailure(it, "无法播放这首网易云歌曲") }
            resolvingId = null
        }
    }

    fun togglePlayback() {
        val current = room?.state ?: return
        if (!canControl(role, room)) {
            error = "房主暂未允许成员控制播放"
            return
        }
        val nextPlaying = !current.playing
        if (nextPlaying) NeteasePlaybackManager.resume() else NeteasePlaybackManager.pause()
        launchRequest({
            ApiClient.controlListenRoom(
                roomId,
                JSONObject().put("action", if (nextPlaying) "play" else "pause"),
            )
        })
    }

    val loginIntent = remember { Intent(context, NeteaseWebLoginActivity::class.java) }
    KernelScreen(title = "一起听", navigationIcon = { BackAction(onBack) }, loading = loading) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
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
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        if (room == null) "正在进入情侣房间…" else "${roomId.uppercase()} · ${if (role == "host") "房主" else "成员"}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            room == null -> "绑定情侣后自动使用双方唯一房间"
                            connected -> "已连接 · ${room?.members ?: 0} 人在线"
                            else -> "连接恢复中…"
                        },
                        fontSize = 13.sp,
                        color = if (connected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    val state = room?.state
                    Text(state?.title?.ifBlank { "还没有选择歌曲" } ?: "还没有选择歌曲", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(state?.artist?.ifBlank { "搜索网易云歌曲开始一起听" } ?: "搜索网易云歌曲开始一起听", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (state?.songId ?: 0L > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("网易云歌曲 · ${formatPosition(roomPlayer.positionMs, state.durationMs)}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(12.dp))
                        LxButton(
                            text = if (state.playing) "暂停" else "继续播放",
                            onClick = ::togglePlayback,
                            enabled = !loading && canControl(role, room) && roomPlayer.track?.id == state.songId,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (role == "member" && room?.settings?.allowMemberControl == false) {
                        Spacer(Modifier.height(8.dp))
                        Text("房主暂未开放成员控制播放。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }

        error?.let { message -> item { Text(message, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) } }
        info?.let { message -> item { Text(message, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) } }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LxButton(
                    text = if (loading) "进入中…" else "重新进入房间",
                    onClick = { launchRequest({ enterOrCreateRoom() }) },
                    enabled = !loading && ClientRuntimeConfig.listenTogetherEnabled,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.weight(1f),
                )
                if (room != null) {
                    LxButton(
                        text = "离开",
                        onClick = {
                            launchRequest({ ApiClient.leaveListenRoom(roomId) }) {
                                room = null
                                sessionToken = null
                                role = ""
                            }
                        },
                        enabled = !loading,
                        variant = LxButtonVariant.Negative,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchTrackRow(track: NeteaseTrack, resolving: Boolean, onClick: (NeteaseTrack) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(track.title, fontWeight = FontWeight.Medium)
            Text(
                listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            LxButton(
                text = if (resolving) "解析中…" else "一起播放",
                onClick = { onClick(track) },
                enabled = !resolving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun canControl(role: String, room: ListenRoomSnapshot?): Boolean =
    role == "host" || room?.settings?.allowMemberControl == true

private fun syncRoomToLocalPlayer(
    snapshot: ListenRoomSnapshot,
    scope: CoroutineScope,
    onFailure: (Throwable) -> Unit,
) {
    val state = snapshot.state
    if (state.source != "netease" || state.songId <= 0L) return
    val remote = state.toTrack()
    scope.launch {
        runCatching {
            val local = NeteasePlaybackManager.stateFlow.value
            if (local.track?.id != remote.id) {
                val url = NeteaseClient.resolvePlaybackUrl(remote.id)
                if (state.playing) NeteasePlaybackManager.play(remote, url, state.positionMs)
                else {
                    NeteasePlaybackManager.play(remote, url, state.positionMs)
                    NeteasePlaybackManager.pause()
                }
            } else {
                val drift = kotlin.math.abs(local.positionMs - state.positionMs)
                if (drift > 2_000L) NeteasePlaybackManager.seekTo(state.positionMs)
                if (state.playing && !local.playing) NeteasePlaybackManager.resume()
                if (!state.playing && local.playing) NeteasePlaybackManager.pause()
            }
        }.onFailure(onFailure)
    }
}

private data class ListenRoomSnapshot(
    val roomId: String,
    val members: Int,
    val settings: ListenRoomSettings,
    val state: ListenState,
) {
    companion object {
        fun fromJson(json: JSONObject): ListenRoomSnapshot {
            val state = json.optJSONObject("state") ?: JSONObject()
            val settings = json.optJSONObject("settings") ?: JSONObject()
            return ListenRoomSnapshot(
                roomId = json.optString("room_id"),
                members = json.optInt("members", 0),
                settings = ListenRoomSettings(settings.optBoolean("allow_member_control", true)),
                state = ListenState(
                    source = state.optString("source"),
                    songId = state.optLong("song_id", 0L),
                    title = state.optString("title"),
                    artist = state.optString("artist"),
                    album = state.optString("album"),
                    coverUrl = state.optString("cover_url"),
                    playing = state.optBoolean("playing"),
                    positionMs = state.optLong("position_ms").coerceAtLeast(0L),
                    durationMs = state.optLong("duration_ms").coerceAtLeast(0L),
                ),
            )
        }
    }
}

private data class ListenRoomSettings(val allowMemberControl: Boolean)

private data class ListenState(
    val source: String,
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
) {
    fun toTrack() = NeteaseTrack(songId, title, artist, album, coverUrl, durationMs)
}

private fun formatPosition(position: Long, duration: Long): String {
    fun format(value: Long): String {
        val seconds = (value / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }
    return "${format(position)} / ${if (duration > 0L) format(duration) else "--:--"}"
}
