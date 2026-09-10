package com.linxi.diary.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.util.AppNoticeBus
import com.linxi.diary.ui.components.LxIcon as Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * App-owned transient feedback. It deliberately lives in the shell rather
 * than delegating to Android Toast, so in-app status keeps the same material,
 * typography, touch target and motion contract on every screen.
 */
@Composable
fun LxNoticeHost(
    notice: AppNoticeBus.Notice?,
    onDismiss: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 12.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = bottomPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = notice != null,
            enter = slideInVertically(
                animationSpec = tween(220),
                initialOffsetY = { it / 2 },
            ) + fadeIn(tween(160)),
            exit = slideOutVertically(
                animationSpec = tween(180),
                targetOffsetY = { it / 2 },
            ) + fadeOut(tween(140)),
        ) {
            val current = notice ?: return@AnimatedVisibility
            LxSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                    },
                tone = LxSurfaceTone.Floating,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (current.isError) MiuixIcons.Basic.Close else MiuixIcons.Ok,
                        contentDescription = null,
                        tint = if (current.isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                    )
                    Text(
                        text = current.message,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    LxButton(
                        onClick = onDismiss,
                        variant = LxButtonVariant.Neutral,
                        modifier = Modifier.size(48.dp),
                        horizontalPadding = 0,
                        content = { Icon(MiuixIcons.Basic.Close, contentDescription = "关闭提示") },
                    )
                }
            }
        }
    }
}
