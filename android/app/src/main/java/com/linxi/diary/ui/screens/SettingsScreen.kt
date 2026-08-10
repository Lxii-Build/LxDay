package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import com.linxi.diary.core.PermissionHelper
import com.linxi.diary.data.ProfileRuntime
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
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? android.app.Activity
    var sharing by remember { mutableStateOf(UserPrefs.sharingEnabled) }
    var cardEnabled by remember { mutableStateOf(UserPrefs.statusCardEnabled) }
    var darkMode by remember { mutableStateOf(UserPrefs.colorMode.coerceIn(0, 2)) }
    var crashCount by remember { mutableStateOf(CrashHandler.crashFiles().size) }
    val partnerName = UserPrefs.partnerName.ifBlank { "未绑定" }
    val bound = UserPrefs.pairId > 0
    val demo = UserPrefs.demoMode

    // 权限状态
    val usageOk = PermissionHelper.hasUsageAccess(context)
    val notifOk = PermissionHelper.hasNotificationListener(context)
    val policyOk = PermissionHelper.hasNotificationPolicyAccess(context)

    KernelScreen(title = "我的") {
        // 分组1：共享与绑定
        item {
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
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
                OverlayDropdownPreference(
                    title = "主题模式",
                    summary = "跟随系统 / 浅色 / 深色",
                    items = listOf("跟随系统", "浅色", "深色"),
                    startAction = { PrefIcon(Icons.Filled.Settings, "主题模式") },
                    selectedIndex = darkMode,
                    onSelectedIndexChange = { v ->
                        darkMode = v
                        UserPrefs.colorMode = v
                    }
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
                Button(
                    onClick = {
                        Logs.i("Settings", "退出登录：重置绑定与授权状态")
                        UserPrefs.token = null
                        UserPrefs.demoMode = false
                        UserPrefs.sharingEnabled = false
                        UserPrefs.pairId = 0
                        UserPrefs.privacyConsented = false
                        UserPrefs.partnerName = ""
                        StatusSyncManager.disconnect()
                        ProfileRuntime.clearSession()
                        StatusForegroundService.stop(context)
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("退出登录")
                }
                Text(
                    "退出仅清除本地登录状态，服务端数据保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
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
