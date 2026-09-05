package com.linxi.diary.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.linxi.diary.MainActivity
import com.linxi.diary.R
import com.linxi.diary.service.NotificationChannels
import com.linxi.diary.util.UserPrefs

/**
 * 本机播放胶囊的唯一通知出口。
 *
 * Android 厂商对“灵动岛”的展示入口不同，但都能识别标准媒体通知。这里不伪装成
 * 厂商私有 API：支持的设备会把低重要性媒体通知放进播放胶囊，不支持的设备则显示
 * 一条可操作的常驻播放通知。通知只包含本机网易云曲目元数据，不包含 Cookie、JWT 或
 * 播放地址。
 */
object MusicNotificationController {
    const val ACTION_TOGGLE = "com.linxi.diary.action.MUSIC_TOGGLE"

    private const val REQUEST_TOGGLE = 100041
    private const val FLAG_ACTIVITY =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun refresh(state: NeteasePlaybackState) {
        val context = appContext ?: return
        val manager = NotificationChannels.ensure(context) ?: return
        if (!UserPrefs.dynamicIslandEnabled || state.track == null) {
            manager.cancel(NotificationChannels.NOTIFY_ID_MUSIC)
            return
        }
        val track = state.track
        val title = if (UserPrefs.dynamicIslandCompact) {
            track.title
        } else {
            "林曦日记 · ${track.title}"
        }
        val lyric = if (UserPrefs.dynamicIslandShowLyrics) {
            NeteasePlaybackManager.currentLyric()
        } else {
            null
        }.orEmpty()
        val text = when {
            lyric.isNotBlank() && !UserPrefs.dynamicIslandCompact -> lyric
            track.artist.isNotBlank() -> track.artist
            else -> "网易云音乐"
        }
        val subText = if (UserPrefs.dynamicIslandCompact) {
            null
        } else {
            "${formatPosition(state.positionMs)} / ${formatPosition(state.durationMs)}"
        }
        val openIntent = PendingIntent.getActivity(
            context,
            NotificationChannels.NOTIFY_ID_MUSIC,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            FLAG_ACTIVITY,
        )
        val toggleIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_TOGGLE,
            Intent(context, MusicNotificationReceiver::class.java).setAction(ACTION_TOGGLE),
            FLAG_ACTIVITY,
        )
        val actionIcon = if (state.playing) android.R.drawable.ic_media_pause
        else android.R.drawable.ic_media_play
        val actionText = if (state.playing) "暂停" else "播放"
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_MUSIC)
            .setSmallIcon(R.drawable.ic_heart)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setContentIntent(openIntent)
            .setProgress(
                state.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L))
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.durationMs <= 0L,
            )
            // Media3 的 MediaStyle 需要额外绑定 MediaSession.Token；这条通知仍使用
            // 标准 transport 类别与操作按钮，避免在没有 session 时构造无效样式。
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(actionIcon, actionText, toggleIntent)
        runCatching { manager.notify(NotificationChannels.NOTIFY_ID_MUSIC, builder.build()) }
    }

    private fun formatPosition(value: Long): String {
        if (value <= 0L) return "0:00"
        val seconds = value / 1_000L
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }
}

/** Notification action receiver; credentials and playback URLs never enter the Intent. */
class MusicNotificationReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MusicNotificationController.ACTION_TOGGLE) return
        val state = NeteasePlaybackManager.stateFlow.value
        if (state.playing) NeteasePlaybackManager.pause() else NeteasePlaybackManager.resume()
    }
}
