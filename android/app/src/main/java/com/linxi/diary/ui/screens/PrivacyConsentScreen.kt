package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.UserPrefs

/**
 * 双方知情授权页（决策 Q2）：
 * 首次绑定后双方各自确认「将互相可见以下数据」才开启采集。
 * 确认后开启状态共享总开关。
 */
@Composable
fun PrivacyConsentScreen(onConsented: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var agreed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(32.dp))
        Text("知情同意 · 状态共享",
            style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("绑定后，你和对方将互相可见以下实时数据：",
            style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(16.dp))
        listOf(
            "电量百分比、是否充电中",
            "屏幕亮起/锁屏/解锁状态",
            "正在使用的 APP、当日各 APP 使用时长",
            "当前播放的音乐（歌名/歌手）",
            "连接的网络（WiFi 名称 / 移动网络）",
            "5 分钟一条的状态历史（永久保留）",
            "当电量低于 15%、连接指定 WiFi 时自动通知对方"
        ).forEach { item ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("• ", color = MaterialTheme.colorScheme.primary)
                Text(item, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("你可以随时在「我的」中关闭状态共享开关，关闭后立即停止采集并清除本机数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("数据仅在你和对方之间传输，全程加密。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("我已了解并同意以上数据共享")
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                UserPrefs.privacyConsented = true
                UserPrefs.sharingEnabled = true
                StatusForegroundService.start(context)
                StatusSyncManager.connect()
                onConsented()
            },
            enabled = agreed,
            modifier = Modifier.fillMaxWidth()
        ) { Text("同意并开启") }
    }
}
