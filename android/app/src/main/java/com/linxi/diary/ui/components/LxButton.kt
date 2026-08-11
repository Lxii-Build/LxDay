package com.linxi.diary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.ui.theme.BrandRed
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一按钮语义：
 * - Positive（同意/添加/登录/注册）：品牌蓝底白字 #277AF7
 * - Negative（拒绝/退出/删除）：不刺眼的红底白字
 * - Neutral（取消等）：弱填充
 * 用 foundation 原语构建，避免依赖 miuix ButtonColors 具体 API，确保跨版本可编译。
 */
enum class LxButtonVariant { Positive, Negative, Neutral }

@Composable
fun LxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: LxButtonVariant = LxButtonVariant.Positive,
    enabled: Boolean = true,
    cornerRadius: Int = 16,
) {
    val container = when (variant) {
        LxButtonVariant.Positive -> BrandBlue
        LxButtonVariant.Negative -> BrandRed
        LxButtonVariant.Neutral -> MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    }
    val contentColor = when (variant) {
        LxButtonVariant.Neutral -> MiuixTheme.colorScheme.onBackground
        else -> Color.White
    }
    val bg = if (enabled) container else container.copy(alpha = 0.4f)
    val fg = if (enabled) contentColor else contentColor.copy(alpha = 0.6f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = fg)
    }
}
