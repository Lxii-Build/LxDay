package com.linxi.diary.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linxi.diary.core.PermissionHelper
import com.linxi.diary.status.Vendor
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.theme.BrandRed
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 保活自检页（Q37=B）。
 *
 * ## 为什么需要它
 *
 * 这类「双人状态同步」App 的可靠性 90% 取决于用户有没有把保活权限开全。
 * 而此前设置页里是**一堆没有状态反馈的跳转按钮**——用户点完也不知道到底开没开，
 * 更不知道少开哪一项会导致什么后果。
 *
 * 管理员报的"状态总是显示不好"，有相当一部分可能就是某一项没开
 *（尤其一加 ColorOS 的自启动管理与后台冻结）。
 * 有了逐项体检，这个模糊问题就变成"哦，自启动没开"这种可定位的结论。
 *
 * 每一项都写清「不开会怎样」，而不是只标个红叉。
 */
@Composable
fun KeepAliveCheckScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    // 从设置页返回时要重新体检（用户可能刚开了权限）。
    var tick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val items = remember(tick) { buildCheckItems(context) }
    val failed = items.count { !it.ok }

    KernelScreen(
        title = "同步自检",
        navigationIcon = { BackAction(onBack) },
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (failed == 0) "全部就绪" else "有 $failed 项待开启",
                        style = MiuixTheme.textStyles.headline1,
                        color = if (failed == 0) MiuixTheme.colorScheme.onSurface else BrandRed,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (failed == 0) {
                            "状态同步所需的权限都已开启。若仍觉得同步不及时，" +
                                "可以在主页看状态卡下方的「更新于…」判断是没同步还是数据本身有问题。"
                        } else {
                            "下面标红的项目会直接影响状态同步的及时性与准确性，建议逐项开启。"
                        },
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        items.forEach { item ->
            item {
                CheckRow(item)
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("为什么需要这些权限", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本应用靠一个常驻前台服务周期采集本机状态并上报。" +
                            "国产 ROM（尤其 ColorOS/OriginOS/MIUI）会积极冻结后台进程，" +
                            "一旦服务被杀，对方看到的就是你几十分钟前的旧状态。" +
                            "Android 15 起 dataSync 类型的前台服务还有单次运行时长上限，" +
                            "我们靠系统闹钟把它拉回来——而闹钟同样需要不被省电策略拦住。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.ok) MiuixTheme.colorScheme.primary
                        else BrandRed.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item.ok) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = "已开启",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Text("!", color = BrandRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (item.ok) "已开启" else item.consequence,
                    fontSize = 13.sp,
                    color = if (item.ok) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else BrandRed.copy(alpha = 0.9f),
                )
            }
            if (!item.ok) {
                LxButton(
                    text = "去开启",
                    onClick = { item.open(context) },
                    variant = LxButtonVariant.Positive,
                    cornerRadius = 12,
                )
            }
        }
    }
}

/** 一个体检项。`consequence` 写"不开会怎样"，比只标红叉有用得多。 */
private data class CheckItem(
    val title: String,
    val ok: Boolean,
    val consequence: String,
    val open: (Context) -> Unit,
)

private fun buildCheckItems(context: Context): List<CheckItem> {
    val vendor = Vendor.fromManufacturer(android.os.Build.MANUFACTURER)
    val list = mutableListOf(
        CheckItem(
            title = "使用情况访问",
            ok = PermissionHelper.hasUsageAccess(context),
            consequence = "未开启时对方看不到你在用什么应用（这一项也是状态历史里前台应用为空的原因）",
            open = { PermissionHelper.toUsageAccess(it) },
        ),
        CheckItem(
            title = "电池优化白名单",
            ok = PermissionHelper.hasIgnoreBattery(context),
            consequence = "未加入时系统会冻结后台进程，状态同步会长时间中断",
            open = { PermissionHelper.toBatteryOptimization(it) },
        ),
        CheckItem(
            title = "通知使用权",
            ok = PermissionHelper.hasNotificationListener(context),
            consequence = "未开启时识别不到正在播放的音乐，常驻卡片被清理后也无法自动恢复",
            open = { PermissionHelper.toNotificationListener(it) },
        ),
        CheckItem(
            title = "勿扰访问",
            ok = PermissionHelper.hasNotificationPolicyAccess(context),
            consequence = "未开启时「紧急找人」的响铃在勿扰模式下会被静音",
            open = { PermissionHelper.toNotificationPolicy(it) },
        ),
    )
    // 厂商自启动管理没有可靠的查询接口，只能引导 + 让用户自己确认。
    // 一加/OPPO 走 ColorOS，是管理员的测试机，所以这条要显眼。
    if (vendor != Vendor.OTHER) {
        list += CheckItem(
            title = "自启动 / 后台运行管理",
            ok = false, // 无法程序化检测，一律提示去确认
            consequence = "${vendorLabel(vendor)} 会限制后台自启，请在系统设置里允许本应用自启动与后台运行",
            open = { PermissionHelper.toVendorAutoStart(it) },
        )
    }
    return list
}

private fun vendorLabel(vendor: Vendor): String = when (vendor) {
    // 一加走 ColorOS，正是管理员的测试机（一加 15 / Android 16）。
    Vendor.COLOROS -> "你的手机（一加/OPPO/realme）"
    Vendor.ORIGINOS -> "你的手机（vivo/iQOO）"
    Vendor.OTHER -> "部分安卓系统"
}
