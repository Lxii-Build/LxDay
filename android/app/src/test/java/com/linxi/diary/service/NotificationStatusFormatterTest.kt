package com.linxi.diary.service

import com.linxi.diary.core.DeviceStatus
import com.linxi.diary.core.MusicInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationStatusFormatterTest {

    @Test
    fun `收起摘要包含电量屏幕与前台应用`() {
        val status = DeviceStatus(
            batteryLevel = 86,
            screenOn = true,
            isLocked = false,
            foregroundApp = "com.tencent.mm" to "微信"
        )

        assertEquals(
            "电量 86% · 亮屏 · 微信",
            NotificationStatusFormatter.summary(status)
        )
    }

    @Test
    fun `展开详情按官方通知字段展示完整状态`() {
        val status = DeviceStatus(
            batteryLevel = 12,
            isCharging = true,
            screenOn = true,
            isLocked = true,
            foregroundApp = "com.spotify.music" to "Spotify",
            ssid = "HomeWiFi",
            music = MusicInfo("Song", "Artist", playing = true)
        )

        assertEquals(
            "电量：12% · 充电中\n屏幕：亮屏 · 锁定\n前台 App：Spotify\n网络：WiFi\n音乐：Song - Artist\n更新于 10:30",
            NotificationStatusFormatter.details(status, "10:30")
        )
    }

    @Test
    fun `SSID 不可见时使用 network 字段判断 WiFi`() {
        val status = DeviceStatus(network = "wifi", ssid = null)

        assertEquals(
            "电量：0%\n屏幕：息屏\n前台 App：无前台\n网络：WiFi\n更新于 10:30",
            NotificationStatusFormatter.details(status, "10:30")
        )
    }

    @Test
    fun `空状态使用安全占位文本`() {
        assertEquals("等待对方状态同步", NotificationStatusFormatter.summary(null))
        assertEquals(
            "电量：--\n屏幕：未知\n前台 App：未知\n网络：未知\n更新：等待对方同步",
            NotificationStatusFormatter.details(null, "10:30")
        )
    }
}
