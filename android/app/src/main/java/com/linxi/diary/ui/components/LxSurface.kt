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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
    edgeRadius: Dp = 15.dp,
    content: @Composable () -> Unit,
) {
    val tokens = LocalLxSurfaceTokens.current
    val fill = color ?: when (tone) {
        LxSurfaceTone.Flat, LxSurfaceTone.Inset -> tokens.surface
        LxSurfaceTone.Raised, LxSurfaceTone.Floating -> tokens.elevated
    }
    val shadow = when (tone) {
        LxSurfaceTone.Raised -> Modifier.shadow(
            elevation = 8.dp,
            shape = shape,
            clip = false,
            ambientColor = tokens.raisedShadow,
            spotColor = tokens.raisedShadow,
        )
        LxSurfaceTone.Floating -> Modifier.shadow(
            elevation = 16.dp,
            shape = shape,
            clip = false,
            ambientColor = tokens.raisedShadow,
            spotColor = tokens.raisedShadow,
        )
        LxSurfaceTone.Flat, LxSurfaceTone.Inset -> Modifier
    }
    val raisedEdge = if (tone == LxSurfaceTone.Raised || tone == LxSurfaceTone.Floating) {
        Modifier.drawWithContent {
            drawContent()
            // A low-alpha edge highlight completes the light/dark dual-shadow
            // material without introducing a hard border around every row.
            drawRoundRect(
                color = tokens.raisedHighlight,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                topLeft = androidx.compose.ui.geometry.Offset(1.dp.toPx(), 1.dp.toPx()),
                size = size.copy(
                    width = (size.width - 2.dp.toPx()).coerceAtLeast(0f),
                    height = (size.height - 2.dp.toPx()).coerceAtLeast(0f),
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(edgeRadius.toPx()),
            )
        }
    } else Modifier
    val inset = if (tone == LxSurfaceTone.Inset) {
        Modifier.drawWithContent {
            drawContent()
            // A restrained pair of edge strokes gives the pressed/inset state a
            // stable boundary without adding a hard outline to every row.
            drawRoundRect(
                color = tokens.insetShadow,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                topLeft = androidx.compose.ui.geometry.Offset(1.dp.toPx(), 1.dp.toPx()),
                size = size.copy(
                    width = (size.width - 2.dp.toPx()).coerceAtLeast(0f),
                    height = (size.height - 2.dp.toPx()).coerceAtLeast(0f),
                ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius((edgeRadius - 1.dp).coerceAtLeast(1.dp).toPx()),
            )
            drawRoundRect(
                color = tokens.insetHighlight,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                topLeft = androidx.compose.ui.geometry.Offset(2.dp.toPx(), 2.dp.toPx()),
                size = size.copy(
                    width = (size.width - 4.dp.toPx()).coerceAtLeast(0f),
                    height = (size.height - 4.dp.toPx()).coerceAtLeast(0f),
                ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius((edgeRadius - 2.dp).coerceAtLeast(1.dp).toPx()),
            )
        }
    } else Modifier

    Box(
        modifier = modifier
            .then(shadow)
            .clip(shape)
            .background(fill)
            .then(raisedEdge)
            .then(inset),
    ) {
        content()
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
    edgeRadius: Dp = 15.dp,
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
        edgeRadius = edgeRadius,
        content = content,
    )
}
