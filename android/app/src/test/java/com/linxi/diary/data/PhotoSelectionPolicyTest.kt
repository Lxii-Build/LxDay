package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoSelectionPolicyTest {

    @Test
    fun `达到上限时不改选择并返回明确结果`() {
        val selected = mutableListOf("a", "b")
        val result = PhotoSelectionPolicy.toggle(selected, "c", multiple = true, maxItems = 2)

        assertEquals(SelectionToggleResult.LimitReached, result)
        assertEquals(listOf("a", "b"), selected)
    }

    @Test
    fun `已选项即使达到上限也能取消`() {
        val selected = mutableListOf("a", "b")
        val result = PhotoSelectionPolicy.toggle(selected, "a", multiple = true, maxItems = 2)

        assertEquals(SelectionToggleResult.Deselected, result)
        assertEquals(listOf("b"), selected)
    }

    @Test
    fun `单选会替换旧项`() {
        val selected = mutableListOf("a")
        val result = PhotoSelectionPolicy.toggle(selected, "b", multiple = false, maxItems = 100)

        assertEquals(SelectionToggleResult.Selected, result)
        assertEquals(listOf("b"), selected)
    }
}
