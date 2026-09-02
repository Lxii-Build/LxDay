package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.BuildConfig
import com.linxi.diary.R
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.theme.BrandRed
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.SimpleDateFormat
import java.util.Locale

private const val REPO_URL = "https://github.com/Lxii-Build/LxDay"

data class ReleaseHistory(
    val versionName: String,
    val versionCode: Int,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val htmlUrl: String,
    val prerelease: Boolean,
    val publishedAt: String,
)

/** GitHub Releases 更新信息（包含当前候选版本与历代更新日志）。 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val force: Boolean = false,
    val channel: String,
    val version: ReleaseHistory?,
    val history: List<ReleaseHistory>,
) {
    companion object {
        fun fromJson(j: JSONObject): UpdateInfo {
            fun parseRelease(v: JSONObject): ReleaseHistory = ReleaseHistory(
                versionName = v.optString("version_name", v.optString("tag_name")),
                versionCode = v.optInt("version_code"),
                name = v.optString("name"),
                notes = v.optString("notes"),
                apkUrl = v.optString("apk_url"),
                htmlUrl = v.optString("html_url"),
                prerelease = v.optBoolean("prerelease"),
                publishedAt = v.optString("published_at"),
            )

            val historyJson = j.optJSONArray("history")
            val history = buildList {
                if (historyJson != null) {
                    for (i in 0 until historyJson.length()) {
                        historyJson.optJSONObject(i)?.let { add(parseRelease(it)) }
                    }
                }
            }
            val candidate = j.optJSONObject("version")?.let(::parseRelease)
            return UpdateInfo(
                hasUpdate = j.optBoolean("has_update"),
                // 服务端明确不返回强制更新；这里也不信任旧服务端的 force 字段。
                force = false,
                channel = j.optString("channel", "stable"),
                version = candidate,
                history = history,
            )
        }
    }
}

private fun formatReleaseTime(raw: String): String {
    if (raw.isBlank()) return "发布时间未知"
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(raw)
    }.getOrNull()
    return if (parsed == null) raw else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(parsed)
    }
}

/** 关于页：版本、仓库根更新日志、仓库与账号操作。 */
@Composable
fun AboutScreen(onBack: () -> Unit, onLogout: () -> Unit, onUnbound: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var upToDate by remember { mutableStateOf(false) }
    var showUnbind by remember { mutableStateOf(false) }
    var unbinding by remember { mutableStateOf(false) }
    var unbindError by remember { mutableStateOf<String?>(null) }

    KernelScreen(title = "关于", navigationIcon = { BackAction(onBack) }) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = "应用图标",
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(20.dp)),
                )
                Spacer(Modifier.height(12.dp))
                Text("林曦日记", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text(
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item {
            Card(Modifier.padding(top = 8.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = "检查更新",
                    summary = when {
                        checking -> "检查中…"
                        upToDate -> "已是最新版本"
                        else -> "当前版本 v${BuildConfig.VERSION_NAME}"
                    },
                    startAction = { AboutIcon(MiuixIcons.Update) },
                    onClick = {
                        if (!checking) {
                            checking = true; upToDate = false
                            scope.launch {
                                runCatching {
                                    UpdateInfo.fromJson(
                                        ApiClient.checkUpdate(BuildConfig.VERSION_CODE, BuildConfig.UPDATE_CHANNEL),
                                    )
                                }
                                    .onSuccess { info ->
                                        Logs.i("Update", "check update: hasUpdate=${info.hasUpdate} channel=${info.channel}")
                                        // 检查结果统一进弹窗：即使当前已是最新，也能查看完整历史 Changelog。
                                        update = info
                                        upToDate = !info.hasUpdate
                                    }
                                    .onFailure { Logs.w("Update", "check update failed", it) }
                                checking = false
                            }
                        }
                    },
                )
                ArrowPreference(
                    title = "开源仓库",
                    summary = REPO_URL,
                    startAction = { AboutIcon(MiuixIcons.File) },
                    onClick = { runCatching { uriHandler.openUri(REPO_URL) } },
                )
                ArrowPreference(
                    title = "查看更新日志",
                    summary = "仓库 CHANGELOG · ${if (BuildConfig.UPDATE_CHANNEL == "testing") "含测试版" else "正式版"}",
                    startAction = { AboutIcon(MiuixIcons.Update) },
                    onClick = {
                        if (!checking) {
                            checking = true
                            scope.launch {
                                runCatching {
                                    UpdateInfo.fromJson(
                                        ApiClient.checkUpdate(BuildConfig.VERSION_CODE, BuildConfig.UPDATE_CHANNEL),
                                    )
                                }.onSuccess {
                                    update = it
                                    upToDate = !it.hasUpdate
                                }
                                    .onFailure { Logs.w("Update", "load changelog failed", it) }
                                checking = false
                            }
                        }
                    },
                )
            }
        }
        item {
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                if (UserPrefs.pairId > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(enabled = !unbinding) { showUnbind = true }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (unbinding) "解除中…" else "解除绑定", color = colorScheme.onBackground, fontWeight = FontWeight.Medium)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            Logs.i("Auth", "logout: clear token/pairId/consent")
                            UserPrefs.token = null
                            UserPrefs.pairId = 0
                            UserPrefs.demoMode = false
                            UserPrefs.sharingEnabled = false
                            UserPrefs.privacyConsented = false
                            StatusSyncManager.disconnect()
                            ProfileRuntime.clearSession()
                            StatusForegroundService.stop(context)
                            onLogout()
                        }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("退出登录", color = BrandRed, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    update?.let { info ->
        UpdateDialog(info = info, onDismiss = { update = null })
    }

    if (showUnbind) {
        OverlayDialog(
            show = true,
            title = "解除绑定",
            onDismissRequest = { if (!unbinding) showUnbind = false },
            renderInRootScaffold = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "解除后你和对方都会回到绑定页，可重新绑定。确定解除当前绑定？",
                    color = colorScheme.onSurfaceVariantSummary,
                )
                unbindError?.let { msg ->
                    Text(msg, color = colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LxButton(
                        "取消",
                        onClick = { showUnbind = false },
                        variant = LxButtonVariant.Neutral,
                        enabled = !unbinding,
                        modifier = Modifier.weight(1f),
                    )
                    LxButton(
                        text = if (unbinding) "解除中…" else "确定解除",
                        onClick = {
                            scope.launch {
                                unbinding = true
                                unbindError = null
                                // 必须先确认服务端解绑成功再清本地：
                                // 此前 runCatching 的结果被直接丢弃，网络失败时服务端仍是绑定状态、
                                // 本地却已清空并跳回绑定页 —— 双端状态分裂，重新登录也回不去。
                                val ok = runCatching {
                                    ApiClient.postJson("/pair/unbind", JSONObject())
                                }.isSuccess
                                if (!ok) {
                                    unbindError = "解除绑定失败，请检查网络后重试"
                                    unbinding = false
                                    return@launch
                                }
                                UserPrefs.pairId = 0
                                UserPrefs.partnerName = ""
                                UserPrefs.sharingEnabled = false
                                StatusSyncManager.disconnect()
                                StatusForegroundService.stop(context)
                                ProfileRuntime.clearSession()
                                unbinding = false
                                showUnbind = false
                                onUnbound()
                            }
                        },
                        variant = LxButtonVariant.Negative,
                        enabled = !unbinding,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        tint = colorScheme.onBackground,
        modifier = Modifier.padding(end = 6.dp),
    )
}

/** 更新提示/历代更新日志弹窗。预发行版始终可稍后处理，不执行强制更新。 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val candidate = info.version
    val canUpdate = info.hasUpdate && candidate?.apkUrl?.isNotBlank() == true
    val scrollState = rememberScrollState()
    OverlayDialog(
        show = true,
        title = if (info.hasUpdate && candidate != null) "发现新版本 ${candidate.versionName}" else "更新日志",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 560.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!info.hasUpdate || candidate == null) {
                    Text("当前已是最新版本", color = colorScheme.onSurfaceVariantSummary)
                }
                if (info.hasUpdate && candidate != null) {
                    Text(
                        "${if (candidate.prerelease) "测试版" else "正式版"} · 发布时间 ${formatReleaseTime(candidate.publishedAt)}",
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    if (candidate.notes.isNotBlank()) {
                        ChangelogText(candidate.notes)
                    } else {
                        Text("修复问题并优化体验，建议更新。", color = colorScheme.onSurfaceVariantSummary)
                    }
                    if (!canUpdate) {
                        Text("该版本暂未附 APK，更新按钮不可用。", color = colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (info.history.isNotEmpty()) {
                    Text("历史版本", fontWeight = FontWeight.Medium, color = colorScheme.onBackground)
                    info.history.forEach { release ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "v${release.versionName} · ${if (release.prerelease) "测试版" else "正式版"}",
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onBackground,
                                )
                                Text(
                                    "${formatReleaseTime(release.publishedAt)} · versionCode ${release.versionCode}",
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                                if (release.notes.isNotBlank()) {
                                    ChangelogText(release.notes, compact = true)
                                }
                            }
                        }
                    }
                } else if (!info.hasUpdate) {
                    Text("暂无更新日志。", color = colorScheme.onSurfaceVariantSummary)
                }
            }

            // 固定操作栏：只有上面的更新日志滚动，按钮始终留在弹窗底部可操作。
            Box(
                Modifier.fillMaxWidth().height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LxButton(
                        "取消",
                        onClick = onDismiss,
                        variant = LxButtonVariant.Neutral,
                        modifier = Modifier.weight(1f),
                    )
                    LxButton(
                        text = "更新",
                        onClick = {
                            val url = candidate?.apkUrl.orEmpty()
                            if (canUpdate && url.isNotBlank()) {
                                runCatching { uriHandler.openUri(url) }
                                onDismiss()
                            }
                        },
                        variant = LxButtonVariant.Positive,
                        enabled = canUpdate,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangelogText(markdown: String, compact: Boolean = false) {
    Text(
        buildAnnotatedString {
            formatChangelog(markdown).forEach { segment ->
                withStyle(
                    SpanStyle(
                        fontWeight = if (segment.bold) FontWeight.Bold else FontWeight.Normal,
                    ),
                ) {
                    append(segment.text)
                }
            }
        },
        color = colorScheme.onSurfaceVariantSummary,
        fontSize = if (compact) 13.sp else 14.sp,
    )
}
