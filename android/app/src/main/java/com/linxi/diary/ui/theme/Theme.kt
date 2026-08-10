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
import com.materialkolor.rememberDynamicColorScheme
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

val LocalLinxiDarkTheme = staticCompositionLocalOf { false }

@Composable
fun LinxiTheme(appearance: AppearanceSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = AppThemeResolver.isDark(appearance.colorMode, isSystemInDarkTheme())
    val controller = remember(appearance.colorMode, darkTheme) {
        ThemeController(
            colorSchemeMode = when (appearance.colorMode) {
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
    // 主题种子色：手动 > 壁纸缓存 > 品牌粉；系统动态色来源返回 null 时回落品牌粉。
    val seedArgb = SeedColorPolicy.resolveSeed(
        source = appearance.colorSource,
        manualArgb = appearance.keyColorArgb,
        wallpaperSeed = appearance.wallpaper?.cachedSeedArgb,
        fallback = LinxiSeedPink,
    ) ?: LinxiSeedPink
    val materialColors = rememberDynamicColorScheme(
        seedColor = Color(seedArgb),
        isDark = darkTheme,
        isAmoled = false,
        style = MaterialKolorMapping.paletteStyle(appearance.paletteStyle),
        specVersion = MaterialKolorMapping.specVersion(appearance.colorSpec, appearance.paletteStyle),
    )
    CompositionLocalProvider(LocalLinxiDarkTheme provides darkTheme) {
        MiuixTheme(controller = controller) {
            MaterialTheme(colorScheme = materialColors, typography = Typography(), content = content)
        }
    }
}
