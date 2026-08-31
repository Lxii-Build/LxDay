package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import com.linxi.diary.core.PermissionHelper
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.util.DiagnosticExporter
import com.linxi.diary.util.UserPrefs

/**
 * 我的（照抄 KernelSU 设置页 SettingPagerMiuix）：
 * KernelScreen 骨架 + 分组 Card + SwitchPreference/ArrowPreference/OverlayDropdownPreference。
 * 每个设置项带 startAction 图标，外观与 KernelSU 一致。
 */
@Composable
fun SettingsScreen(
    onOpenKeepAliveCheck: () -> Unit = {},
    onOpenConsent: () -> Unit = {},
    onOpenBind: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenProfileEdit: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? android.app.Activity
    var sharing by remember { mutableStateOf(UserPrefs.sharingEnabled) }
    var cardEnabled by remember { mutableStateOf(UserPrefs.statusCardEnabled) }
    var quietNotify by remember { mutableStateOf(UserPrefs.quietNotifyEnabled) }
    val partnerName = UserPrefs.partnerName.ifBlank { "未绑定" }
    val bound = UserPrefs.pairId > 0
    val demo = UserPrefs.demoMode
    var showLogSheet by remember { mutableStateOf(false) }

    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            activity?.let { act ->
                lifecycleOwner.lifecycleScope.launch { DiagnosticExporter.save(act, uri) }
            }
        }
    }

    // 权限状态。
    //
    // 必须用 State 承载并在 ON_RESUME 时重查，原因有两个：
    // ① 这些检查会做 AppOpsManager IPC 与 Settings.Secure 查询，
    //    直接写在 composition 体里等于每次重组都在主线程跑一遍；
    // ② 用户跳去系统设置授权后返回，旧写法不会重查，界面仍显示"未开启"，
    //    用户会以为没生效而反复点。
    var permTick by remember { mutableStateOf(0) }
    val usageOk = remember(permTick) { PermissionHelper.hasUsageAccess(context) }
    val notifOk = remember(permTick) { PermissionHelper.hasNotificationListener(context) }
    val policyOk = remember(permTick) { PermissionHelper.hasNotificationPolicyAccess(context) }
    val batteryOk = remember(permTick) { PermissionHelper.hasIgnoreBattery(context) }
    // 自检项待办数（与 KeepAliveCheckScreen 的判定保持一致：厂商自启动无法程序化检测，
    // 一律计入待确认）。
    val keepAliveFailed = remember(permTick) {
        var n = 0
        if (!usageOk) n++
        if (!notifOk) n++
        if (!policyOk) n++
        if (!batteryOk) n++
        if (com.linxi.diary.status.Vendor.fromManufacturer(android.os.Build.MANUFACTURER)
            != com.linxi.diary.status.Vendor.OTHER
        ) n++
        n
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    KernelScreen(title = "我的") {
        // 分组1：共享与绑定
        item {
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "编辑资料",
                    summary = "头像、名称、性别、简介与生日",
                    startAction = { PrefIcon(MiuixIcons.ContactsCircle, "编辑资料") },
                    onClick = onOpenProfileEdit
                )
                ArrowPreference(
                    title = "伴侣",
                    summary = if (bound) partnerName else "未绑定",
                    startAction = { PrefIcon(MiuixIcons.Contacts, "伴侣") },
                    onClick = { if (!bound) onOpenBind() }
                )
                SwitchPreference(
                    title = "状态共享",
                    summary = if (demo) "调试模式不采集、不上传真实状态" else "关闭后立即停止采集并清除本机数据",
                    startAction = { PrefIcon(MiuixIcons.FavoritesFill, "状态共享") },
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
                    startAction = { PrefIcon(MiuixIcons.Ok, "知情同意") },
                    onClick = onOpenConsent
                )
                ArrowPreference(
                    title = "伴侣状态历史",
                    summary = if (demo) "调试模式不读取服务端历史" else "查看状态时间线与电量曲线",
                    startAction = { PrefIcon(MiuixIcons.Recent, "伴侣状态历史") },
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
                    startAction = { PrefIcon(MiuixIcons.Settings, "主题与界面") },
                    onClick = onOpenAppearance
                )
                SwitchPreference(
                    title = "常驻状态卡片",
                    summary = "通知栏常驻展示对方状态",
                    startAction = { PrefIcon(MiuixIcons.Messages, "常驻状态卡片") },
                    checked = cardEnabled,
                    enabled = !demo && UserPrefs.privacyConsented && sharing,
                    onCheckedChange = { on ->
                        cardEnabled = on
                        UserPrefs.statusCardEnabled = on
                        if (on) StatusForegroundService.start(context)
                        else StatusForegroundService.stop(context)
                    }
                )
                // 静默通知：对方息屏/亮屏、上线/下线时在通知栏留一条，
                // 不弹横幅、不响铃、不振动（走 status_quiet 渠道，IMPORTANCE_LOW + setSound(null)）。
                SwitchPreference(
                    title = "伴侣动态静默通知",
                    summary = "对方息屏/亮屏、上下线时通知栏提示，不响铃不弹窗",
                    startAction = { PrefIcon(MiuixIcons.Messages, "伴侣动态静默通知") },
                    checked = quietNotify,
                    enabled = !demo && UserPrefs.privacyConsented && sharing,
                    onCheckedChange = { on ->
                        quietNotify = on
                        UserPrefs.quietNotifyEnabled = on
                    }
                )
            }
        }

        // 分组3：权限与保活
        item {
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                // 自检入口放最前：一次看清所有保活项的状态，并写明"不开会怎样"。
                // 此前这一组全是"没有状态反馈的跳转按钮"，用户点完也不知道开没开。
                ArrowPreference(
                    title = "同步自检",
                    summary = if (keepAliveFailed > 0) "有 $keepAliveFailed 项待开启" else "全部就绪",
                    startAction = { PrefIcon(MiuixIcons.Ok, "同步自检") },
                    onClick = onOpenKeepAliveCheck,
                )
                ArrowPreference(
                    title = "使用情况访问",
                    summary = if (usageOk) "已开启" else "识别前台 APP 与用量统计",
                    startAction = { PrefIcon(MiuixIcons.ContactsCircle, "使用情况访问") },
                    onClick = { PermissionHelper.toUsageAccess(context) }
                )
                ArrowPreference(
                    title = "通知使用权",
                    summary = if (notifOk) "已开启" else "识别音乐 + 卡片兜底",
                    startAction = { PrefIcon(MiuixIcons.Lock, "通知使用权") },
                    onClick = { PermissionHelper.toNotificationListener(context) }
                )
                ArrowPreference(
                    title = "勿扰访问",
                    summary = if (policyOk) "已开启" else "强制响铃可绕过勿扰",
                    startAction = { PrefIcon(MiuixIcons.Messages, "勿扰访问") },
                    onClick = { PermissionHelper.toNotificationPolicy(context) }
                )
                // 电池优化白名单：PermissionHelper 里早就写好了 hasIgnoreBattery/toBatteryOptimization，
                // 但全项目零引用 —— 国产 ROM 上前台服务被省电策略杀掉是"状态不同步"的最大单一原因，
                // 缺这个入口等于把最有效的保活手段藏起来了。
                ArrowPreference(
                    title = "电池优化白名单",
                    summary = if (batteryOk) "已加入白名单" else "允许后台运行，避免状态同步中断",
                    startAction = { PrefIcon(MiuixIcons.Messages, "电池优化白名单") },
                    onClick = { PermissionHelper.toBatteryOptimization(context) }
                )
                ArrowPreference(
                    title = "vivo/OPPO 自启动白名单",
                    summary = "防后台被杀，保证同步",
                    startAction = { PrefIcon(MiuixIcons.Send, "自启动白名单") },
                    onClick = { PermissionHelper.toVendorAutoStart(context) }
                )
            }
        }

        // 发送日志 + 关于（连成一张卡，仿 KernelSU）
        item {
            Card(Modifier.padding(top = 12.dp, bottom = 8.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "发送日志",
                    summary = "保存到设备文件或分享诊断包",
                    startAction = { PrefIcon(MiuixIcons.Send, "发送日志") },
                    onClick = { showLogSheet = true }
                )
                ArrowPreference(
                    title = "关于",
                    summary = "版本、开源仓库、检查更新、退出登录",
                    startAction = { PrefIcon(MiuixIcons.Info, "关于") },
                    onClick = onOpenAbout
                )
            }
        }
    }

    if (showLogSheet) {
        OverlayDialog(
            show = true,
            title = "发送日志",
            onDismissRequest = { showLogSheet = false },
            renderInRootScaffold = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ArrowPreference(
                    title = "保存日志",
                    summary = "保存诊断包到设备文件",
                    startAction = { PrefIcon(MiuixIcons.Backup, "保存日志") },
                    onClick = {
                        showLogSheet = false
                        saveLogLauncher.launch("linxi-diagnostics-${System.currentTimeMillis()}.zip")
                    }
                )
                ArrowPreference(
                    title = "发送日志",
                    summary = "通过系统分享导出诊断包",
                    startAction = { PrefIcon(MiuixIcons.Share, "发送日志") },
                    onClick = {
                        showLogSheet = false
                        activity?.let { act -> lifecycleOwner.lifecycleScope.launch { DiagnosticExporter.share(act) } }
                    }
                )
                LxButton(
                    text = "取消",
                    onClick = { showLogSheet = false },
                    variant = LxButtonVariant.Neutral,
                    cornerRadius = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
