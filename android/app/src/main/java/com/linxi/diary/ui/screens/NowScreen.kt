package com.linxi.diary.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.linxi.diary.ui.theme.LocalLinxiDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.core.DeviceStatus
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.data.RelationshipDays
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.WarningCard
import com.linxi.diary.ui.components.WarningLevel
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 远程互动按钮客户端冷却时长：点击后进行中态持续、且期间禁用重复点击（毫秒）。 */
private const val INTERACTION_COOLDOWN_MS = 7000L

/**
 * 主页（照抄 KernelSU HomeMiuix）：
 * 绿色状态卡（动态 secondaryContainer / 非动态浅绿#DFFAE4）+ WarningCard 警示条
 * + 互动入口 Card + InfoText 信息卡（我的手机）。
 */
@Composable
fun NowScreen(
    onOpenBind: () -> Unit = {}
) {
    val demo = UserPrefs.demoMode
    val partner = if (demo) null else DeviceStatusHolder.partner
    val partnerName = UserPrefs.partnerName.ifBlank { "对方" }
    val profile = if (demo) null else ProfileRuntime.repository.profile.collectAsState().value
    val bound = !demo && UserPrefs.pairId > 0
    val anniversary = profile?.anniversaryDate
    val relationshipDays = anniversary?.let {
        RelationshipDays.dayNumber(it, java.time.LocalDate.now())
    }

    // 远程互动三按钮各自独立的“进行中”态：点击后 7 秒内变蓝 + 禁用，到点自动恢复（客户端冷却）。
    var comfortActive by remember { mutableStateOf(false) }
    var calmActive by remember { mutableStateOf(false) }
    var ringActive by remember { mutableStateOf(false) }
    LaunchedEffect(comfortActive) { if (comfortActive) { delay(INTERACTION_COOLDOWN_MS); comfortActive = false } }
    LaunchedEffect(calmActive) { if (calmActive) { delay(INTERACTION_COOLDOWN_MS); calmActive = false } }
    LaunchedEffect(ringActive) { if (ringActive) { delay(INTERACTION_COOLDOWN_MS); ringActive = false } }

    KernelScreen(title = "主页") {
        item {
            Column(
                Modifier.padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (relationshipDays != null && anniversary != null) {
                    RelationshipDaysCard(relationshipDays, anniversary, partnerName)
                } else if (bound && profile != null && anniversary == null) {
                    // 已绑定、资料已加载但未设纪念日：克制地提示去「我的 · 编辑资料」设置。
                    WarningCard("在「我的 · 编辑资料」里设置你们的纪念日", level = WarningLevel.Notice)
                }
                if (partner != null && partner.batteryLevel < 15) {
                    WarningCard("对方电量不足 15%", level = WarningLevel.Error)
                }
                if (partner == null) {
                    WarningCard("等待 $partnerName 同步状态", level = WarningLevel.Notice)
                }

                // 伴侣状态卡（KernelSU StatusCard：绿色卡片 + 右下大图标叠层）
                PartnerStatusCard(partner, partnerName)

                // 远程互动（KernelSU Card 风格）；调试模式不发送真实事件。
                SectionTitle("远程互动")
                if (demo) {
                    WarningCard("示例模式不发送消息或响铃提醒", level = WarningLevel.Notice)
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActionCard(
                            "求陪伴", Icons.Filled.Favorite, Modifier.weight(1f),
                            active = comfortActive, activeTitle = "已发送…"
                        ) {
                            comfortActive = true
                            StatusSyncManager.sendEvent("comfort_request")
                        }
                        ActionCard(
                            "求冷静", Icons.Filled.CheckCircle, Modifier.weight(1f),
                            active = calmActive, activeTitle = "已发送…"
                        ) {
                            calmActive = true
                            StatusSyncManager.sendEvent("calm_request")
                        }
                    }
                    ActionCard(
                        "响铃提醒（紧急找人）", Icons.Filled.Notifications, Modifier.fillMaxWidth(),
                        active = ringActive, activeTitle = "响铃中…"
                    ) {
                        ringActive = true
                        StatusSyncManager.sendEvent("ring_request")
                    }
                }

                // 我的手机信息（KernelSU InfoCard：InfoText 格式）
                SectionTitle("我的手机")
                MyPhoneInfoCard()
            }
        }
    }
}

