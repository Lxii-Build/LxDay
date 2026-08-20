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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.linxi.diary.R
import com.linxi.diary.RingActivity
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.util.Logs

/**
 * 强制响铃（无视静音/振动，尽力而为）。
 *
 * 策略：
 * 1) 闹钟音量流（STREAM_ALARM）独立于静音/振动模式，拨到最大；
 * 2) 已授权「勿扰访问」→ 把中断过滤器切到「仅闹钟」，绕过勿扰压制铃声；
 * 3) USAGE_ALARM 播放提示音（媒体/铃声在静音下会被吞，闹钟流不会）；
 * 4) 全屏通知 + 唤醒屏幕，锁屏也能看到；通知上挂【停止响铃】按钮；
 * 5) 强震动（有限次 waveform，不再无限循环）。
 *
 * 停止路径（任一命中即彻底停止，且互相幂等）：
 * - [RING_DURATION_MS] 到点自动停（本地定时器，不依赖网络）
 * - 通知栏【停止响铃】按钮 → RingStopReceiver
 * - 全屏页 RingActivity 的「我知道了」/ onDestroy
 * - 发送方撤回：WS `ring_cancel` → [stopRing]
 *
 * 停止时必须还原副作用：闹钟音量、勿扰过滤器、振动、通知。
 *
 * 边界：用户开启勿扰「完全静音（Total Silence，连闹钟也屏蔽）」时，
 * 任何应用都无法强制出声——系统级限制，无法绕过。
 */
object RingHelper {

    /** 响铃时长：管理员定 7 秒（TodoList_0820 客户端第 5 条）。 */
    const val RING_DURATION_MS = 7_000L

    /** 响铃通知 id。与其它通知严格区分，避免被覆盖导致【停止】按钮消失。 */
    const val NOTIFY_ID_RING = 10002

    /** 待办强提醒的响铃时长（区别于互动响铃）。 */
    private const val TODO_REMIND_MS = 8_000L

    fun forceRing(c: Context, ringId: String? = null) {
        // 同一时刻只允许一个响铃会话：新的请求先把上一个彻底停掉，避免叠音与定时器互相踩。
        RingController.stop(c, "superseded")

        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1) 闹钟流音量拉满（先记下原值，停止时还原）
        var savedVolume: Int? = null
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (_: SecurityException) {
            // Android 12+ 部分厂商限制后台调音量
            savedVolume = null
        }

        // 2) 勿扰访问授权后切「仅闹钟」过滤器（先记下原值，停止时还原）
        var savedFilter: Int? = null
        try {
            if (nm.isNotificationPolicyAccessGranted) {
                savedFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            }
        } catch (t: Throwable) {
            Logs.w("Ring", "Failed to switch interruption filter", t)
            savedFilter = null
        }

        // 3) 播放闹钟声音（循环，由定时器在 7s 后停止）
        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(c, alarmUri(c))
                isLooping = true
                setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
                prepare()
                start()
            }
        } catch (t: Throwable) {
            Logs.e("Ring", "Failed to start alarm playback", t)
            null
        }

        // 5) 强震动：有限次 waveform（repeat = -1），即便 stop 未被调用也不会永久震动。
        val vibrator = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 600, 300, 600, 300, 1200, 300, 600, 300, 600), -1
                )
            )
        } catch (t: Throwable) {
            Logs.w("Ring", "Failed to start vibration", t)
        }

        RingController.begin(
            RingSession(
                ringId = ringId,
                player = mp,
                savedAlarmVolume = savedVolume,
                savedInterruptionFilter = savedFilter,
            )
        )

        // 4) 全屏通知：灭屏/锁屏也能亮屏显示，并挂【停止响铃】按钮。
        // 即便 RingActivity 因权限/厂商限制没被拉起，接收方也能在通知栏一键停止。
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                nm.notify(NOTIFY_ID_RING, buildRingNotification(c, ringId))
            } catch (t: Throwable) {
                Logs.w("Ring", "Failed to post ring notification", t)
            }
        } else {
            Logs.w("Ring", "POST_NOTIFICATIONS denied; ring UI unavailable, relying on auto-stop")
        }

        // 到点自动停止：本地定时器，不依赖网络与对端。
        RingController.scheduleAutoStop(c, RING_DURATION_MS)
    }

    private fun buildRingNotification(c: Context, ringId: String?): android.app.Notification {
        val fullScreen = PendingIntent.getActivity(
            c, 0,
            Intent(c, RingActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            c, 1,
            RingStopReceiver.intent(c, ringId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(c, StatusForegroundService.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setContentTitle("对方 正在找你")
            .setContentText("紧急响铃请求，点击查看")
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(R.drawable.ic_alarm, "停止响铃", stopIntent)
            .build()
    }

    /**
     * 停止当前响铃。供通知按钮、全屏页、以及发送方 WS 撤回（ring_cancel）共同调用。
     *
     * @param ringId 非空时仅停止匹配的会话（防止撤回把后来的新响铃误停）；为空则停止任意会话。
     * @return 是否真的停掉了一个进行中的会话（用于决定是否回执 ring_stopped）。
     */
    fun stopRing(c: Context, ringId: String? = null, reason: String = "manual"): Boolean =
        RingController.stopIfMatches(c, ringId, reason)

    private fun alarmUri(c: Context): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    /**
     * 待办「强提醒」：闹钟流 80% 音量 + 震动 + 普通通知（不做全屏）。
     * 区别于「强制响铃」的最大音量 + 全屏通知。
     *
     * 注意：本方法可能在 OkHttp WebSocket 读线程被调用（该线程无 Looper），
     * 因此定时器必须显式绑定主线程 Looper，不能用无参 Handler()。
     */
    fun todoStrongRemind(c: Context, title: String) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var savedVolume: Int? = null
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.8f).toInt(), 0)
        } catch (_: SecurityException) {
            savedVolume = null
        }

        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(c, alarmUri(c))
                isLooping = false
                setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
                prepare(); start()
            }
        } catch (t: Throwable) {
            Logs.e("Ring", "Failed to start todo reminder playback", t)
            null
        }

        val vibrator = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1))
        } catch (t: Throwable) {
            Logs.w("Ring", "Failed to vibrate for todo reminder", t)
        }

        // 显式主线程 Looper：本方法可能运行在无 Looper 的 WS 读线程上。
        RingController.mainHandler.postDelayed({
            runCatching { if (mp?.isPlaying == true) mp.stop() }
            runCatching { mp?.release() }
            runCatching { vibrator?.cancel() }
            if (savedVolume != null) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            }
        }, TODO_REMIND_MS)
    }
}

