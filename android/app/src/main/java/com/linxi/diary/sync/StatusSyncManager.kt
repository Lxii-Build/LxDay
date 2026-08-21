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
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.core.MusicInfo
import com.linxi.diary.core.RingHelper
import com.linxi.diary.service.NotificationChannels
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
        handleRejected = ::handleRejected,
    )

    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // 心跳保活：15s 一次，服务端判死 45s ≈ 3 个周期。心跳帧极小，流量可忽略。
            .pingInterval(SyncIntervalPolicy.HEARTBEAT_SECONDS, TimeUnit.SECONDS)
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

            override fun onClosing(w: WebSocket, code: Int, reason: String) {
                // 收到关闭帧即认为对端/自身连接将断，尽快让 UI 与通知反映离线。
                Logs.i("Sync", "WS closing: code=$code")
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
            // 带抖动：双方常在同一 WiFi，确定性退避会导致两台设备永远同步重连（惊群）。
            val delayMs = WsReconnectPolicy.backoffWithJitterMillis(retry)
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

    /**
     * 上报本机全量状态。共享开关关闭时不推送。关键变更调用 pushNow() 即时推送。
     *
     * **WS 断线时改走 HTTP 兜底**（Q36=B）。
     * 此前这里是 `val w = ws ?: return`——WS 没连上就把这次采集**直接扔掉**，
     * 而服务端当时也没有任何 REST 状态写入端点。于是地铁、电梯、切飞行模式期间
     * 状态完全停更，对方看到的是一个"看起来很正常"的旧值（UI 又不显示时效）。
     * 现在 WS 优先（省一次 HTTP 往返 + 服务端能即时转发给对方），失败才用 `POST /status`。
     */
    fun pushNow() {
        try {
            if (!SharingRuntimePolicy.canRunNow()) return
            val s = DeviceStatusHolder.current ?: return
            val payload = JSONObject().apply {
                put("type", "status_update")
                put("data", s.toJson())
            }.toString()

            val w = ws
            if (w != null && runCatching { w.send(payload) }.getOrDefault(false)) {
                return // WS 已送达
            }
            // WS 不可用或发送失败 → HTTP 兜底。
            // 起独立协程而非阻塞：pushNow 会在采集线程里被调用，不能在这里等网络。
            scope.launch {
                runCatching { ApiClient.reportStatus(s.toJson()) }
                    .onFailure { Logs.w("Sync", "REST 兜底上报失败", it) }
            }
        } catch (t: Throwable) {
            Logs.w("Sync", "pushNow 失败", t)
        }
    }

    /**
     * 触发一次性事件：comfort_request / calm_request / ring_request。
     *
     * @param ringId 互动请求的唯一 id，用于之后撤回（ring_cancel）时精确匹配。
     * @return 是否真的发出去了。**离线必须返回 false**——此前无论有没有连上都静默返回，
     *         UI 却立刻显示"已发送"，造成离线假成功。
     */
    fun sendEvent(type: String, ringId: String? = null): Boolean {
        return try {
            if (!SharingRuntimePolicy.canRunNow()) {
                Logs.w("Sync", "Sharing disabled; event dropped: $type")
                return false
            }
            val socket = ws ?: run {
                Logs.w("Sync", "WS offline; event not sent: $type")
                return false
            }
            socket.send(JSONObject().apply {
                put("type", type)
                put("data", JSONObject().apply {
                    put("ts", System.currentTimeMillis())
                    if (ringId != null) put("ring_id", ringId)
                })
            }.toString())
        } catch (t: Throwable) {
            Logs.w("Sync", "sendEvent($type) failed", t)
            false
        }
    }

    /** 发送方撤回响铃：让接收方立刻停止响铃。 */
    fun sendRingCancel(ringId: String?): Boolean = sendEvent(MSG_RING_CANCEL, ringId)

    /**
     * 接收方已关闭响铃的回执，供发送方结束"响铃中"倒计时并显示「对方已知悉」。
     * 由 RingStopReceiver / RingActivity 调用。
     */
    fun sendRingStopped(ringId: String?): Boolean = sendEvent(MSG_RING_STOPPED, ringId)

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
                val previous = DeviceStatusHolder.partner
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
                // 息屏/亮屏 → 静默通知（不弹不响，仅落通知栏）。
                maybeNotifyQuiet(previous, DeviceStatusHolder.partner)
            }
            "comfort_request" -> notifyEvent("对方 需要你的陪伴", "点击回应 TA")
            "calm_request" -> notifyEvent("对方 现在需要冷静", "暂时放缓沟通，给 TA 一点空间")
            "ring_request" -> appContext?.let { ctx ->
                // RingHelper 自己就会发带【停止响铃】按钮的全屏通知（同一个 id）。
                // 旧代码此处还额外 notify(10002) 一条普通通知，会把全屏通知连同停止按钮一起覆盖掉。
                val ringId = m.optJSONObject("data")?.optString("ring_id")?.takeIf { it.isNotEmpty() }
                RingHelper.forceRing(ctx, ringId)
            }
            // 发送方撤回：立即停止本机响铃，并回执让对端结束倒计时。
            MSG_RING_CANCEL -> appContext?.let { ctx ->
                val ringId = m.optJSONObject("data")?.optString("ring_id")?.takeIf { it.isNotEmpty() }
                if (RingHelper.stopRing(ctx, ringId, reason = "remote-cancel")) {
                    sendRingStopped(ringId)
                }
            }
            // 接收方已关闭：发送方侧结束"响铃中"状态。
            MSG_RING_STOPPED -> InteractionEvents.onRingStopped(
                m.optJSONObject("data")?.optString("ring_id")?.takeIf { it.isNotEmpty() }
            )
            "todo_new" -> {
                val title = m.optJSONObject("data")?.optString("title") ?: "新待办"
                notifyEvent("对方 给你添加了待办", title)
            }
            "todo_completed" -> {
                val title = m.optJSONObject("data")?.optString("title") ?: ""
                notifyEvent("待办已完成", "对方 完成了：$title")
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
            // 渠道由 NotificationChannels 统一创建；此处不再各自 createNotificationChannel
            // （对已存在渠道改不了 importance/声音，反而制造属性竞态）。
            val nm = NotificationChannels.ensure(c) ?: return
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

    /**
     * 服务端拒绝了本机刚发出的动作（如响铃 10 分钟内已 3 次）。
     * 结束发送方的"进行中"状态并把原因交给 UI —— 此前服务端超频是静默丢弃，
     * 客户端 UI 仍显示"已发送"，用户完全不知道对方根本没收到。
     */
    private fun handleRejected(action: String, reason: String) {
        Logs.w("Sync", "Action rejected by server: action=$action")
        InteractionEvents.onRejected(action, reason)
    }

    private val quietThrottle = QuietNotifyThrottle()

    /** 伴侣屏幕状态变化 → 静默通知（60s 内同类合并，受设置页开关控制）。 */
    private fun maybeNotifyQuiet(previous: DeviceStatus?, next: DeviceStatus?) {
        val cur = next ?: return
        val event = QuietNotifyPolicy.diff(previous, cur) ?: return
        if (!quietThrottle.tryAcquire(event.kind, enabled = UserPrefs.quietNotifyEnabled)) return
        notifyQuiet(event.title, event.body)
    }

    /** 在线/离线变化 → 静默通知。由 WS 建连与判死路径调用。 */
    fun notifyPresence(online: Boolean) {
        val event = QuietNotifyPolicy.presence(online)
        if (!quietThrottle.tryAcquire(event.kind, enabled = UserPrefs.quietNotifyEnabled)) return
        notifyQuiet(event.title, event.body)
    }

    /**
     * 静默通知：只落通知栏，不弹横幅、不响铃、不振动（管理员要求的"静默的通知"）。
     * 用固定 id 覆盖更新，避免对方频繁亮息屏时堆满一屏。
     */
    fun notifyQuiet(title: String, body: String) {
        try {
            val c = appContext ?: return
            val nm = NotificationChannels.ensure(c) ?: return
            val openApp = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(
                NotificationChannels.NOTIFY_ID_QUIET,
                NotificationCompat.Builder(c, NotificationChannels.CHANNEL_QUIET)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(openApp)
                    .build()
            )
        } catch (t: Throwable) {
            Logs.w("Sync", "notifyQuiet failed", t)
        }
    }

    const val MSG_RING_CANCEL = "ring_cancel"
    const val MSG_RING_STOPPED = "ring_stopped"
}
