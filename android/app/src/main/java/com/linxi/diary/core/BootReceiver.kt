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
import kotlinx.coroutines.withTimeout

/**
 * 开机自启保活：重启后拉起前台服务 + 重连 WebSocket + **重建待办闹钟**。
 *
 * 只监听 `ACTION_BOOT_COMPLETED`：`LOCKED_BOOT_COMPLETED` 需要 receiver 声明
 * `directBootAware="true"` 才会投递，而本应用的 SharedPreferences 是 MODE_PRIVATE
 * （直接启动阶段不可读），声明了反而会在解锁前触发并崩溃。相应 action 已从 manifest 移除。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        /**
         * 进程级作用域，**不能是实例字段**。
         *
         * BroadcastReceiver 实例本该在 `onReceive` 返回后即可回收；
         * 挂在实例上的 scope 会让协程持有 receiver 实例不放。
         * 放到 companion 里之后，协程持有的是这个单例作用域，与 receiver 实例解耦。
         *
         * 配合下面的 `withTimeout`：`goAsync()` 拿到的 pending 必须在有限时间内
         * `finish()`，否则系统会按 ANR 处理这个广播；网络请求挂住时尤其危险。
         */
        private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 重建闹钟的整体预算。
         *
         * 开机广播的 receiver 有约 10 秒的执行窗口（后台广播更短），
         * 超时未 finish 会被判 ANR。这里给 8 秒：够拉一次待办列表，
         * 又留出余量让 finally 里的 finish 一定跑得到。
         */
        private const val RESCHEDULE_TIMEOUT_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ProfileSyncPolicy.canConnectNow()) return

        val appCtx = context.applicationContext
        if (SharingRuntimePolicy.canRunNow()) {
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
        bootScope.launch {
            try {
                // 整段限时：ApiClient 若因为网络黑洞挂住，goAsync 的 pending
                // 就永远不会 finish，系统按 ANR 处理该广播，且这段时间里
                // receiver 与整份待办列表都被协程持有着。
                withTimeout(RESCHEDULE_TIMEOUT_MS) {
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
                }
            } catch (t: Throwable) {
                Logs.w("Boot", "Failed to reschedule todo alarms", t)
            } finally {
                pending.finish()
            }
        }
    }
}
