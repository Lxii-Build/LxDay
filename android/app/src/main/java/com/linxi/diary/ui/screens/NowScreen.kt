package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.GlassCard

/**
 * Tab ① 此刻：对方实时状态卡 + 求陪伴/求冷静/响铃 + 历史入口。
 * 数据来自 DeviceStatusHolder.partner（WS 推送实时更新）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowScreen(
    onOpenHistory: () -> Unit = {},
    onOpenBind: () -> Unit = {}
) {
    val partner = DeviceStatusHolder.partner
    val partnerName = com.linxi.diary.util.UserPrefs.partnerName.ifBlank { "对方" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部：标题 + 历史入口
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("此刻", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenHistory) { Text("历史") }
        }

        Spacer(Modifier.height(12.dp))

        if (partner == null) {
            GlassCard {
                Text("等待对方状态同步…", style = MaterialTheme.typography.bodyMedium)
                Text("打开对方的 App 并保持运行即可开始共享",
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            GlassCard {
                Text(partnerName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                StatusRow("电量", "${partner.batteryLevel}%${if (partner.isCharging) " · 充电中" else ""}")
                StatusRow("屏幕", if (partner.screenOn) "亮 · ${if (partner.isLocked) "锁定" else "已解锁"}" else "灭屏")
                StatusRow("前台", partner.foregroundApp?.second ?: "息屏/无前台")
                StatusRow("网络", partner.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi: $it" } ?: "移动网络")
                partner.music?.let { StatusRow("音乐", "♪ ${it.title} - ${it.artist}") }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 互动按钮：求陪伴 / 求冷静 / 响铃
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { StatusSyncManager.sendEvent("comfort_request") },
                modifier = Modifier.weight(1f)
            ) { Text("求陪伴") }
            Button(
                onClick = { StatusSyncManager.sendEvent("calm_request") },
                modifier = Modifier.weight(1f)
            ) { Text("求冷静") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { StatusSyncManager.sendEvent("ring_request") },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) { Text("响铃提醒（紧急找人）") }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
