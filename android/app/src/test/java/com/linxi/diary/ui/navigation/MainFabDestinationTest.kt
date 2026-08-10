package com.linxi.diary.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainFabDestinationTest {
    @Test
    fun `只有待办和日记页面显示主层 FAB`() {
        assertEquals(MainFabDestination.None, MainFabDestination.forPage(0))
        assertEquals(MainFabDestination.Todo, MainFabDestination.forPage(1))
        assertEquals(MainFabDestination.Diary, MainFabDestination.forPage(2))
        assertEquals(MainFabDestination.None, MainFabDestination.forPage(3))
    }
}
