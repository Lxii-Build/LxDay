package com.linxi.diary.data

import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * The room/session owner for Together Listening.
 *
 * The screen is a view of this state, not the owner of the socket.  Keeping the
 * session here means opening the full player, switching tabs, or leaving the
 * Together page does not silently disconnect a couple's room.  The only code
 * that sends a user playback command is [control]; remote snapshots are applied
 * through [applyRoom] and never call the control endpoint again.
 */
data class ListenRoomSettings(val allowMemberControl: Boolean)

data class ListenState(
    val kind: String,
    val source: String,
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val watchUrl: String,
    val watchTitle: String,
    val watchPositionMs: Long,
    val watchDurationMs: Long,
    val watchPlaying: Boolean,
) {
    val isWatch: Boolean get() = kind == "watch"
    fun toTrack() = NeteaseTrack(songId, title, artist, album, coverUrl, durationMs)
}

data class ListenRoomSnapshot(
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
                members = json.optInt("members", 0).coerceAtLeast(0),
                settings = ListenRoomSettings(settings.optBoolean("allow_member_control", true)),
                state = ListenState(
                    kind = state.optString("kind", "audio").ifBlank { "audio" },
                    source = state.optString("source"),
                    songId = state.optLong("song_id", 0L).coerceAtLeast(0L),
                    title = state.optString("title"),
                    artist = state.optString("artist"),
                    album = state.optString("album"),
                    coverUrl = state.optString("cover_url"),
                    playing = state.optBoolean("playing"),
                    positionMs = state.optLong("position_ms").coerceAtLeast(0L),
                    durationMs = state.optLong("duration_ms").coerceAtLeast(0L),
                    watchUrl = state.optString("watch_url"),
                    watchTitle = state.optString("watch_title"),
                    watchPositionMs = state.optLong("watch_position_ms").coerceAtLeast(0L),
                    watchDurationMs = state.optLong("watch_duration_ms").coerceAtLeast(0L),
                    watchPlaying = state.optBoolean("watch_playing"),
                ),
            )
        }
    }
}

