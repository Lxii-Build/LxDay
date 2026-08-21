package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Email
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private val USERNAME_REGEX = Regex("^[A-Za-z]{3,20}$")

/**
 * 注册页：用户名（3-20 位大小写英文）+ 邮箱 + 验证码（发送验证码 60s 倒计时）+ 密码。
 * 注册成功写入 token 后进入绑定流程。
 */
@Composable
fun RegisterScreen(
    onRegistered: () -> Unit,
    onBack: () -> Unit,
) {
    // 注册是登录的子页：拦截系统返回键回到登录页，而非退出到桌面。
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    val usernameOk = USERNAME_REGEX.matches(username)
    val emailOk = email.contains("@") && email.contains(".")
    val passwordOk = password.length >= 6
    val codeOk = code.isNotBlank()
    val canSubmit = usernameOk && emailOk && codeOk && passwordOk && !busy

    fun sendCode() {
        if (!emailOk || countdown > 0 || sending) return
        scope.launch {
            sending = true; error = null
            runCatching { ApiClient.sendCode(email.trim()) }
                .onSuccess {
                    Logs.i("Auth", "verify code sent")
                    countdown = 60
                    while (countdown > 0) { delay(1000); countdown -= 1 }
                }
                .onFailure {
                    Logs.w("Auth", "send code failed", it)
                    error = it.message ?: "验证码发送失败"
                }
            sending = false
        }
    }

    fun submit() {
        scope.launch {
            busy = true; error = null
            runCatching {
                val data = ApiClient.register(username.trim(), email.trim(), code.trim(), password)
                UserPrefs.token = data.getString("token")
                UserPrefs.myUserId = data.optLong("user_id")
            }.onSuccess {
                Logs.i("Auth", "register success")
                onRegistered()
            }.onFailure {
                Logs.w("Auth", "register failed", it)
                error = it.message ?: "注册失败"
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
            "创建账号，绑定 TA 后一起记录状态与待办",
            color = colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(32.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(
                        value = username, onValueChange = { username = it },
                        label = "用户名（英文 3-20 位）",
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                MiuixIcons.Contacts,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!usernameOk) FieldHint("用户名需 3-20 位英文字母（大小写均可）")
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(
                        value = email, onValueChange = { email = it },
                        label = "邮箱",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        leadingIcon = {
                            Icon(
                                MiuixIcons.Email,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!emailOk) FieldHint(if (email.isBlank()) "请输入邮箱以接收验证码" else "邮箱格式不正确")
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TextField(
                                value = code, onValueChange = { code = it },
                                label = "验证码",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                leadingIcon = {
                                    Icon(
                                        MiuixIcons.Pin,
                                        contentDescription = null,
                                        tint = colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        LxButton(
                            text = when {
                                countdown > 0 -> "${countdown}s"
                                sending -> "发送中…"
                                else -> "发送验证码"
                            },
                            onClick = { sendCode() },
                            enabled = emailOk && countdown == 0 && !sending,
                            variant = LxButtonVariant.Neutral,
                            modifier = Modifier.width(112.dp),
                        )
                    }
                    if (!codeOk) FieldHint("请输入邮箱收到的验证码")
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(
                        value = password, onValueChange = { password = it },
                        label = "密码（至少 6 位）",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = {
                            Icon(
                                MiuixIcons.Lock,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!passwordOk) FieldHint("密码至少 6 位")
                }
                error?.let { Text(it, color = colorScheme.error, fontSize = 13.sp) }
                LxButton(
                    text = if (busy) "注册中…" else "注册",
                    onClick = { submit() },
                    enabled = canSubmit,
                    variant = LxButtonVariant.Positive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "已有账号？返回登录",
            color = colorScheme.primary,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() }
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
