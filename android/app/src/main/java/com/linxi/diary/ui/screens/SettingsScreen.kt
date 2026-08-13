package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
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
import com.linxi.diary.util.DiagnosticExporter
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
    onOpenAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? android.app.Activity
    var sharing by remember { mutableStateOf(UserPrefs.sharingEnabled) }
    var cardEnabled by remember { mutableStateOf(UserPrefs.statusCardEnabled) }
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

        // 发送日志 + 关于（连成一张卡，仿 KernelSU）
        item {
            Card(Modifier.padding(top = 12.dp, bottom = 8.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "发送日志",
                    summary = "保存到设备文件或分享诊断包",
                    startAction = { PrefIcon(Icons.AutoMirrored.Filled.Send, "发送日志") },
                    onClick = { showLogSheet = true }
                )
                ArrowPreference(
                    title = "关于",
                    summary = "版本、开源仓库、检查更新、退出登录",
                    startAction = { PrefIcon(Icons.Filled.Info, "关于") },
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
                    startAction = { PrefIcon(Icons.Filled.Save, "保存日志") },
                    onClick = {
                        showLogSheet = false
                        saveLogLauncher.launch("linxi-diagnostics-${System.currentTimeMillis()}.zip")
                    }
                )
                ArrowPreference(
                    title = "发送日志",
                    summary = "通过系统分享导出诊断包",
                    startAction = { PrefIcon(Icons.Filled.Share, "发送日志") },
                    onClick = {
                        showLogSheet = false
                        activity?.let { act -> lifecycleOwner.lifecycleScope.launch { DiagnosticExporter.share(act) } }
                    }
                )
                MiuixButton(
                    onClick = { showLogSheet = false },
                    cornerRadius = 12.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("取消") }
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
