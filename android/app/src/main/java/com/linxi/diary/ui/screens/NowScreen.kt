package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.UserPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor

/**
 * Tab ① 此刻（KernelSU Home 风格）：
 * 伴侣状态卡（大图标叠层 Box）+ 互动按钮 + 系统信息卡 + 快捷入口。
 */
@Composable
fun NowScreen(
    onOpenHistory: () -> Unit = {},
    onOpenBind: () -> Unit = {}
) {
    val partner = DeviceStatusHolder.partner
    val partnerName = UserPrefs.partnerName.ifBlank { "对方" }
    val contentColor = LocalContentColor.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部标题栏 + 历史入口
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallTitle("此刻")
            Spacer(Modifier.weight(1f))
            BasicComponent(
                title = "历史",
                startAction = { Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.padding(end = 6.dp)) },
                onClick = onOpenHistory
            )
        }

        if (partner == null) {
            // 空状态：伴侣状态卡（未同步）
            StatusCard(
                title = "等待 ${partnerName} 同步",
                subtitle = "对方的 App 保持运行并开启状态共享后显示",
                icon = Icons.Filled.CheckCircle,
                iconColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.8f),
                containerColor = MiuixTheme.colorScheme.secondaryContainer
            )
        } else {
            // 伴侣状态卡
            val statusText = buildString {
                append("正在使用 ${partner.foregroundApp?.second ?: "息屏/无前台"}")
                if (partner.music?.playing == true) append(" · ♪ ${partner.music.title}")
            }
            StatusCard(
                title = statusText,
                subtitle = "电量 ${partner.batteryLevel}%${if (partner.isCharging) " · 充电中" else ""} · " +
                        "${if (partner.screenOn) "亮屏${if (partner.isLocked) "·锁定" else "·解锁"}" else "灭屏"}" +
                        " · ${partner.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi: $it" } ?: "移动网络"}",
                icon = Icons.Filled.CheckCircle,
                iconColor = if (partner.batteryLevel < 15) Color(0xFFF44336) else MiuixTheme.colorScheme.primary.copy(alpha = 0.8f),
                containerColor = if (partner.batteryLevel < 15) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.secondaryContainer,
                onClick = { }
            )
        }

        Spacer(Modifier.height(12.dp))

        // 互动按钮区
        SmallTitle("远程互动")
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                onClick = { StatusSyncManager.sendEvent("comfort_request") }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("求陪伴", color = contentColor)
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                onClick = { StatusSyncManager.sendEvent("calm_request") }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("求冷静", color = contentColor)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            onClick = { StatusSyncManager.sendEvent("ring_request") }
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔔 响铃提醒（紧急找人）", color = MiuixTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 系统信息卡（我的手机状态）
        SmallTitle("我的手机")
        Spacer(Modifier.height(4.dp))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            val my = DeviceStatusHolder.current
            if (my != null) {
                BasicComponent(title = "电量", summary = "${my.batteryLevel}%${if (my.isCharging) " · 充电中" else ""}")
                BasicComponent(title = "屏幕", summary = if (my.screenOn) "亮屏" else "灭屏")
                BasicComponent(title = "前台", summary = my.foregroundApp?.second ?: "息屏/无前台")
                BasicComponent(title = "网络", summary = my.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi: $it" } ?: "移动网络")
            } else {
                BasicComponent(title = "状态共享未开启", summary = "在「我的」中开启后开始采集")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** KernelSU 式状态卡：大图标叠层 Box（右下角大图标 + 左上标题） */
@Composable
private fun StatusCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    containerColor: Color,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 右下角大图标
            Box(
                Modifier.fillMaxSize().offset(x = 27.dp, y = 31.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    modifier = Modifier.size(110.dp),
                    imageVector = icon,
                    tint = iconColor,
                    contentDescription = null
                )
            }
            // 左上标题
            Box(
                Modifier.fillMaxSize().padding(16.dp, 14.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Column {
                    Text(title, fontSize = 22.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Spacer(Modifier.height(1.dp))
                    Text(subtitle, fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }
            }
        }
    }
}
