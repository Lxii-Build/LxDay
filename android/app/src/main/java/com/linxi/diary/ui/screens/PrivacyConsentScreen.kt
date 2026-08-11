package com.linxi.diary.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.SharingRuntimePolicy
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 知情同意（页内 Dialog，覆盖在当前页之上）：
 * - 绑定成功后强制弹出（未同意=不开启共享但可留在应用，可关闭）。
 * - 「我的」页点“知情同意”弹只读态。
 * 沿用 miuix OverlayDialog，按钮统一 LxButton。
 */
@Composable
fun PrivacyConsentDialog(
    show: Boolean,
    reviewMode: Boolean,
    onConsented: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var agreed by remember(show) { mutableStateOf(false) }

    LaunchedEffect(show, reviewMode) {
        if (show) Logs.i("Consent", if (reviewMode) "show consent (review)" else "show consent (forced)")
    }

    OverlayDialog(
        show = show,
        title = "知情同意 · 状态共享",
        summary = if (reviewMode) "你已完成授权，可随时返回。" else "请完整阅读并确认后开启共享。",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "绑定后，你和对方将互相可见以下实时数据：",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            listOf(
                "电量百分比、是否充电中",
                "屏幕亮起、锁屏和解锁状态",
                "正在使用的 APP、当日各 APP 使用时长",
                "当前播放的音乐（歌名和歌手）",
                "网络连接类型和 WiFi 名称",
                "每 5 分钟记录的状态历史",
                "低电量或连接关注 WiFi 时的自动提醒",
            ).forEach { item ->
                Row(Modifier.fillMaxWidth()) {
                    Text("•  ", color = MiuixTheme.colorScheme.primary)
                    Text(item, color = MiuixTheme.colorScheme.onBackground)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "你可以随时在「我的」中关闭状态共享；关闭后立即停止采集和同步。数据仅在你和伴侣之间传输。",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(4.dp))

            if (reviewMode) {
                LxButton(
                    text = "关闭",
                    onClick = onDismiss,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { agreed = !agreed }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (agreed) ToggleableState.On else ToggleableState.Off,
                        onClick = { agreed = !agreed },
                    )
                    Text(
                        "我已了解并同意以上数据共享",
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LxButton(
                        text = "暂不开启",
                        onClick = onDismiss,
                        variant = LxButtonVariant.Neutral,
                        modifier = Modifier.weight(1f),
                    )
                    LxButton(
                        text = if (UserPrefs.demoMode) "同意并进入示例" else "同意并开启",
                        onClick = {
                            if (!agreed) return@LxButton
                            val enableSharing = SharingRuntimePolicy.enableSharingAfterConsent(UserPrefs.demoMode)
                            UserPrefs.privacyConsented = true
                            UserPrefs.sharingEnabled = enableSharing
                            if (enableSharing) {
                                runCatching { StatusForegroundService.start(context) }
                                runCatching { StatusSyncManager.connect() }
                                Logs.i("Consent", "consent granted, sharing enabled")
                            } else {
                                Logs.i("Consent", "consent granted, sharing kept off (demo)")
                            }
                            onConsented()
                        },
                        enabled = agreed,
                        variant = LxButtonVariant.Positive,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
