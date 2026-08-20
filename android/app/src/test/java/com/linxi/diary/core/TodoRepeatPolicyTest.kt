package com.linxi.diary.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 循环提醒的下一次时刻计算。语义必须与服务端 handlers.go 的 normalizeRepeat/nextRemind 一致，
 * 否则本地闹钟与服务端推送会错时（用户收到两次或错过）。
 */
class TodoRepeatPolicyTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun weekdayIndexOf(ms: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ms }
            .let { (it.get(Calendar.DAY_OF_WEEK) + 5) % 7 }

    @Test
    fun `全选七天归一化为每天`() {
        assertEquals(1 to 0, TodoRepeatPolicy.normalize(2, TodoRepeatPolicy.ALL_WEEKDAYS_MASK))
    }

    @Test
    fun `一天都没选退化为仅一次`() {
        assertEquals(0 to 0, TodoRepeatPolicy.normalize(2, 0))
    }

    @Test
    fun `每周只保留低七位掩码`() {
        // 高位脏数据（0xF80）必须被裁掉，只留周一与周三。
        assertEquals(2 to 0b0000101, TodoRepeatPolicy.normalize(2, 0xF80 or 0b0000101))
    }

    @Test
    fun `仅一次不产生下一次`() {
        val cur = at(2026, 8, 20)
        assertNull(TodoRepeatPolicy.nextRemindAt(cur, repeatType = 0, weekdays = 0, nowMs = cur))
    }

    @Test
    fun `每天推进到次日同一时刻`() {
        val cur = at(2026, 8, 20, hour = 9, minute = 30)
        val next = TodoRepeatPolicy.nextRemindAt(cur, repeatType = 1, weekdays = 0, nowMs = cur)
        assertEquals(at(2026, 8, 21, hour = 9, minute = 30), next)
    }

    @Test
    fun `每天在远期过期时间上也能推进到未来而不死循环`() {
        // 脏数据场景：remindAt 是一年前。必须仍然返回一个晚于 now 的时刻，且不卡死。
        val stale = at(2025, 8, 20, hour = 8)
        val now = at(2026, 8, 20, hour = 12)
        val next = TodoRepeatPolicy.nextRemindAt(stale, repeatType = 1, weekdays = 0, nowMs = now)
        assertTrue("next=$next 应晚于 now=$now", next != null && next > now)
    }

    @Test
    fun `每周命中下一个被选中的星期几`() {
        val cur = at(2026, 8, 20, hour = 7)
        val curIdx = weekdayIndexOf(cur)
        // 只选「当天的后两天」那个星期几，期望正好推进 2 天。
        val targetIdx = (curIdx + 2) % 7
        val next = TodoRepeatPolicy.nextRemindAt(
            cur, repeatType = 2, weekdays = 1 shl targetIdx, nowMs = cur
        )
        assertEquals(at(2026, 8, 22, hour = 7), next)
        assertEquals(targetIdx, weekdayIndexOf(next!!))
    }

    @Test
    fun `每周选中当天时跳到下周同一天而不是原地`() {
        val cur = at(2026, 8, 20, hour = 7)
        val idx = weekdayIndexOf(cur)
        val next = TodoRepeatPolicy.nextRemindAt(cur, repeatType = 2, weekdays = 1 shl idx, nowMs = cur)
        // 结果必须严格晚于 now，且落在同一星期几（+7 天）。
        assertEquals(at(2026, 8, 27, hour = 7), next)
        assertEquals(idx, weekdayIndexOf(next!!))
    }

    @Test
    fun `每周掩码为空返回空`() {
        val cur = at(2026, 8, 20)
        assertNull(TodoRepeatPolicy.nextRemindAt(cur, repeatType = 2, weekdays = 0, nowMs = cur))
    }
}