data class ListenSessionState(
    val room: ListenRoomSnapshot? = null,
    val roomId: String = "",
    val role: String = "",
    val connected: Boolean = false,
    val loading: Boolean = false,
    val hasSessionToken: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

object ListenSessionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = kotlinx.coroutines.flow.MutableStateFlow(ListenSessionState())
    private var socket: WebSocket? = null
    private var sessionToken: String? = null
    private var ownerToken: String? = null
    private var startJob: Job? = null
    private var syncJob: Job? = null
    private var reconnectJob: Job? = null
    private var generation = 0L

    val stateFlow: kotlinx.coroutines.flow.StateFlow<ListenSessionState> = state

    /** Start once for the currently authenticated user. Safe to call repeatedly. */
    fun ensureStarted() {
        if (!ClientRuntimeConfig.listenTogetherEnabled) return
        val token = UserPrefs.token?.takeIf(String::isNotBlank) ?: return
        if (UserPrefs.pairId <= 0L) return
        if (ownerToken != null && ownerToken != token) clearInternal()
        ownerToken = token
        if (state.value.room != null || startJob?.isActive == true) {
            if (state.value.room != null && !state.value.connected && reconnectJob?.isActive != true) {
                reconnect(state.value.roomId, generation)
            }
            return
        }
        val runGeneration = generation
        startJob = scope.launch {
            update { it.copy(loading = true, error = null, info = null) }
            runCatching { joinOrCreateRoom() }
                .onSuccess { response ->
                    applyResponse(response, runGeneration)
                    connect(runGeneration)
                    startSync(runGeneration)
                }
                .onFailure { error ->
                    if (runGeneration == generation) {
                        update { it.copy(loading = false, error = error.message ?: "进入一起听失败，请稍后重试") }
                    }
                }
            if (runGeneration == generation) update { it.copy(loading = false) }
        }
    }

    fun retry() {
        if (state.value.room == null) ensureStarted()
        else reconnect(state.value.roomId, generation)
    }

    /** Send a user intent and only apply the returned authoritative room state. */
    fun control(action: JSONObject, successMessage: String? = null) {
        val roomId = state.value.roomId
        if (roomId.isBlank() || !canControl(state.value)) return
        val runGeneration = generation
        scope.launch {
            update { it.copy(loading = true, error = null, info = null) }
            runCatching { ApiClient.controlListenRoom(roomId, action) }
                .onSuccess { response ->
                    applyResponse(response, runGeneration)
                    successMessage?.let { message ->
                        if (runGeneration == generation) update { it.copy(info = message) }
                    }
                }
                .onFailure { error ->
                    if (runGeneration == generation) {
                        update { it.copy(error = error.message ?: "播放操作失败，请稍后重试") }
                    }
                }
            if (runGeneration == generation) update { it.copy(loading = false) }
        }
    }

    /** Publish a URL/timeline for Together Watching through the same
     * authoritative room and permission path as Together Listening. */
    fun setWatch(url: String, title: String, durationMs: Long = 0L) {
        control(
            JSONObject().apply {
                put("action", "watch_set")
                put("watch_url", url.trim())
                put("watch_title", title.trim())
                put("watch_duration_ms", durationMs.coerceAtLeast(0L))
                put("watch_playing", false)
            },
            successMessage = "观看链接已同步给伴侣",
        )
    }

    fun clearWatch() = control(JSONObject().put("action", "watch_clear"), "已清除一起看的内容")

    /** Heartbeats are deliberately quiet: they must not replace the page with
     * a spinner every few seconds while a direct video is playing. */
    fun heartbeatWatch(positionMs: Long, playing: Boolean) {
        val room = state.value.room ?: return
        if (!room.state.isWatch || !canControl(state.value)) return
        val roomId = state.value.roomId
        if (roomId.isBlank()) return
        val runGeneration = generation
        scope.launch {
            runCatching {
                ApiClient.controlListenRoom(
                    roomId,
                    JSONObject().apply {
                        put("action", "watch_heartbeat")
                        put("watch_position_ms", positionMs.coerceAtLeast(0L))
                        put("watch_playing", playing)
                    },
                )
            }.onSuccess { response -> applyResponse(response, runGeneration) }
                .onFailure { error ->
                    if (runGeneration == generation) update { it.copy(error = error.message ?: "一起看进度同步失败") }
                }
        }
    }

    fun leave() {
        val roomId = state.value.roomId
        if (roomId.isBlank()) {
            clearInternal()
            return
        }
        val runGeneration = generation
        scope.launch {
            update { it.copy(loading = true, error = null, info = null) }
            runCatching { ApiClient.leaveListenRoom(roomId) }
                .onSuccess {
                    if (runGeneration == generation) {
                        clearInternal()
                        update { it.copy(info = "已离开一起听房间") }
                    }
                }
                .onFailure { error ->
                    if (runGeneration == generation) {
                        update { it.copy(error = error.message ?: "离开房间失败，请稍后重试") }
                    }
                }
            if (runGeneration == generation) update { it.copy(loading = false) }
        }
    }

    /** Called when auth expires or the user explicitly signs out. */
    fun clearForLogout() = clearInternal()

    private suspend fun joinOrCreateRoom(): JSONObject {
        return try {
            ApiClient.joinCurrentListenRoom()
        } catch (cause: ApiException) {
            if (cause.bizCode == 1035) ApiClient.createListenRoom() else throw cause
        }
    }

    private fun applyResponse(response: JSONObject, runGeneration: Long) {
        if (runGeneration != generation) return
        val roomJson = response.optJSONObject("room") ?: response.takeIf { it.has("state") }
        roomJson?.let { applyRoom(ListenRoomSnapshot.fromJson(it), runGeneration) }
        response.optString("role").takeIf(String::isNotBlank)?.let { role ->
            update { it.copy(role = role) }
        }
        response.optString("token").takeIf(String::isNotBlank)?.let { token ->
            sessionToken = token
            update { it.copy(hasSessionToken = true) }
        }
        response.optString("room_id").takeIf(String::isNotBlank)?.let { roomId ->
            update { it.copy(roomId = roomId) }
        }
    }

    private fun applyRoom(snapshot: ListenRoomSnapshot, runGeneration: Long) {
        if (runGeneration != generation) return
        update {
            it.copy(
                room = snapshot,
                roomId = snapshot.roomId.ifBlank { it.roomId },
                error = null,
            )
        }
        syncRemoteToLocal(snapshot, runGeneration)
    }

    private fun connect(runGeneration: Long) {
        if (runGeneration != generation) return
        val roomId = state.value.roomId
        val token = sessionToken
        if (roomId.isBlank() || token.isNullOrBlank()) return
        socket?.close(1000, "replace session")
        socket = null
        socket = ApiClient.openListenSocket(roomId, token, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (runGeneration == generation && socket === webSocket) {
                    update { it.copy(connected = true, error = null) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (runGeneration != generation || socket !== webSocket) return
                scope.launch {
                    runCatching {
                        val event = JSONObject(text)
                        if (event.optString("type") == "room_closed") {
                            clearInternal("一起听房间已关闭")
                        } else {
                            event.optJSONObject("room")?.let {
                                applyRoom(ListenRoomSnapshot.fromJson(it), runGeneration)
                            }
                        }
                    }.onFailure {
                        if (runGeneration == generation && socket === webSocket) {
                            update { it.copy(error = "一起听消息格式异常，请重新进入") }
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (runGeneration == generation && socket === webSocket) {
                    update { it.copy(connected = false) }
                    scheduleReconnect(runGeneration)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (runGeneration == generation && socket === webSocket) {
                    update { it.copy(connected = false, error = "一起听连接中断，正在恢复…") }
                    scheduleReconnect(runGeneration)
                }
            }
        })
    }

    private fun scheduleReconnect(runGeneration: Long) {
        if (reconnectJob?.isActive == true || state.value.room == null) return
        reconnectJob = scope.launch {
            delay(3_000L)
            if (runGeneration == generation) refreshSession(runGeneration)
        }
    }

    private fun reconnect(roomId: String, runGeneration: Long) {
        if (roomId.isBlank() || runGeneration != generation) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch { refreshSession(runGeneration) }
    }

    private suspend fun refreshSession(runGeneration: Long) {
        // Re-join instead of merely polling state: a second device or a
        // server restart may have revoked this client's in-memory WS token.
        // REST state can still succeed in that situation, which otherwise
        // leaves the UI looking healthy while every socket retry is 401.
        runCatching { joinOrCreateRoom() }
            .onSuccess { response ->
                applyResponse(response, runGeneration)
                connect(runGeneration)
                startSync(runGeneration)
            }
            .onFailure { error ->
                if (runGeneration == generation) {
                    update { it.copy(connected = false, error = error.message ?: "一起听状态恢复失败") }
                }
            }
    }

    private fun startSync(runGeneration: Long) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive && runGeneration == generation && sessionToken != null) {
                delay(if (state.value.role == "host") 5_000L else 30_000L)
                val snapshot = state.value.room ?: continue
                val local = NeteasePlaybackManager.refreshProgress()
                if (state.value.role == "host" && !snapshot.state.isWatch && local.track?.id == snapshot.state.songId) {
                    runCatching {
                        ApiClient.controlListenRoom(
                            snapshot.roomId,
                            JSONObject().apply {
                                put("action", "heartbeat")
                                put("playing", local.playing)
                                put("position_ms", local.positionMs)
                            },
                        )
                    }.onSuccess { response -> applyResponse(response, runGeneration) }
                        .onFailure { update { it.copy(connected = false) } }
                } else {
                    runCatching { ApiClient.listenRoomState(snapshot.roomId) }
                        .onSuccess { response -> applyRoom(ListenRoomSnapshot.fromJson(response), runGeneration) }
                        .onFailure { update { it.copy(connected = false) } }
                }
            }
        }
    }

    private fun syncRemoteToLocal(snapshot: ListenRoomSnapshot, runGeneration: Long) {
        val remoteState = snapshot.state
        if (remoteState.source != "netease" || remoteState.songId <= 0L) return
        val remote = remoteState.toTrack()
        scope.launch {
            runCatching {
                if (runGeneration != generation) return@runCatching
                val local = NeteasePlaybackManager.stateFlow.value
                if (local.track?.id != remote.id) {
                    val url = NeteaseClient.resolvePlaybackUrl(remote.id)
                    NeteasePlaybackManager.play(remote, url, remoteState.positionMs)
                    if (!remoteState.playing) NeteasePlaybackManager.pause()
                } else {
                    if (kotlin.math.abs(local.positionMs - remoteState.positionMs) > 2_000L) {
                        NeteasePlaybackManager.seekTo(remoteState.positionMs)
                    }
                    if (remoteState.playing && !local.playing) NeteasePlaybackManager.resume()
                    if (!remoteState.playing && local.playing) NeteasePlaybackManager.pause()
                }
            }.onFailure { error ->
                if (runGeneration == generation) update { it.copy(error = error.message ?: "无法解析伴侣正在播放的歌曲") }
            }
        }
    }

    private fun canControl(current: ListenSessionState): Boolean =
        current.role == "host" || current.room?.settings?.allowMemberControl == true

    private fun update(transform: (ListenSessionState) -> ListenSessionState) {
        state.value = transform(state.value)
    }

    private fun clearInternal(message: String? = null) {
        generation++
        startJob?.cancel()
        syncJob?.cancel()
        reconnectJob?.cancel()
        socket?.close(1000, "session cleared")
        socket = null
        sessionToken = null
        ownerToken = null
        state.value = ListenSessionState(info = message)
    }
}

fun formatListenPosition(position: Long, duration: Long): String {
    fun format(value: Long): String {
        val seconds = (value / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }
    return "${format(position)} / ${if (duration > 0L) format(duration) else "--:--"}"
}
