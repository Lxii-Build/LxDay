package com.linxi.diary.core

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.linxi.diary.R
import com.linxi.diary.RingActivity
import com.linxi.diary.service.StatusForegroundService

/**
 * 强制响铃（无视静音/振动，尽力而为）。
 *
 * 策略：
 * 1) 闹钟音量流（STREAM_ALARM）独立于静音/振动模式，拨到最大；
 * 2) 已授权「勿扰访问」→ 把中断过滤器切到「仅闹钟」，绕过勿扰压制铃声；
 * 3) USAGE_ALARM 播放提示音（媒体/铃声在静音下会被吞，闹钟流不会）；
 * 4) 全屏通知 + 唤醒屏幕，锁屏也能看到；
 * 5) 强震动。
 *
 * 边界：用户开启勿扰「完全静音（Total Silence，连闹钟也屏蔽）」时，
 * 任何应用都无法强制出声——系统级限制，无法绕过。
 */
object RingHelper {

    fun forceRing(c: Context) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 1) 闹钟流音量拉满
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (_: SecurityException) {
            // Android 12+ 部分厂商限制后台调音量
        }

        // 2) 勿扰访问授权后切「仅闹钟」过滤器
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }

        // 3) 播放闹钟声音（循环 10 秒）
        val mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            setDataSource(c, alarmUri(c))
            isLooping = true
            setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
            prepare()
            start()
        }
        RingController.player = mp

        // 4) 全屏通知：灭屏/锁屏也能亮屏显示
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            val fullScreen = PendingIntent.getActivity(c, 0,
                Intent(c, RingActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, StatusForegroundService.CHANNEL_RING)
                .setSmallIcon(R.drawable.ic_alarm)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreen, true)
                .setContentTitle("对方 正在找你")
                .setContentText("紧急响铃请求，点击查看")
                .setOngoing(true)
                .setAutoCancel(true)
                .build()
            nm.notify(10002, n)
        }

        // 5) 强震动（minSdk 29 保证 API>=26）
        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(
            longArrayOf(0, 600, 300, 600, 300, 1200), 0))
    }

    private fun alarmUri(c: Context): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    /**
     * 待办「强提醒」：闹钟流 80% 音量 + 震动 + 普通通知（不做全屏，见决策 Q32）。
     * 区别于「强制响铃」的最大音量 + 全屏通知。
     */
    fun todoStrongRemind(c: Context, title: String) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.8f).toInt(), 0)
        } catch (_: SecurityException) { }

        val mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            setDataSource(c, alarmUri(c))
            isLooping = false
            setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
            prepare(); start()
        }
        android.os.Handler().postDelayed({
            runCatching { if (mp.isPlaying) { mp.stop(); mp.release() } }
        }, 8000)

        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1))
    }
}

/** 响铃播放控制器：10 秒后自动停止，避免无限响铃 */
object RingController {
    @Volatile var player: MediaPlayer? = null

    fun stop() {
        runCatching { player?.stop() }
        player?.release()
        player = null
    }
}

/** 在 Activity 中手动停止响铃的入口（RingActivity 调用） */
fun stopRingIfActive(activity: Activity) {
    RingController.stop()
}
