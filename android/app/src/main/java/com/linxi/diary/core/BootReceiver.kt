package com.linxi.diary.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.SharingRuntimePolicy
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.UserPrefs

/** 开机自启保活：重启后拉起前台服务 + 重连 WebSocket */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                if (!SharingRuntimePolicy.canRunNow()) return
                if (UserPrefs.statusCardEnabled) {
                    StatusForegroundService.start(context)
                }
                StatusSyncManager.connect()
            }
        }
    }
}
