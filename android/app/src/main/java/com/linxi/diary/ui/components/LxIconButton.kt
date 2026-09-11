package com.linxi.diary.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.ui.theme.BrandRed
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * First-party icon action with the same material and touch contract as [LxButton].
 *
 * The upstream icon button is intentionally kept out of feature screens: its
 * visual treatment and minimum size vary with the library theme, which made
 * toolbar actions look like native controls beside our neumorphic cards.
 */
@Composable
fun LxIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: LxButtonVariant = LxButtonVariant.Neutral,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    @Suppress("DEPRECATION") edgeRadius: Dp = 13.dp,
    /**
     * 期望的方形边长。
     *
     * ★ 为什么需要这个参数 ★
     *
     * 此前组件内部把尺寸写死成 `sizeIn(min = max = [MIN_TOUCH_DP])`，本意是
     * 「任何图标按钮的可点区域都不小于 48dp」。但 `sizeIn` 的默认语义是
     * **与传入约束求交集**，所以调用点无论传多大的 `Modifier.size(...)`，
     * 最终都会被这个 max 钳回 48dp：
     *
     *   `Modifier.size(64.dp)` → `size()` 产出 64×64 固定约束
     *                          → 再与 `sizeIn(max = 48.dp)` 求交 → **48×48**
     *
     * 结果就是「一起看」的播放键（想做成 64dp 的主按钮）和歌词页顶部的
     * 播放键（想做成 44dp）在真机上全被压成同一个 48dp 方块 —— 主按钮
     * 失去视觉权重，这正是管理员反复说「按钮都长一样、看不出主次」的原因之一。
     *
     * 修复方式刻意保持**纯增量**：新增本参数（默认值 = 历史上的 48dp），
     * 并把它同时用于 min/max。因此：
     *   · 不传的调用点行为与之前**逐像素一致**（不会影响其它 30+ 处调用）；
     *   · 想放大/缩小的调用点直接传值即可，不必再靠 `Modifier.size(...)`
     *     去和内部的 `sizeIn` 打架。
     *
     * 无障碍下限：无论调用点传什么，最终都会与 [MIN_TOUCH_DP] 取较大值兜底，
     * 所以不会出现小于 48dp 的触达区。
     */
    buttonSize: Dp = MIN_TOUCH_DP.dp,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalLxSurfaceTokens.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val fill = when (variant) {
        LxButtonVariant.Positive -> BrandBlue
        LxButtonVariant.Negative -> BrandRed
        LxButtonVariant.Neutral -> tokens.elevated
    }
    val contentColor = when (variant) {
        LxButtonVariant.Neutral -> MiuixTheme.colorScheme.onBackground
        else -> Color.White
    }
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }

    LxSurface(
        modifier = modifier
            // Icon actions are controls, not layout containers.  A TopAppBar
            // may offer its slot the whole remaining width; a min size alone
            // would make the clickable surface stretch into that slot.
            // Keep the hit target exactly the requested square and center the
            // visual glyph in it.
            //
            // 用 coerceAtLeast 兜住无障碍下限：调用点想做大按钮就做大，
            // 但绝不能小到 48dp 以下。
            .sizeIn(
                minWidth = buttonSize.coerceAtLeast(MIN_TOUCH_DP.dp),
                minHeight = buttonSize.coerceAtLeast(MIN_TOUCH_DP.dp),
                maxWidth = buttonSize.coerceAtLeast(MIN_TOUCH_DP.dp),
                maxHeight = buttonSize.coerceAtLeast(MIN_TOUCH_DP.dp),
            )
            .then(semantics)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        tone = if (pressed && enabled) LxSurfaceTone.Inset else LxSurfaceTone.Raised,
        shape = shape,
        color = fill.copy(alpha = if (enabled) 1f else 0.45f),
        // edgeRadius 已废弃：描边跟随 shape 的真实轮廓，避免圆形/特殊形状上出现断点。
        content = {
            CompositionLocalProvider(LocalContentColor provides contentColor.copy(alpha = if (enabled) 1f else 0.55f)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
            }
        },
    )
}
