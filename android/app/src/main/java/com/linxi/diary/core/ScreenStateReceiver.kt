package com.linxi.diary.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.linxi.diary.sync.StatusSyncManager

/**
 * 亮屏/锁屏/解锁监听。
 * 注意：ACTION_SCREEN_ON / ACTION_SCREEN_OFF 无法在清单静态注册，
 * 必须在前台服务内通过 registerReceiver 动态注册。
 * 关键变更（解锁/亮屏）即时上报。
 */
class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> DeviceStatusHolder.screenOn = true
            Intent.ACTION_SCREEN_OFF -> DeviceStatusHolder.screenOn = false
            Intent.ACTION_USER_PRESENT -> DeviceStatusHolder.isLocked = false
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> DeviceStatusHolder.isLocked = true
            else -> return
        }
        // 亮屏/解锁为关键状态变更 → 即时上报
        StatusSyncManager.pushNow()
    }
}
