package com.linxi.diary.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OngoingStatusPolicyTest {

    @Test
    fun `Adapter 顺序：厂商专项优先，标准通知兜底`() {
        // ColorOS 设备：优先 ColorOS，其次 Android16，最后标准。
        val order = OngoingStatusPolicy.adapterOrder(Vendor.COLOROS)
        assertEquals(
            listOf(AdapterId.COLOROS, AdapterId.ANDROID_LIVE_UPDATE, AdapterId.STANDARD),
            order,
        )
    }

    @Test
    fun `OriginOS 设备优先 OriginOS 专项`() {
        val order = OngoingStatusPolicy.adapterOrder(Vendor.ORIGINOS)
        assertEquals(AdapterId.ORIGINOS, order.first())
        assertEquals(AdapterId.STANDARD, order.last())
    }

    @Test
    fun `未知厂商只走 Android16 与标准通知`() {
        val order = OngoingStatusPolicy.adapterOrder(Vendor.OTHER)
        assertEquals(listOf(AdapterId.ANDROID_LIVE_UPDATE, AdapterId.STANDARD), order)
    }

    @Test
    fun `厂商识别按制造商字符串`() {
        assertEquals(Vendor.COLOROS, Vendor.fromManufacturer("OPPO"))
        assertEquals(Vendor.COLOROS, Vendor.fromManufacturer("realme"))
        assertEquals(Vendor.COLOROS, Vendor.fromManufacturer("OnePlus"))
        assertEquals(Vendor.ORIGINOS, Vendor.fromManufacturer("vivo"))
        assertEquals(Vendor.OTHER, Vendor.fromManufacturer("Xiaomi"))
        assertEquals(Vendor.OTHER, Vendor.fromManufacturer(null))
    }

    @Test
    fun `选择第一个可用 Adapter`() {
        val chosen = OngoingStatusPolicy.choose(
            order = listOf(AdapterId.COLOROS, AdapterId.ANDROID_LIVE_UPDATE, AdapterId.STANDARD),
            support = mapOf(
                AdapterId.COLOROS to SupportState.RequiresVendorApproval,
                AdapterId.ANDROID_LIVE_UPDATE to SupportState.Unsupported,
                AdapterId.STANDARD to SupportState.Supported,
            ),
        )
        assertEquals(AdapterId.STANDARD, chosen)
    }

    @Test
    fun `专项可用时优先专项`() {
        val chosen = OngoingStatusPolicy.choose(
            order = listOf(AdapterId.COLOROS, AdapterId.STANDARD),
            support = mapOf(
                AdapterId.COLOROS to SupportState.Supported,
                AdapterId.STANDARD to SupportState.Supported,
            ),
        )
        assertEquals(AdapterId.COLOROS, chosen)
    }

    @Test
    fun `标准通知始终兜底可用`() {
        assertEquals(SupportState.Supported, OngoingStatusPolicy.standardSupport())
    }
}
