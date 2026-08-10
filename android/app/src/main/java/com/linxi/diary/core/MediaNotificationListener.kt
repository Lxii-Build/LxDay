package com.linxi.diary.core

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.SharingRuntimePolicy
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.UserPrefs

/**
 * 通知使用权监听：
 * 1. 识别媒体通知（CATEGORY_TRANSPORT）→ 提取 歌曲名/歌手/播放中 → 即时上报。
 * 2. 兜底：常驻卡片被用户侧滑/系统清理后，自动重新拉起（Android 14 前台通知可被侧滑，用此兜底）。
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_TRANSPORT) {
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
            val artist = ex.getCharSequence("android.media.metadata.ARTIST")?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "未知歌手"
            val hasPause = n.actions?.any { it.icon == android.R.drawable.ic_media_pause } == true
            DeviceStatusHolder.music = MusicInfo(title, artist, hasPause)
            StatusSyncManager.pushNow() // 开始播放音乐 → 即时推送
            return
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_TRANSPORT) {
            DeviceStatusHolder.music = null
            StatusSyncManager.pushNow()
        }
        // 侧滑删除兜底重拉
        if (sbn.id == StatusForegroundService.NOTIFY_ID_CARD &&
            sbn.packageName == packageName && shouldRestoreCard()) {
            StatusForegroundService.restoreCard(this)
        }
    }

    private fun shouldRestoreCard(): Boolean =
        UserPrefs.statusCardEnabled && SharingRuntimePolicy.canRunNow()
}
