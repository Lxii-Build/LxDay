package com.linxi.diary.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.MyProfile
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.util.Logs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 个人资料编辑页：头像（点击上传）、名称、性别（圆形单选）、简介、生日/纪念日。
 * 对接 GET/PUT /profile/me。
 */
@Composable
fun ProfileEditScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<MyProfile?>(null) }
    var nickname by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf(0) }
    var signature by remember { mutableStateOf("") }
    var year by remember { mutableStateOf(2000) }
    var month by remember { mutableStateOf(1) }
    var day by remember { mutableStateOf(1) }
    var avatarUploading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var avatarVersion by remember { mutableStateOf(0) }

    fun applyProfile(p: MyProfile) {
        profile = p
        nickname = p.nickname
        gender = p.gender
        signature = p.signature
        val parts = p.birthday.split("-")
        year = parts.getOrNull(0)?.toIntOrNull() ?: 2000
        month = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 12)
        day = (parts.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, 31)
    }

    LaunchedEffect(Unit) {
        runCatching { MyProfile.fromJson(ApiClient.getMyProfile()) }
            .onSuccess { applyProfile(it) }
            .onFailure { error = it.message; Logs.w("Profile", "load my profile failed", it) }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            avatarUploading = true
            scope.launch {
                runCatching {
                    val ext = context.contentResolver.getType(uri)?.substringAfterLast('/')?.lowercase() ?: "img"
                    val file = java.io.File(context.cacheDir, "avatar_src_${System.currentTimeMillis()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    ApiClient.uploadAvatar(file)
                    MyProfile.fromJson(ApiClient.getMyProfile())
                }.onSuccess { applyProfile(it); avatarVersion++ }
                    .onFailure { Logs.w("Profile", "upload avatar failed", it) }
                avatarUploading = false
            }
        }
    }

    ProfileEditContent(
        nickname = nickname, onNickname = { nickname = it },
        gender = gender, onGender = { gender = it },
        signature = signature, onSignature = { signature = it },
        year = year, month = month, day = day,
        onYear = { year = it }, onMonth = { month = it }, onDay = { day = it },
        avatarUrl = profile?.avatarUrl, avatarVersion = avatarVersion,
        avatarUploading = avatarUploading,
        onPickAvatar = {
            if (!avatarUploading) avatarPicker.launch(
                arrayOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/heif", "image/heic")
            )
        },
        error = error,
        saving = saving,
        onBack = onBack,
        onSave = {
            saving = true
            scope.launch {
                val birthday = "%04d-%02d-%02d".format(year, month, day)
                runCatching { ApiClient.updateMyProfile(nickname.trim(), gender, signature.trim(), birthday) }
                    .onSuccess {
                        Logs.i("Profile", "profile updated")
                        applyProfile(MyProfile.fromJson(it))
                        onBack()
                    }
                    .onFailure { error = it.message; Logs.w("Profile", "update profile failed", it) }
                saving = false
            }
        },
    )
}

@Composable
private fun ProfileEditContent(
    nickname: String, onNickname: (String) -> Unit,
    gender: Int, onGender: (Int) -> Unit,
    signature: String, onSignature: (String) -> Unit,
    year: Int, month: Int, day: Int,
    onYear: (Int) -> Unit, onMonth: (Int) -> Unit, onDay: (Int) -> Unit,
    avatarUrl: String?, avatarVersion: Int, avatarUploading: Boolean,
    onPickAvatar: () -> Unit,
    error: String?, saving: Boolean,
    onBack: () -> Unit, onSave: () -> Unit,
) {
    KernelScreen(title = "编辑资料", navigationIcon = { BackAction(onBack) }) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NetworkAvatar(url = avatarUrl, version = avatarVersion, fallback = nickname, size = 96.dp, onClick = onPickAvatar)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (avatarUploading) "上传中…" else "点击更换头像",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.clickable { onPickAvatar() },
                )
            }
        }
        item {
            SmallTitle("名称")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    TextField(value = nickname, onValueChange = onNickname, label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            SmallTitle("性别")
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    GenderOption("男", gender == 1) { onGender(1) }
                    GenderOption("女", gender == 2) { onGender(2) }
                    GenderOption("保密", gender == 0) { onGender(0) }
                }
            }
        }
        item {
            SmallTitle("简介")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    TextField(value = signature, onValueChange = onSignature, label = "一句话介绍自己", modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            SmallTitle("生日 / 纪念日")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepperRow("年", year, 1900, 2100, onYear)
                    StepperRow("月", month, 1, 12, onMonth)
                    StepperRow("日", day, 1, 31, onDay)
                }
            }
        }
        item {
            error?.let { Text(it, color = colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
            LxButton(
                text = if (saving) "保存中…" else "保存",
                onClick = onSave,
                enabled = nickname.isNotBlank() && !saving,
                variant = LxButtonVariant.Positive,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun NetworkAvatar(url: String?, version: Int, fallback: String, size: Dp, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, url, version) {
        value = null
        if (!url.isNullOrBlank()) {
            val bytes = ApiClient.downloadBytes(url)
            value = bytes?.let {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(BrandBlue.copy(alpha = 0.12f)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(bitmap = b, contentDescription = "头像", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            fallback.isNotBlank() -> Text(fallback.take(1), color = BrandBlue, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            else -> Icon(Icons.Rounded.Person, contentDescription = "头像", tint = BrandBlue, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun GenderOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape)
            .background(if (selected) BrandBlue else colorScheme.onBackground.copy(alpha = 0.08f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = if (selected) Color.White else colorScheme.onSurface)
    }
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.weight(1f))
        StepBtn("−", value > min) { onChange((value - 1).coerceAtLeast(min)) }
        Text("  $value  ", color = colorScheme.onSurface, fontSize = 15.sp)
        StepBtn("+", value < max) { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun StepBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(colorScheme.onBackground.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, color = colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
    }
}


