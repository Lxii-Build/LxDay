package com.linxi.diary.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
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
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.util.UserPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 此刻（照抄 KernelSU Home 结构）：
 * KernelScreen 骨架 + 伴侣状态卡（大图标叠层）+ 低电量警示卡 + 互动入口 + 我的手机信息卡。
 */
@Composable
fun NowScreen(
    onOpenHistory: () -> Unit = {},
    onOpenBind: () -> Unit = {}
) {
    val partner = DeviceStatusHolder.partner
    val partnerName = UserPrefs.partnerName.ifBlank { "对方" }

    KernelScreen(
        title = "此刻",
        actions = {
            Text(
                "历史",
                color = colorScheme.primary,
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
    ) {
        item {
            Column(
                Modifier.padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 伴侣状态卡
                PartnerStatusCard(partner, partnerName)

                // 低电量警示（WarningCard 风格）
                if (partner != null && partner.batteryLevel < 15) {
                    WarningRow("对方电量不足 15%", level = "error")
                }
                if (partner == null) {
                    WarningRow("等待 ${partnerName.ifBlank { "对方" }} 同步状态", level = "notice")
                }

                // 远程互动
                SmallTitleRow("远程互动")
                Row(
                    Modifier.fillMaxWidth(),
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
                ActionCard(
                    "响铃提醒（紧急找人）",
                    Icons.Filled.Notifications,
                    Modifier.fillMaxWidth()
                ) { StatusSyncManager.sendEvent("ring_request") }

                // 我的手机信息
                SmallTitleRow("我的手机")
                MyPhoneCard()
            }
        }
    }
}

/** 伴侣状态卡（KernelSU StatusCard 风格：大图标叠层 Box） */
@Composable
private fun PartnerStatusCard(partner: DeviceStatus?, partnerName: String) {
    if (partner == null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("等待 ${partnerName.ifBlank { "对方" }} 同步",
                    style = MiuixTheme.textStyles.headline1)
                Spacer(Modifier.height(4.dp))
                Text("对方的 App 保持运行并开启状态共享后显示",
                    color = colorScheme.onSurface.copy(alpha = 0.78f))
            }
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 右下角大图标（KernelSU offset 27,31 / 110dp）
            Box(
                Modifier.fillMaxSize().offset(x = 27.dp, y = 31.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(110.dp)
                )
            }
            // 左上标题
            Box(
                Modifier.fillMaxSize().padding(16.dp, 14.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Column {
                    Text(
                        partner.foregroundApp?.second?.let { "正在使用 $it" } ?: "息屏/无前台",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "电量 ${partner.batteryLevel}%${if (partner.isCharging) " · 充电中" else ""} · " +
                                "${if (partner.screenOn) "亮屏${if (partner.isLocked) "·锁定" else "·解锁"}" else "灭屏"}" +
                                " · ${partner.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi: $it" } ?: "移动网络"}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    partner.music?.let {
                        if (it.playing) {
                            Spacer(Modifier.height(2.dp))
                            Text("♪ ${it.title} - ${it.artist}", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 单行警示条（KernelSU WarningCard 风格） */
@Composable
private fun WarningRow(message: String, level: String) {
    val container = if (level == "error") colorScheme.errorContainer else colorScheme.tertiaryContainer
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, fontSize = 14.sp,
                color = if (level == "error") colorScheme.onErrorContainer else colorScheme.onTertiaryContainer)
        }
    }
}

/** 分组小标题 */
@Composable
private fun SmallTitleRow(text: String) {
    Text(text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 8.dp, bottom = 8.dp))
}

/** 互动卡片按钮（无涟漪） */
@Composable
private fun ActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(modifier = modifier, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null,
                tint = colorScheme.onSurface,
                modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 我的手机信息卡（KernelSU InfoCard 风格） */
@Composable
private fun MyPhoneCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
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
}
