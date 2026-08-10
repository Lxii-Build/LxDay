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
import com.linxi.diary.core.ScreenStateReceiver
import com.linxi.diary.core.StatusCollector
import com.linxi.diary.sync.SharingRuntimePolicy
import com.linxi.diary.sync.StatusSyncManager
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
        const val CHANNEL_CARD = "status_card"
        const val CHANNEL_EVENT = "status_event"
        const val CHANNEL_RING = "status_ring"
        const val NOTIFY_ID_CARD = 10001

        const val ACTION_REFRESH = "com.linxi.diary.REFRESH"
        const val ACTION_RING = "com.linxi.diary.RING"
        const val ACTION_SYNC = "com.linxi.diary.SYNC" // 后台 5 分钟定时采集
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
            if (!UserPrefs.statusCardEnabled || !SharingRuntimePolicy.canRunNow()) return
            startSafe(context, null)
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
    private var lastBatteryNotified = -1 // 低电量去重
    private var lastNotificationState: NotificationRenderState? = null

    override fun onCreate() {
        super.onCreate()
        if (!UserPrefs.statusCardEnabled || !SharingRuntimePolicy.canRunNow()) {
            stopSelf()
            return
        }
        try { createChannels() } catch (t: Throwable) { Logs.w("Service", "createChannels 异常", t) }
        // 动态注册亮屏/锁屏监听（ACTION_SCREEN_ON/OFF 无法静态注册）
        screenReceiver = ScreenStateReceiver().also { r ->
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_ON)
            f.addAction(Intent.ACTION_SCREEN_OFF)
            f.addAction(Intent.ACTION_USER_PRESENT)
            f.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
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
        if (!UserPrefs.statusCardEnabled || !SharingRuntimePolicy.canRunNow()) {
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
     */
    private fun refreshNow() {
        try {
            if (!SharingRuntimePolicy.canRunNow()) {
                DeviceStatusHolder.current = null
                DeviceStatusHolder.partner = null
                updateCardIfChanged(null)
                return
            }
            val s = StatusCollector.collectAll(this)
            DeviceStatusHolder.current = s
            StatusSyncManager.pushNow()
            // 低电量(<15%)即时事件：从 >=20 降到低电量时触发一次，防止重复刷屏
            if (s.batteryLevel in 1..14 && lastBatteryNotified != s.batteryLevel) {
                lastBatteryNotified = s.batteryLevel
                StatusSyncManager.sendEvent("low_battery")
            } else if (s.batteryLevel >= 20) {
                lastBatteryNotified = -1
            }
            updateCardIfChanged(DeviceStatusHolder.partner)
        } catch (t: Throwable) {
            Logs.e("Service", "refreshNow 异常", t)
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

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_CARD, "伴侣状态卡", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = "常驻显示伴侣实时状态，静默更新"
        })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_EVENT, "互动提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "求陪伴/待办/日记等互动提醒"
        })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_RING, "紧急响铃", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "强制响铃通知（铃声由播放器控制，不重复响）"
        })
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
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        wifiReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        wifiReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
