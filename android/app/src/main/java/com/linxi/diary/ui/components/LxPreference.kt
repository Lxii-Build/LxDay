package com.linxi.diary.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            // Keep the full padded row tappable. Putting clickable before
            // padding makes the visual inset outside the hit target on some
            // Compose versions, which is especially noticeable beside a
            // switch in a dense settings card.
            .then(clickModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startAction != null) {
            Row(
                modifier = Modifier.width(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                startAction()
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = contentAlpha),
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = contentAlpha),
                )
            }
        }
        if (endAction != null) {
            Spacer(Modifier.width(12.dp))
            endAction()
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
