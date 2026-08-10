package com.linxi.diary.status

import org.junit.Assert.assertEquals
import org.junit.Test

class OngoingStatusControllerTest {

    private fun status() = OngoingStatus(
        partnerName = "对方", foregroundApp = "微信", screenOn = true,
        batteryLevel = 80, charging = false, network = "WiFi",
        syncLabel = "刚刚", updateTimeMillis = 1L,
    )

    private class FakeAdapter(
        override val id: AdapterId,
        private val support: SupportState,
        private val showResult: Boolean = true,
    ) : OngoingStatusAdapter {
        var shownCount = 0; private set
        var cleared = false; private set
        override fun support(): SupportState = support
        override fun show(status: OngoingStatus): Boolean { shownCount++; return showResult }
        override fun clear() { cleared = true }
    }

    @Test
    fun `专项可用时选择专项承载`() {
        val coloros = FakeAdapter(AdapterId.COLOROS, SupportState.Supported)
        val standard = FakeAdapter(AdapterId.STANDARD, SupportState.Supported)
        val controller = OngoingStatusController(listOf(coloros, standard), Vendor.COLOROS)
        val used = controller.present(status(), LockscreenPrivacy.FULL)
        assertEquals(AdapterId.COLOROS, used)
        assertEquals(1, coloros.shownCount)
        assertEquals(0, standard.shownCount)
    }

    @Test
    fun `专项不可用时降级标准通知`() {
        val coloros = FakeAdapter(AdapterId.COLOROS, SupportState.Unsupported)
        val standard = FakeAdapter(AdapterId.STANDARD, SupportState.Supported)
        val controller = OngoingStatusController(listOf(coloros, standard), Vendor.COLOROS)
        val used = controller.present(status(), LockscreenPrivacy.FULL)
        assertEquals(AdapterId.STANDARD, used)
        assertEquals(1, standard.shownCount)
    }

    @Test
    fun `专项呈现失败运行期降级标准通知`() {
        val coloros = FakeAdapter(AdapterId.COLOROS, SupportState.Supported, showResult = false)
        val standard = FakeAdapter(AdapterId.STANDARD, SupportState.Supported)
        val controller = OngoingStatusController(listOf(coloros, standard), Vendor.COLOROS)
        val used = controller.present(status(), LockscreenPrivacy.FULL)
        assertEquals(AdapterId.STANDARD, used)
        assertEquals(1, coloros.shownCount)
        assertEquals(1, standard.shownCount)
    }

    @Test
    fun `内容未变化时不重复呈现`() {
        val standard = FakeAdapter(AdapterId.STANDARD, SupportState.Supported)
        val controller = OngoingStatusController(listOf(standard), Vendor.OTHER)
        controller.present(status(), LockscreenPrivacy.FULL)
        controller.present(status().copy(updateTimeMillis = 999L), LockscreenPrivacy.FULL)
        assertEquals(1, standard.shownCount) // 仅时间变化不刷新
    }

    @Test
    fun `真实内容变化触发再次呈现`() {
        val standard = FakeAdapter(AdapterId.STANDARD, SupportState.Supported)
        val controller = OngoingStatusController(listOf(standard), Vendor.OTHER)
        controller.present(status(), LockscreenPrivacy.FULL)
        controller.present(status().copy(batteryLevel = 10), LockscreenPrivacy.FULL)
        assertEquals(2, standard.shownCount)
    }
}
