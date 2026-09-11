// Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar) — Apache 2.0.

package com.linxi.diary.ui.liquid.miuix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.linxi.diary.ui.liquid.miuix.InnerShadow
import com.linxi.diary.ui.liquid.miuix.innerShadow
import com.linxi.diary.ui.liquid.miuix.lens
import com.linxi.diary.ui.liquid.miuix.rememberCombinedBackdrop
import com.linxi.diary.ui.liquid.miuix.vibrancy
import com.linxi.diary.ui.liquid.miuix.DampedDragAnimation
import com.linxi.diary.ui.liquid.miuix.InteractiveHighlight
import com.linxi.diary.ui.theme.LocalLinxiDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

// Mirrors miuix-blur HighlightStyle's LIGHT_REF — keep in sync.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, ≈ 6° tilt

/** Tracks gravity for a `dualPeak` highlight's primary light, with an extra UV-clockwise offset on top. */
@Composable
private fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f,
): Highlight {
    val baseStyle = base.style as BloomStroke
    val tilt by rememberDeviceTilt()
    val rotatedPrimary = remember(tilt, baseStyle.primaryLight, extraDegrees) {
        val basePrimary = baseStyle.primaryLight
        val gx = tilt.gravityX
        val gy = tilt.gravityY
        val gMagSq = gx * gx + gy * gy
        val (lx0, ly0) = if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
            val invMag = 1f / sqrt(gMagSq)
            (gx * invMag) to (gy * invMag)
        } else {
            0f to -1f
        }
        val rad = extraDegrees * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = c * lx0 - s * ly0
        val ly = s * lx0 + c * ly0
        basePrimary.copy(
            position = LightPosition(
                x = LIGHT_REF_X + lx,
                y = LIGHT_REF_Y + ly,
                z = basePrimary.position.z,
            ),
        )
    }
    return remember(base, rotatedPrimary) {
        base.copy(style = baseStyle.copy(primaryLight = rotatedPrimary))
    }
}

