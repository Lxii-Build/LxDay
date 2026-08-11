package com.linxi.diary.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.ApiClient
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 登录页：账号（用户名或邮箱）+ 密码 + 登录按钮 + 跳转注册。
 * 登录成功写入 token 后进入绑定流程。debug 下保留“跳过”入口。
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onNavigateRegister: () -> Unit,
    onSkipDebug: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val canSubmit = account.isNotBlank() && password.isNotBlank() && !busy

    fun submit() {
        scope.launch {
            busy = true; error = null
            runCatching {
                val data = ApiClient.login(account.trim(), password)
                UserPrefs.token = data.getString("token")
                UserPrefs.myUserId = data.optLong("user_id")
            }.onSuccess {
                Logs.i("Auth", "login success")
                onLoggedIn()
            }.onFailure {
                Logs.w("Auth", "login failed", it)
                error = it.message ?: "登录失败"
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("欢迎回来", color = colorScheme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("登录林曦日记，继续记录你们的日常", color = colorScheme.onSurface.copy(alpha = 0.78f), fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TextField(
                    value = account, onValueChange = { account = it },
                    label = "用户名或邮箱",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = password, onValueChange = { password = it },
                    label = "密码",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = colorScheme.error, fontSize = 13.sp) }
                LxButton(
                    text = if (busy) "登录中…" else "登录",
                    onClick = { submit() },
                    enabled = canSubmit,
                    variant = LxButtonVariant.Positive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "还没有账号？点击注册",
            color = colorScheme.primary,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onNavigateRegister() }
                .padding(8.dp),
        )

        if (com.linxi.diary.BuildConfig.DEBUG) {
            Spacer(Modifier.height(8.dp))
            Text(
                "跳过（开发调试）",
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSkipDebug() }
                    .padding(8.dp),
            )
        }
    }
}
