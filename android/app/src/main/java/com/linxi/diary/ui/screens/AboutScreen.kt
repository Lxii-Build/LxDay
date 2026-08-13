package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.ui.theme.BrandRed
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val REPO_URL = "https://github.com/Lxii-Build/LxDay"
private const val SITE_URL = "https://love.lxii.cc"

/** 更新信息（/app/latest 的 data）。 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val force: Boolean,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
) {
    companion object {
        fun fromJson(j: JSONObject): UpdateInfo {
            val v = j.optJSONObject("version") ?: JSONObject()
            return UpdateInfo(
                hasUpdate = j.optBoolean("has_update"),
                force = j.optBoolean("force"),
                versionName = v.optString("version_name"),
                apkUrl = v.optString("apk_url"),
                notes = v.optString("notes"),
            )
        }
    }
}

/** 关于页（仿 KernelSU）：图标 + 名称 + 版本 + 仓库/官网链接 + 检查更新 + 退出登录。 */
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
                    startAction = { AboutIcon(Icons.Rounded.SystemUpdate) },
                    onClick = {
                        if (!checking) {
                            checking = true; upToDate = false
                            scope.launch {
                                runCatching { UpdateInfo.fromJson(ApiClient.checkUpdate(BuildConfig.VERSION_CODE)) }
                                    .onSuccess { info ->
                                        Logs.i("Update", "check update: hasUpdate=${info.hasUpdate} force=${info.force}")
                                        if (info.hasUpdate) update = info else upToDate = true
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
                    startAction = { AboutIcon(Icons.Rounded.Code) },
                    onClick = { runCatching { uriHandler.openUri(REPO_URL) } },
                )
                ArrowPreference(
                    title = "官网",
                    summary = SITE_URL,
                    startAction = { AboutIcon(Icons.Rounded.Language) },
                    onClick = { runCatching { uriHandler.openUri(SITE_URL) } },
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
                                runCatching { ApiClient.postJson("/pair/unbind", JSONObject()) }
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

/** 更新提示弹窗；force=true 时不可取消。 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    OverlayDialog(
        show = true,
        title = "发现新版本 ${info.versionName}",
        onDismissRequest = if (info.force) null else onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (info.notes.isNotBlank()) {
                Text(info.notes, color = colorScheme.onSurfaceVariantSummary)
            } else {
                Text("修复问题并优化体验，建议更新。", color = colorScheme.onSurfaceVariantSummary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!info.force) {
                    LxButton("稍后", onClick = onDismiss, variant = LxButtonVariant.Neutral, modifier = Modifier.weight(1f))
                }
                LxButton(
                    text = "去更新",
                    onClick = { runCatching { uriHandler.openUri(info.apkUrl) } },
                    variant = LxButtonVariant.Positive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
