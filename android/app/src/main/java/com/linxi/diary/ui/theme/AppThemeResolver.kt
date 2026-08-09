package com.linxi.diary.ui.theme

object AppThemeResolver {
    fun isDark(mode: ColorMode, systemDark: Boolean): Boolean = when (mode) {
        ColorMode.SYSTEM -> systemDark
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
}
