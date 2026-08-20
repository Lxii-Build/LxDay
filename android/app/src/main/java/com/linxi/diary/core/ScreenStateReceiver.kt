package com.linxi.diary.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.AppForegroundState

/**
 * 亮屏/锁屏/解锁监听。
 * 注意：ACTION_SCREEN_ON / ACTION_SCREEN_OFF 无法在清单静态注册，
 * 必须在前台服务内通过 registerReceiver 动态注册。
 * 关键变更（亮屏/息屏/解锁）即时上报。
 */
class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> DeviceStatusHolder.screenOn = true
            Intent.ACTION_SCREEN_OFF -> DeviceStatusHolder.screenOn = false
            Intent.ACTION_USER_PRESENT -> {
                DeviceStatusHolder.isLocked = false
                DeviceStatusHolder.screenOn = true
            }
            else -> return
        }
        // 必须先重新采集再上报：pushNow() 发的是 DeviceStatusHolder.current（上一次的快照），
        // 而本方法只改了 Holder.screenOn/isLocked。旧实现直接 pushNow()，
        // 推给对方的 screen_on 仍是旧值 —— "息屏了对方不知道"是数据错，不是延迟。
        StatusForegroundService.syncNow(context)
        // 屏幕状态变了 → 同步档位随之切换（息屏 5min / 亮屏按前后台）。
        SyncHeartbeat.schedule(
            context.applicationContext,
            appVisible = AppForegroundState.isForeground,
            screenOn = DeviceStatusHolder.screenOn,
            force = true,
        )
    }
}
