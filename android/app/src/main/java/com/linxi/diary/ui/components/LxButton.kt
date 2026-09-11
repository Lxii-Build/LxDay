package com.linxi.diary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.ui.theme.BrandRed
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
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
    LxButton(
        onClick = onClick,
        modifier = modifier,
        variant = variant,
        enabled = enabled,
        cornerRadius = cornerRadius,
        horizontalPadding = horizontalPadding,
    ) {
        Text(text = text, color = buttonContentColor(variant, enabled))
    }
}

/**
 * 供需要图标、数字或紧凑字号的场景使用的内容式版本。
 *
 * 这能让所有可点控件仍走同一套语义色、圆角与 48dp 最小触达区，而不会为了展示
 * 一个图标又退回裸 miuix Button。
 */
@Composable
fun LxButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: LxButtonVariant = LxButtonVariant.Positive,
    enabled: Boolean = true,
    cornerRadius: Int = 16,
    horizontalPadding: Int = 20,
    content: @Composable () -> Unit,
) {
    val tokens = LocalLxSurfaceTokens.current
    val container = when (variant) {
        LxButtonVariant.Positive -> BrandBlue
        LxButtonVariant.Negative -> BrandRed
        LxButtonVariant.Neutral -> tokens.surface
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val bg = if (enabled) container else container.copy(alpha = 0.45f)
    val shape = RoundedCornerShape(cornerRadius.dp)
    val shadowColor = tokens.raisedShadow
    val pressedEdge = tokens.insetShadow

    Box(
        // ★ 顺序就是一切：图形层尺寸问题见文件末尾的说明 ★
        //
        // 阴影必须在最外层：`drawBehind` 比 `clip` 更早落在链上，因此它绘制时
        // 拿到的 `size` 是**按钮外框尺寸**；阴影以该尺寸为中心向外放大绘制一圈，
        // 随后 `background(bg)` 把与按钮重叠的部分盖住，只在四周露出一道柔和暗边
        // —— 这就是拟态的浮起感。若把它放到 `clip` 之后，一旦调用方再追加 padding，
        // 绘制尺寸就会缩到内容大小，暗边跑进按钮内部、甚至整块矩形被合成出来。
        modifier = modifier
            // 1) 阴影先按外框撑开，保证绘制尺寸 = 按钮外框。
            .drawBehind { drawSoftShadow(shape, shadowColor, pressed && enabled) }
            // 2) 下限在前、clip 在后：圆角与背景覆盖的是撑开后的尺寸。
            //    单字按钮（如「删」）也不会缩成一个小方块。
            .defaultMinSize(minWidth = MIN_TOUCH_DP.dp, minHeight = MIN_TOUCH_DP.dp)
            .clip(shape)
            // 3) 填充是 `background`，不内缩 —— 不透明度变化不会让尺寸跟着变。
            .background(bg)
            // 4) 按压态内描边：`border` 只是又一次 `drawBehind`，不创建图形层。
            .then(
                if (pressed && enabled) {
                    Modifier.border(2.dp, pressedEdge, shape)
                } else {
                    Modifier
                }
            )
            // 5) 命中区覆盖「含内边距的整个按钮」：padding 在 clickable 之后，
            //    内边距自然落在可点范围内（此前 padding 在全链最内层，行为一致）。
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            // 6) 内边距放在图形层之外，只推挤内容，不参与背景/阴影的绘制尺寸。
            .padding(vertical = 13.dp, horizontal = horizontalPadding.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides buttonContentColor(variant, enabled)) {
            // A content-style button used to expose a raw Box scope.  Multiple
            // children (icon + spacer + label) were therefore painted at the
            // same origin on the affected renderer.  Keep the public API
            // flexible, but give its children a deterministic horizontal
            // layout just like the text overload.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * 在按钮外框四周画一圈柔和的外阴影。
 *
 * 为什么不再用 `Modifier.shadow(..., clip = false)` ★ 本次修复的核心 ★
 *
 * `Modifier.shadow` 会为该节点分配一个专用的 `RenderNode` 图形层；`clip = false` 时
 * 层的内容区是**该节点自身的内容测量尺寸**。把这个 modifier 挂在 `padding` 的
 * **外侧**还没事，一旦像旧代码那样挂在 `padding` 的**内侧**（旧链：
 * `...clip().background().shadow().clickable().padding()`），图形层就只剩
 * 「按钮外框减去内边距」那块，于是合成出来的是一个**比按钮小一圈、居中、
 * 颜色等于底色提亮版**的矩形 —— 正是管理员截图里「取消」按钮中那条
 * `(237,240,245)` 浅灰条、「添加」按钮中那块 `(158,195,250)` 浅蓝块。
 * 现象是静态可见的，不需要按压。
 *
 * 现在改成纯 `drawBehind` 绘制：
 * - `drawBehind` 不创建离屏图层，只是往当前 canvas 上多画一笔；
 * - 绘制尺寸就是节点尺寸（本链上 `drawBehind` 位于 `clip` 之前，即按钮外框）；
 * - 用 `scale` 把轮廓放大到按钮外框之外，由外向内叠若干层逐渐加深的填充，
 *   模拟真实阴影的衰减；后续 `background(bg)` 会把与按钮重叠的部分盖住，
 *   只露出四周外扩的一圈。
 *
 * `toArgb()` 是必须的：在 `drawBehind` 的 `DrawScope` 里要求 `Color` 已经绑定到
 * 具体色彩空间，直接使用 `Color.copy(alpha=)` 在某些 Compose 版本上会走
 * `Color.Unspecified` 分支而静默丢弃。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSoftShadow(
    shape: androidx.compose.ui.graphics.Shape,
    shadowColor: Color,
    pressed: Boolean,
) {
    // 阴影色本身是半透明的（如 0x5C93A1B5），这里只用它的 RGB 与 alpha 强度，
    // 避免在 drawBehind 里出现 Unspecified 色彩空间。
    val base = Color(shadowColor.toArgb())
    if (base.alpha <= 0f) return

    // 按压时按钮「陷下去」：外扩幅度与强度一起收小。
    val layers = if (pressed) 2 else 3
    val maxSpread = if (pressed) 3f else 6f

    for (i in layers downTo 1) {
        val spread = maxSpread * i / layers
        val alpha = base.alpha * 0.34f * (1f - (i - 1f) / layers)
        if (alpha <= 0f) continue
        val scaleX = (size.width + spread * 2f) / size.width
        val scaleY = (size.height + spread * 2f) / size.height
        scale(scaleX, scaleY, pivot = center) {
            drawSolidOutline(shape = shape, color = base.copy(alpha = alpha))
        }
    }
}

/**
 * 用 [shape] 的真实轮廓填充一块颜色。
 *
 * 走 `createOutline` 而非手写 `drawRoundRect(cornerRadius=...)`：圆角矩形 / 圆形 /
 * 胶囊形都能严格跟随形状，调用方不必把半径再传一遍。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSolidOutline(
    shape: androidx.compose.ui.graphics.Shape,
    color: Color,
) {
    if (color.alpha <= 0f) return
    when (val outline = shape.createOutline(size, LayoutDirection.Ltr, this)) {
        is androidx.compose.ui.graphics.Outline.Rectangle -> drawRect(
            color = color,
            topLeft = Offset.Zero,
            size = size,
        )

        is androidx.compose.ui.graphics.Outline.Rounded -> {
            val r = outline.roundRect
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(r.topLeftCornerRadius.x, r.topLeftCornerRadius.y),
            )
        }

        // 任意 Path 无法可靠地按外框缩放，宁可不画也不画歪（与 strokeOutline 同一取舍）。
        is androidx.compose.ui.graphics.Outline.Generic -> return
    }
}

@Composable
private fun buttonContentColor(variant: LxButtonVariant, enabled: Boolean): Color {
    val color = when (variant) {
        LxButtonVariant.Neutral -> MiuixTheme.colorScheme.onBackground
        else -> Color.White
    }
    return if (enabled) color else color.copy(alpha = 0.6f)
}
