package com.linxi.diary

import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.core.RingController
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.theme.BrandRed
import com.linxi.diary.util.Logs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 强制响铃全屏页：全屏 Intent 拉起后展示紧急信息，点击「我知道了」停止响铃。
 * manifest 已配置 showWhenLocked / turnScreenOn，锁屏也会亮屏显示。
 * 用 ComponentActivity（非 AppCompat），避免 AppCompat 主题继承要求。
 *
 * ## 为什么保留 XML 兜底（管理员 Q3=C）
 *
 * 这一屏要在**锁屏之上、息屏唤醒瞬间**渲染，是全 App 时序最敏感的一处。
 * Compose 首帧比 XML 慢（要初始化 Composition），且它跑在独立 Activity 里，
 * 会**第一次**在这个进程触发 miuix 主题初始化。
 *
 * 功能重要性（紧急找人）高于观感：正常路径走 miuix 保持统一观感，
 * 一旦 Compose/miuix 初始化抛异常就回退 XML —— 宁可丑，也不能让
 * 「对方紧急找我」整个哑掉。XML 那份只留最简结构，不再维护样式。
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                RingScreen(onDismiss = { dismissAndStop() })
            }
        } catch (t: Throwable) {
            Logs.e("Ring", "Compose 渲染失败，回退 XML 布局", t)
            fallbackToXml()
        }
    }

    private fun fallbackToXml() {
        runCatching {
            setContentView(R.layout.activity_ring)
            findViewById<Button>(R.id.btn_dismiss).setOnClickListener { dismissAndStop() }
        }.onFailure { Logs.e("Ring", "XML 兜底也失败", it) }
    }

    private fun dismissAndStop() {
        // 记下 ringId 再停：stop 会清空会话，之后就取不到了。
        val ringId = RingController.currentRingId
        // stop 需要 Context 才能还原音量/勿扰、取消振动与通知（见 RingController）。
        RingController.stop(applicationContext, "activity-dismiss")
        // 回执给发送方，结束其"响铃中"倒计时——否则对方以为没送达会反复响铃。
        StatusSyncManager.sendRingStopped(ringId)
        finish()
    }

    override fun onDestroy() {
        RingController.stop(applicationContext, "activity-destroy")
        super.onDestroy()
    }
}

/**
 * 响铃全屏内容。
 *
 * 刻意**不套 LinxiTheme/WallpaperHost**：那条链路会拉起 materialkolor 取色与
 * backdrop 液态玻璃，在锁屏唤醒瞬间是不必要的开销与风险。
 * 这里只用 miuix 基础组件 + 固定品牌色，首帧尽可能快。
 */
@Composable
private fun RingScreen(onDismiss: () -> Unit) {
    MiuixTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(BrandRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Alarm,
                        contentDescription = null,
                        tint = BrandRed,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "对方正在找你",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "紧急响铃请求",
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(40.dp))
                LxButton(
                    text = "我知道了",
                    onClick = onDismiss,
                    variant = LxButtonVariant.Positive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
