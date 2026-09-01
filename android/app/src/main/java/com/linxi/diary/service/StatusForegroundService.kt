package com.linxi.diary.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.linxi.diary.MainActivity
import com.linxi.diary.R
import com.linxi.diary.core.DeviceStatus
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.core.NetworkWatcher
import com.linxi.diary.core.ScreenStateReceiver
import com.linxi.diary.core.StatusCollector
import com.linxi.diary.core.SyncHeartbeat
import com.linxi.diary.sync.AppForegroundState
import com.linxi.diary.sync.SharingRuntimePolicy
import com.linxi.diary.sync.StatusSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.linxi.diary.util.Logs
import com.linxi.diary.util.TimeUtil
import com.linxi.diary.util.UserPrefs

/**
 * 常驻状态卡片前台服务（dataSync|location）。
 *
 * - 前台服务保证进程存活，通知不可被一键清理（Android 13-）持续展示；
 * - 收起态：48dp 横向 RemoteViews，展示头像、前台 App 与状态摘要；
 * - 展开态：横向 RemoteViews 展示屏幕、电量和网络组件，保留标准「响铃提醒」Action；
 * - 静默更新：仅业务状态变化时刷新，setOnlyAlertOnce(true) + IMPORTANCE_LOW；
 * - Android 14+ 前台通知可被侧滑，兜底见 MediaNotificationListener.onNotificationRemoved。
 */
class StatusForegroundService : Service() {

