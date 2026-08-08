package com.linxi.diary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MilkGlass 玻璃卡片组件。
 * 规范：glass-2 卡片 = 渐变 0.55→0.30 + blur16(移动端) + radius34 + 白描边 0.55。
 * Compose 无法实时背景模糊（API<31 无特效），用「渐变玻璃色 + 描边 + 阴影」近似（规范允许静态模糊替代）。
 */

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 34.dp,
    glassStart: Color = MaterialTheme.colorScheme.surfaceVariant,
    glassEnd: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(glassStart, glassEnd)))
            .border(1.dp, borderColor, RoundedCornerShape(radius))
            .padding(16.dp),
        content = content
    )
}

/** 一级凹面输入框背景 */
@Composable
fun glassInputBackground(radius: Dp = 22.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return Modifier
        .background(MaterialTheme.colorScheme.surfaceVariant, shape)
        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
}
