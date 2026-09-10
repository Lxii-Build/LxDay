package com.linxi.diary.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.ui.theme.LocalLinxiDarkTheme
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor

enum class WarningLevel { Error, Notice }

/**
 * 照抄 KernelSU 的 WarningCard：警示条（动态色 secondaryContainer/tertiaryContainer，
 * 非动态浅红/浅黄，content 文字固定 F72727/F5A623）。
 */
@Composable
fun WarningCard(
    message: String,
    modifier: Modifier = Modifier,
    level: WarningLevel = WarningLevel.Error,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val containerColor = level.containerColor()
    val textColor = level.contentColor()
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                fontSize = 14.sp,
                color = textColor,
            )
            action?.invoke()
        }
    }
    if (onClick != null) {
        LxClickableSurface(
            modifier = modifier,
            color = containerColor,
            onClick = onClick,
            contentDescription = message,
            content = body,
        )
    } else {
        LxSurface(
            modifier = modifier,
            color = containerColor,
            content = body,
        )
    }
}

@Composable
private fun WarningLevel.containerColor(): Color = when {
    isDynamicColor -> when (this) {
        WarningLevel.Error -> colorScheme.errorContainer
        WarningLevel.Notice -> colorScheme.tertiaryContainer
    }

    LocalLinxiDarkTheme.current -> when (this) {
        // 深色页面保持石墨表面，语义色只留给文字；大面积红/黄底会破坏拟物层级。
        WarningLevel.Error -> Color(0xFF2C292D)
        WarningLevel.Notice -> Color(0xFF2C2B28)
    }

    else -> when (this) {
        WarningLevel.Error -> Color(0xFFF8E2E2)
        WarningLevel.Notice -> Color(0xFFFFF0DB)
    }
}

@Composable
private fun WarningLevel.contentColor(): Color = when {
    isDynamicColor -> when (this) {
        WarningLevel.Error -> colorScheme.onErrorContainer
        WarningLevel.Notice -> colorScheme.onTertiaryContainer
    }

    else -> when (this) {
        WarningLevel.Error -> Color(0xFFF72727)
        WarningLevel.Notice -> Color(0xFFF5A623)
    }
}