    companion object {
        // 渠道常量统一由 NotificationChannels 定义（此前 4 处各自创建、属性打架）。
        // 这里保留同名别名，避免调用方大范围改动。
        const val CHANNEL_CARD = NotificationChannels.CHANNEL_CARD
        const val CHANNEL_EVENT = NotificationChannels.CHANNEL_EVENT
        const val CHANNEL_RING = NotificationChannels.CHANNEL_RING
        const val NOTIFY_ID_CARD = NotificationChannels.NOTIFY_ID_CARD

        const val ACTION_REFRESH = "com.linxi.diary.REFRESH"
        const val ACTION_RING = "com.linxi.diary.RING"

        /** 周期心跳：重新采集本机状态 + 兼作服务存活自检。由 SyncHeartbeat 调度。 */
        const val ACTION_SYNC = "com.linxi.diary.SYNC"
        private const val EXTRA_FORCE_NOTIFICATION_REFRESH = "force_notification_refresh"

        /** 刷新卡片（收到 partner_status 时调用）。用户关闭卡片后忽略 */
        fun refreshCard(context: Context?) {
            val c = context ?: return
            if (!UserPrefs.statusCardEnabled || !SharingRuntimePolicy.canRunNow()) return
            startSafe(c, ACTION_REFRESH)
        }

        fun restoreCard(context: Context?) {
            val c = context ?: return
            if (!UserPrefs.statusCardEnabled || !SharingRuntimePolicy.canRunNow()) return
            startSafe(c, ACTION_REFRESH, forceNotificationRefresh = true)
        }

        fun start(context: Context) {
            // 状态卡片只是展示开关，不能阻止后台采集与上报。
            if (!SharingRuntimePolicy.canRunNow()) return
            startSafe(context, null)
        }

        /**
         * 重新采集本机状态并立即上报。
         *
         * 亮屏/息屏、网络恢复、周期心跳都走这里 —— 关键是**先采集再上报**：
         * 直接调 StatusSyncManager.pushNow() 只会把上一次的旧快照发出去。
         */
        fun syncNow(context: Context?) {
            val c = context ?: return
            if (!SharingRuntimePolicy.canRunNow()) return
            startSafe(c, ACTION_SYNC)
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, StatusForegroundService::class.java))
            }.onFailure { Logs.w("Service", "停止前台服务异常", it) }
        }

        /** 安全启动前台服务：Android 12+ 后台启动受限，捕获异常不闪退 */
        private fun startSafe(
            context: Context,
            action: String?,
            forceNotificationRefresh: Boolean = false,
        ) {
            try {
                val i = Intent(context, StatusForegroundService::class.java)
                if (action != null) i.action = action
                if (forceNotificationRefresh) i.putExtra(EXTRA_FORCE_NOTIFICATION_REFRESH, true)
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                Logs.e("Service", "前台服务启动失败（可能无通知权限或后台限制）", t)
                try {
                    context.startService(Intent(context, StatusForegroundService::class.java))
                } catch (t2: Throwable) {
                    Logs.e("Service", "兜底 startService 也失败", t2)
                }
            }
        }
    }

    private var screenReceiver: ScreenStateReceiver? = null
    private var wifiReceiver: BroadcastReceiver? = null
    /**
     * 采集用的协程作用域。
     * SupervisorJob：某次采集抛异常不该让后续采集全部停摆。
     */
    private val collectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastNotificationState: NotificationRenderState? = null

    override fun onCreate() {
        super.onCreate()
        if (!SharingRuntimePolicy.canRunNow()) {
            stopSelf()
            return
        }
        try { createChannels() } catch (t: Throwable) { Logs.w("Service", "createChannels 异常", t) }
        // 网络可用性监听：替代静态注册的 NetworkReceiver（CONNECTIVITY_CHANGE 在 API24+ 不投递）
        NetworkWatcher.register(this)
        // 周期心跳：ACTION_SYNC 此前从未被任何代码调度，周期采集实际不存在。
        SyncHeartbeat.schedule(
            this,
            appVisible = AppForegroundState.isForeground,
            screenOn = DeviceStatusHolder.screenOn,
            force = true,
        )
        // 动态注册亮屏/锁屏监听（ACTION_SCREEN_ON/OFF 无法静态注册）
        screenReceiver = ScreenStateReceiver().also { r ->
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_ON)
            f.addAction(Intent.ACTION_SCREEN_OFF)
            f.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // 指定 WiFi 连接监听：连接「关注 WiFi」时触发事件
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val ssid = StatusCollector.network(this@StatusForegroundService).first
                val watch = UserPrefs.watchSsid
                if (watch.isNotBlank() && ssid == watch) {
                    StatusSyncManager.sendEvent("wifi_joined")
                }
            }
        }.also { r ->
            val f = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // Android 要求 startForegroundService 后尽快发布通知；先发占位，再采集更新。
        try {
            startForeground(NOTIFY_ID_CARD, buildCard(DeviceStatusHolder.partner))
        } catch (t: Throwable) {
            Logs.e("Service", "startForeground 失败（无通知权限？）", t)
            stopSelf()
            return
        }
        refreshNow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SharingRuntimePolicy.canRunNow()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_FORCE_NOTIFICATION_REFRESH, false) == true) {
            lastNotificationState = null
        }
        when (intent?.action) {
            ACTION_REFRESH -> refreshNow() // 静默刷新
            ACTION_SYNC -> refreshNow()    // 后台定时采集（AlarmManager 5min）
            ACTION_RING -> StatusSyncManager.sendEvent("ring_request")
            else -> refreshNow()
        }
        return START_STICKY
    }

    /**
     * 采集本机状态 + 上报 + 刷新卡片（卡片显示伴侣状态）。
     * 共享总开关关闭时：停止采集、清空本机状态，不推送。
     *
     * **采集必须在 IO 线程**（0821 修）。此前整段跑在主线程，而
     * `StatusCollector.collectAll` 里有 `queryEvents`（旧实现遍历 24 小时全部事件，
     * 重度使用能有上万条）与 `queryAndAggregateUsageStats` 两个重活，
     * 而前台档位是**每 10 秒**采集一次 —— 这是实打实的 ANR 风险，也会让 UI 掉帧。
     *
     * 通知栏更新留在主线程：`startForeground` 与 RemoteViews 由系统在主线程校验。
     */
    private fun refreshNow() {
        if (!SharingRuntimePolicy.canRunNow()) {
            DeviceStatusHolder.current = null
            DeviceStatusHolder.partner = null
            updateCardIfChanged(null)
            return
        }
        collectScope.launch {
            try {
                val s = StatusCollector.collectAll(this@StatusForegroundService)
                DeviceStatusHolder.current = s
                StatusSyncManager.pushNow()
                // 低电量提醒由服务端在状态落地时统一判定并推送，避免客户端额外
                // 发送 low_battery 与状态上报产生重复通知或被绕过状态校验。
                withContext(Dispatchers.Main) {
                    updateCardIfChanged(DeviceStatusHolder.partner)
                }
            } catch (t: Throwable) {
                Logs.e("Service", "refreshNow 异常", t)
            }
        }
    }

    private fun updateCardIfChanged(partner: DeviceStatus?) {
        val state = NotificationRenderState(
            card = NotificationCardState.from(partner),
            avatarFingerprint = NotificationAvatarCache.fingerprint(filesDir),
        )
        if (!NotificationUpdatePolicy.shouldUpdate(lastNotificationState, state)) return
        lastNotificationState = state
        runCatching { startForeground(NOTIFY_ID_CARD, buildCard(partner, state.card)) }
            .onFailure { Logs.w("Service", "更新状态卡失败", it) }
    }

    /** 渠道创建委托给 NotificationChannels 单一入口（幂等）。 */
    private fun createChannels() {
        NotificationChannels.ensure(this)
    }

    /** 构建紧凑横向状态卡；系统仍保留通知装饰和标准响铃 Action。 */
    private fun buildCard(
        partner: DeviceStatus?,
        state: NotificationCardState = NotificationCardState.from(partner),
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val updateTime = if (partner == null) "等待" else TimeUtil.nowTime()
        val avatar = NotificationAvatarCache.load(filesDir)
        val compactCard = RemoteViews(packageName, R.layout.notification_status_card_compact).apply {
            setTextViewText(R.id.notification_update_time, updateTime)
            setTextViewText(R.id.notification_foreground, state.foreground)
            setTextViewText(
                R.id.notification_summary,
                listOf(state.sync, state.battery, state.network).joinToString(" · "),
            )
            if (avatar != null) {
                setImageViewBitmap(R.id.notification_avatar, avatar)
            } else {
                setImageViewResource(R.id.notification_avatar, R.drawable.notification_avatar_placeholder)
            }
        }
        val expandedCard = RemoteViews(packageName, R.layout.notification_status_card).apply {
            setTextViewText(R.id.notification_update_time, updateTime)
            setTextViewText(R.id.notification_foreground, state.foreground)
            setTextViewText(R.id.notification_sync, state.sync)
            setTextViewText(R.id.notification_phone, state.phone)
            setTextViewText(R.id.notification_battery, state.battery)
            setTextViewText(R.id.notification_network, state.network)
            if (avatar != null) {
                setImageViewBitmap(R.id.notification_avatar, avatar)
            } else {
                setImageViewResource(R.id.notification_avatar, R.drawable.notification_avatar_placeholder)
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_CARD)
            .setSmallIcon(R.drawable.ic_heart)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCustomContentView(compactCard)
            .setCustomBigContentView(expandedCard)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .addAction(R.drawable.ic_alarm, "响铃提醒", serviceAction(ACTION_RING))
            .setContentIntent(openApp)
            .build()
    }

    private fun serviceAction(action: String): PendingIntent {
        val i = Intent(this, StatusForegroundService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onDestroy() {
        NetworkWatcher.unregister()
        // 心跳不在此取消：服务可能是被系统杀掉的，届时正需要心跳把它拉回来。
        // 仅在未绑定/关闭共享时由 SyncHeartbeatReceiver 自行 cancel。
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        wifiReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        wifiReceiver = null
        // 取消在飞的采集：服务已销毁，再回写 DeviceStatusHolder 或更新通知都没有意义。
        runCatching { collectScope.cancel() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
