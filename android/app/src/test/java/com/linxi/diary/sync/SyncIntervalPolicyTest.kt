package com.linxi.diary.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SyncIntervalPolicyTest {

    @Test
    fun `前台可见用最短间隔`() {
        assertEquals(
            SyncIntervalPolicy.FOREGROUND_MS,
            SyncIntervalPolicy.intervalMs(appVisible = true, screenOn = true)
        )
    }

    @Test
    fun `后台但亮屏用中等间隔`() {
        assertEquals(
            SyncIntervalPolicy.BACKGROUND_MS,
            SyncIntervalPolicy.intervalMs(appVisible = false, screenOn = true)
        )
    }

    @Test
    fun `息屏优先级高于前台可见`() {
        // Composition 在息屏时仍可能存活，此时必须按息屏档而非前台档，否则白耗电。
        assertEquals(
            SyncIntervalPolicy.SCREEN_OFF_MS,
            SyncIntervalPolicy.intervalMs(appVisible = true, screenOn = false)
        )
        assertEquals(
            SyncIntervalPolicy.Phase.SCREEN_OFF,
            SyncIntervalPolicy.phase(appVisible = true, screenOn = false)
        )
    }

    @Test
    fun `分档间隔依次放宽`() {
        assertTrue(SyncIntervalPolicy.FOREGROUND_MS < SyncIntervalPolicy.BACKGROUND_MS)
        assertTrue(SyncIntervalPolicy.BACKGROUND_MS < SyncIntervalPolicy.SCREEN_OFF_MS)
    }

    @Test
    fun `前台间隔比改造前的30秒更短`() {
        // 管理员要求「同步间隔再减小一点」，此前是 30s。
        assertTrue(SyncIntervalPolicy.FOREGROUND_MS < 30_000L)
    }
}

class WsReconnectJitterTest {

    @Test
    fun `抖动后仍落在基值正负两成区间内`() {
        repeat(4) { retry ->
            val base = WsReconnectPolicy.backoffMillis(retry)
            val lo = (base * 0.8).toLong()
            val hi = (base * 1.2).toLong()
            repeat(50) {
                val v = WsReconnectPolicy.backoffWithJitterMillis(retry)
                assertTrue("retry=$retry v=$v 应在 [$lo,$hi]", v in lo..hi)
            }
        }
    }

    @Test
    fun `抖动不会产生非正的延迟`() {
        repeat(100) {
            assertTrue(WsReconnectPolicy.backoffWithJitterMillis(0) >= 100L)
        }
    }

    @Test
    fun `同一随机种子下抖动可复现`() {
        val a = WsReconnectPolicy.backoffWithJitterMillis(3, Random(42))
        val b = WsReconnectPolicy.backoffWithJitterMillis(3, Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `退避仍然封顶在16秒附近`() {
        assertEquals(16_000L, WsReconnectPolicy.backoffMillis(9))
        val v = WsReconnectPolicy.backoffWithJitterMillis(9)
        assertTrue("v=$v", v in 12_800L..19_200L)
    }
}
