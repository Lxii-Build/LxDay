package com.linxi.diary.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.SyncIntervalPolicy
import com.linxi.diary.util.Logs

/**
 * 周期心跳调度器：按分档间隔重复触发 [StatusForegroundService.ACTION_SYNC]
 *（重新采集本机状态 → 上报），并兼作前台服务存活自检。
 *
 * 为什么需要它：`ACTION_SYNC` 的注释写着"后台 5 分钟定时采集 / AlarmManager 5min"，
 * 但全仓**没有任何代码调度过它** —— 周期采集从来不存在，
 * 而 `hub.go` 里"客户端 5min 上报天然对齐"的假设也因此不成立。
 *
 * 用 AlarmManager 而非 Handler/协程循环：进程被系统回收后仍能把服务拉起来，
 * 这同时解决了 Android 15+ `dataSync` 前台服务 6h/24h 超时被 stopSelf 后
 * **没有任何重启路径**的问题。
 *
 * 精确性：这里用 `setWindow`（非精确），因为心跳晚几十秒无害，
 * 不该占用「精确闹钟」这种敏感权限配额（那个留给待办到点提醒）。
 */
object SyncHeartbeat {

    private const val ACTION_HEARTBEAT = "com.linxi.diary.SYNC_HEARTBEAT"
    private const val REQUEST_CODE = 9001

    /** 允许的触发窗口：取间隔的 1/5，最少 30s。 */
    private fun windowOf(intervalMs: Long): Long = maxOf(intervalMs / 5, 30_000L)

    @Volatile
    private var scheduledInterval: Long = -1L

    /**
     * 按当前档位（重新）安排下一次心跳。
     *
     * 幂等：档位未变时不重复排（避免每次亮屏都取消重排导致心跳被无限推迟）。
     *
     * @param appVisible App 是否前台可见
     * @param screenOn 屏幕是否亮着
     * @param force 强制重排（用于首次启动或档位判断之外的场景）
     */
    fun schedule(context: Context, appVisible: Boolean, screenOn: Boolean, force: Boolean = false) {
        val interval = SyncIntervalPolicy.intervalMs(appVisible, screenOn)
        if (!force && interval == scheduledInterval) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context)
        val triggerAt = System.currentTimeMillis() + interval
        try {
            // setWindow 会覆盖同一 PendingIntent 的既有闹钟，无需先 cancel。
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, windowOf(interval), pending)
            scheduledInterval = interval
            Logs.i(
                "Sync",
                "Heartbeat scheduled: phase=${SyncIntervalPolicy.phase(appVisible, screenOn)} interval=${interval}ms"
            )
        } catch (t: Throwable) {
            Logs.w("Sync", "Failed to schedule heartbeat", t)
        }
    }

    /** 心跳触发后由接收器调用，安排下一次（AlarmManager 单次闹钟需自行续期）。 */
    fun rescheduleAfterFire(context: Context, appVisible: Boolean, screenOn: Boolean) {
        scheduledInterval = -1L // 强制重排
        schedule(context, appVisible, screenOn, force = true)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context)
        runCatching { am.cancel(pending) }
        pending.cancel()
        scheduledInterval = -1L
        Logs.i("Sync", "Heartbeat cancelled")
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SyncHeartbeatReceiver::class.java).setAction(ACTION_HEARTBEAT)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
