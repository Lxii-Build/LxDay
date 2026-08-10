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
        assertTrue(
            NotificationUpdatePolicy.shouldUpdate(
                previous = null,
                current = NotificationRenderState(state, avatarFingerprint = 1),
            )
        )
        assertTrue(
            NotificationUpdatePolicy.shouldUpdate(
                previous = NotificationRenderState(state, avatarFingerprint = 1),
                current = NotificationRenderState(state.copy(battery = "85%"), avatarFingerprint = 1),
            )
        )
    }

    @Test
    fun `业务状态未变化时不因时间文本重复刷新`() {
        assertFalse(
            NotificationUpdatePolicy.shouldUpdate(
                previous = NotificationRenderState(state, avatarFingerprint = 1),
                current = NotificationRenderState(state, avatarFingerprint = 1),
            )
        )
    }

    @Test
    fun `头像缓存变化时即使业务状态相同也刷新`() {
        assertTrue(
            NotificationUpdatePolicy.shouldUpdate(
                previous = NotificationRenderState(state, avatarFingerprint = 1),
                current = NotificationRenderState(state, avatarFingerprint = 2),
            )
        )
    }
}
