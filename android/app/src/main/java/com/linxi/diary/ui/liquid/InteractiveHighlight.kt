package com.linxi.diary.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 移植自 AndroidLiquidGlass（Apache-2.0）的按压光斑。
 * AGSL RuntimeShader 画径向光斑（按压时出现在指尖位置），
 * 需 SDK 33+（RuntimeShader 支持），否则静默跳过。
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader =
        if (isRuntimeShaderSupported()) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
            )
        } else {
            null
        }

    val modifier: Modifier =
        Modifier.drawWithContent {
            drawContent()
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                if (shader != null) {
                    drawRect(
                        Color.White.copy(0.08f * progress),
                        blendMode = BlendMode.Plus
                    )
                    shader.apply {
                        val position = position(size, positionAnimation.value)
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", Color.White.copy(0.15f * progress))
                        setFloatUniform("radius", size.minDimension * 1.5f)
                        setFloatUniform(
                            "position",
                            position.x.coerceIn(0f, size.width),
                            position.y.coerceIn(0f, size.height)
                        )
                    }
                    drawRect(
                        brush = androidx.compose.ui.graphics.ShaderBrush(shader.asComposeShader()),
                        blendMode = BlendMode.Plus
                    )
                }
            }
        }

    /** 手势修改器：按下时记录起点并驱动光斑 */
    val gestureModifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch {
                        positionAnimation.animateTo(down.position, positionAnimationSpec)
                    }
                }
            },
            onDragEnd = { end ->
                animationScope.launch {
                    launch {
                        positionAnimation.animateTo(end.position, positionAnimationSpec)
                    }
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec)
                }
            }
        ) { change, _ ->
            animationScope.launch {
                positionAnimation.animateTo(change.position, positionAnimationSpec)
            }
        }
    }
}
