package com.linxi.diary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Visual surface roles.  A surface never owns click semantics. */
enum class LxSurfaceTone { Flat, Raised, Inset, Floating }

/**
 * 沿 [shape] 的**真实轮廓**描一圈宽度为 [strokeWidth]、向内偏移 [inset] 的边。
 *
 * 为什么不能用 `drawRoundRect` + 手写 cornerRadius：
 * 那条路径要求调用方把圆角半径再传一遍，而半径是 `shape` 的属性。调用方一旦
 * 使用 `CircleShape`、胶囊形或自定义 Shape，手写半径就必然与真实形状不符，
 * 描边会局部脱离边缘 —— 圆形按钮上表现为「四个对角的孤立白点」。
 *
 * 这里改用与 Compose 裁剪同源的 `createOutline`，并借助描边**居中**于路径的特性
 * 来手工内缩：先按 `inset + strokeWidth / 2` 收缩路径，再以 [strokeWidth] 描边，
 * 于是描边内边界正好落在 `inset` 处，外边界为 `inset + strokeWidth`。
 *
 * 兜底策略：`Outline.Generic`（自定义 Path，当前调用点不会产生）直接跳过不描边 ——
 * 宁可少一条边，也不引入可能编译不过或画歪的缩放代码。
 * 矩形与圆角矩形走各自的精确分支，覆盖了本仓全部实际形状。
 *
 * `internal` 而非 `private`：按钮类组件（LxButton 的按压态）也需要同一套
 * 轮廓描边，共享实现才能保证两处的形状算法不会再次分叉。
 */
internal fun DrawScope.strokeOutline(
    shape: Shape,
    color: Color,
    strokeWidth: Float,
    inset: Float,
) {
    if (strokeWidth <= 0f) return
    // 描边以路径为中心向两侧各扩 strokeWidth/2，故先额外收缩半个笔宽。
    val shrink = inset + strokeWidth / 2f
    val innerWidth = size.width - shrink * 2f
    val innerHeight = size.height - shrink * 2f
    // 卡片比描边还小时无可绘制区域，直接跳过，避免负尺寸 Path 崩溃。
    if (innerWidth <= 0f || innerHeight <= 0f) return

    when (val outline = shape.createOutline(size, LayoutDirection.Ltr, this)) {
        is Outline.Rectangle -> drawRect(
            color = color,
            topLeft = Offset(shrink, shrink),
            size = Size(innerWidth, innerHeight),
            style = Stroke(width = strokeWidth),
        )

        is Outline.Rounded -> {
            val r = outline.roundRect
            // 圆角半径随内缩同步减小，且不超过收缩后半宽/半高，保证仍是合法圆角矩形。
            val maxRadius = minOf(innerWidth, innerHeight) / 2f
            val radius = (r.topLeftCornerRadius.x - shrink).coerceIn(0f, maxRadius)
            drawRoundRect(
                color = color,
                topLeft = Offset(r.left + shrink, r.top + shrink),
                size = Size(innerWidth, innerHeight),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = strokeWidth),
            )
        }

        is Outline.Generic -> {
            // 任意 Path（自定义 Shape）：Compose 的 Path 没有可靠的「按外框等比缩放」
            // 原语，Matrix 变换在不同版本间的可用方法也不一致。这里**有意不描边**，
            // 而不是拼一段可能编译不过或画歪的代码。
            //
            // 影响面为零：全仓的 LxSurface 调用点只使用 RoundedCornerShape 与
            // CircleShape，两者都走上面的精确分支；Generic 只有在将来引入自定义
            // Shape 时才会命中，那时应改为传入已知的圆角半径而非依赖缩放。
            return
        }
    }
}

/**
 * Shared first-party container for the soft-light material.
 *
 * It intentionally does not add padding or clickable semantics.  Callers keep
 * control of layout and accessibility while all surfaces share the same neutral
 * graphite/light palette and shadow direction.
 */
