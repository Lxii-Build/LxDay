package com.linxi.diary.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoContentTest {
    @Test
    fun `调试示例使用本地负数 ID 且不包含提醒`() {
        assertEquals(3, DemoContent.todos.size)
        assertTrue(DemoContent.todos.all { it.id < 0 && it.remindAtMs == null })
        assertEquals(3, DemoContent.diaries.size)
        assertTrue(DemoContent.diaries.all { it.id < 0 })
    }

    @Test
    fun `只有显式调试模式使用示例数据`() {
        assertTrue(DemoMode.shouldUseDemo(enabled = true))
        assertTrue(!DemoMode.shouldUseDemo(enabled = false))
    }
}
