package com.linxi.diary.sync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.linxi.diary.BuildConfig
import com.linxi.diary.MainActivity
import com.linxi.diary.core.DeviceStatus
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.core.MusicInfo
import com.linxi.diary.core.RingHelper
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket 实时同步：
 * - 连接鉴权（token query）
 * - 30s ping 心跳（OkHttp 自动），服务端 90s 超时清理
 * - 断线指数退避重连：1s→2s→4s→…上限 60s
 * - 服务端事件分发：状态/求陪伴/求冷静/响铃/待办/日记
 */
object StatusSyncManager {

    // WS 地址：优先用构建期注入的 WS_URL，否则由 BASE_URL 推导（https→wss 同 host）。
    private val WS_URL: String = BuildConfig.WS_URL.ifBlank { deriveWsUrl(BuildConfig.BASE_URL) }

    private fun deriveWsUrl(baseUrl: String): String {
        val scheme = if (baseUrl.startsWith("https", ignoreCase = true)) "wss" else "ws"
        val host = baseUrl.substringAfter("://", baseUrl).substringBefore("/")
        return "$scheme://$host/ws"
    }

    private var ws: WebSocket? = null
    private var retry = 0
    private var connectionGeneration = 0L
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatcher = WsMessageDispatcher(
        refreshProfile = ProfileRuntime::refreshAsync,
        handleSensitive = ::handleInner,
    )

    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // 心跳保活
            .build()
    }

    fun init(app: Application) {
        appContext = app
    }

    @Synchronized
    fun connect() {
        if (!ProfileSyncPolicy.canConnectNow()) return
        val token = UserPrefs.token ?: return
        if (ws != null) return
        val generation = connectionGeneration
        val req = Request.Builder().url("$WS_URL?token=$token").build()
        ws = sharedClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, res: Response) {
                synchronized(this@StatusSyncManager) {
                    if (generation != connectionGeneration) return
                    retry = 0
                }
                pushNow() // 上线立即上报一次全量状态
            }

            override fun onMessage(w: WebSocket, text: String) = handle(text)

            override fun onFailure(w: WebSocket, t: Throwable, res: Response?) {
                synchronized(this@StatusSyncManager) {
                    if (ws !== w) return
                    ws = null
                }
                scheduleReconnect(generation, res?.code)
            }

            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                synchronized(this@StatusSyncManager) {
                    if (ws !== w) return
                    ws = null
                }
                scheduleReconnect(generation, httpCode = null)
            }
        })
    }

    @Synchronized
    fun disconnect() {
        connectionGeneration++
        retry = 0
        reconnectJob?.cancel()
        reconnectJob = null
        val socket = ws
        ws = null
        socket?.close(1000, "session ended")
    }

    private fun scheduleReconnect(generation: Long, httpCode: Int?) {
        synchronized(this) {
            if (generation != connectionGeneration) return
            if (!WsReconnectPolicy.shouldReconnect(httpCode)) {
                Logs.w("Sync", "WS 鉴权失败($httpCode)，停止重连")
                return
            }
            if (!ProfileSyncPolicy.canConnectNow()) return
            val delayMs = WsReconnectPolicy.backoffMillis(retry)
            retry++
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                if (generation == connectionGeneration && ProfileSyncPolicy.canConnectNow()) {
                    connect()
                }
            }
        }
    }

    /** 上报本机全量状态。共享开关关闭时不推送。关键变更调用 pushNow() 即时推送 */
    fun pushNow() {
        try {
            if (!SharingRuntimePolicy.canRunNow()) return
            val w = ws ?: return
            val s = DeviceStatusHolder.current ?: return
            w.send(JSONObject().apply {
                put("type", "status_update")
                put("data", s.toJson())
            }.toString())
        } catch (t: Throwable) {
            Logs.w("Sync", "pushNow 失败", t)
        }
    }

    /** 触发一次性事件：comfort_request / calm_request / ring_request */
    fun sendEvent(type: String) {
        try {
            if (!SharingRuntimePolicy.canRunNow()) return
            ws?.send(JSONObject().apply {
                put("type", type)
                put("data", JSONObject().put("ts", System.currentTimeMillis()))
            }.toString())
        } catch (t: Throwable) {
            Logs.w("Sync", "sendEvent($type) 失败", t)
        }
    }

    private fun handle(text: String) {
        try {
            dispatcher.dispatch(text, SharingRuntimePolicy.canRunNow())
        } catch (t: Throwable) {
            Logs.e("Sync", "处理 WS 消息异常", t)
        }
    }

    private fun handleInner(m: JSONObject) {
        when (m.getString("type")) {
            "partner_status" -> {
                val j = m.getJSONObject("data")
                DeviceStatusHolder.partner = DeviceStatus(
                    batteryLevel = j.optInt("battery"),
                    isCharging = j.optBoolean("charging"),
                    screenOn = j.optBoolean("screen_on"),
                    isLocked = j.optBoolean("locked"),
                    foregroundApp = j.optJSONObject("foreground_app")?.let {
                        it.optString("pkg") to it.optString("name")
                    },
                    music = j.optJSONObject("music")?.let {
                        MusicInfo(it.optString("title"), it.optString("artist"), it.optBoolean("playing"))
                    },
                    ssid = j.optString("ssid").takeIf { it.isNotEmpty() && it != "null" },
                    network = j.optString("network").ifEmpty { "wifi" },
                    ts = j.optLong("ts")
                )
                StatusForegroundService.refreshCard(appContext) // 更新常驻卡片
            }
            "comfort_request" -> notifyEvent("对方 需要你的陪伴", "点击回应 TA")
            "calm_request" -> notifyEvent("对方 现在需要冷静", "暂时放缓沟通，给 TA 一点空间")
            "ring_request" -> appContext?.let {
                RingHelper.forceRing(it)
                notifyRing()
            }
            "todo_new" -> {
                val title = m.optJSONObject("data")?.optString("title") ?: "新待办"
                notifyEvent("对方 给你添加了待办", title)
            }
            "todo_completed" -> {
                val title = m.optJSONObject("data")?.optString("title") ?: ""
                notifyEvent("待办已完成", "对方 完成了：$title")
            }
            "diary_new" -> {
                val title = m.optJSONObject("data")?.optString("title") ?: "新日记"
                notifyEvent("对方 发布了新日记", title)
            }
            "low_battery" -> {
                val level = m.optJSONObject("data")?.optInt("battery") ?: 0
                notifyEvent("对方 电量不足 15%", "TA 电量仅剩 $level%，记得提醒充电")
            }
            "wifi_joined" -> notifyEvent("对方 已连接你关注的 WiFi", "TA 已到家/到达常用地点")
            "todo_remind" -> {
                val title = m.optJSONObject("data")?.optString("title") ?: "待办"
                val remindType = m.optJSONObject("data")?.optInt("remind_type") ?: 0
                if (remindType == 1) {
                    appContext?.let { RingHelper.todoStrongRemind(it, title) }
                } else {
                    notifyEvent("待办提醒", title)
                }
            }
        }
    }

    private fun notifyEvent(title: String, body: String) {
        try {
            val c = appContext ?: return
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(StatusForegroundService.CHANNEL_EVENT,
                    "互动提醒", NotificationManager.IMPORTANCE_HIGH))
            val openApp = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, StatusForegroundService.CHANNEL_EVENT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), n)
        } catch (t: Throwable) {
            Logs.w("Sync", "notifyEvent 失败", t)
        }
    }

    private fun notifyRing() {
        try {
            val c = appContext ?: return
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(StatusForegroundService.CHANNEL_RING,
                    "紧急响铃", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(10002, NotificationCompat.Builder(c, StatusForegroundService.CHANNEL_RING)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("紧急响铃已触发")
                .setContentText("对方正在找你，点击打开 APP")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .build())
        } catch (t: Throwable) {
            Logs.w("Sync", "notifyRing 失败", t)
        }
    }

}
