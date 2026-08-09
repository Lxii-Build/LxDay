package com.linxi.diary.service

import com.linxi.diary.core.DeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCardStateTest {
    @Test
    fun `横向通知把设备状态拆成独立组件`() {
        val status = DeviceStatus(
            batteryLevel = 86,
            isCharging = true,
            screenOn = true,
            isLocked = false,
            foregroundApp = "com.tencent.mm" to "微信",
            network = "wifi"
        )

        assertEquals(
            NotificationCardState(
                foreground = "微信",
                sync = "已同步",
                phone = "亮屏 · 已解锁",
                battery = "86% · 充电中",
                network = "WiFi"
            ),
            NotificationCardState.from(status)
        )
    }

    @Test
    fun `无状态时使用紧凑未知占位`() {
        assertEquals(
            NotificationCardState(
                foreground = "等待同步",
                sync = "未同步",
                phone = "未知状态",
                battery = "未知电量",
                network = "未知网络"
            ),
            NotificationCardState.from(null)
        )
    }
}
