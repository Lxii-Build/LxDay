package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.linxi.diary.data.ImagePrep
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.AnniversaryDatePolicy
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.MyProfile
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.util.UserPrefs
import java.time.LocalDate
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.theme.BrandBlue
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.util.Logs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 个人资料编辑页：头像（点击上传）、名称、性别（圆形单选）、简介、生日（个人，/profile/me）、
 * 纪念日（情侣共用，/pair/anniversary，仅已绑定时显示）。
 */
@Composable
fun ProfileEditScreen(
    onBack: () -> Unit,
    /** 触发选图（导航层跳到 PhotoPicker 单选模式 → 裁剪页）。 */
    onPickAvatar: () -> Unit = {},
    /** 裁剪完成后回传的文件；为 null 表示本次没有待上传的头像。 */
    croppedAvatar: java.io.File? = null,
    /** 消费掉 croppedAvatar，避免重组时重复上传。 */
    onCroppedConsumed: () -> Unit = {},
) {
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
    // 纪念日（情侣共用，走 /pair/anniversary，与生日不同）；仅已绑定时展示。
    val bound = UserPrefs.pairId > 0
    val today = remember { LocalDate.now() }
    var annYear by remember { mutableStateOf(today.year) }
    var annMonth by remember { mutableStateOf(today.monthValue) }
    var annDay by remember { mutableStateOf(today.dayOfMonth) }
    // 进入页展示的纪念日（未设置时缺省今天）；保存时与之对比，仅“有改动”才写 /pair/anniversary。
    var anniversaryBaseline by remember { mutableStateOf(today) }
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
        // 加载情侣共用纪念日：优先用已缓存的情侣资料，其次拉取 /pair/status；缺省保持今天。
        if (bound) {
            val loaded = ProfileRuntime.repository.profile.value?.anniversaryDate
                ?: runCatching {
                    val s = ApiClient.pairStatus()
                    if (s.isNull("anniversary_date")) null
                    else s.optString("anniversary_date").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                }.getOrNull()
            if (loaded != null) {
                anniversaryBaseline = loaded
                annYear = loaded.year; annMonth = loaded.monthValue; annDay = loaded.dayOfMonth
            }
        }
    }

    var avatarError by remember { mutableStateOf<String?>(null) }

    /**
     * 头像上传：接收已裁剪好的文件，直接传。
     *
     * 选图与裁剪由 [com.linxi.diary.ui.screens.PhotoPickerScreen] +
     * [AvatarCropScreen] 负责（管理员 Q13=C：头像也用和相册一样的选图器，并加圆形裁剪）。
     * 此前用系统 Photo Picker，与全 App 的 miuix 观感不一致；
     * 更早还用过 SAF 文件浏览器，是观感最差的一处。
     */
    fun uploadAvatarFile(file: java.io.File) {
        avatarUploading = true
        avatarError = null
        scope.launch {
            runCatching {
                ApiClient.uploadAvatar(file)
                MyProfile.fromJson(ApiClient.getMyProfile())
            }.onSuccess { applyProfile(it); avatarVersion++ }
                .onFailure {
                    // 失败必须有反馈：此前只写日志，UI 上「上传中…」消失、头像没变，
                    // 用户完全不知道发生了什么。
                    avatarError = it.message?.takeIf { m -> m.isNotBlank() } ?: "头像上传失败，请重试"
                    Logs.w("Profile", "upload avatar failed", it)
                }
            file.delete()
            avatarUploading = false
        }
    }

    // 裁剪页回来后自动上传。用 key 保证同一个文件只上传一次
    // （重组会重跑 LaunchedEffect 体，若不消费掉就会重复上传）。
    LaunchedEffect(croppedAvatar) {
        val file = croppedAvatar ?: return@LaunchedEffect
        onCroppedConsumed()
        uploadAvatarFile(file)
    }

    ProfileEditContent(
        nickname = nickname, onNickname = { nickname = it },
        gender = gender, onGender = { gender = it },
        signature = signature, onSignature = { signature = it },
        year = year, month = month, day = day,
        onYear = { year = it }, onMonth = { month = it }, onDay = { day = it },
        showAnniversary = bound,
        annYear = annYear, annMonth = annMonth, annDay = annDay,
        onAnnYear = { annYear = it }, onAnnMonth = { annMonth = it }, onAnnDay = { annDay = it },
        avatarUrl = profile?.avatarUrl, avatarVersion = avatarVersion,
        avatarUploading = avatarUploading,
        onPickAvatar = { if (!avatarUploading) onPickAvatar() },
        // 头像上传失败的原因优先于表单错误展示（用户刚做的动作最相关）。
        error = avatarError ?: error,
        saving = saving,
        onBack = onBack,
        onSave = {
            saving = true
            scope.launch {
                val birthday = "%04d-%02d-%02d".format(year, month, day)
                runCatching { ApiClient.updateMyProfile(nickname.trim(), gender, signature.trim(), birthday) }
                    .onSuccess { updated ->
                        Logs.i("Profile", "profile updated")
                        applyProfile(MyProfile.fromJson(updated))
                        // 纪念日（情侣共用）：仅已绑定且相较基线有改动时，写入 /pair/anniversary。
                        val annSaved = if (bound) {
                            val ann = AnniversaryDatePolicy.clampDate(annYear, annMonth, annDay, LocalDate.now())
                            if (ann != anniversaryBaseline) {
                                runCatching { ApiClient.updateAnniversary(ann.toString()) }
                                    .onSuccess {
                                        Logs.i("Profile", "anniversary updated")
                                        ProfileRuntime.applyAuthoritative(it)
                                        anniversaryBaseline = ann
                                        annYear = ann.year; annMonth = ann.monthValue; annDay = ann.dayOfMonth
                                    }
                                    .onFailure { error = it.message; Logs.w("Profile", "update anniversary failed", it) }
                                    .isSuccess
                            } else true
                        } else true
                        if (annSaved) onBack()
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
    showAnniversary: Boolean,
    annYear: Int, annMonth: Int, annDay: Int,
    onAnnYear: (Int) -> Unit, onAnnMonth: (Int) -> Unit, onAnnDay: (Int) -> Unit,
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
            SmallTitle("生日")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepperRow("年", year, 1900, 2100, onYear)
                    StepperRow("月", month, 1, 12, onMonth)
                    StepperRow("日", day, 1, 31, onDay)
                }
            }
        }
        if (showAnniversary) {
            item {
                SmallTitle("纪念日")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "你们在一起的起点（双方共用）",
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        StepperRow("年", annYear, 1900, 2100, onAnnYear)
                        StepperRow("月", annMonth, 1, 12, onAnnMonth)
                        StepperRow("日", annDay, 1, 31, onAnnDay)
                    }
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

/**
 * 头像展示。
 *
 * 改用 Coil：原实现是 `produceState` + `BitmapFactory.decodeByteArray`，
 * produceState 默认跑在**主线程**，等于每次换头像都在主线程解码一张图；
 * 且没有任何内存/磁盘缓存，每次重组或页面重入都重新下载一遍。
 * Coil 负责缓存、降采样与线程调度，[AppImageLoader] 负责带上鉴权头。
 *
 * `version` 参与 cache key：头像换了但 URL 不变时（服务端原子替换同名文件）
 * 必须让 Coil 认为这是新图，否则会一直显示旧头像。
 */
@Composable
private fun NetworkAvatar(url: String?, version: Int, fallback: String, size: Dp, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.size(size).clip(CircleShape).background(BrandBlue.copy(alpha = 0.12f)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = coil3.request.ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey("$url#v$version")
                    .diskCacheKey("$url#v$version")
                    .build(),
                imageLoader = AppImageLoader.get(context),
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fallback.isNotBlank()) {
            Text(fallback.take(1), color = BrandBlue, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Icon(MiuixIcons.Contacts, contentDescription = "头像", tint = BrandBlue, modifier = Modifier.size(40.dp))
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


