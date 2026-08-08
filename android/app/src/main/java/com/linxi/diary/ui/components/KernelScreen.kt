package com.linxi.diary.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 照抄 KernelSU 的页面骨架：
 * Scaffold + BlurredBar 毛玻璃顶栏 + LazyColumn(spacedBy 12dp) + 页面内容录制为玻璃采样源。
 * 各页面（此刻/待办/日记/我的）复用此骨架，只提供 title/actions 与 LazyColumn content。
 */
@Composable
fun KernelScreen(
    title: String,
    actions: @Composable () -> Unit = {},
    enableBlur: Boolean = true,
    bottomPadding: androidx.compose.ui.unit.Dp = 12.dp,
    floatingActionButton: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    title = title,
                    color = barColor,
                    actions = { actions() },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        floatingActionButton = floatingActionButton,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + bottomPadding
                ),
                content = content
            )
        }
    }
}

/** KernelSU 顶栏 actions 的返回/历史按钮辅助 */
@Composable
fun BackAction(onBack: () -> Unit) {
    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
        androidx.compose.material3.Icon(
            Close,
            contentDescription = "返回"
        )
    }
}
