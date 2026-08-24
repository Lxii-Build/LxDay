package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFFSET 分页错位导致 LazyColumn 重复 key 崩溃的回归测试。
 *
 * ## 根因
 *
 * 服务端是 `ORDER BY ... LIMIT ? OFFSET ?`，客户端把「已加载条数」当 offset。
 * 列表持续增长时会错位：
 *
 * ```
 * 第 1 页（offset=0, limit=50）→ [1..50]
 * 新写入 1 条 → 全体下移
 * 第 2 页（offset=50）        → 服务端的第 51 条正是原来的第 50 条 → 重复
 * ```
 *
 * `LazyColumn` 的 `items(key = ...)` **要求 key 唯一，重复即抛
 * IllegalArgumentException 崩溃**。
 *
 * 两个真实触发场景：
 * - 状态历史每 5 分钟落一条，看「今天」时列表一直在长。傍晚超过 50 条后
 *   滚到底必崩，而滚到底是 `LaunchedEffect` 自动触发的 —— 用户没做任何操作。
 * - 相册详情里**对方**上传照片同样让列表增长。自己上传走整页重载，所以自测发现不了。
 *
 * 数据库唯一约束拦不住：每条记录本身都合法且唯一，重复只存在于客户端拼出来的那份列表。
 */
class PagingMergeTest {

    private data class Row(val id: Long)

    @Test
    fun `重复项必须被丢掉`() {
        val current = listOf(Row(50), Row(49), Row(48))
        // 错位一位：服务端把第 48 条又返回了一次
        val more = listOf(Row(48), Row(47), Row(46))
        val merged = PagingMerge.appendDistinct(current, more) { it.id }
        assertEquals(
            "重复的 48 必须被丢掉，否则 LazyColumn 撞重复 key 崩溃",
            listOf(50L, 49L, 48L, 47L, 46L), merged.map { it.id },
        )
    }

    @Test
    fun `无重复时行为与直接拼接一致`() {
        val current = listOf(Row(3), Row(2))
        val more = listOf(Row(1))
        assertEquals(listOf(3L, 2L, 1L), PagingMerge.appendDistinct(current, more) { it.id }.map { it.id })
    }

    @Test
    fun `合并结果的key必须始终唯一`() {
        // 这是崩溃的直接判据：无论输入多脏，输出的 key 集合必须无重复
        val current = listOf(Row(5), Row(4), Row(3))
        val dirty = listOf(Row(5), Row(4), Row(3), Row(2)) // 整页几乎全重
        val merged = PagingMerge.appendDistinct(current, dirty) { it.id }
        val ids = merged.map { it.id }
        assertEquals("合并后不允许出现重复 key", ids.size, ids.toSet().size)
        assertEquals(listOf(5L, 4L, 3L, 2L), ids)
    }

    @Test
    fun `整页都是重复时要能判定到底`() {
        // 错位超过一整页时会这样。不停下来就会无限拉同一页：
        // 表现为滚到底后一直转圈、流量白跑。
        val current = listOf(Row(3), Row(2), Row(1))
        val allDup = listOf(Row(3), Row(2), Row(1))
        assertTrue(PagingMerge.allDuplicates(current, allDup) { it.id })
        // 只要有一条是新的就不算到底
        assertFalse(PagingMerge.allDuplicates(current, listOf(Row(3), Row(0))) { it.id })
    }

    @Test
    fun `空的追加不改变原列表`() {
        val current = listOf(Row(2), Row(1))
        assertEquals(current, PagingMerge.appendDistinct(current, emptyList()) { it.id })
        assertTrue(PagingMerge.allDuplicates(current, emptyList()) { it.id })
    }

    @Test
    fun `全是重复时返回原列表实例语义`() {
        val current = listOf(Row(2), Row(1))
        val merged = PagingMerge.appendDistinct(current, listOf(Row(2))) { it.id }
        assertEquals("没有新项时内容应与原列表一致", current.map { it.id }, merged.map { it.id })
    }

    @Test
    fun `状态历史用ts做key同样成立`() {
        // 状态历史的 key 是时间戳而不是 id，同一套逻辑要能复用
        data class Entry(val ts: Long)
        val current = listOf(Entry(1_787_000_300_000), Entry(1_787_000_000_000))
        val more = listOf(Entry(1_787_000_000_000), Entry(1_786_999_700_000))
        val merged = PagingMerge.appendDistinct(current, more) { it.ts }
        assertEquals(3, merged.size)
        assertEquals(merged.size, merged.map { it.ts }.toSet().size)
    }
}