@Composable
fun LxSurface(
    modifier: Modifier = Modifier,
    tone: LxSurfaceTone = LxSurfaceTone.Raised,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color? = null,
    /**
     * @deprecated 已无作用，保留仅为兼容既有调用点。
     *
     * 高光此前用该值拼圆角矩形，导致它与 [shape] 不一致时描边脱离边缘
     * （圆形按钮上的「白色泛黄小点」就是这样来的）。现在描边直接取自
     * `shape.createOutline()`，圆角天然跟随 [shape]，无需再手工传半径。
     */
    edgeRadius: Dp = 15.dp,
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION")
    edgeRadius
    val tokens = LocalLxSurfaceTokens.current
    val fill = color ?: when (tone) {
        LxSurfaceTone.Flat, LxSurfaceTone.Inset -> tokens.surface
        LxSurfaceTone.Raised, LxSurfaceTone.Floating -> tokens.elevated
    }
    // ★ 阴影与描边一律用 drawBehind，绝不用 Modifier.shadow / drawWithContent ★
    //
    // 同 LxButton：`Modifier.shadow(..., clip = false)` 会为节点分配专用 RenderNode
    // 图形层，在受影响 GPU 上会把这个层合成为一块**不透明矩形**（颜色 = 底色提亮版），
    // 即管理员截图里的「白色/浅色横条」。`drawWithContent` 也会把当前节点提升为
    // 带离屏层的绘制节点，同样有该风险。`drawBehind` 只是往当前 canvas 多画一笔，
    // 既不创建图层、也不接管 content 的绘制，从机制上杜绝这类矩形。
    //
    // 绘制尺寸：本链 `drawBehind` 位于 `clip`/`background` 之前（即卡片外框），
    // 因此阴影与描边都按卡片外框尺寸绘制，不会缩成「内容尺寸」的矩形。
    val raised = tone == LxSurfaceTone.Raised || tone == LxSurfaceTone.Floating

    Box(
        modifier = modifier
            // 1) 柔和外阴影：按卡片外框尺寸自绘，不依赖平台阴影 API。
            .then(
                if (raised) {
                    Modifier.drawBehind {
                        val elevation = if (tone == LxSurfaceTone.Floating) 3 else 2
                        drawSoftShadow(shape, tokens.raisedShadow, elevation)
                    }
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(fill)
            // 2) 高光（Raised/Floating）：描真实轮廓，扁平外阴影构成拟态双影。
            .then(
                if (raised) {
                    Modifier.drawBehind {
                        // A low-alpha edge highlight completes the light/dark dual-shadow
                        // material without introducing a hard border around every row.
                        //
                        // ★ 高光必须描**真实形状**的轮廓，不能自己拼圆角矩形 ★
                        //
                        // 此前这里用 `drawRoundRect(cornerRadius = edgeRadius)` 画一条内缩 1dp 的
                        // 圆角矩形描边。edgeRadius 是独立参数（默认 15dp），而卡片形状由 `shape`
                        // 决定 —— 两者一旦不一致，高光就不再贴合卡片边缘：
                        //
                        //   · 圆形按钮（shape = CircleShape, 64dp）：真实半径应为 31dp，
                        //     但 edgeRadius 默认 15dp → 画出的圆角矩形四个角向内收缩了 16dp。
                        //     缺口出现在 45°/135°/225°/315° 四个对角，而 12/3/6/9 点钟方向
                        //     高光仍贴着边缘 —— 视觉上就是「几个孤立的白色小点」，
                        //     左上角那个最亮（raisedHighlight 87% 不透明）。
                        //     低饱和浅底上高光混合后红通道先饱和，观感还偏暖 → 「泛黄」。
                        //
                        // 改用 shape.createOutline() 拿轮廓，并用描边宽度外扩半个 stroke 实现
                        // 内缩偏移：无论调用方传 CircleShape、大圆角还是自定义 Shape，
                        // 高光都严格贴合边缘，不可能再出现局部缺口。
                        strokeOutline(
                            shape = shape,
                            color = tokens.raisedHighlight,
                            strokeWidth = 1.dp.toPx(),
                            inset = 1.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                }
            )
            // 3) 内凹（Inset）：一对克制的边缘描边，同样按真实轮廓取形。
            .then(
                if (tone == LxSurfaceTone.Inset) {
                    Modifier.drawBehind {
                        // A restrained pair of edge strokes gives the pressed/inset state a
                        // stable boundary without adding a hard outline to every row.
                        strokeOutline(
                            shape = shape,
                            color = tokens.insetShadow,
                            strokeWidth = 2.dp.toPx(),
                            inset = 1.dp.toPx(),
                        )
                        strokeOutline(
                            shape = shape,
                            color = tokens.insetHighlight,
                            strokeWidth = 1.dp.toPx(),
                            inset = 2.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        content()
    }
}

/**
 * 在卡片外框四周画一圈柔和的外阴影（与 LxButton 的 `drawSoftShadow` 同源思路）。
 *
 * 为什么不用 `Modifier.shadow(..., clip = false)` ★ 本次修复的核心 ★
 *
 * `Modifier.shadow` 会为该节点分配一个专用 `RenderNode` 图形层。在受影响的
 * Android GPU 路径上，该层被合成为**不透明矩形**，颜色是底色的提亮版、尺寸
 * 比卡片小一圈且居中 —— 正是管理员反复提交的「白色/浅色横条」。前几轮修复
 * 都围着按压缩边（`strokeOutline`/`edgeRadius`）打转，而**静止态就生效的
 * `shadow(clip = false)` 从未被动过**，所以一直没修好。
 *
 * 这里改为纯 `drawBehind` 自绘：
 * - `drawBehind` 不创建离屏图层，只是往当前 canvas 上多画一笔；
 * - 用 `scale` 把轮廓放大到卡片外框之外，由外向内叠若干层逐渐加深的填充，
 *   模拟真实阴影的衰减（越靠外越淡、越靠内越深）；
 * - 所有绘制都发生在 `clip(shape)` **之前**的节点上，因此不会被卡片自身裁掉，
 *   后续 `background(fill)` 会把阴影与卡片重叠的部分盖住，只露出外扩的一圈。
 *
 * `elevation` 只控制外扩幅度与叠层数（2 ≈ 8dp，3 ≈ 16dp），不依赖平台 API。
 */
private fun DrawScope.drawSoftShadow(
    shape: Shape,
    shadowColor: Color,
    elevation: Int,
) {
    // 阴影色本身是半透明的（如 0x5C93A1B5），这里只用它的 RGB 与 alpha 强度，
    // 避免在 drawBehind 里出现 Unspecified 色彩空间。
    val base = Color(shadowColor.toArgb())
    if (base.alpha <= 0f) return

    val layers = if (elevation >= 3) 4 else 3
    val maxSpread = if (elevation >= 3) 10f else 6f

    // 由外向内：每层把轮廓放大一点点、透明度高一点点，叠出柔和衰减。
    for (i in layers downTo 1) {
        val spread = maxSpread * i / layers
        val alpha = base.alpha * 0.30f * (1f - (i - 1f) / layers)
        if (alpha <= 0f) continue
        val scaleX = (size.width + spread * 2f) / size.width
        val scaleY = (size.height + spread * 2f) / size.height
        // 以尺寸中心为锚点放大，保证阴影从四周均匀外扩。
        scale(scaleX, scaleY, pivot = center) {
            drawOutline(
                shape = shape,
                color = base.copy(alpha = alpha),
            )
        }
    }
}

/**
 * 用 [shape] 的真实轮廓填充一块颜色。
 *
 * 与 `strokeOutline` 互补：那个描边、这个填充。同样走 `createOutline`，
 * 因此圆角矩形 / 圆形 / 胶囊形都能严格跟随形状，不需要调用方重传半径。
 */
private fun DrawScope.drawOutline(shape: Shape, color: Color) {
    if (color.alpha <= 0f) return
    when (val outline = shape.createOutline(size, LayoutDirection.Ltr, this)) {
        is Outline.Rectangle -> drawRect(color = color, topLeft = Offset.Zero, size = size)

        is Outline.Rounded -> {
            val r = outline.roundRect
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(r.topLeftCornerRadius.x, r.topLeftCornerRadius.y),
            )
        }

        // 任意 Path 无法可靠地按外框缩放，宁可不画也不画歪（与 strokeOutline 同一取舍）。
        is Outline.Generic -> return
    }
}

/**
 * Interactive counterpart for cards that are genuinely actions.
 *
 * Keeping this separate from [LxSurface] makes it impossible for a decorative
 * surface to accidentally become a giant button while still giving album and
 * discovery tiles the same material as non-interactive cards.  The click
 * semantics stay on the wrapper, so nested controls (such as an overflow
 * button) can consume their own event just as they did with the old Miuix Card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LxClickableSurface(
    modifier: Modifier = Modifier,
    tone: LxSurfaceTone = LxSurfaceTone.Raised,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color? = null,
    @Suppress("DEPRECATION") edgeRadius: Dp = 15.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    semanticRole: Role = Role.Button,
    stateDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val semantics = Modifier.semantics {
        role = semanticRole
        contentDescription?.let { this.contentDescription = it }
        stateDescription?.let { this.stateDescription = it }
    }
    val interaction = if (onLongClick == null) {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier.combinedClickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    LxSurface(
        modifier = modifier.then(interaction).then(semantics),
        // The pressed state sinks into the same material instead of flashing a
        // platform ripple over the neumorphic surface.
        tone = if (pressed && enabled) LxSurfaceTone.Inset else tone,
        shape = shape,
        color = color,
        // edgeRadius 已废弃：高光跟随 shape 的真实轮廓，这里不再向下传递。
        content = content,
    )
}
