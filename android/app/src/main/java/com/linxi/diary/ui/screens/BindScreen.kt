package com.linxi.diary.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.ApiClient
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 绑定流程（与主题一致的 miuix 风格）：
 * 生成邀请码 / 输入邀请码，绑定后进入主界面。
 */
@Composable
fun BindScreen(onBound: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) }
    var inviteCode by remember { mutableStateOf("") }
    var myCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun createInvite() {
        scope.launch {
            busy = true; error = null
            runCatching {
                val resp = ApiClient.postJson("/pair/create-invite", JSONObject())
                myCode = resp.getString("invite_code")
            }.onFailure { e -> error = e.message }
            busy = false
        }
    }

    fun bind() {
        scope.launch {
            busy = true; error = null
            runCatching {
                val resp = ApiClient.postJson("/pair/bind",
                    JSONObject().put("invite_code", inviteCode))
                UserPrefs.demoMode = false
                UserPrefs.pairId = resp.optLong("pair_id")
                val partner = resp.optJSONObject("partner")
                UserPrefs.partnerName = partner?.optString("nickname") ?: ""
                UserPrefs.privacyConsented = false
                UserPrefs.sharingEnabled = false
                onBound()
            }.onFailure { e -> error = e.message }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("绑定你的伴侣", color = colorScheme.onBackground, fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("邀请码 1 小时有效，仅支持双人绑定",
            color = colorScheme.onSurface.copy(alpha = 0.78f), fontSize = 14.sp)

        Spacer(Modifier.height(28.dp))

        // 模式切换 tab
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeCard("我创建", mode == 0, Modifier.weight(1f)) { mode = 0 }
            ModeCard("我输入", mode == 1, Modifier.weight(1f)) { mode = 1 }
        }

        Spacer(Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    0 -> if (myCode.isEmpty()) {
                        Text("创建邀请码，发给对方绑定",
                            color = colorScheme.onSurface.copy(alpha = 0.78f), fontSize = 14.sp)
                        Button(
                            onClick = { createInvite() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "生成中…" else "生成邀请码") }
                    } else {
                        Text("你的邀请码",
                            color = colorScheme.onSurface.copy(alpha = 0.78f), fontSize = 14.sp)
                        Text(
                            myCode,
                            color = colorScheme.primary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("让伴侣在对方手机上输入此码",
                            color = colorScheme.onSurface.copy(alpha = 0.78f), fontSize = 14.sp)
                    }
                    1 -> {
                        TextField(
                            value = inviteCode, onValueChange = { inviteCode = it },
                            label = "6 位邀请码",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { bind() },
                            enabled = inviteCode.length == 6 && !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "绑定中…" else "绑定") }
                    }
                }
                error?.let {
                    Text(it, color = colorScheme.error, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "跳过（开发调试）",
            color = colorScheme.primary,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // 调试模式只显示本地示例，不上传、不提醒、不参与真实绑定。
                    UserPrefs.demoMode = true
                    UserPrefs.pairId = 1
                    UserPrefs.partnerName = "调试伴侣"
                    UserPrefs.privacyConsented = false
                    UserPrefs.sharingEnabled = false
                    StatusSyncManager.disconnect()
                    StatusForegroundService.stop(context)
                    onBound()
                }
                .padding(12.dp)
        )
    }
}

@Composable
private fun ModeCard(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier,
        onClick = onClick,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = if (selected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.78f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}