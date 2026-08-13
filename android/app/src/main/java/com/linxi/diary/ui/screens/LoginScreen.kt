package com.linxi.diary.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.R
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
 * 登录成功写入 token 后进入绑定流程。
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onNavigateRegister: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val accountOk = account.isNotBlank()
    val passwordOk = password.isNotBlank()
    val canSubmit = accountOk && passwordOk && !busy

    fun submit() {
        scope.launch {
            busy = true; error = null
            runCatching {
                val data = ApiClient.login(account.trim(), password)
                UserPrefs.token = data.getString("token")
                UserPrefs.myUserId = data.optLong("user_id")
                // 登录 ≠ 重新绑定：若后台已存在绑定关系，写回 pairId，登录后直接进主页并同步数据。
                runCatching {
                    val p = ApiClient.pairStatus()
                    if (p.optBoolean("bound")) {
                        UserPrefs.pairId = p.optLong("pair_id")
                        UserPrefs.partnerName = p.optJSONObject("partner")?.optString("nickname") ?: ""
                    } else {
                        UserPrefs.pairId = 0
                    }
                }
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
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = "林曦日记",
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text("林曦日记", color = colorScheme.onBackground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "登录后同步你们的状态、待办与纪念日",
            color = colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(32.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(
                        value = account, onValueChange = { account = it },
                        label = "用户名或邮箱",
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!accountOk) FieldHint("请输入用户名或注册邮箱")
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(
                        value = password, onValueChange = { password = it },
                        label = "密码",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!passwordOk) FieldHint("请输入登录密码")
                }
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

        Spacer(Modifier.height(24.dp))
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
    }
}

@Composable
private fun FieldHint(text: String) {
    Text(
        text,
        color = colorScheme.onSurfaceVariantSummary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}
