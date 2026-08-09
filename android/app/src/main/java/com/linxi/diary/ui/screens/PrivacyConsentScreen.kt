package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.UserPrefs
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 双方知情同意（模态弹窗，AndroidX Dialog 保证真机一定弹出）。
 * 首次绑定后弹出，确认「将互相可见以下数据」才开启采集。
 * 不可取消、返回键也挡掉——必须先明确同意。
 */
@Composable
fun PrivacyConsentScreen(onConsented: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var agreed by remember { mutableStateOf(false) }
    com.linxi.diary.util.Logs.i("Consent", "知情同意弹窗组合开始")

    Dialog(
        onDismissRequest = { /* 不可取消：必须明确同意或拒绝 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("知情同意 · 状态共享",
                        style = MiuixTheme.textStyles.title3,
                        color = colorScheme.onBackground)
                    Text("绑定后，你和对方将互相可见以下实时数据：",
                        color = colorScheme.onSurface.copy(alpha = 0.78f))
                    Spacer(Modifier.height(4.dp))

                    listOf(
                        "电量百分比、是否充电中",
                        "屏幕亮起/锁屏/解锁状态",
                        "正在使用的 APP、当日各 APP 使用时长",
                        "当前播放的音乐（歌名/歌手）",
                        "连接的网络（WiFi 名称 / 移动网络）",
                        "5 分钟一条的状态历史（永久保留）",
                        "当电量低于 15%、连接指定 WiFi 时自动通知对方"
                    ).forEach { item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("•  ", color = colorScheme.primary)
                            Text(item, color = colorScheme.onBackground)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("你可以随时在「我的」中关闭状态共享开关，关闭后立即停止采集并清除本机数据。",
                        color = colorScheme.onSurface.copy(alpha = 0.78f))
                    Text("数据仅在你和对方之间传输，全程加密。",
                        color = colorScheme.onSurface.copy(alpha = 0.78f))

                    Spacer(Modifier.height(12.dp))

                    // 同意勾选（按钮组，暗色对比清晰）
                    Button(
                        onClick = { agreed = !agreed },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 12.dp
                    ) {
                        Text(if (agreed) "✓ 我已了解并同意以上数据共享" else "点此同意以上数据共享")
                    }

                    Button(
                        onClick = {
                            if (agreed) {
                                com.linxi.diary.util.Logs.i("Consent", "用户同意，开启共享")
                                UserPrefs.privacyConsented = true
                                UserPrefs.sharingEnabled = true
                                runCatching { StatusForegroundService.start(context) }
                                runCatching { StatusSyncManager.connect() }
                                onConsented()
                            }
                        },
                        enabled = agreed,
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 12.dp
                    ) { Text("同意并开启") }
                }
            }
        }
    }
}
