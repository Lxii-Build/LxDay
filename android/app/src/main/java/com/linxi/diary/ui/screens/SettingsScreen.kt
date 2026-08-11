package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import com.linxi.diary.core.PermissionHelper
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.AnniversaryDatePolicy
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.data.RelationshipDays
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.util.CrashHandler
import com.linxi.diary.util.DiagnosticExporter
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs

/**
 * 我的（照抄 KernelSU 设置页 SettingPagerMiuix）：
 * KernelScreen 骨架 + 分组 Card + SwitchPreference/ArrowPreference/OverlayDropdownPreference。
 * 每个设置项带 startAction 图标，外观与 KernelSU 一致。
 */
@Composable
fun SettingsScreen(
    onOpenConsent: () -> Unit = {},
    onOpenBind: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenProfileEdit: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val activity = context as? android.app.Activity
    var sharing by remember { mutableStateOf(UserPrefs.sharingEnabled) }
    var cardEnabled by remember { mutableStateOf(UserPrefs.statusCardEnabled) }
    var crashCount by remember { mutableStateOf(CrashHandler.crashFiles().size) }
    val partnerName = UserPrefs.partnerName.ifBlank { "未绑定" }
    val bound = UserPrefs.pairId > 0
    val demo = UserPrefs.demoMode
    val profile = if (demo) null else ProfileRuntime.repository.profile.collectAsState().value
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showAnniversaryDialog by remember { mutableStateOf(false) }
    var avatarUploading by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            avatarUploading = true
            scope.launch {
                runCatching {
                    val ext = context.contentResolver.getType(uri)
                        ?.substringAfterLast('/')?.lowercase() ?: "img"
                    val file = java.io.File(context.cacheDir, "avatar_src_${System.currentTimeMillis()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    // 当前为居中方裁全幅；服务端按同一约定裁剪并生成动画主图与静态缩略图。
                    ApiClient.uploadAvatar(file)
                }.onSuccess { ProfileRuntime.applyAuthoritative(it) }
                    .onFailure { Logs.w("Settings", "上传头像失败", it) }
                avatarUploading = false
            }
        }
    }

    // 权限状态
    val usageOk = PermissionHelper.hasUsageAccess(context)
    val notifOk = PermissionHelper.hasNotificationListener(context)
    val policyOk = PermissionHelper.hasNotificationPolicyAccess(context)

    KernelScreen(title = "我的") {
        // 分组1：共享与绑定
        item {
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "编辑资料",
                    summary = "头像、名称、性别、简介与生日",
                    startAction = { PrefIcon(Icons.Filled.AccountCircle, "编辑资料") },
                    onClick = onOpenProfileEdit
                )
                ArrowPreference(
                    title = "伴侣",
                    summary = if (bound) partnerName else "未绑定",
                    startAction = { PrefIcon(Icons.Filled.Person, "伴侣") },
                    onClick = { if (!bound) onOpenBind() }
                )
                if (bound && !demo) {
                    ArrowPreference(
                        title = "我的头像",
                        summary = when {
                            avatarUploading -> "上传中…"
                            profile?.me?.avatarUrl != null -> "点击更换（支持动态 GIF/WebP）"
                            else -> "点击设置头像"
                        },
                        startAction = { PrefIcon(Icons.Filled.Face, "我的头像") },
                        onClick = {
                            if (!avatarUploading) {
                                avatarPicker.launch(
                                    arrayOf(
                                        "image/png", "image/webp", "image/gif",
                                        "image/heif", "image/heic", "image/avif", "image/bmp",
                                    )
                                )
                            }
                        }
                    )
                    ArrowPreference(
                        title = "我的昵称",
                        summary = profile?.me?.nickname?.ifBlank { "未设置" } ?: "点击设置",
                        startAction = { PrefIcon(Icons.Filled.Edit, "我的昵称") },
                        onClick = { showNicknameDialog = true }
                    )
                    val anniversarySummary = profile?.anniversaryDate?.let { date ->
                        val days = RelationshipDays.dayNumber(date, java.time.LocalDate.now())
                        if (days != null) "$date · 第 $days 天" else date.toString()
                    } ?: "点击设置"
                    ArrowPreference(
                        title = "纪念日",
                        summary = anniversarySummary,
                        startAction = { PrefIcon(Icons.Filled.DateRange, "纪念日") },
                        onClick = { showAnniversaryDialog = true }
                    )
                }
                SwitchPreference(
                    title = "状态共享",
                    summary = if (demo) "调试模式不采集、不上传真实状态" else "关闭后立即停止采集并清除本机数据",
                    startAction = { PrefIcon(Icons.Filled.Favorite, "状态共享") },
                    checked = sharing,
                    enabled = !demo && UserPrefs.privacyConsented,
                    onCheckedChange = { on ->
                        sharing = on
                        UserPrefs.sharingEnabled = on
                        if (on) {
                            StatusForegroundService.start(context)
                            StatusSyncManager.connect()
                        } else {
                            DeviceStatusHolder_local.clear()
                            StatusForegroundService.stop(context)
                        }
                    }
                )
                ArrowPreference(
                    title = "知情同意",
                    summary = if (UserPrefs.privacyConsented) "已完成授权" else "需先完成知情授权",
                    startAction = { PrefIcon(Icons.Filled.CheckCircle, "知情同意") },
                    onClick = onOpenConsent
                )
                ArrowPreference(
                    title = "伴侣状态历史",
                    summary = if (demo) "调试模式不读取服务端历史" else "查看状态时间线与电量曲线",
                    startAction = { PrefIcon(Icons.Filled.History, "伴侣状态历史") },
                    onClick = if (demo) null else onOpenHistory
                )
            }
        }

        // 分组2：外观
        item {
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "主题与界面",
                    summary = "配色、壁纸、动态取色与界面开关",
                    startAction = { PrefIcon(Icons.Filled.Settings, "主题与界面") },
                    onClick = onOpenAppearance
                )
                SwitchPreference(
                    title = "常驻状态卡片",
                    summary = "通知栏常驻展示对方状态",
                    startAction = { PrefIcon(Icons.Filled.Notifications, "常驻状态卡片") },
                    checked = cardEnabled,
                    enabled = !demo && UserPrefs.privacyConsented && sharing,
                    onCheckedChange = { on ->
                        cardEnabled = on
                        UserPrefs.statusCardEnabled = on
                        if (on) StatusForegroundService.start(context)
                        else StatusForegroundService.stop(context)
                    }
                )
            }
        }

        // 分组3：权限与保活
        item {
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "使用情况访问",
                    summary = if (usageOk) "已开启" else "识别前台 APP 与用量统计",
                    startAction = { PrefIcon(Icons.Filled.AccountCircle, "使用情况访问") },
                    onClick = { PermissionHelper.toUsageAccess(context) }
                )
                ArrowPreference(
                    title = "通知使用权",
                    summary = if (notifOk) "已开启" else "识别音乐 + 卡片兜底",
                    startAction = { PrefIcon(Icons.Filled.Lock, "通知使用权") },
                    onClick = { PermissionHelper.toNotificationListener(context) }
                )
                ArrowPreference(
                    title = "勿扰访问",
                    summary = if (policyOk) "已开启" else "强制响铃可绕过勿扰",
                    startAction = { PrefIcon(Icons.Filled.Notifications, "勿扰访问") },
                    onClick = { PermissionHelper.toNotificationPolicy(context) }
                )
                ArrowPreference(
                    title = "vivo/OPPO 自启动白名单",
                    summary = "防后台被杀，保证同步",
                    startAction = { PrefIcon(Icons.AutoMirrored.Filled.Send, "自启动白名单") },
                    onClick = { PermissionHelper.toVendorAutoStart(context) }
                )
            }
        }

        // 分组4：调试
        item {
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "崩溃日志",
                    summary = "$crashCount 条 · 本机 app 私有目录",
                    startAction = { PrefIcon(Icons.Filled.Build, "崩溃日志") },
                    onClick = {
                        crashCount = CrashHandler.crashFiles().size
                        Logs.i("Settings", "崩溃日志数=$crashCount")
                    }
                )
                ArrowPreference(
                    title = "导出诊断日志",
                    summary = "导出私有目录中最近 7 天运行日志与崩溃记录",
                    startAction = { PrefIcon(Icons.Filled.Share, "导出诊断日志") },
                    onClick = {
                        activity?.let { ownerActivity ->
                            lifecycleOwner.lifecycleScope.launch { DiagnosticExporter.share(ownerActivity) }
                        }
                    }
                )
                ArrowPreference(
                    title = "清空崩溃日志",
                    summary = "删除本机崩溃记录",
                    startAction = { PrefIcon(Icons.Filled.Delete, "清空崩溃日志") },
                    onClick = {
                        CrashHandler.clearCrashes()
                        crashCount = 0
                    }
                )
            }
        }

        // 退出登录
        item {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                MiuixButton(
                    onClick = {
                        Logs.i("Settings", "退出登录：重置绑定与授权状态")
                        UserPrefs.token = null
                        UserPrefs.demoMode = false
                        UserPrefs.sharingEnabled = false
                        UserPrefs.privacyConsented = false
                        StatusSyncManager.disconnect()
                        ProfileRuntime.clearSession()
                        StatusForegroundService.stop(context)
                        onLogout()
                    },
                    cornerRadius = 16.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("退出登录", color = Color(0xFFD9412F))
                }
                Text(
                    "退出仅清除本地登录状态，服务端数据保留",
                    color = colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    if (showNicknameDialog) {
        NicknameEditDialog(
            initial = profile?.me?.nickname.orEmpty(),
            onDismiss = { showNicknameDialog = false },
            onConfirm = { name ->
                showNicknameDialog = false
                scope.launch {
                    runCatching { ApiClient.updateNickname(name) }
                        .onSuccess { ProfileRuntime.applyAuthoritative(it) }
                        .onFailure { Logs.w("Settings", "更新昵称失败", it) }
                }
            }
        )
    }
    if (showAnniversaryDialog) {
        AnniversaryEditDialog(
            initial = profile?.anniversaryDate,
            onDismiss = { showAnniversaryDialog = false },
            onConfirm = { date ->
                showAnniversaryDialog = false
                scope.launch {
                    runCatching { ApiClient.updateAnniversary(date.toString()) }
                        .onSuccess { ProfileRuntime.applyAuthoritative(it) }
                        .onFailure { Logs.w("Settings", "更新纪念日失败", it) }
                }
            }
        )
    }
}

/** preference 起始图标（KernelSU 风格：右偏 6dp，onBackground 色） */
@Composable
private fun PrefIcon(icon: ImageVector, desc: String) {
    Icon(
        icon,
        contentDescription = desc,
        modifier = Modifier.padding(end = 6.dp),
        tint = colorScheme.onBackground
    )
}

/** 本地清空辅助 */
private object DeviceStatusHolder_local {
    fun clear() {
        com.linxi.diary.core.DeviceStatusHolder.current = null
        com.linxi.diary.core.DeviceStatusHolder.partner = null
    }
}

@Composable
private fun NicknameEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val trimmed = value.trim()
    val valid = trimmed.length in 2..32
    OverlayDialog(show = true, onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("修改昵称", fontSize = 18.sp)
                TextField(value = value, onValueChange = { value = it }, label = "昵称（2-32 字）")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MiuixButton(onClick = onDismiss, cornerRadius = 12.dp) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    MiuixButton(enabled = valid, onClick = { onConfirm(trimmed) }, cornerRadius = 12.dp) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun AnniversaryEditDialog(
    initial: java.time.LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (java.time.LocalDate) -> Unit,
) {
    val today = java.time.LocalDate.now()
    val base = initial ?: today
    var year by remember { mutableStateOf(base.year) }
    var month by remember { mutableStateOf(base.monthValue) }
    var day by remember { mutableStateOf(base.dayOfMonth) }
    val candidate = AnniversaryDatePolicy.clampDate(year, month, day, today)
    OverlayDialog(show = true, onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("设置纪念日", fontSize = 18.sp)
                Text("在一起的第一天为第 1 天", color = colorScheme.onSurface.copy(alpha = 0.7f))
                NumberStepperRow("年", candidate.year, today.year - 80, today.year) { year = it; day = candidate.dayOfMonth }
                NumberStepperRow("月", candidate.monthValue, 1, 12) { month = it; day = candidate.dayOfMonth }
                NumberStepperRow(
                    "日", candidate.dayOfMonth, 1,
                    AnniversaryDatePolicy.daysInMonth(candidate.year, candidate.monthValue)
                ) { day = it }
                Text("已选择：$candidate", color = colorScheme.onSurface)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MiuixButton(onClick = onDismiss, cornerRadius = 12.dp) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    MiuixButton(onClick = { onConfirm(candidate) }, cornerRadius = 12.dp) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun NumberStepperRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, color = colorScheme.onSurface.copy(alpha = 0.78f))
        Spacer(Modifier.weight(1f))
        MiuixButton(enabled = value > min, onClick = { onChange((value - 1).coerceAtLeast(min)) }, cornerRadius = 12.dp) {
            Text("-")
        }
        Text("  $value  ", color = colorScheme.onSurface)
        MiuixButton(enabled = value < max, onClick = { onChange((value + 1).coerceAtMost(max)) }, cornerRadius = 12.dp) {
            Text("+")
        }
    }
}
