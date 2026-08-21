package com.linxi.diary.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态时效判定测试（Q36=D）。
 *
 * `NowScreen` 此前完全不看 `ts`：2 小时前的状态和 2 秒前的长得一模一样。
 * WS 一断（地铁/电梯/飞行模式），对方看到的是一个**自信满满的错误信息**——
 * 「正在使用微信 · 亮屏 · 电量 68%」，而这是两小时前的快照。
 * 管理员报的"状态总是显示不好"，很大一部分正是"同步不到时看不出来"。
 */
class StatusFreshnessTest {

    private val now = 1_787_000_000_000L

    @Test
    fun `新鲜状态正常展示`() {
        assertEquals(StatusFreshness.Level.Fresh, StatusFreshness.levelOf(now, now))
        assertEquals(
            StatusFreshness.Level.Fresh,
            StatusFreshness.levelOf(now - 60_000, now),
        )
        // 恰好在阈值上仍算新鲜
        assertEquals(
            StatusFreshness.Level.Fresh,
            StatusFreshness.levelOf(now - StatusFreshness.STALE_THRESHOLD_MS, now),
        )
    }

    @Test
    fun `超过10分钟标记可能过期`() {
        assertEquals(
            StatusFreshness.Level.Stale,
            StatusFreshness.levelOf(now - StatusFreshness.STALE_THRESHOLD_MS - 1, now),
        )
        assertEquals(
            StatusFreshness.Level.Stale,
            StatusFreshness.levelOf(now - 30 * 60_000, now),
        )
    }

    @Test
    fun `超过1小时视为失联`() {
        assertEquals(
            StatusFreshness.Level.Offline,
            StatusFreshness.levelOf(now - StatusFreshness.OFFLINE_THRESHOLD_MS - 1, now),
        )
        assertEquals(
            StatusFreshness.Level.Offline,
            StatusFreshness.levelOf(now - 3 * 60 * 60_000, now),
        )
    }

    @Test
    fun `从未同步过视为失联`() {
        assertEquals(StatusFreshness.Level.Offline, StatusFreshness.levelOf(0, now))
        assertEquals(StatusFreshness.Level.Offline, StatusFreshness.levelOf(-1, now))
    }

    @Test
    fun `时钟偏差不应误报过期`() {
        // 双方手机时钟可能有几秒到几分钟偏差，对方的 ts 可能"来自未来"。
        // 不能因为这个就报"过期"——那会让正常同步的状态莫名置灰。
        assertEquals(
            StatusFreshness.Level.Fresh,
            StatusFreshness.levelOf(now + 5 * 60_000, now),
        )
        assertEquals("刚刚", StatusFreshness.relativeLabel(now + 60_000, now))
    }

    @Test
    fun `相对时间文案`() {
        assertEquals("刚刚", StatusFreshness.relativeLabel(now, now))
        assertEquals("刚刚", StatusFreshness.relativeLabel(now - 30_000, now))
        assertEquals("3 分钟前", StatusFreshness.relativeLabel(now - 3 * 60_000, now))
        assertEquals("59 分钟前", StatusFreshness.relativeLabel(now - 59 * 60_000, now))
        assertEquals("2 小时前", StatusFreshness.relativeLabel(now - 2 * 60 * 60_000, now))
        assertEquals("3 天前", StatusFreshness.relativeLabel(now - 3L * 24 * 60 * 60_000, now))
        assertEquals("尚未同步", StatusFreshness.relativeLabel(0, now))
    }

    @Test
    fun `提示文案必须能区分三种情形`() {
        val fresh = StatusFreshness.hintText(now - 60_000, now)
        val stale = StatusFreshness.hintText(now - 30 * 60_000, now)
        val offline = StatusFreshness.hintText(now - 3 * 60 * 60_000, now)
        val never = StatusFreshness.hintText(0, now)

        // 三种文案必须彼此不同，否则用户无法分辨
        assertTrue("新鲜态应说'更新于'：$fresh", fresh.contains("更新于"))
        assertTrue("过期态必须明说可能已过期：$stale", stale.contains("可能已过期"))
        assertTrue("失联态应说失联：$offline", offline.contains("失联"))
        assertTrue("从未同步应单独提示：$never", never.contains("尚未"))
        assertEquals(4, setOf(fresh, stale, offline, never).size)
    }
}
