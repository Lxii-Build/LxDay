package com.linxi.diary.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.GlassCard

/**
 * Tab ① 此刻：对方实时状态（电量环 + 状态条）+ 互动按钮 + 历史入口。
 * UI 遵循 MilkGlass 玻璃拟态：顶部渐变、状态圆点、分项高亮配色。
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
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.background)
                )
            )
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部标题栏 + 历史入口
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("此刻", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenHistory) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("历史")
            }
        }

        if (partner == null) {
            EmptyState(partnerName)
        } else {
            // 伴侣状态
            Text(
                partnerName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            if (partner.music?.playing == true) {
                Text(
                    "♪ ${partner.music.title} - ${partner.music.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))

            // 电量环形图 + 核心状态
            GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BatteryRing(
                        battery = partner.batteryLevel,
                        charging = partner.isCharging,
                        modifier = Modifier.size(88.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        StatusPill(
                            icon = Icons.Default.Power,
                            label = if (partner.screenOn) "亮屏" else "灭屏",
                            detail = if (partner.isLocked) "已锁定" else "已解锁",
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        StatusPill(
                            icon = Icons.Default.Wifi,
                            label = "网络",
                            detail = partner.ssid?.takeIf { it.isNotBlank() }
                                ?.let { "WiFi: $it" } ?: "移动网络",
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                StatusAppRow(partner.foregroundApp?.second ?: "息屏/无前台")
            }

            Spacer(Modifier.height(16.dp))

            // 当日用量（可选）
            if (partner.usage.isNotEmpty()) {
                Text("当日使用", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    partner.usage.take(3).forEachIndexed { i, u ->
                        if (i > 0) HorizontalDivider(
                            Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(u.name, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Text("${u.minutes} 分钟", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("远程互动", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))

        // 互动按钮
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                "求陪伴", Icons.Default.Favorite,
                onClick = { StatusSyncManager.sendEvent("comfort_request") },
                container = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                "求冷静", Icons.Default.AcUnit,
                onClick = { StatusSyncManager.sendEvent("calm_request") },
                container = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        ActionButton(
            "响铃提醒（紧急找人）", Icons.Default.NotificationsActive,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = { StatusSyncManager.sendEvent("ring_request") },
            container = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EmptyState(partnerName: String) {
    GlassCard(modifier = Modifier.padding(16.dp)) {
        Text("等待 ${partnerName.ifBlank { "对方" }} 同步状态…",
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text("对方的 App 保持运行并已开启状态共享后，这里将实时显示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BatteryRing(battery: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        charging -> MaterialTheme.colorScheme.primary
        battery < 15 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val arcSize = size.copy(
                width = size.width - stroke,
                height = size.height - stroke
            )
            drawArc(
                color = color.copy(alpha = 0.15f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = battery * 3.6f, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$battery%", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(if (charging) "充电中" else "电量",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusPill(icon: ImageVector, label: String, detail: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("$label  $detail", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusAppRow(app: String) {
    Row(
        Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.shapes.medium
        ).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Apps, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("前台   $app", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    container: Color,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}