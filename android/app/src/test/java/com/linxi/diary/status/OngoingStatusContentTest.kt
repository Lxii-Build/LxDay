package com.linxi.diary.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OngoingStatusContentTest {

    private fun sample() = OngoingStatus(
        partnerName = "对方",
        foregroundApp = "微信",
        screenOn = true,
        batteryLevel = 80,
        charging = false,
        network = "WiFi",
        syncLabel = "刚刚同步",
        updateTimeMillis = 1_000L,
    )

    @Test
    fun `仅更新时间变化不改变内容哈希`() {
        val a = sample()
        val b = a.copy(updateTimeMillis = 999_999L)
        assertEquals(OngoingStatusContent.hash(a), OngoingStatusContent.hash(b))
    }

    @Test
    fun `真实状态变化改变内容哈希`() {
        val a = sample()
        assertNotEquals(OngoingStatusContent.hash(a), OngoingStatusContent.hash(a.copy(batteryLevel = 30)))
        assertNotEquals(OngoingStatusContent.hash(a), OngoingStatusContent.hash(a.copy(foregroundApp = "抖音")))
        assertNotEquals(OngoingStatusContent.hash(a), OngoingStatusContent.hash(a.copy(screenOn = false)))
    }

    @Test
    fun `内容未变化时不触发刷新`() {
        val a = sample()
        assertFalse(OngoingStatusContent.shouldRefresh(lastHash = OngoingStatusContent.hash(a), next = a.copy(updateTimeMillis = 5L)))
    }

    @Test
    fun `内容变化时触发刷新`() {
        val a = sample()
        assertTrue(OngoingStatusContent.shouldRefresh(lastHash = OngoingStatusContent.hash(a), next = a.copy(batteryLevel = 10)))
    }

    @Test
    fun `简要隐私级隐藏前台 App 与网络名`() {
        val filtered = LockscreenPrivacy.BRIEF.filter(sample())
        assertEquals(null, filtered.foregroundApp)
        assertEquals(null, filtered.network) // 不显示 SSID
        assertEquals(80, filtered.batteryLevel) // 电量保留
    }

    @Test
    fun `隐藏级不显示任何敏感内容`() {
        val filtered = LockscreenPrivacy.HIDDEN.filter(sample())
        assertEquals(null, filtered.foregroundApp)
        assertEquals(null, filtered.network)
        assertEquals(null, filtered.batteryLevel)
    }

    @Test
    fun `完整级保留全部内容`() {
        val filtered = LockscreenPrivacy.FULL.filter(sample())
        assertEquals("微信", filtered.foregroundApp)
        assertEquals("WiFi", filtered.network)
    }
}
