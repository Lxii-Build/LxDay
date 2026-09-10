package com.linxi.diary.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.ui.components.LxIcon as Icon
import top.yukonga.miuix.kmp.basic.Switch
import com.linxi.diary.ui.components.LxText as Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Stable preference row used by the app instead of the fixed-height Miuix
 * preference layouts.  Long Chinese summaries are measured as normal content,
 * so a second line cannot paint over the next row on light-theme devices.
 */
@Composable
fun LxPreferenceRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    endAction: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    val contentAlpha = if (enabled) 1f else 0.48f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            // Keep the full padded row tappable. Putting clickable before
            // padding makes the visual inset outside the hit target on some
            // Compose versions, which is especially noticeable beside a
            // switch in a dense settings card.
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (startAction != null) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(48.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    startAction()
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(
                // A weighted child must be allowed to shrink.  Without the
                // explicit zero minimum, long CJK summaries could retain an
                // intrinsic width and push the trailing switch/chevron back
                // over the text on narrow devices.
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onBackground.copy(alpha = contentAlpha),
                )
            }
            if (endAction != null) {
                Spacer(Modifier.width(12.dp))
                // Keep the trailing control in a fixed, clipped slot.  The
                // title row never shares vertical space with the summary, so
                // long Chinese copy cannot paint underneath a switch/value.
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(48.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    endAction()
                }
            } else if (onClick != null) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (!summary.isNullOrBlank()) {
            Text(
                text = summary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (startAction != null) 44.dp else 0.dp,
                        end = if (endAction != null) 84.dp else if (onClick != null) 32.dp else 0.dp,
                    ),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = contentAlpha),
            )
        }
    }
}

@Composable
fun LxArrowPreference(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    LxPreferenceRow(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        startAction = startAction,
        onClick = onClick,
    )
}

@Composable
fun LxSwitchPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    LxPreferenceRow(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        startAction = startAction,
        endAction = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
fun LxChoicePreference(
    title: String,
    summary: String? = null,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    var showChoices by remember { mutableStateOf(false) }
    val safeIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    LxPreferenceRow(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        startAction = startAction,
        endAction = {
            Text(
                text = items.getOrNull(safeIndex).orEmpty(),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = { showChoices = true },
    )

    if (showChoices) {
        OverlayDialog(
            show = true,
            title = title,
            onDismissRequest = { showChoices = false },
            renderInRootScaffold = true,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEachIndexed { index, item ->
                    LxButton(
                        text = item,
                        onClick = {
                            onSelectedIndexChange(index)
                            showChoices = false
                        },
                        enabled = enabled,
                        variant = if (index == safeIndex) LxButtonVariant.Positive else LxButtonVariant.Neutral,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(2.dp))
                LxButton(
                    text = "取消",
                    onClick = { showChoices = false },
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun LxPreferenceGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LxSurface(
        modifier = modifier,
        tone = LxSurfaceTone.Raised,
    ) {
        Column(content = content)
    }
}
