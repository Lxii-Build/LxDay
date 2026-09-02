package com.linxi.diary.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.linxi.diary.sync.StatusFreshness
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.WarningCard
import com.linxi.diary.ui.components.WarningLevel
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.ui.theme.LocalLinxiDarkTheme
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType

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
    val currentState by DeviceStatusHolder.currentFlow.collectAsStateWithLifecycle()
    val partnerState by DeviceStatusHolder.partnerFlow.collectAsStateWithLifecycle()
    val partner = if (demo) null else partnerState
    val partnerName = UserPrefs.partnerName.ifBlank { "对方" }
    val profile = if (demo) null else ProfileRuntime.repository.profile.collectAsState().value
    val bound = !demo && UserPrefs.pairId > 0
    val anniversary = profile?.anniversaryDate
    val relationshipDays = anniversary?.let {
        RelationshipDays.dayNumber(it, java.time.LocalDate.now())
    }

    // 远程互动三按钮各自独立的"进行中"态：变蓝后可再次点击撤回。
    var comfortActive by remember { mutableStateOf(false) }
    var calmActive by remember { mutableStateOf(false) }
    var ringActive by remember { mutableStateOf(false) }
    var comfortRequestId by remember { mutableStateOf<String?>(null) }
    var calmRequestId by remember { mutableStateOf<String?>(null) }

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
                SectionTitle("远程互动", "把此刻的需要直接告诉对方")
                if (demo) {
                    WarningCard("示例模式不发送消息或响铃提醒", level = WarningLevel.Notice)
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActionCard(
                            "求陪伴", MiuixIcons.FavoritesFill, Modifier.weight(1f),
                            active = comfortActive, activeTitle = "已发送…（点击撤回）",
                            allowClickWhenActive = true,
                        ) {
                            InteractionEvents.clearRejection()
                            if (comfortActive) {
                                if (StatusSyncManager.sendInteractionCancel("comfort_cancel", comfortRequestId)) {
                                    comfortActive = false
                                    comfortRequestId = null
                                } else {
                                    InteractionEvents.onRejected("comfort_cancel", "当前离线，撤回未发出")
                                }
                            } else {
                                val requestId = java.util.UUID.randomUUID().toString()
                                if (StatusSyncManager.sendEvent("comfort_request", requestId)) {
                                    comfortRequestId = requestId
                                    comfortActive = true
                                } else {
                                    InteractionEvents.onRejected("comfort_request", "当前离线，消息未发出")
                                }
                            }
                        }
                        ActionCard(
                            "求冷静", MiuixIcons.Ok, Modifier.weight(1f),
                            active = calmActive, activeTitle = "已发送…（点击撤回）",
                            allowClickWhenActive = true,
                        ) {
                            InteractionEvents.clearRejection()
                            if (calmActive) {
                                if (StatusSyncManager.sendInteractionCancel("calm_cancel", calmRequestId)) {
                                    calmActive = false
                                    calmRequestId = null
                                } else {
                                    InteractionEvents.onRejected("calm_cancel", "当前离线，撤回未发出")
                                }
                            } else {
                                val requestId = java.util.UUID.randomUUID().toString()
                                if (StatusSyncManager.sendEvent("calm_request", requestId)) {
                                    calmRequestId = requestId
                                    calmActive = true
                                } else {
                                    InteractionEvents.onRejected("calm_request", "当前离线，消息未发出")
                                }
                            }
                        }
                    }
                    // 响铃：进行中显示"响铃中"并提供【撤回】，对方关闭后显示"对方已知悉"。
                    val ringAcked = pendingRing?.acknowledged == true
                    ActionCard(
                        "响铃提醒（紧急找人）", MiuixIcons.Messages, Modifier.fillMaxWidth(),
                        active = ringActive,
                        activeTitle = if (ringAcked) "对方已知悉" else "响铃中…（点击撤回）",
                        allowClickWhenActive = true, // 否则撤回点不到
                    ) {
                        if (ringActive) {
                            // 进行中再次点击 = 撤回：让对方立刻停止响铃。
                            if (StatusSyncManager.sendRingCancel(pendingRing?.ringId)) {
                                ringActive = false
                                InteractionEvents.clear()
                            } else {
                                InteractionEvents.onRejected("ring_cancel", "当前离线，撤回未发出")
                            }
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
                SectionTitle("我的手机", "当前共享给对方的状态")
                MyPhoneInfoCard(current = currentState, demo = demo)
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
                    imageVector = MiuixIcons.FavoritesFill,
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

/**
 * 每 30 秒推进一次的"当前时间"。
 *
 * 时效文案（"3 分钟前"）必须会自己变，否则用户盯着屏幕时它一直停在"刚刚"，
 * 反而更容易误信。30 秒粒度足够（文案本身就是分钟级），也不会白耗电。
 */
@Composable
private fun rememberNowTick(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** 伴侣状态卡：优先展示可信度，再把高频信息拆成可扫读的指标。 */
@Composable
private fun PartnerStatusCard(partner: DeviceStatus?, partnerName: String) {
    val cardColor = when {
        isDynamicColor -> colorScheme.secondaryContainer
        LocalLinxiDarkTheme.current -> Color(0xFF1A3825)
        else -> Color(0xFFDFFAE4)
    }
    val iconTint = if (isDynamicColor) colorScheme.primary.copy(alpha = 0.8f) else Color(0xFF36D167)
    val nowTick = rememberNowTick()
    val level = StatusFreshness.levelOf(partner?.ts ?: 0L, nowTick)
    val stale = level != StatusFreshness.Level.Fresh
    val mainColor =
        if (stale) colorScheme.onBackground.copy(alpha = 0.56f)
        else colorScheme.onBackground
    val statusTitle = when {
        partner == null -> "等待 $partnerName 同步"
        level == StatusFreshness.Level.Offline -> "$partnerName 状态未知"
        else -> partner.foregroundApp?.second?.let { "正在使用 $it" } ?: "息屏 / 无前台"
    }
    val freshnessText = StatusFreshness.hintText(partner?.ts ?: 0L, nowTick)
    val freshnessBadge = when (level) {
        StatusFreshness.Level.Fresh -> "同步正常"
        StatusFreshness.Level.Stale -> "可能过期"
        StatusFreshness.Level.Offline -> "已失联"
    }
    val statusSummary = buildString {
        append(statusTitle)
        append('，')
        append(freshnessText)
        if (partner != null && level != StatusFreshness.Level.Offline) {
            append("，电量 ${partner.batteryLevel}%")
            if (partner.isCharging) append("，充电中")
            append(if (partner.screenOn) {
                if (partner.isLocked) "，亮屏，已锁定" else "，亮屏，已解锁"
            } else {
                "，灭屏"
            })
            append("，${partner.ssid?.takeIf { it.isNotBlank() } ?: "移动网络"}")
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = statusSummary },
        colors = CardDefaults.defaultColors(color = cardColor),
        pressFeedbackType = PressFeedbackType.Tilt
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$partnerName 的状态",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onBackground.copy(alpha = 0.68f),
                )
                Spacer(Modifier.weight(1f))
                FreshnessBadge(freshnessBadge, stale, iconTint)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = statusTitle,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = mainColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = freshnessText,
                fontSize = 12.sp,
                color = if (stale) com.linxi.diary.ui.theme.BrandRed.copy(alpha = 0.88f)
                else colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 3.dp),
            )
            if (partner != null && level != StatusFreshness.Level.Offline) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusMetric(
                        label = "电量",
                        value = "${partner.batteryLevel}%${if (partner.isCharging) " · 充电" else ""}",
                        color = mainColor,
                        modifier = Modifier.weight(1f),
                    )
                    StatusMetric(
                        label = "屏幕",
                        value = if (partner.screenOn) {
                            if (partner.isLocked) "亮屏 · 锁定" else "亮屏 · 解锁"
                        } else {
                            "灭屏"
                        },
                        color = mainColor,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                StatusMetric(
                    label = "网络",
                    value = partner.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi · $it" } ?: "移动网络",
                    color = mainColor,
                    modifier = Modifier.fillMaxWidth(),
                )
                partner.music?.takeIf { it.playing }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "正在播放  ${it.title} · ${it.artist}",
                        fontSize = 13.sp,
                        color = mainColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FreshnessBadge(text: String, stale: Boolean, freshColor: Color) {
    val accent = if (stale) com.linxi.diary.ui.theme.BrandRed else freshColor
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun StatusMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.onBackground.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = colorScheme.onBackground.copy(alpha = 0.58f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分组小标题 */
@Composable
private fun SectionTitle(text: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) {
        Text(
            text,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface,
        )
        Text(
            subtitle,
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 互动卡片按钮（KernelSU Card 风格，无涟漪）。active=进行中：变蓝 + 进行文本。 */
@Composable
private fun ActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeTitle: String = "进行中…",
    /**
     * 进行中态是否仍可点击。
     * 互动请求与响铃都允许在进行中点击，以便及时撤回；只有确实不支持撤回的动作才保持 false。
     */
    allowClickWhenActive: Boolean = false,
    onClick: () -> Unit
) {
    val fg = if (active) Color.White else colorScheme.onSurface
    val enabled = !active || allowClickWhenActive
    val visibleTitle = if (active) activeTitle else title
    val colors = if (active) {
        CardDefaults.defaultColors(color = BrandBlue, contentColor = Color.White)
    } else {
        CardDefaults.defaultColors()
    }
    val cardModifier = modifier
        .defaultMinSize(minHeight = 64.dp)
        .semantics {
            role = Role.Button
            contentDescription = visibleTitle
            stateDescription = if (active) "进行中" else "可用"
            if (!enabled) disabled()
        }

    if (enabled) {
        Card(modifier = cardModifier, onClick = onClick, colors = colors) {
            ActionCardContent(icon, visibleTitle, fg)
        }
    } else {
        Card(modifier = cardModifier, colors = colors) {
            ActionCardContent(icon, visibleTitle, fg)
        }
    }
}

@Composable
private fun ActionCardContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    color: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** 我的手机信息卡：标签和值同排，减少滚动并方便快速比对。 */
@Composable
private fun MyPhoneInfoCard(current: DeviceStatus?, demo: Boolean) {
    @Composable
    fun InfoRow(title: String, content: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.width(56.dp),
            )
            Text(
                text = content,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        val my = current
        if (my != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                InfoRow("电量", "${my.batteryLevel}%${if (my.isCharging) " · 充电中" else ""}")
                InfoRow("屏幕", if (my.screenOn) "亮屏" else "灭屏")
                InfoRow("前台", my.foregroundApp?.second ?: "息屏 / 无前台")
                InfoRow("网络", my.ssid?.takeIf { it.isNotBlank() }?.let { "WiFi · $it" } ?: "移动网络")
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val status = when {
                    demo -> "示例模式不采集真实状态"
                    !UserPrefs.privacyConsented -> "完成知情同意后开始采集"
                    !UserPrefs.sharingEnabled -> "状态共享已关闭，可在「我的」中开启"
                    else -> "状态共享已开启，正在等待本机状态"
                }
                InfoRow("状态共享", status)
            }
        }
    }
}
