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
    edgeRadius: Dp = 13.dp,
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
            // Keep the hit target exactly 48dp and center the visual glyph in
            // that fixed square.
            .sizeIn(
                minWidth = MIN_TOUCH_DP.dp,
                minHeight = MIN_TOUCH_DP.dp,
                maxWidth = MIN_TOUCH_DP.dp,
                maxHeight = MIN_TOUCH_DP.dp,
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
        edgeRadius = edgeRadius,
    ) {
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
    }
}
