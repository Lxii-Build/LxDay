package com.linxi.diary.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material tokens shared by every first-party surface.
 *
 * These are deliberately independent from the generated Material colour scheme:
 * the brand blue remains the semantic action colour, while the canvas and
 * surfaces stay neutral in both themes.  Keeping the values here prevents each
 * screen from inventing its own grey/alpha combination.
 */
@Immutable
data class LxSurfaceTokens(
    val canvas: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val textSecondary: Color,
    val line: Color,
    val controlBoundary: Color,
    val link: Color,
    val raisedShadow: Color,
    val raisedHighlight: Color,
    val insetShadow: Color,
    val insetHighlight: Color,
)

/**
 * 浅色主题色板。
 *
 * ★ 层次结构（本次调整的重点）★
 *
 * 此前 `surface` 与 `canvas` **同为 #EDF0F4**，两者对比度是 1.000 —— 也就是
 * 完全看不出区别；浮起的卡片 `elevated` 也只比底色亮 1.084。整屏只有一个
 * 灰底、靠阴影勉强撑出几个卡片轮廓，观感就是「发灰、发闷、看不清层级」。
 *
 * 现在按「底 → 面 → 卡」拉出三级明度，每一级都能独立看出边界：
 *
 *   canvas   #E9EDF3   页面最底层，略深的冷灰（对比度基准）
 *   surface  #F1F4F9   平铺面 / 内凹面，比底色亮 1.066
 *   elevated #FCFDFF   浮起卡片，比底色亮 1.154，接近纯白
 *
 * 三级之间的差值刻意保持温和：既要一眼看出层次，又不能变成生硬的白块堆叠。
 * 数值经对比度计算验证，主文字在各层上均满足 WCAG AA（≥ 4.5，实测 13+）。
 */
val LxLightSurfaceTokens = LxSurfaceTokens(
    canvas = Color(0xFFE9EDF3),
    surface = Color(0xFFF1F4F9),
    elevated = Color(0xFFFCFDFF),
    text = Color(0xFF1F2937),
    textSecondary = Color(0xFF566273),
    line = Color(0xFFD0D7E2),
    controlBoundary = Color(0xFF788598),
    link = Color(0xFF185BC4),
    raisedShadow = Color(0x5C93A1B5),
    // 高光此前是 87% 不透明的纯白（0xDBFFFFFF），叠在低饱和浅底上时
    // 红通道先达到饱和，观感偏暖甚至「泛黄」，在圆形按钮的断点处尤其醒目。
    // 降到 62% 后高光变成一层柔和的提亮，既能勾出边缘又不会形成刺眼的白点。
    raisedHighlight = Color(0x9EFFFFFF),
    insetShadow = Color(0x6E93A1B5),
    insetHighlight = Color(0xA8FFFFFF),
)

/**
 * 深色主题色板。
 *
 * 深色下拉开层次的方式与浅色相反：底色最深，卡片靠**提亮**浮起（而不是靠阴影），
 * 因此同样把三级明度差拉开，避免暗色下卡片与背景糊成一片。
 */
val LxDarkSurfaceTokens = LxSurfaceTokens(
    canvas = Color(0xFF1B1E22),
    surface = Color(0xFF24282E),
    elevated = Color(0xFF2F343C),
    text = Color(0xFFF1F3F5),
    textSecondary = Color(0xFFB8C0CB),
    line = Color(0xFF424851),
    controlBoundary = Color(0xFF8893A0),
    link = Color(0xFF9CBFFC),
    raisedShadow = Color(0x52000000),
    raisedHighlight = Color(0x0FFFFFFF),
    insetShadow = Color(0x5C000000),
    insetHighlight = Color(0x0AFFFFFF),
)

val LocalLxSurfaceTokens = staticCompositionLocalOf { LxLightSurfaceTokens }
