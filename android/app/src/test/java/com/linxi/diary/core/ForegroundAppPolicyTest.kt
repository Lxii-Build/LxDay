package com.linxi.diary.core

import com.linxi.diary.core.ForegroundAppPolicy.Event
import com.linxi.diary.core.ForegroundAppPolicy.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台应用判定的回归测试。
 *
 * 管理员报「获取前台应用相关信息和息屏亮屏状态，总是显示不好」。
 * 查明三个缺陷，这里逐条锁死：
 *   ② 旧实现遍历过去 24 小时全部事件（前台每 10 秒一次，重度使用上万条）
 *   ③ 只认 RESUMED、**不看 PAUSED/STOPPED** → 回桌面后仍显示上一个应用
 *   ④ **息屏时不清空** → 息屏后仍挂着"正在使用微信"
 */
class ForegroundAppPolicyTest {

    private val base = 1_787_000_000_000L

    @Test
    fun `最后一个RESUMED就是前台应用`() {
        val events = listOf(
            Event("com.android.launcher", base, Type.Resumed),
            Event("com.tencent.mm", base + 1000, Type.Resumed),
        )
        assertEquals("com.tencent.mm", ForegroundAppPolicy.resolve(events))
    }

    @Test
    fun `回桌面后不应仍显示上一个应用`() {
        // 用户用微信 → 按 Home。旧实现只看 RESUMED，最后一个仍是微信，
        // 于是一直显示"正在使用微信"，而人已经在桌面了。
        val events = listOf(
            Event("com.tencent.mm", base, Type.Resumed),
            Event("com.tencent.mm", base + 5000, Type.Paused),
        )
        assertNull(
            "最后一个动作是 PAUSED，应判为无前台应用（在桌面）",
            ForegroundAppPolicy.resolve(events),
        )
    }

    @Test
    fun `STOPPED同样算离开`() {
        val events = listOf(
            Event("com.tencent.mm", base, Type.Resumed),
            Event("com.tencent.mm", base + 3000, Type.Stopped),
        )
        assertNull(ForegroundAppPolicy.resolve(events))
    }

    @Test
    fun `切换应用时同刻的PAUSED与RESUMED应取RESUMED`() {
        // A→B 切换时，A.Paused 与 B.Resumed 常常是同一毫秒。
        // 此时前台显然是 B，不能因为排序不稳定而判成"无前台"。
        val events = listOf(
            Event("com.a", base, Type.Resumed),
            Event("com.a", base + 5000, Type.Paused),
            Event("com.b", base + 5000, Type.Resumed),
        )
        assertEquals("com.b", ForegroundAppPolicy.resolve(events))
        // 换个输入顺序结果必须一致
        assertEquals("com.b", ForegroundAppPolicy.resolve(events.reversed()))
    }

    @Test
    fun `空事件列表返回null`() {
        assertNull(ForegroundAppPolicy.resolve(emptyList()))
    }

    @Test
    fun `息屏时不应查询前台应用`() {
        // 这是「息屏后仍显示正在使用微信」的修复点。
        assertFalse(
            "息屏时不该查前台应用",
            ForegroundAppPolicy.shouldQuery(ScreenState.Off, hasUsageAccess = true),
        )
        assertFalse(
            "AOD（息屏显示）同样不算在用手机",
            ForegroundAppPolicy.shouldQuery(ScreenState.Aod, hasUsageAccess = true),
        )
        assertTrue(
            "亮屏且有权限才查",
            ForegroundAppPolicy.shouldQuery(ScreenState.On, hasUsageAccess = true),
        )
        assertFalse(
            "没有「使用情况访问」权限时白查一趟没意义",
            ForegroundAppPolicy.shouldQuery(ScreenState.On, hasUsageAccess = false),
        )
    }

    @Test
    fun `查询窗口必须远小于24小时`() {
        // 旧实现是 86_400_000L（24 小时）。前台档位每 10 秒采集一次，
        // 每次遍历上万条事件——这是性能与耗电的双重问题。
        assertTrue(
            "主窗口应在分钟级",
            ForegroundAppPolicy.PRIMARY_WINDOW_MS <= 5 * 60_000L,
        )
        val windows = ForegroundAppPolicy.windowSequence()
        // 必须严格递增，否则回退逻辑白做
        for (i in 1 until windows.size) {
            assertTrue(
                "回退窗口必须递增：${windows.toList()}",
                windows[i] > windows[i - 1],
            )
        }
        // 第一个窗口就是主窗口
        assertEquals(ForegroundAppPolicy.PRIMARY_WINDOW_MS, windows.first())
    }

    @Test
    fun `缓存在TTL内可用超出则失效`() {
        val now = base
        assertTrue(ForegroundAppPolicy.cacheUsable(now - 60_000, now))
        assertTrue(ForegroundAppPolicy.cacheUsable(now, now))
        assertFalse(
            "超过 TTL 的缓存不该再用",
            ForegroundAppPolicy.cacheUsable(now - ForegroundAppPolicy.CACHE_TTL_MS - 1, now),
        )
        assertFalse("从未缓存过", ForegroundAppPolicy.cacheUsable(0, now))
    }

    @Test
    fun `AOD不应算作亮屏上报`() {
        // 一加 15 的 AOD 默认开着。若把 AOD 当亮屏，
        // 对方会看到"他在用手机"，而屏幕只是显示着时钟。
        assertTrue(ScreenState.On.reportAsOn)
        assertFalse("AOD 不是在用手机", ScreenState.Aod.reportAsOn)
        assertFalse(ScreenState.Off.reportAsOn)
        // 三档都要有中文描述
        assertEquals("亮屏", ScreenState.On.label)
        assertEquals("息屏", ScreenState.Off.label)
        assertEquals("息屏显示", ScreenState.Aod.label)
    }
}
