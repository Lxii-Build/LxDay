package com.linxi.diary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
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

/**
 * 无障碍最小触达尺寸。Android 无障碍指南要求可点区域不小于 48dp。
 *
 * **下限焊在组件里而不是靠调用点传参**：此前只设了 `vertical = 13.dp`、横向零留白，
 * 顶栏里不带 `fillMaxWidth` 的调用点（「上传」「取消」「系统相册」）被压成文字本身的
 * 宽度——管理员报的「右上角上传按键太窄」就是这个。靠每个调用点自己记着传参迟早会漏。
 */
const val MIN_TOUCH_DP = 48

@Composable
fun LxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: LxButtonVariant = LxButtonVariant.Positive,
    enabled: Boolean = true,
    cornerRadius: Int = 16,
    /**
     * 水平内边距。
     *
     * **必须有默认值**：此前只设了 `vertical = 13.dp`，横向完全没有留白，
     * 于是不带 `fillMaxWidth` 的调用点（顶栏里的「上传」「系统相册」「取消」）
     * 会被压成文字本身的宽度，又窄又难点 —— 管理员报的「右上角上传按键太窄」就是这个。
     *
     * 20dp 是常规按钮的舒适值；顶栏这类紧凑位置传 14dp。
     * 无论传多少，[MIN_TOUCH_DP] 的下限都由 `defaultMinSize` 兜住。
     */
    horizontalPadding: Int = 20,
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
            // 下限在前、clip 在后：这样圆角与背景覆盖的是撑开后的尺寸。
            // 单字按钮（如「删」）也不会缩成一个小方块。
            .defaultMinSize(minWidth = MIN_TOUCH_DP.dp, minHeight = MIN_TOUCH_DP.dp)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = horizontalPadding.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = fg)
    }
}
