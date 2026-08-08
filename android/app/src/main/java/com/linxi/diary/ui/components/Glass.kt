package com.linxi.diary.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 通用卡片（临时兼容层，替代已废弃的 MilkGlass 玻璃卡片）。
 * 基于 Material3 Surface + 主题圆角；后续页面迁移到 miuix Card 后移除。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(radius),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        content = {
            Column(Modifier.padding(16.dp), content = content)
        }
    )
}
