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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linxi.diary.core.DeviceStatus
import com.linxi.diary.core.DeviceStatusHolder
import com.linxi.diary.core.RingHelper
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.data.RelationshipDays
import com.linxi.diary.sync.InteractionEvents
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.WarningCard
import com.linxi.diary.ui.components.WarningLevel
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 远程互动按钮客户端冷却时长：点击后进行中态持续、且期间禁用重复点击（毫秒）。
 * 与服务端 `store.go` 的 interactionCooldownWindow 保持一致（7s/1 次）。
 */
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
    // 订阅 StateFlow 而非直读字段：此前读的是普通 @Volatile var，
    // Compose 不会建立订阅，服务端推到了 UI 也不重组 —— 这才是"状态同步不实时"的真因。
    val partnerState by DeviceStatusHolder.partnerFlow.collectAsStateWithLifecycle()
    val partner = if (demo) null else partnerState
    val partnerName = UserPrefs.partnerName.ifBlank { "对方" }
    val profile = if (demo) null else ProfileRuntime.repository.profile.collectAsState().value
    val bound = !demo && UserPrefs.pairId > 0
    val anniversary = profile?.anniversaryDate
    val relationshipDays = anniversary?.let {
        RelationshipDays.dayNumber(it, java.time.LocalDate.now())
    }

    // 远程互动三按钮各自独立的"进行中"态：点击后 7 秒内变蓝 + 禁用，到点自动恢复（客户端冷却）。
    var comfortActive by remember { mutableStateOf(false) }
    var calmActive by remember { mutableStateOf(false) }
    var ringActive by remember { mutableStateOf(false) }
    LaunchedEffect(comfortActive) { if (comfortActive) { delay(INTERACTION_COOLDOWN_MS); comfortActive = false } }
    LaunchedEffect(calmActive) { if (calmActive) { delay(INTERACTION_COOLDOWN_MS); calmActive = false } }

    // 响铃的进行中态由 InteractionEvents 驱动（含对方已知悉回执），不再只是本地 flag。
    val pendingRing by InteractionEvents.pending.collectAsStateWithLifecycle()
    val rejection by InteractionEvents.rejection.collectAsStateWithLifecycle()
    LaunchedEffect(ringActive) {
        if (ringActive) {
            delay(RingHelper.RING_DURATION_MS)
            ringActive = false
            InteractionEvents.clear()
        }
    }
    // 服务端拒绝（超频）→ 立即结束进行中态，把原因显示出来后清除。
    LaunchedEffect(rejection) {
        if (rejection != null) {
            comfortActive = false; calmActive = false; ringActive = false
        }
    }

    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    KernelScreen(
        title = "主页",
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                runCatching { ProfileRuntime.refreshAsync() }
                delay(400) // 让刷新动画走完一个完整循环，避免闪跳
                refreshing = false
            }
        },
    ) {
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
                // 服务端明确拒绝了刚才那次互动（如响铃超频）：给出可读原因，
                // 而不是像此前那样静默丢弃、UI 仍显示"已发送"。
                rejection?.let { reason ->
                    WarningCard(reason, level = WarningLevel.Error)
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
                            InteractionEvents.clearRejection()
                            // 只有真发出去才进入"已发送"态：WS 离线时 sendEvent 返回 false，
                            // 此前无论成败都亮起，造成离线假成功。
                            if (StatusSyncManager.sendEvent("comfort_request")) {
                                comfortActive = true
                            } else {
                                InteractionEvents.onRejected("comfort_request", "当前离线，消息未发出")
                            }
                        }
                        ActionCard(
                            "求冷静", Icons.Filled.CheckCircle, Modifier.weight(1f),
                            active = calmActive, activeTitle = "已发送…"
                        ) {
                            InteractionEvents.clearRejection()
                            if (StatusSyncManager.sendEvent("calm_request")) {
                                calmActive = true
                            } else {
                                InteractionEvents.onRejected("calm_request", "当前离线，消息未发出")
                            }
                        }
                    }
                    // 响铃：进行中显示"响铃中"并提供【撤回】，对方关闭后显示"对方已知悉"。
                    val ringAcked = pendingRing?.acknowledged == true
                    ActionCard(
                        "响铃提醒（紧急找人）", Icons.Filled.Notifications, Modifier.fillMaxWidth(),
                        active = ringActive,
                        activeTitle = if (ringAcked) "对方已知悉" else "响铃中…（点击撤回）",
                        allowClickWhenActive = true, // 否则撤回点不到
                    ) {
                        if (ringActive) {
                            // 进行中再次点击 = 撤回：让对方立刻停止响铃。
                            StatusSyncManager.sendRingCancel(pendingRing?.ringId)
                            ringActive = false
                            InteractionEvents.clear()
                        } else {
                            InteractionEvents.clearRejection()
                            val ringId = java.util.UUID.randomUUID().toString()
                            if (StatusSyncManager.sendEvent("ring_request", ringId)) {
                                ringActive = true
                                InteractionEvents.begin("ring_request", ringId)
                            } else {
                                InteractionEvents.onRejected("ring_request", "当前离线，响铃未发出")
                            }
                        }
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
    /**
     * 进行中态是否仍可点击。
     * 求陪伴/求冷静为 false（冷却期禁止重复发）；
     * 响铃为 true —— 进行中点击即「撤回」，若沿用禁用逻辑就永远撤不回。
     */
    allowClickWhenActive: Boolean = false,
    onClick: () -> Unit
) {
    val fg = if (active) Color.White else colorScheme.onSurface
    Card(
        modifier = modifier,
        onClick = { if (!active || allowClickWhenActive) onClick() },
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
