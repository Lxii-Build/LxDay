package com.linxi.diary.sync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.linxi.diary.MainActivity
import com.linxi.diary.core.DeviceStatus
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.core.MusicInfo
import com.linxi.diary.core.RingHelper
import com.linxi.diary.service.StatusForegroundService
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

    private const val WS_URL = "wss://api.linxi.app/ws"

    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var retry = 0
    private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(app: Application) {
        appContext = app
    }

    fun connect() {
        val token = UserPrefs.token ?: return
        if (ws != null) return
        val c = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // 心跳保活
            .build()
        client = c
        val req = Request.Builder().url("$WS_URL?token=$token").build()
        ws = c.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, res: Response) {
                retry = 0
                pushNow() // 上线立即上报一次全量状态
            }

            override fun onMessage(w: WebSocket, text: String) = handle(text)

            override fun onFailure(w: WebSocket, t: Throwable, res: Response?) {
                ws = null
                scheduleReconnect()
            }

            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                ws = null
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        val delayMs = (1L shl retry.coerceAtMost(6)) * 1000L
        retry++
        scope.launch {
            delay(delayMs)
            connect()
        }
    }

    /** 上报本机全量状态。共享开关关闭时不推送。关键变更调用 pushNow() 即时推送 */
    fun pushNow() {
        if (!UserPrefs.sharingEnabled) return
        val w = ws ?: return
        val s = DeviceStatusHolder.current ?: return
        w.send(JSONObject().apply {
            put("type", "status_update")
            put("data", s.toJson())
        }.toString())
    }

    /** 触发一次性事件：comfort_request / calm_request / ring_request */
    fun sendEvent(type: String) {
        ws?.send(JSONObject().apply {
            put("type", type)
            put("data", JSONObject().put("ts", System.currentTimeMillis()))
        }.toString())
    }

    private fun handle(text: String) {
        val m = JSONObject(text)
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
    }

    private fun notifyRing() {
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
    }

}
