package com.linxi.diary.service

import com.linxi.diary.core.DeviceStatus

data class NotificationCardState(
    val foreground: String,
    val sync: String,
    val phone: String,
    val battery: String,
    val network: String,
) {
    companion object {
        fun from(status: DeviceStatus?): NotificationCardState {
            if (status == null) {
                return NotificationCardState(
                    foreground = "等待同步",
                    sync = "未同步",
                    phone = "未知状态",
                    battery = "未知电量",
                    network = "未知网络",
                )
            }
            return NotificationCardState(
                foreground = status.foregroundApp?.second?.takeIf { it.isNotBlank() } ?: "无前台应用",
                sync = "已同步",
                phone = if (status.screenOn) "亮屏 · ${if (status.isLocked) "锁定" else "已解锁"}" else "息屏",
                battery = "${status.batteryLevel}%${if (status.isCharging) " · 充电中" else ""}",
                network = if (status.network.equals("wifi", true) || !status.ssid.isNullOrBlank()) "WiFi" else "移动网络",
            )
        }
    }
}
