package com.linxi.diary.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 与 KernelSU 对齐：仅跟随系统、浅色、深色三种模式。 */
enum class ColorMode(val value: Int) {
    SYSTEM(0), LIGHT(1), DARK(2);
    companion object {
        fun fromValue(value: Int) = when (value) {
            LIGHT.value -> LIGHT
            DARK.value, 3 -> DARK // 兼容旧 AMOLED 偏好
            else -> SYSTEM
        }
    }
}

data class AppSettings(val colorMode: ColorMode)

val LocalLinxiDarkTheme = staticCompositionLocalOf { false }

@Composable
fun LinxiTheme(appSettings: AppSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = appSettings.colorMode == ColorMode.DARK ||
        (appSettings.colorMode == ColorMode.SYSTEM && isSystemInDarkTheme())
    val controller = remember(appSettings.colorMode, darkTheme) {
        ThemeController(
            colorSchemeMode = when (appSettings.colorMode) {
                ColorMode.SYSTEM -> ColorSchemeMode.System
                ColorMode.LIGHT -> ColorSchemeMode.Light
                ColorMode.DARK -> ColorSchemeMode.Dark
            },
            keyColor = Color(LinxiSeedPink),
            isDark = darkTheme,
        )
    }
    LaunchedEffect(darkTheme) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalLinxiDarkTheme provides darkTheme) {
        MiuixTheme(controller = controller) {
            MaterialTheme(typography = Typography(), content = content)
        }
    }
}
