package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.theme.ColorMode
import com.linxi.diary.ui.theme.rememberThemeState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * 主题与界面二级页（精简）：仅保留主题模式（跟随系统 / 浅色 / 深色）。
 * 其余外观设置项（动态取色/壁纸/玻璃/悬浮栏/缩放等）已移除；AppearanceSettings 相关字段保留但不展示。
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val themeState = rememberThemeState()
    val s by themeState.appearance

    val colorModeItems = listOf("跟随系统", "浅色", "深色")

    KernelScreen(title = "主题与界面", navigationIcon = { BackAction(onBack) }) {
        item {
            SmallTitle("主题模式")
            Card(Modifier.padding(top = 6.dp).fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = "主题模式",
                    summary = "跟随系统 / 浅色 / 深色",
                    items = colorModeItems,
                    selectedIndex = s.colorMode.ordinal,
                    onSelectedIndexChange = { i ->
                        themeState.update { it.copy(colorMode = ColorMode.entries[i]) }
                    },
                )
            }
        }
    }
}
