package com.linxi.diary.data

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.linxi.diary.MainActivity
import com.linxi.diary.R
import com.linxi.diary.service.NotificationChannels
import com.linxi.diary.util.UserPrefs

/**
 * 本机播放的标准媒体通知出口。
 *
 * “播放胶囊/灵动岛”不是 App 可以强制创建的悬浮窗：MediaSession 是 Android 官方媒体
 * 控制入口，系统会根据用户与厂商桌面的实时活动/媒体通知设置自行决定呈现方式。本页
 * 设置只控制是否发布这条媒体通知，以及是否把本机缓存的当前歌词作为副文案。通知只
 * 包含本机曲目元数据，不包含 Cookie、JWT 或播放地址。
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object MusicNotificationController {
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var mediaSession: MediaSession? = null

    fun init(context: Context, session: MediaSession) {
        appContext = context.applicationContext
        mediaSession = session
    }

    fun refresh(state: NeteasePlaybackState) {
        val context = appContext ?: return
        val manager = NotificationChannels.ensure(context) ?: return
        val session = mediaSession ?: return
        if (state.track == null || !UserPrefs.musicPlaybackCapsuleEnabled) {
            manager.cancel(NotificationChannels.NOTIFY_ID_MUSIC)
            return
        }
        val track = state.track
        val lyric = if (UserPrefs.musicPlaybackCapsuleLyrics) {
            NeteasePlaybackManager.currentLyric()?.take(100)
        } else {
            null
        }
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_MUSIC)
            .setSmallIcon(R.drawable.ic_heart)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentTitle(track.title)
            .setContentText(lyric ?: track.artist.ifBlank { "网易云音乐" })
            .apply {
                if (!lyric.isNullOrBlank()) setSubText(track.artist.ifBlank { "网易云音乐" })
            }
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context,
                    NotificationChannels.NOTIFY_ID_MUSIC,
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setProgress(
                state.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L))
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.durationMs <= 0L,
            )
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
        runCatching { manager.notify(NotificationChannels.NOTIFY_ID_MUSIC, builder.build()) }
    }
}
