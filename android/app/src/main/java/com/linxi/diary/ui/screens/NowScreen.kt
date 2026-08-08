package com.linxi.diary.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.core.DeviceStatus
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.UserPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tab ① 此刻：伴侣实时状态 + 远程互动 + 我的手机信息。
 * 克制、精致、信息优先（killaislop：无渐变标题/无 emoji/无发光堆叠）。
 */
@Composable
fun NowScreen(
    onOpenHistory: () -> Unit = {},
    onOpenBind: () -> Unit = {}
) {
    val partner = DeviceStatusHolder.partner
    val partnerName = UserPrefs.partnerName.ifBlank { "对方" }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶部标题栏 + 历史入口
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("此刻", style = MiuixTheme.textStyles.title1)
            Spacer(Modifier.weight(1f))
            Text(
                "历史",
                color = MiuixTheme.colorScheme.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenHistory
                    )
                    .padding(8.dp)
            )
        }

        // 伴侣状态卡
        Text(
            partnerName,
            style = MiuixTheme.textStyles.title3,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(4.dp))
        if (partner == null) {
            PartnerStatusEmpty(partnerName)
        } else {
            PartnerStatusCard(partner)
        }

        Spacer(Modifier.height(20.dp))

        // 远程互动
        SmallTitle("远程互动")
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionCard(
                "求陪伴",
                Icons.Filled.Favorite,
                Modifier.weight(1f)
            ) { StatusSyncManager.sendEvent("comfort_request") }
            ActionCard(
                "求冷静",
                Icons.Filled.CheckCircle,
                Modifier.weight(1f)
            ) { StatusSyncManager.sendEvent("calm_request") }
        }
        Spacer(Modifier.height(12.dp))
        ActionCard(
            "响铃提醒（紧急找人）",
            Icons.Filled.NotificationsActive,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) { StatusSyncManager.sendEvent("ring_request") }

        Spacer(Modifier.height(20.dp))

        // 我的手机信息
        SmallTitle("我的手机")
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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

        Spacer(Modifier.height(24.dp))
    }
}

/** 未同步空状态 */
@Composable
private fun PartnerStatusEmpty(partnerName: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("等待 ${partnerName.ifBlank { "对方" }} 同步状态",
                style = MiuixTheme.textStyles.headline1)
            Spacer(Modifier.height(6.dp))
            Text("对方的 App 保持运行并开启状态共享后，这里将实时显示",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

/** 伴侣状态卡：信息优先，状态色块标记 */
@Composable
private fun PartnerStatusCard(partner: DeviceStatus) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            StatusLine("前台", partner.foregroundApp?.second ?: "息屏/无前台",
                accent = partner.foregroundApp != null)
            StatusLine("电量",
                "${partner.batteryLevel}%${if (partner.isCharging) " · 充电中" else ""}",
                accent = partner.isCharging)
            StatusLine("屏幕",
                if (partner.screenOn) "亮屏${if (partner.isLocked) " · 锁定" else " · 已解锁"}" else "灭屏",
                accent = partner.screenOn)
            StatusLine("网络",
                partner.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi: $it" } ?: "移动网络",
                accent = partner.ssid != null)
            partner.music?.let {
                if (it.playing) {
                    StatusLine("音乐", "♪ ${it.title} - ${it.artist}", accent = true)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, accent: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        // 状态色点（克制：仅 6dp 小圆点，非发光）
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (accent) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f),
                    CircleShape
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** 互动卡片按钮（无涟漪，克制） */
@Composable
private fun ActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(modifier = modifier, onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
