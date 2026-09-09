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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import com.linxi.diary.core.PermissionHelper
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.components.LxSurface
import com.linxi.diary.ui.components.LxSurfaceTone
import com.linxi.diary.ui.components.LxArrowPreference
import com.linxi.diary.ui.components.LxSwitchPreference
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
    onOpenAbout: () -> Unit = {},
    onOpenMusicSettings: () -> Unit = {},
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
        // 顶部只保留两个最常用入口，避免“编辑资料”和“伴侣”被埋在长列表里。
        item {
            LxSurface(Modifier.padding(top = 12.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                LxArrowPreference(
                    title = "我的资料",
                    summary = "头像、名称、性别、简介与生日",
                    startAction = { PrefIcon(MiuixIcons.ContactsCircle, "我的资料") },
                    onClick = onOpenProfileEdit,
                )
                LxArrowPreference(
                    title = if (bound) "已绑定 · $partnerName" else "绑定伴侣",
                    summary = if (bound) "共同相册、状态和待办已连接" else "绑定后才能同步状态与共同内容",
                    startAction = { PrefIcon(MiuixIcons.Contacts, "伴侣") },
                    onClick = { if (!bound) onOpenBind() },
                )
            }
        }

        item {
            LxSurface(Modifier.padding(top = 12.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                LxArrowPreference(
                    title = "音乐设置",
                    summary = "网易云账号、收藏、播放行为、歌词与播放胶囊",
                    startAction = { PrefIcon(MiuixIcons.Messages, "音乐设置") },
                    onClick = onOpenMusicSettings,
                )
            }
        }

        // 隐私与共享：把原先分散在三张卡里的状态、常驻卡片和静默通知放到一个语义组。
        item {
            LxSurface(Modifier.padding(top = 12.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                LxSwitchPreference(
                    title = "状态共享",
                    summary = if (demo) "调试模式不采集、不上传真实状态" else "总开关：状态、常驻卡片和动态通知",
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
                    },
                )
                LxSwitchPreference(
                    title = "状态卡片与静默提醒",
                    summary = when {
                        !sharing -> "先开启状态共享"
                        cardEnabled && quietNotify -> "常驻卡片、上下线提醒均已开启"
                        cardEnabled -> "常驻卡片已开启，动态提醒已关闭"
                        quietNotify -> "动态提醒已开启，常驻卡片已关闭"
                        else -> "关闭常驻卡片与上下线提醒"
                    },
                    startAction = { PrefIcon(MiuixIcons.Messages, "状态卡片与静默提醒") },
                    checked = cardEnabled || quietNotify,
                    enabled = !demo && UserPrefs.privacyConsented && sharing,
                    onCheckedChange = { on ->
                        cardEnabled = on
                        quietNotify = on
                        UserPrefs.statusCardEnabled = on
                        UserPrefs.quietNotifyEnabled = on
                        if (on) StatusForegroundService.start(context)
                        else StatusForegroundService.stop(context)
                    },
                )
                LxArrowPreference(
                    title = "知情同意与状态历史",
                    summary = if (!UserPrefs.privacyConsented) "需先完成知情授权" else if (demo) "调试模式不读取服务端历史" else "查看状态时间线与电量曲线",
                    startAction = { PrefIcon(MiuixIcons.Ok, "知情同意与状态历史") },
                    onClick = if (!UserPrefs.privacyConsented) onOpenConsent else if (demo) null else onOpenHistory,
                )
            }
        }

        // 连接与保活：单独入口打开完整自检，避免六个跳转项占满“我的”页。
        item {
            LxSurface(Modifier.padding(top = 12.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                LxArrowPreference(
                    title = "连接与后台运行",
                    summary = if (keepAliveFailed > 0) "$keepAliveFailed 项权限待处理 · 点击查看详情" else "权限和后台保活均已就绪",
                    startAction = { PrefIcon(MiuixIcons.Lock, "连接与后台运行") },
                    onClick = onOpenKeepAliveCheck,
                )
                LxArrowPreference(
                    title = "主题与界面",
                    summary = "配色、壁纸、动态取色与界面开关",
                    startAction = { PrefIcon(MiuixIcons.Settings, "主题与界面") },
                    onClick = onOpenAppearance,
                )
            }
        }

        item {
            LxSurface(Modifier.padding(top = 12.dp, bottom = 8.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                LxArrowPreference(
                    title = "诊断与关于",
                    summary = "发送日志、版本信息、检查更新和退出登录",
                    startAction = { PrefIcon(MiuixIcons.Info, "诊断与关于") },
                    onClick = onOpenAbout,
                )
                LxArrowPreference(
                    title = "发送日志",
                    summary = "保存到设备文件或分享诊断包",
                    startAction = { PrefIcon(MiuixIcons.Send, "发送日志") },
                    onClick = { showLogSheet = true },
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
                LxArrowPreference(
                    title = "保存日志",
                    summary = "保存诊断包到设备文件",
                    startAction = { PrefIcon(MiuixIcons.Backup, "保存日志") },
                    onClick = {
                        showLogSheet = false
                        saveLogLauncher.launch("linxi-diagnostics-${System.currentTimeMillis()}.zip")
                    }
                )
                LxArrowPreference(
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
