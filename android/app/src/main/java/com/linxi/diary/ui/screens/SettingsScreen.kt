package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linxi.diary.core.PermissionHelper
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.CrashHandler
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs

/**
 * Tab ④ 我的：绑定状态 / 知情授权 / 状态共享总开关 / 深色模式 / 常驻卡片开关 / 保活引导 / 退出登录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenConsent: () -> Unit = {},
    onOpenBind: () -> Unit = {}
) {
    val context = LocalContext.current
    var sharing by remember { mutableStateOf(UserPrefs.sharingEnabled) }
    var cardEnabled by remember { mutableStateOf(UserPrefs.statusCardEnabled) }
    var darkMode by remember { mutableStateOf(UserPrefs.darkMode) }
    val partnerName = UserPrefs.partnerName.ifBlank { "未绑定" }
    val bound = UserPrefs.pairId > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("我的", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // 绑定状态
        SettingRow("伴侣", partnerName)
        if (!bound) {
            TextButton(onClick = onOpenBind) { Text("去绑定") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 状态共享总开关
        SettingRow("状态共享") {
            Switch(checked = sharing, onCheckedChange = { on ->
                sharing = on
                UserPrefs.sharingEnabled = on
                if (!on) {
                    // 关闭：停止采集 + 本地清空（服务端数据仅停止新增）
                    DeviceStatusHolder_local.clear()
                    StatusSyncManager.pushNow()
                }
            })
        }
        if (!UserPrefs.privacyConsented) {
            TextButton(onClick = onOpenConsent) { Text("需先完成知情授权") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 主题设置（ColorMode：跟随系统/浅色/深色/深色AMOLED）
        SettingRow("主题模式") {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = when (darkMode) { 1 -> "浅色"; 2 -> "深色"; 3 -> "深色 AMOLED"; else -> "跟随系统" },
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).width(150.dp)
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色", 3 to "深色 AMOLED").forEach { (v, label) ->
                        DropdownMenuItem(text = { Text(label) },
                            onClick = {
                                darkMode = v
                                UserPrefs.colorMode = v
                                UserPrefs.darkMode = v
                                expanded = false
                            })
                    }
                }
            }
        }
        // 动态取色 / 固定种子色
        SettingRow("动态取色", desc = "关闭后使用固定情侣主题色") {
            var dynColor by remember { mutableStateOf(UserPrefs.keyColor == 0) }
            Switch(checked = dynColor, onCheckedChange = { on ->
                dynColor = on
                UserPrefs.keyColor = if (on) 0 else 0xFFB49EDE.toInt() // 粉紫种子
            })
        }

        // 常驻卡片开关
        SettingRow("常驻状态卡片") {
            Switch(checked = cardEnabled, onCheckedChange = { on ->
                cardEnabled = on
                UserPrefs.statusCardEnabled = on
                if (on) StatusForegroundService.start(context)
                else StatusForegroundService.stop(context)
            })
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 权限与保活引导
        Text("权限与保活", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (!PermissionHelper.hasUsageAccess(context)) {
            SettingRow("使用情况访问", desc = "识别前台 APP 与用量统计") {
                TextButton(onClick = { PermissionHelper.toUsageAccess(context) }) { Text("去开启") }
            }
        }
        if (!PermissionHelper.hasNotificationListener(context)) {
            SettingRow("通知使用权", desc = "识别音乐 + 卡片兜底") {
                TextButton(onClick = { PermissionHelper.toNotificationListener(context) }) { Text("去开启") }
            }
        }
        if (!PermissionHelper.hasNotificationPolicyAccess(context)) {
            SettingRow("勿扰访问", desc = "强制响铃可绕过勿扰") {
                TextButton(onClick = { PermissionHelper.toNotificationPolicy(context) }) { Text("去开启") }
            }
        }
        SettingRow("vivo/OPPO 自启动白名单", desc = "防后台被杀，保证同步") {
            TextButton(onClick = { PermissionHelper.toVendorAutoStart(context) }) { Text("去设置") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 调试区：崩溃日志 + 日志查看指引
        Text("调试", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        var crashCount by remember { mutableStateOf(CrashHandler.crashFiles().size) }
        SettingRow("崩溃日志", desc = "$crashCount 条 · 本机 app 私有目录") {
            TextButton(onClick = {
                crashCount = CrashHandler.crashFiles().size
                Logs.i("Settings", "崩溃日志数=$crashCount")
            }) { Text("刷新") }
        }
        SettingRow("查看运行日志", desc = "adb logcat -s Linxi:V —— 集中前缀 Linxi") {
        }
        TextButton(onClick = {
            CrashHandler.clearCrashes()
            crashCount = 0
        }) { Text("清空崩溃日志") }
        Spacer(Modifier.height(8.dp))

        // 退出登录
        Button(onClick = {
            UserPrefs.token = null
            UserPrefs.sharingEnabled = false
            StatusForegroundService.stop(context)
        }, colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()) {
            Text("退出登录")
        }
        Text("退出仅清除本地登录状态，服务端数据保留",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** 本地清空辅助（避免与 core 包冲突命名） */
private object DeviceStatusHolder_local {
    fun clear() {
        com.linxi.diary.core.DeviceStatusHolder.current = null
        com.linxi.diary.core.DeviceStatusHolder.partner = null
    }
}

@Composable
private fun SettingRow(
    title: String,
    desc: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            desc?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}
