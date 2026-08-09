package com.linxi.diary.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeResolverTest {
    @Test
    fun `强制浅色忽略系统暗色`() {
        assertFalse(AppThemeResolver.isDark(ColorMode.LIGHT, systemDark = true))
    }

    @Test
    fun `强制深色忽略系统浅色`() {
        assertTrue(AppThemeResolver.isDark(ColorMode.DARK, systemDark = false))
    }

    @Test
    fun `跟随系统使用系统暗色状态`() {
        assertTrue(AppThemeResolver.isDark(ColorMode.SYSTEM, systemDark = true))
        assertFalse(AppThemeResolver.isDark(ColorMode.SYSTEM, systemDark = false))
    }
}
