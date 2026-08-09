package com.linxi.diary.service

import com.linxi.diary.core.DeviceStatus

/** Android 官方通知模板使用的收起摘要与展开详情文本。 */
object NotificationStatusFormatter {
    fun summary(status: DeviceStatus?): String {
        if (status == null) return "等待对方状态同步"
        val screen = if (status.screenOn) "亮屏" else "息屏"
        val app = status.foregroundApp?.second?.takeIf { it.isNotBlank() } ?: "无前台"
        return "电量 ${status.batteryLevel}% · $screen · $app"
    }

    fun details(status: DeviceStatus?, updateTime: String): String {
        if (status == null) {
            return "电量：--\n屏幕：未知\n前台 App：未知\n网络：未知\n更新：等待对方同步"
        }
        val screen = if (status.screenOn) {
            "亮屏 · ${if (status.isLocked) "锁定" else "已解锁"}"
        } else {
            "息屏"
        }
        val network = if (status.network.equals("wifi", ignoreCase = true) || !status.ssid.isNullOrBlank()) {
            "WiFi"
        } else {
            "移动网络"
        }
        val music = status.music?.takeIf { it.playing }
            ?.let { "音乐：${it.title} - ${it.artist}\n" }
            ?: ""
        return "电量：${status.batteryLevel}%${if (status.isCharging) " · 充电中" else ""}\n" +
            "屏幕：$screen\n" +
            "前台 App：${status.foregroundApp?.second?.takeIf { it.isNotBlank() } ?: "无前台"}\n" +
            "网络：$network\n" +
            music +
            "更新于 $updateTime"
    }
}
