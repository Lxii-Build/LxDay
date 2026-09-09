package com.linxi.diary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

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
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(15.dp.toPx()),
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
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
            )
            drawRoundRect(
                color = tokens.insetHighlight,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                topLeft = androidx.compose.ui.geometry.Offset(2.dp.toPx(), 2.dp.toPx()),
                size = size.copy(
                    width = (size.width - 4.dp.toPx()).coerceAtLeast(0f),
                    height = (size.height - 4.dp.toPx()).coerceAtLeast(0f),
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(13.dp.toPx()),
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
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val semantics = if (contentDescription == null) {
        Modifier.semantics { role = Role.Button }
    } else {
        Modifier.semantics {
            role = Role.Button
            this.contentDescription = contentDescription
        }
    }
    val interaction = if (onLongClick == null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
    }
    LxSurface(
        modifier = modifier.then(interaction).then(semantics),
        tone = tone,
        shape = shape,
        color = color,
        content = content,
    )
}
