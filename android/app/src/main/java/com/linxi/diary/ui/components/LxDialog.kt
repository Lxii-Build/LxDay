package com.linxi.diary.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全 App 统一的弹窗骨架。**规范由组件强制，不靠自觉。**
 *
 * 管理员反复要求过（0821 额外要求 2）：「弹窗相关的，确认按钮和取消按钮的按钮背景颜色不一样」。
 * 此前全仓 11 处 OverlayDialog 有 6 处不合规——相册/历史三处直接用裸 miuix `Button`，
 * 「删除相册」与「保存名称」**完全同色**，危险操作没有任何视觉区分。
 * 根因是"每个弹窗各写一遍两个按钮"，只要有人少传一个 variant 就破功。
 *
 * 所以这里把按钮语义**焊死在组件里**：
 * - 取消永远在左、`Neutral`（弱填充灰底）
 * - 确认永远在右，`destructive=true` 时自动变 `Negative`（品牌红），否则 `Positive`（品牌蓝）
 * - 二者等宽 `weight(1f)`、间距 8dp
 * 调用方不再有"忘记设颜色"的机会。
 *
 * @param destructive 危险/不可逆操作（删除、清空、解绑）。确认按钮转红。
 * @param confirmDelayMs 确认按钮的冷静期（毫秒）。不可逆操作默认 1 秒可点，防手滑连点。
 * @param busy 处理中：按钮禁用、文案换成 busyText、且不允许点外部关闭。
 */
@Composable
fun LxConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelText: String = "取消",
    destructive: Boolean = false,
    busy: Boolean = false,
    busyText: String = "处理中…",
    confirmDelayMs: Long = if (destructive) 1000L else 0L,
    extraContent: (@Composable () -> Unit)? = null,
) {
    if (!show) return

    // 冷静期：危险操作的确认按钮先禁用一小会儿。
    // 弹窗刚出现时用户的手指往往还停在原来那个按钮的位置上，
    // 没有这段延迟，"删除→确认"两下连点就把照片删了。
    var armed by remember(show, confirmDelayMs) { mutableStateOf(confirmDelayMs <= 0L) }
    LaunchedEffect(show, confirmDelayMs) {
        if (confirmDelayMs > 0L) {
            armed = false
            delay(confirmDelayMs)
            armed = true
        }
    }

    OverlayDialog(
        show = true,
        title = title,
        // 处理中不允许点外部关闭：请求已经发出去了，关掉弹窗只会让用户以为取消了。
        onDismissRequest = { if (!busy) onDismiss() },
        renderInRootScaffold = true,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (message.isNotBlank()) {
                Text(
                    message,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
            }
            extraContent?.let {
                it()
                Spacer(Modifier.height(12.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LxButton(
                    text = cancelText,
                    onClick = onDismiss,
                    enabled = !busy,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.weight(1f),
                )
                LxButton(
                    text = if (busy) busyText else confirmText,
                    onClick = onConfirm,
                    enabled = !busy && armed,
                    // 危险操作红、常规操作蓝——两侧背景色必然不同。
                    variant = if (destructive) LxButtonVariant.Negative else LxButtonVariant.Positive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 表单类弹窗（新建相册、写描述等）：与 [LxConfirmDialog] 同一套按钮语义，
 * 但主体是调用方提供的输入控件，且确认按钮可按输入合法性禁用。
 */
@Composable
fun LxFormDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelText: String = "取消",
    busy: Boolean = false,
    busyText: String = "提交中…",
    content: @Composable () -> Unit,
) {
    if (!show) return
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = { if (!busy) onDismiss() },
        renderInRootScaffold = true,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LxButton(
                    text = cancelText,
                    onClick = onDismiss,
                    enabled = !busy,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.weight(1f),
                )
                LxButton(
                    text = if (busy) busyText else confirmText,
                    onClick = onConfirm,
                    enabled = confirmEnabled && !busy,
                    variant = LxButtonVariant.Positive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