/** 恋爱天数卡（纪念日当天为第 1 天）。 */
@Composable
private fun RelationshipDaysCard(days: Long, anniversary: java.time.LocalDate, partnerName: String) {
    val cardColor = when {
        isDynamicColor -> colorScheme.primaryContainer
        LocalLinxiDarkTheme.current -> Color(0xFF3A2233)
        else -> Color(0xFFFCE4F1)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = cardColor),
        pressFeedbackType = PressFeedbackType.Tilt
    ) {
        Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier.fillMaxSize().offset(x = 27.dp, y = 31.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = if (isDynamicColor) colorScheme.primary.copy(alpha = 0.8f) else Color(0xFFF06AA8),
                    modifier = Modifier.size(110.dp)
                )
            }
            Box(
                Modifier.fillMaxSize().padding(16.dp, 14.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Column {
                    Text(
                        "和 $partnerName 在一起",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "第 $days 天",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "纪念日 ${anniversary.year}.${anniversary.monthValue}.${anniversary.dayOfMonth}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/** 伴侣状态卡（照抄 KernelSU StatusCard：大图标叠层 Box offset 27,31 + 110dp） */
@Composable
private fun PartnerStatusCard(partner: DeviceStatus?, partnerName: String) {
    val cardColor = when {
        isDynamicColor -> colorScheme.secondaryContainer
        LocalLinxiDarkTheme.current -> Color(0xFF1A3825)
        else -> Color(0xFFDFFAE4)
    }
    val iconTint = if (isDynamicColor) colorScheme.primary.copy(alpha = 0.8f) else Color(0xFF36D167)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = cardColor),
        pressFeedbackType = PressFeedbackType.Tilt
    ) {
        Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 右下角大图标（KernelSU offset 27,31 / 110dp）
            Box(
                Modifier.fillMaxSize().offset(x = 27.dp, y = 31.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = iconTint,
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
                        when {
                            partner == null -> "等待 $partnerName 同步"
                            else -> partner.foregroundApp?.second?.let { "正在使用 $it" } ?: "息屏/无前台"
                        },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onBackground
                    )
                    Spacer(Modifier.height(1.dp))
                    if (partner != null) {
                        Text(
                            "电量 ${partner.batteryLevel}%${if (partner.isCharging) " · 充电中" else ""} · " +
                                    "${if (partner.screenOn) "亮屏${if (partner.isLocked) "·锁定" else "·解锁"}" else "灭屏"}" +
                                    " · ${partner.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi: $it" } ?: "移动网络"}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onBackground
                        )
                        partner.music?.let {
                            if (it.playing) {
                                Spacer(Modifier.height(1.dp))
                                Text("♪ ${it.title} - ${it.artist}", fontSize = 14.sp,
                                    color = colorScheme.onBackground)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 分组小标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 8.dp, bottom = 4.dp)
    )
}

/** 互动卡片按钮（KernelSU Card 风格，无涟漪）。active=进行中：变蓝 + 进行文本 + 禁用点击（7 秒客户端冷却）。 */
@Composable
private fun ActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeTitle: String = "进行中…",
    onClick: () -> Unit
) {
    val fg = if (active) Color.White else colorScheme.onSurface
    Card(
        modifier = modifier,
        onClick = { if (!active) onClick() },
        colors = if (active) {
            CardDefaults.defaultColors(color = BrandBlue, contentColor = Color.White)
        } else {
            CardDefaults.defaultColors()
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (active) activeTitle else title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = fg)
        }
    }
}

/** 我的手机信息卡（照抄 KernelSU InfoCard：InfoText 标题+内容，项间 24dp） */
@Composable
private fun MyPhoneInfoCard() {
    @Composable
    fun InfoText(
        title: String,
        content: String,
        bottomPadding: androidx.compose.ui.unit.Dp = 24.dp
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface
        )
        Text(
            text = content,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding)
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        val my = DeviceStatusHolder.current
        if (my != null) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                InfoText("电量", "${my.batteryLevel}%${if (my.isCharging) " · 充电中" else ""}")
                InfoText("屏幕", if (my.screenOn) "亮屏" else "灭屏")
                InfoText("前台", my.foregroundApp?.second ?: "息屏/无前台")
                InfoText("网络", my.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi: $it" } ?: "移动网络", bottomPadding = 0.dp)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                InfoText("状态共享", "在「我的」中开启后开始采集", bottomPadding = 0.dp)
            }
        }
    }
}
