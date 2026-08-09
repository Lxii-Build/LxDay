package com.linxi.diary.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationUpdatePolicyTest {
    private val state = NotificationCardState(
        foreground = "微信",
        sync = "已同步",
        phone = "亮屏 · 已解锁",
        battery = "86%",
        network = "WiFi",
    )

    @Test
    fun `首次状态和业务状态变化时刷新通知`() {
        assertTrue(NotificationUpdatePolicy.shouldUpdate(previous = null, current = state))
        assertTrue(
            NotificationUpdatePolicy.shouldUpdate(
                previous = state,
                current = state.copy(battery = "85%"),
            )
        )
    }

    @Test
    fun `业务状态未变化时不因时间文本重复刷新`() {
        assertFalse(NotificationUpdatePolicy.shouldUpdate(previous = state, current = state))
    }
}
