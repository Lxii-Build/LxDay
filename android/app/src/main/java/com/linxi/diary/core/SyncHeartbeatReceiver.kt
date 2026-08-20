package com.linxi.diary.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.AppForegroundState
import com.linxi.diary.sync.SharingRuntimePolicy
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs

/**
 * 周期心跳的落点：重新采集并上报本机状态，然后安排下一次。
 *
 * 同时兼作前台服务存活自检 —— `syncNow` 走的是 startForegroundService，
 * 服务若已被系统杀掉（Android 15+ dataSync 有 6h/24h 运行上限，
 * 超时 stopSelf 后此前没有任何重启路径）会在此被重新拉起。
 */
class SyncHeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        if (UserPrefs.pairId <= 0 || !SharingRuntimePolicy.canRunNow()) {
            // 未绑定或已关闭共享：停掉心跳，别白耗电。
            SyncHeartbeat.cancel(appCtx)
            return
        }
        Logs.i("Sync", "Heartbeat fired")
        StatusForegroundService.syncNow(appCtx)
        SyncHeartbeat.rescheduleAfterFire(
            appCtx,
            appVisible = AppForegroundState.isForeground,
            screenOn = DeviceStatusHolder.screenOn,
        )
    }
}
