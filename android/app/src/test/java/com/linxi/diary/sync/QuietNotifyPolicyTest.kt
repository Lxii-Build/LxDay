package com.linxi.diary.sync

import com.linxi.diary.core.DeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietNotifyPolicyTest {

    private fun status(screenOn: Boolean, battery: Int = 50) =
        DeviceStatus(batteryLevel = battery, screenOn = screenOn)

    @Test
    fun `首次拿到伴侣状态不发通知`() {
        // 否则每次冷启动或 WS 重连都会弹一条，属纯噪音。
        assertNull(QuietNotifyPolicy.diff(previous = null, next = status(screenOn = true)))
    }

    @Test
    fun `亮屏与息屏各自产生对应事件`() {
        val on = QuietNotifyPolicy.diff(status(screenOn = false), status(screenOn = true))
        assertEquals(QuietNotifyPolicy.Kind.SCREEN_ON, on?.kind)

        val off = QuietNotifyPolicy.diff(status(screenOn = true), status(screenOn = false))
        assertEquals(QuietNotifyPolicy.Kind.SCREEN_OFF, off?.kind)
    }

    @Test
    fun `屏幕状态没变则不发通知`() {
        // 电量等其它字段变化不应触发屏幕类通知。
        assertNull(
            QuietNotifyPolicy.diff(
                status(screenOn = true, battery = 80),
                status(screenOn = true, battery = 30)
            )
        )
    }

    @Test
    fun `同类事件在合并窗口内只发一条`() {
        val base = 1_000_000L
        assertTrue(QuietNotifyPolicy.shouldSend(QuietNotifyPolicy.Kind.SCREEN_ON, null, base))
        assertFalse(
            QuietNotifyPolicy.shouldSend(QuietNotifyPolicy.Kind.SCREEN_ON, base, base + 59_999)
        )
        assertTrue(
            QuietNotifyPolicy.shouldSend(QuietNotifyPolicy.Kind.SCREEN_ON, base, base + 60_000)
        )
    }

    @Test
    fun `开关关闭时一律不发`() {
        assertFalse(
            QuietNotifyPolicy.shouldSend(
                QuietNotifyPolicy.Kind.SCREEN_OFF, lastSentAtMs = null, nowMs = 1L, enabled = false
            )
        )
    }

    @Test
    fun `节流器按事件种类独立记账`() {
        var now = 0L
        val throttle = QuietNotifyThrottle { now }

        assertTrue(throttle.tryAcquire(QuietNotifyPolicy.Kind.SCREEN_ON))
        assertFalse("同种类窗口内应被拦", throttle.tryAcquire(QuietNotifyPolicy.Kind.SCREEN_ON))
        // 不同种类互不影响：刚发过亮屏，不应拦住息屏。
        assertTrue(throttle.tryAcquire(QuietNotifyPolicy.Kind.SCREEN_OFF))

        now += QuietNotifyPolicy.DEDUP_WINDOW_MS
        assertTrue("窗口过后应放行", throttle.tryAcquire(QuietNotifyPolicy.Kind.SCREEN_ON))
    }

    @Test
    fun `上线离线事件文案区分`() {
        assertEquals(QuietNotifyPolicy.Kind.ONLINE, QuietNotifyPolicy.presence(true).kind)
        assertEquals(QuietNotifyPolicy.Kind.OFFLINE, QuietNotifyPolicy.presence(false).kind)
    }
}
