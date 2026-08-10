package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.theme.AppColorSpec
import com.linxi.diary.ui.theme.AppPaletteStyle
import com.linxi.diary.ui.theme.AppearanceSettings
import com.linxi.diary.ui.theme.ColorMode
import com.linxi.diary.ui.theme.ColorSource
import com.linxi.diary.ui.theme.rememberThemeState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 主题与界面二级页（KernelSU 外观中心业务子集）：
 * 模式 / Monet / 颜色来源 / PaletteStyle / ColorSpec / 模糊 / 悬浮栏 / 玻璃 / 预测返回 / 页面缩放 / 壁纸。
 * UI 只观察 AppearanceSettings 单一状态，改动经 ThemeState.update 落盘。
 */
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onOpenWallpaper: () -> Unit,
) {
    val themeState = rememberThemeState()
    val s by themeState.appearance

    val colorModeItems = listOf("跟随系统", "浅色", "深色")
    val colorSourceItems = listOf("壁纸取色", "系统动态色", "手动种子色")
    val styleItems = AppPaletteStyle.entries.map { it.label }
    val specItems = listOf("2021", "2025")
    val scaleItems = listOf("50%", "75%", "100%", "125%", "150%")
    val scaleValues = listOf(50, 75, 100, 125, 150)

    KernelScreen(title = "主题与界面", navigationIcon = { BackAction(onBack) }) {
        item {
            SmallTitle("配色")
            Card(Modifier.padding(top = 6.dp).fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = "主题模式",
                    items = colorModeItems,
                    selectedIndex = s.colorMode.ordinal,
                    onSelectedIndexChange = { i ->
                        themeState.update { it.copy(colorMode = ColorMode.entries[i]) }
                    },
                )
                SwitchPreference(
                    title = "系统动态色 Monet",
                    summary = "跟随系统壁纸取色（Android 12+）",
                    checked = s.monetEnabled,
                    onCheckedChange = { on -> themeState.update { it.copy(monetEnabled = on) } },
                )
                OverlayDropdownPreference(
                    title = "颜色来源",
                    items = colorSourceItems,
                    selectedIndex = s.colorSource.ordinal,
                    onSelectedIndexChange = { i ->
                        themeState.update { it.copy(colorSource = ColorSource.entries[i]) }
                    },
                )
                OverlayDropdownPreference(
                    title = "调色板样式",
                    items = styleItems,
                    selectedIndex = s.paletteStyle.ordinal,
                    onSelectedIndexChange = { i ->
                        themeState.update { it.copy(paletteStyle = AppPaletteStyle.entries[i]) }
                    },
                )
                OverlayDropdownPreference(
                    title = "配色规格",
                    summary = specSummary(s),
                    items = specItems,
                    selectedIndex = if (s.colorSpec == AppColorSpec.SPEC_2025) 1 else 0,
                    onSelectedIndexChange = { i ->
                        val spec = if (i == 1) AppColorSpec.SPEC_2025 else AppColorSpec.SPEC_2021
                        themeState.update { it.copy(colorSpec = spec) }
                    },
                )
            }
        }
        item {
            SmallTitle("界面")
            Card(Modifier.padding(top = 6.dp).fillMaxWidth()) {
                SwitchPreference(
                    title = "页面模糊",
                    summary = "顶栏与悬浮栏毛玻璃采样",
                    checked = s.blurEnabled,
                    onCheckedChange = { on -> themeState.update { it.copy(blurEnabled = on) } },
                )
                SwitchPreference(
                    title = "悬浮底栏",
                    summary = "关闭后使用普通导航栏",
                    checked = s.floatingBottomBarEnabled,
                    onCheckedChange = { on -> themeState.update { it.copy(floatingBottomBarEnabled = on) } },
                )
                if (s.floatingBottomBarEnabled) {
                    SwitchPreference(
                        title = "悬浮栏玻璃",
                        checked = s.floatingGlassEnabled,
                        onCheckedChange = { on -> themeState.update { it.copy(floatingGlassEnabled = on) } },
                    )
                }
                SwitchPreference(
                    title = "预测性返回",
                    checked = s.predictiveBackEnabled,
                    onCheckedChange = { on -> themeState.update { it.copy(predictiveBackEnabled = on) } },
                )
                OverlayDropdownPreference(
                    title = "页面缩放",
                    items = scaleItems,
                    selectedIndex = scaleValues.indexOf(s.clampedScalePercent()).coerceAtLeast(0),
                    onSelectedIndexChange = { i ->
                        themeState.update { it.copy(pageScalePercent = scaleValues[i]) }
                    },
                )
            }
        }
        item {
            SmallTitle("壁纸")
            Card(Modifier.padding(top = 6.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "全局壁纸",
                    summary = if (s.wallpaper != null) "已设置 · 点击更换或移除" else "选择图片并裁剪为壁纸",
                    onClick = onOpenWallpaper,
                )
            }
        }
    }
}

private fun specSummary(s: AppearanceSettings): String {
    val effective = s.effectiveColorSpec()
    return if (effective != s.colorSpec) "当前样式不支持 2025，已回落 2021" else "Material 配色规格版本"
}

private val AppPaletteStyle.label: String
    get() = when (this) {
        AppPaletteStyle.TONAL_SPOT -> "柔和"
        AppPaletteStyle.NEUTRAL -> "中性"
        AppPaletteStyle.VIBRANT -> "鲜明"
        AppPaletteStyle.EXPRESSIVE -> "表现"
        AppPaletteStyle.RAINBOW -> "彩虹"
        AppPaletteStyle.FRUIT_SALAD -> "果盘"
        AppPaletteStyle.MONOCHROME -> "单色"
        AppPaletteStyle.FIDELITY -> "保真"
        AppPaletteStyle.CONTENT -> "内容"
    }
