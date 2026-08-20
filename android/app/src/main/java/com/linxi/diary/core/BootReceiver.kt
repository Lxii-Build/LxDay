package com.linxi.diary.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.TodoItem
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.AppForegroundState
import com.linxi.diary.sync.ProfileSyncPolicy
import com.linxi.diary.sync.SharingRuntimePolicy
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机自启保活：重启后拉起前台服务 + 重连 WebSocket + **重建待办闹钟**。
 *
 * 只监听 `ACTION_BOOT_COMPLETED`：`LOCKED_BOOT_COMPLETED` 需要 receiver 声明
 * `directBootAware="true"` 才会投递，而本应用的 SharedPreferences 是 MODE_PRIVATE
 * （直接启动阶段不可读），声明了反而会在解锁前触发并崩溃。相应 action 已从 manifest 移除。
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ProfileSyncPolicy.canConnectNow()) return

        val appCtx = context.applicationContext
        if (SharingRuntimePolicy.canRunNow() && UserPrefs.statusCardEnabled) {
            StatusForegroundService.start(appCtx)
        }
        StatusSyncManager.connect()

        // 重启后重排周期心跳（AlarmManager 的闹钟不跨重启保留）。
        SyncHeartbeat.schedule(
            appCtx,
            appVisible = AppForegroundState.isForeground,
            screenOn = DeviceStatusHolder.screenOn,
            force = true,
        )

        // 重建待办本地闹钟：AlarmManager 注册的闹钟在重启后全部丢失，
        // 而此前没有任何重建逻辑 —— 重启一次，所有本地待办提醒就永久失效了。
        rescheduleTodoAlarms(appCtx)
    }

    private fun rescheduleTodoAlarms(context: Context) {
        if (UserPrefs.pairId <= 0) return
        val pending = goAsync()
        scope.launch {
            try {
                val meId = UserPrefs.myUserId
                val arr = ApiClient.todos(status = 0)
                var restored = 0
                for (i in 0 until arr.length()) {
                    val t = runCatching { TodoItem.fromJson(arr.getJSONObject(i)) }.getOrNull() ?: continue
                    // 与 TodoScreen 同一条规则：本地闹钟只在"被提醒者=本人"时调度，
                    // 指派给对方的待办以服务端扫描推送为准，避免响错设备。
                    if (!t.remindEnabled || t.assigneeId != meId) continue
                    val remindAt = t.remindAtMs ?: continue
                    val at = if (remindAt > System.currentTimeMillis()) {
                        remindAt
                    } else {
                        // 已过期：若是循环提醒则顺延到下一次，否则跳过。
                        TodoRepeatPolicy.nextRemindAt(remindAt, t.repeatType, t.weekdays) ?: continue
                    }
                    TodoAlarmScheduler.schedule(
                        context, t.id, t.title, t.remindType, at, t.repeatType, t.weekdays
                    )
                    restored++
                }
                Logs.i("Boot", "Rescheduled $restored todo alarms after reboot")
            } catch (t: Throwable) {
                Logs.w("Boot", "Failed to reschedule todo alarms", t)
            } finally {
                pending.finish()
            }
        }
    }
}