/** 一次响铃会话的全部可撤销状态。 */
data class RingSession(
    val ringId: String?,
    val player: MediaPlayer?,
    val savedAlarmVolume: Int?,
    val savedInterruptionFilter: Int?,
)

/**
 * 响铃会话控制器：负责自动停止定时器与副作用还原，所有停止入口都经过这里，保证幂等。
 *
 * 旧实现只 stop/release 了 MediaPlayer，既不 cancel 振动、也不还原音量与勿扰、
 * 更没有任何自动停止定时器（注释谎称"10 秒后自动停止"），
 * 导致"必须把 App 划掉后台才能停止响铃"。
 */
object RingController {

    val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var session: RingSession? = null

    private var autoStop: Runnable? = null

    /** 当前是否有响铃在进行（供 UI 显示"响铃中"）。 */
    val isRinging: Boolean get() = session != null

    /** 当前会话 id（供发送方撤回时比对）。 */
    val currentRingId: String? get() = session?.ringId

    @Synchronized
    fun begin(s: RingSession) {
        session = s
    }

    @Synchronized
    fun scheduleAutoStop(c: Context, delayMs: Long) {
        autoStop?.let { mainHandler.removeCallbacks(it) }
        val appCtx = c.applicationContext
        val task = Runnable { stop(appCtx, "timeout") }
        autoStop = task
        mainHandler.postDelayed(task, delayMs)
    }

    /** 仅当 ringId 匹配（或未指定）时停止，避免撤回误停后来的新响铃。 */
    @Synchronized
    fun stopIfMatches(c: Context, ringId: String?, reason: String): Boolean {
        val cur = session ?: return false
        if (ringId != null && cur.ringId != null && cur.ringId != ringId) {
            Logs.i("Ring", "Ignore stop for stale ring id")
            return false
        }
        stopLocked(c, reason)
        return true
    }

    @Synchronized
    fun stop(c: Context, reason: String = "manual") {
        if (session == null) return
        stopLocked(c, reason)
    }

    private fun stopLocked(c: Context, reason: String) {
        val cur = session ?: return
        session = null
        autoStop?.let { mainHandler.removeCallbacks(it) }
        autoStop = null

        runCatching { if (cur.player?.isPlaying == true) cur.player.stop() }
        runCatching { cur.player?.release() }

        // 振动必须显式取消：旧实现从不调用，是"停不下来"的一半原因。
        runCatching {
            (c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        }

        // 还原被强改的系统设置，否则用户的音量与勿扰会被永久篡改。
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        cur.savedAlarmVolume?.let { v ->
            runCatching { am?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
        }
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        cur.savedInterruptionFilter?.let { f ->
            runCatching {
                if (nm?.isNotificationPolicyAccessGranted == true) nm.setInterruptionFilter(f)
            }
        }
        runCatching { nm?.cancel(RingHelper.NOTIFY_ID_RING) }

        Logs.i("Ring", "Ring stopped (reason=$reason)")
    }
}

/** 在 Activity 中手动停止响铃的入口（RingActivity 调用） */
fun stopRingIfActive(activity: Activity) {
    RingController.stop(activity.applicationContext, "activity")
}
