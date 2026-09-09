package com.linxi.diary.data

import android.app.PendingIntent
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
                PendingIntent.getActivity(
                    context,
                    NotificationChannels.NOTIFY_ID_MUSIC,
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setProgress(
                state.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L))
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.durationMs <= 0L,
            )
            // Keep the actions explicit. Some vendor media surfaces (including
            // Oppo's fluid cloud) only expose buttons that are present on the
            // NotificationCompat action list; a MediaSession alone is not enough
            // when the player has one lazily-resolved media item.
            .addAction(action(context, ACTION_PREVIOUS, "上一首", R.drawable.ic_skip_previous, 0))
            .addAction(
                action(
                    context,
                    if (state.playing) ACTION_PAUSE else ACTION_PLAY,
                    if (state.playing) "暂停" else "播放",
                    if (state.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                    1,
                )
            )
            .addAction(action(context, ACTION_NEXT, "下一首", R.drawable.ic_skip_next, 2))
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        runCatching { manager.notify(NotificationChannels.NOTIFY_ID_MUSIC, builder.build()) }
    }

    private fun action(
        context: Context,
        action: String,
        title: String,
        icon: Int,
        requestCode: Int,
    ): NotificationCompat.Action {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NotificationChannels.NOTIFY_ID_MUSIC + requestCode,
            Intent(context, MusicMediaActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(icon, title, pendingIntent).build()
    }

    const val ACTION_PREVIOUS = "com.linxi.diary.action.MUSIC_PREVIOUS"
    const val ACTION_PLAY = "com.linxi.diary.action.MUSIC_PLAY"
    const val ACTION_PAUSE = "com.linxi.diary.action.MUSIC_PAUSE"
    const val ACTION_NEXT = "com.linxi.diary.action.MUSIC_NEXT"
}
