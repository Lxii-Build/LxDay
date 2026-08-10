package com.linxi.diary.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.linxi.diary.R
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.util.UserPrefs

/**
 * 待办到点本地提醒（双保险之一：服务端扫描为主 + 本地 AlarmManager 兜底）。
 * remind_type=0 普通：通知 + 短震动
 * remind_type=1 强提醒：闹钟流 80% 音量 + 震动 + 普通通知（非全屏，见决策 Q27/Q32）
 */
class TodoAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (UserPrefs.demoMode || UserPrefs.pairId <= 0) return
        val title = intent.getStringExtra("title") ?: "待办提醒"
        val remindType = intent.getIntExtra("remind_type", 0)
        val todoId = intent.getLongExtra("todo_id", 0)

        if (remindType == 1) {
            playStrongRing(context)
        } else {
            vibrate(context, shortVibrate = true)
        }
        notify(context, title, remindType)
    }

    private fun notify(context: Context, title: String, remindType: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            StatusForegroundService.CHANNEL_EVENT, "互动提醒",
            NotificationManager.IMPORTANCE_HIGH))
        nm.notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(context, StatusForegroundService.CHANNEL_EVENT)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("待办提醒：$title")
                .setContentText(if (remindType == 1) "强提醒（闹钟音量）" else "点击查看")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build())
    }

    private fun playStrongRing(context: Context) {
        // 强提醒：闹钟流 80% 音量（区别于强制响铃的最大音量，见决策 Q32）
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.8f).toInt(), 0)
        } catch (_: SecurityException) { }

        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            setDataSource(context, uri)
            isLooping = false // 强提醒只响一次
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            prepare(); start()
        }
        // 8 秒后自动停止
        mp.setOnCompletionListener { mp.release() }
        android.os.Handler().postDelayed({
            runCatching { if (mp.isPlaying) { mp.stop(); mp.release() } }
        }, 8000)

        vibrate(context, shortVibrate = false)
    }

    private fun vibrate(context: Context, shortVibrate: Boolean) {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = if (shortVibrate) longArrayOf(0, 300) else longArrayOf(0, 500, 200, 500)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}

/** 待办本地提醒调度器：创建待办时注册 AlarmManager，删除/完成时取消 */
object TodoAlarmScheduler {

    fun schedule(context: Context, todoId: Long, title: String, remindType: Int, remindAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TodoAlarmReceiver::class.java)
            .setAction("com.linxi.diary.TODO_ALARM")
            .putExtra("todo_id", todoId)
            .putExtra("title", title)
            .putExtra("remind_type", remindType)
        val pending = PendingIntent.getBroadcast(context, todoId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAtMs, pending)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, remindAtMs, pending)
        }
        // 触发后由服务端扫描主提醒，本地 AlarmManager 为双保险兜底
    }

    fun cancel(context: Context, todoId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TodoAlarmReceiver::class.java)
            .setAction("com.linxi.diary.TODO_ALARM")
        val pending = PendingIntent.getBroadcast(context, todoId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pending)
        pending.cancel()
    }
}
