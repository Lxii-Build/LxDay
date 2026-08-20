package com.linxi.diary.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.linxi.diary.R
import com.linxi.diary.service.NotificationChannels
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs

/**
 * 待办到点本地提醒（双保险之一：服务端扫描为主 + 本地 AlarmManager 兜底）。
 * remind_type=0 普通：通知 + 短震动
 * remind_type=1 强提醒：闹钟流 80% 音量 + 震动 + 普通通知（非全屏）
 *
 * 触发后若该待办是循环提醒（每天/每周），会立即重排下一次闹钟——
 * 旧实现只注册了一次性闹钟，导致"每天/每周"的重复提醒只响第一次。
 */
class TodoAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (UserPrefs.pairId <= 0) return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "待办提醒"
        val remindType = intent.getIntExtra(EXTRA_REMIND_TYPE, 0)
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, 0)
        val repeatType = intent.getIntExtra(EXTRA_REPEAT_TYPE, 0)
        val weekdays = intent.getIntExtra(EXTRA_WEEKDAYS, 0)
        val remindAtMs = intent.getLongExtra(EXTRA_REMIND_AT, 0)

        if (remindType == 1) {
            playStrongRing(context)
        } else {
            vibrate(context, shortVibrate = true)
        }
        notify(context, todoId, title, remindType)

        // 循环提醒：立刻排下一次。不依赖 App 被打开，也不依赖服务端。
        if (repeatType != 0 && remindAtMs > 0) {
            val next = TodoRepeatPolicy.nextRemindAt(remindAtMs, repeatType, weekdays)
            if (next != null) {
                TodoAlarmScheduler.schedule(
                    context, todoId, title, remindType, next, repeatType, weekdays
                )
            }
        }
    }

    private fun notify(context: Context, todoId: Long, title: String, remindType: Int) {
        val nm = NotificationChannels.ensure(context) ?: return
        // 通知 id 用待办 id：同一待办重复触发时覆盖而非堆叠，且可被精确撤销。
        // 旧实现用 currentTimeMillis().toInt()，通知会无限堆积且无法撤销。
        nm.notify(
            NotificationChannels.todoNotifyId(todoId),
            NotificationCompat.Builder(context, NotificationChannels.CHANNEL_EVENT)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("待办提醒：$title")
                .setContentText(if (remindType == 1) "强提醒（闹钟音量）" else "点击查看")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun playStrongRing(context: Context) {
        // 强提醒：闹钟流 80% 音量（区别于强制响铃的最大音量），停止时还原原音量。
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var savedVolume: Int? = null
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.8f).toInt(), 0)
        } catch (_: SecurityException) {
            savedVolume = null
        }

        val uri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val mp = try {
            if (uri == null) null else MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = false // 强提醒只响一次
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                prepare(); start()
            }
        } catch (t: Throwable) {
            Logs.e("Todo", "Failed to play strong reminder", t)
            null
        }

        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrate(context, shortVibrate = false)

        // 8 秒后收尾。显式绑定主线程 Looper：无参 Handler() 在无 Looper 的线程上会抛异常。
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { if (mp?.isPlaying == true) mp.stop() }
            runCatching { mp?.release() }
            runCatching { vibrator?.cancel() }
            if (savedVolume != null) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            }
        }, 8000)
    }

    private fun vibrate(context: Context, shortVibrate: Boolean) {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = if (shortVibrate) longArrayOf(0, 300) else longArrayOf(0, 500, 200, 500)
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    companion object {
        const val ACTION_TODO_ALARM = "com.linxi.diary.TODO_ALARM"
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REMIND_TYPE = "remind_type"
        const val EXTRA_REPEAT_TYPE = "repeat_type"
        const val EXTRA_WEEKDAYS = "weekdays"
        const val EXTRA_REMIND_AT = "remind_at"
    }
}

/**
 * 待办本地提醒调度器：创建待办时注册 AlarmManager，删除/完成时取消。
 *
 * minSdk=33 下 `setExactAndAllowWhileIdle` 在未获精确闹钟授权时会抛 SecurityException，
 * 而旧实现既没声明权限也没 try-catch，导致「添加带提醒的待办」直接崩溃。
 * 现在：先查 canScheduleExactAlarms()，无权限则降级为非精确窗口闹钟，全程兜 try-catch。
 */
object TodoAlarmScheduler {

    /** 是否可以调度精确闹钟。已声明 USE_EXACT_ALARM 时恒为 true。 */
    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    }

    fun schedule(
        context: Context,
        todoId: Long,
        title: String,
        remindType: Int,
        remindAtMs: Long,
        repeatType: Int = 0,
        weekdays: Int = 0,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context, todoId, title, remindType, remindAtMs, repeatType, weekdays)
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAtMs, pending)
            } else {
                // 降级：非精确窗口闹钟。可能晚几分钟，但绝不崩溃，也不会静默什么都不做。
                Logs.w("Todo", "Exact alarm not permitted; falling back to inexact window")
                am.setWindow(
                    AlarmManager.RTC_WAKEUP, remindAtMs, INEXACT_WINDOW_MS, pending
                )
            }
        } catch (e: SecurityException) {
            // 部分厂商 ROM 即便 canScheduleExactAlarms() 为 true 仍可能拒绝。
            Logs.w("Todo", "Exact alarm rejected by system; retry as inexact", e)
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, remindAtMs, INEXACT_WINDOW_MS, pending) }
        } catch (t: Throwable) {
            Logs.e("Todo", "Failed to schedule todo alarm", t)
        }
    }

    fun cancel(context: Context, todoId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // PendingIntent 匹配只看 action/data/type/class/categories，不看 extras，
        // 故此处用最小 Intent 即可命中 schedule() 注册的那一个。
        val intent = Intent(context, TodoAlarmReceiver::class.java)
            .setAction(TodoAlarmReceiver.ACTION_TODO_ALARM)
        val pending = PendingIntent.getBroadcast(
            context, todoId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { am.cancel(pending) }
        pending.cancel()
        // 同时撤掉可能已弹出的提醒通知。
        runCatching {
            NotificationChannels.ensure(context)?.cancel(NotificationChannels.todoNotifyId(todoId))
        }
    }

    private fun pendingIntent(
        context: Context,
        todoId: Long,
        title: String,
        remindType: Int,
        remindAtMs: Long,
        repeatType: Int,
        weekdays: Int,
    ): PendingIntent {
        val intent = Intent(context, TodoAlarmReceiver::class.java)
            .setAction(TodoAlarmReceiver.ACTION_TODO_ALARM)
            .putExtra(TodoAlarmReceiver.EXTRA_TODO_ID, todoId)
            .putExtra(TodoAlarmReceiver.EXTRA_TITLE, title)
            .putExtra(TodoAlarmReceiver.EXTRA_REMIND_TYPE, remindType)
            .putExtra(TodoAlarmReceiver.EXTRA_REPEAT_TYPE, repeatType)
            .putExtra(TodoAlarmReceiver.EXTRA_WEEKDAYS, weekdays)
            .putExtra(TodoAlarmReceiver.EXTRA_REMIND_AT, remindAtMs)
        return PendingIntent.getBroadcast(
            context, todoId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val INEXACT_WINDOW_MS = 10 * 60 * 1000L
}
