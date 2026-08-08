package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.data.ApiClient
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 绑定流程：生成邀请码（作为创建者）或输入对方邀请码。
 * 绑定成功后进入知情授权页。
 */
@Composable
fun BindScreen(onBound: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) } // 0=创建邀请码 1=输入邀请码
    var inviteCode by remember { mutableStateOf("") }
    var myCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun createInvite() {
        scope.launch {
            busy = true
            error = null
            runCatching {
                val resp = ApiClient.postJson("/pair/create-invite", JSONObject())
                myCode = resp.getString("invite_code")
            }.onFailure { e -> error = e.message }
            busy = false
        }
    }

    fun bind() {
        scope.launch {
            busy = true
            error = null
            runCatching {
                val resp = ApiClient.postJson("/pair/bind",
                    JSONObject().put("invite_code", inviteCode))
                UserPrefs.pairId = resp.optLong("pair_id")
                val partner = resp.optJSONObject("partner")
                UserPrefs.partnerName = partner?.optString("nickname") ?: ""
                UserPrefs.sharingEnabled = true
                onBound()
            }.onFailure { e -> error = e.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {

        Text("绑定你的伴侣", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("邀请码 1 小时有效，仅支持双人绑定",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(24.dp))

        // 模式切换（用 FilterChip 保证 API 兼容）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = { Text("我创建") })
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = { Text("我输入") })
        }

        Spacer(Modifier.height(24.dp))

        when (mode) {
            0 -> {
                if (myCode.isEmpty()) {
                    Button(onClick = { createInvite() }, enabled = !busy) {
                        Text(if (busy) "生成中…" else "生成邀请码")
                    }
                } else {
                    Text("你的邀请码", style = MaterialTheme.typography.bodyMedium)
                    Text(myCode,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text("让伴侣在对方手机上输入此码", style = MaterialTheme.typography.bodySmall)
                }
            }
            1 -> {
                OutlinedTextField(value = inviteCode, onValueChange = { inviteCode = it },
                    label = { Text("6 位邀请码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(onClick = { bind() }, enabled = inviteCode.length == 6 && !busy) {
                    Text(if (busy) "绑定中…" else "绑定")
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            // 开发联调：跳过绑定直接进主界面
            UserPrefs.pairId = 1
            UserPrefs.partnerName = "调试伴侣"
            UserPrefs.privacyConsented = true
            UserPrefs.sharingEnabled = true
            onBound()
        }) { Text("跳过（开发调试）") }
    }
}