@Composable
fun RowScope.FloatingBottomBarItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current
    Column(
        modifier
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/**
 * Bottom bar surface mode.
 *
 * 这里区分「**有动画**」和「**用 RenderEffect 采样**」两个此前被绑死在一起的概念。
 *
 * - [Liquid]：完整液态玻璃。需要 `Backdrop` + `drawBackdrop`，会创建 RenderEffect
 *   图层。视觉最好，但在部分 Android 15/16 GPU 驱动上会让相邻文本出现
 *   ghosting / 白色矩形（仓库里多处注释都记录过这个坑）。
 * - [Miuix]：**默认**。保留全部不依赖离屏图层的动画（选中胶囊的弹簧位移、
 *   拖拽时的缩放开合、按压高光/内凹阴影、选中项文字缩放），只是用
 *   `graphicsLayer` + 静态渐变模拟玻璃观感，**完全不创建 RenderEffect**，
 *   因此从机制上不可能触发 ghosting。
 * - [Opaque]：无动画的纯色兜底，仅在调用方明确要求时使用。
 */
enum class FloatingBarSurfaceMode { Liquid, Miuix, Opaque }

@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop?,
    tabsCount: Int,
    /**
     * 渲染模式。默认 [FloatingBarSurfaceMode.Miuix]：**动画全开、离屏图层全关**。
     *
     * 此前这里是一个 `isBlurEnabled: Boolean`，默认 true，而 [LinxiApp] 调用点
     * 显式传了 false —— 于是 `activeBackdrop` 变 null，不仅玻璃模糊没了，
     * 连下面这些**根本不需要 RenderEffect** 的动画也一并被跳过：
     *   · 选中胶囊的弹簧位移（`dampedDragAnimation.value` 驱动的 translationX）；
     *   · 拖拽/切换时的缩放开合（`dampedDragAnimation.scaleX/scaleY`）；
     *   · 按压高光与内凹阴影（`interactiveHighlight` / `innerShadow`）；
     *   · 选中项文字/图标的放大（`LocalFloatingBottomBarTabScale`）。
     * 结果整条底栏只剩一个静态的浅蓝椭圆，这就是管理员说的「动画被阉割」。
     */
    surfaceMode: FloatingBarSurfaceMode = FloatingBarSurfaceMode.Miuix,
    isBlurEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val isInDark = LocalLinxiDarkTheme.current
    val pillShape = remember { CircleShape }
    val accentColor = MiuixTheme.colorScheme.primary
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer

    // isBlurEnabled 保留是为了兼容旧调用点（语义 =「允许液态玻璃」）；
    // 真正决定是否创建 RenderEffect 图层的是 liquidMode。
    val liquidMode = surfaceMode == FloatingBarSurfaceMode.Liquid && isBlurEnabled
    val opaqueMode = surfaceMode == FloatingBarSurfaceMode.Opaque
    val containerColor = if (liquidMode) surfaceContainer.copy(0.4f) else surfaceContainer

    // Do not allocate or attach a RenderEffect backdrop unless the caller
    // explicitly opted into the liquid path.  On affected Android 15/16 GPU
    // paths merely keeping a live backdrop around was enough to produce
    // ghosted text/white rectangles in neighbouring composables.
    val activeBackdrop = if (liquidMode) backdrop else null
    // rememberLayerBackdrop() 是 @Composable，必须在条件**外部**无副作用地求值，
    // 否则一旦 activeBackdrop 从 null 变成非 null（或反过来），Compose 的
    // slot table 里会多/少一个 composable 调用，重组直接抛
    // 「Unbalanced composition」。这里无条件只调用一次，真正使用时再判空。
    val tabsBackdrop = rememberLayerBackdrop()
        .takeIf { activeBackdrop != null }
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex()) }

    class DampedDragAnimationHolder {
        var instance: DampedDragAnimation? = null
    }

    val holder = remember { DampedDragAnimationHolder() }

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex().toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                if (tabWidthPx == 0f) return@DampedDragAnimation false

                val currentValue = anim.value
                val indicatorX = currentValue * tabWidthPx
                val padding = with(density) { 4.dp.toPx() }
                val globalTouchX = if (isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            }
        ).also { holder.instance = it }
    }

    LaunchedEffect(selectedIndex) {
        snapshotFlow { selectedIndex() }.collectLatest { currentIndex = it }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            dampedDragAnimation.animateToValue(index.toFloat())
            onSelected(index)
        }
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            }
        )
    }

    val baseHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)
    val pillHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = 90f)

    val combinedBackdrop = if (activeBackdrop != null && tabsBackdrop != null) {
        rememberCombinedBackdrop(activeBackdrop, tabsBackdrop)
    } else {
        null
    }

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                }
                .graphicsLayer { translationX = panelOffset }
                .dropShadow(
                    shape = pillShape,
                    shadow = Shadow(
                        radius = 10.dp,
                        color = Color.Black,
                        alpha = if (isInDark) 0.2f else 0.1f,
                    ),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .then(
                    if (activeBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = activeBackdrop,
                            shape = { pillShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            highlight = { baseHighlight.copy(alpha = 0.75f) },
                            layerBlock = {
                                val width = size.width.coerceAtLeast(1f)
                                val s = lerp(1f, 1f + 16.dp.toPx() / width, dampedDragAnimation.pressProgress)
                                scaleX = s
                                scaleY = s
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, pillShape)
                    }
                )
                .then(interactiveHighlight.modifier)
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                content()
            }
        }

        if (activeBackdrop != null && tabsBackdrop != null) {
            CompositionLocalProvider(
                LocalFloatingBottomBarTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                },
                LocalContentColor provides accentColor,
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = activeBackdrop,
                            shape = { pillShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(interactiveHighlight.modifier)
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        } else if (!opaqueMode) {
            // Miuix 模式下的选中项高亮层。
            //
            // Liquid 分支靠第二层 `.alpha(0f)` + `layerBackdrop` 把「选中色文字」
            // 叠加在胶囊上。这条路径必然创建图层，所以这里改用**纯 alpha 叠色**：
            // 同一个 content() 再画一遍，用 accentColor 着色并以 alpha 0→1
            // 淡入到胶囊范围内，视觉等价、机制上零图层。
            CompositionLocalProvider(
                // 选中项文字缩放：这条动画同样不依赖 RenderEffect，此前一并被跳过。
                LocalFloatingBottomBarTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                },
                LocalContentColor provides accentColor,
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .fillMaxHeight()
                        // 用 graphicsLayer 的 alpha 做淡入，而不是 Modifier.alpha()：
                        // 两者都不创建离屏层，但 graphicsLayer 能保证与下方胶囊
                        // 的位移共用同一个变换坐标系。
                        .graphicsLayer { alpha = dampedDragAnimation.pressProgress }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }

        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            if (activeBackdrop != null && combinedBackdrop != null) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                        }
                        .then(interactiveHighlight.gestureModifier)
                        .then(dampedDragAnimation.modifier)
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { pillShape },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                lens(
                                    refractionHeight = 10.dp.toPx() * progress,
                                    refractionAmount = 14.dp.toPx() * progress,
                                    depthEffect = true,
                                    chromaticAberration = 0.5f,
                                )
                            },
                            highlight = { pillHighlight.copy(alpha = dampedDragAnimation.pressProgress) },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    color = if (!isInDark) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                        .innerShadow(shape = pillShape) {
                            InnerShadow(
                                radius = 8.dp * dampedDragAnimation.pressProgress,
                                color = Color.Black.copy(alpha = 0.15f),
                                alpha = dampedDragAnimation.pressProgress,
                            )
                        }
                        .height(56.dp)
                        .width(tabWidthDp)
                )
            } else if (opaqueMode) {
                // 纯色兜底：连位移动画都不要，仅用于排障对比。
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                        }
                        .clip(pillShape)
                        .background(accentColor.copy(alpha = 0.15f), pillShape)
                        .height(56.dp)
                        .width(tabWidthDp)
                )
            } else {
                // ★ Miuix 模式：三条「被阉割」的动画在这里全部恢复 ★
                //
                // 关键点是这套动画**根本不依赖 RenderEffect**：
                //   · 位移 —— `dampedDragAnimation.value` 是纯 Animatable + 弹簧；
                //   · 缩放 —— `dampedDragAnimation.scaleX/scaleY` 同样是 Animatable；
                //   · 高光 —— 用 drawBehind 的品牌色微光 + 白色渐变表达，
                //     不走 InteractiveHighlight（理由见下方 modifier 链上的注释）。
                // 此前它们被跳过，唯一原因就是旧代码把整个 else 分支写成了静态 background。
                // 现在改为在真实节点上用 graphicsLayer / drawBehind 表达，
                // 不创建任何离屏层，因此不会引发 ghosting。
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                            // 缩放回弹：`DampedDragAnimation` 的 scaleX/scaleY，
                            // 视觉流速减法与 Liquid 分支保持同一套公式。
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        }
                        // 拖拽手势：`dampedDragAnimation.modifier` 里就带着 press()/release()，
                        // 所以「按住 → 胶囊放大、松开 → 弹回」在无 RenderEffect 时依然成立。
                        .then(dampedDragAnimation.modifier)
                        // ★ 这里刻意不挂 `interactiveHighlight.modifier` ★
                        //
                        // InteractiveHighlight 的光斑由它自己的 gestureModifier 驱动，
                        // 而 gestureModifier 只在 Liquid 分支的指示器 Box 上挂载；
                        // Miuix 分支不挂它，pressProgress 就恒为 0，光斑永远不会绘制。
                        // 且它的 drawWithContent 排在 clip/background **之前**（绘制链
                        // 外层），就算 progress > 0，光斑也会被后面的背景整块盖住。
                        // 所以 Miuix 模式的「按压高光」改由下方 drawBehind 里的
                        // 品牌色微光承担：同样随 pressProgress 弹簧淡入淡出，
                        // 且绘制顺序正确（画在背景之上）、零额外图层。
                        .clip(pillShape)
                        .background(accentColor.copy(alpha = lerp(0.15f, 0.26f, dampedDragAnimation.pressProgress)), pillShape)
                        // 静态渐变代替玻璃采样：让胶囊本身有「受光」的立体感。
                        // Brush 按节点自身尺寸绘制，只往当前 canvas 多画一笔，
                        // 不创建图层、不接管 content 绘制。
                        .drawBehind {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.22f),
                                        Color.White.copy(alpha = 0.02f),
                                    ),
                                ),
                            )
                            // 按压时再叠一层品牌色微光，作为「高光」在无 RenderEffect
                            // 路径下的替代：视觉上有回应，机制上零图层。
                            drawRect(color = accentColor.copy(alpha = 0.10f * dampedDragAnimation.pressProgress))
                        }
                        .height(56.dp)
                        .width(tabWidthDp)
                )
            }
        }
    }
}
