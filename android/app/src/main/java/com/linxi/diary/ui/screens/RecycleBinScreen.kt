package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.data.PhotoItem
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.components.LxConfirmDialog
import com.linxi.diary.ui.theme.BrandRed
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 回收站。
 *
 * 此前只能「恢复」，不能彻底删除——而服务端全链路软删、磁盘文件从不删除，
 * 于是磁盘只涨不跌。现在（Q21=C）：
 *   - 单张「彻底删除」+ 顶部「清空回收站」，都会**真删磁盘文件**
 *   - 每张显示「还剩 N 天自动删除」，让用户知道这不是永久保险箱
 *   - 两个删除动作都走 LxConfirmDialog：红色确认按钮 + 1 秒冷静期
 */
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var keepDays by remember { mutableStateOf(-1) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf(0L) }
    var purgeTarget by remember { mutableStateOf<PhotoItem?>(null) }
    var confirmPurgeAll by remember { mutableStateOf(false) }
    var purgeBusy by remember { mutableStateOf(false) }

    fun load(pull: Boolean = false) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            runCatching { ApiClient.recycledPhotosFull() }
                .onSuccess { result ->
                    photos = result.first
                    keepDays = result.second
                }
                .onFailure { error = albumFriendlyError(it) }
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { load() }

    purgeTarget?.let { target ->
        LxConfirmDialog(
            show = true,
            title = "彻底删除",
            message = "这张照片将被永久删除，服务器上的原图与缩略图会一并清除，之后无法恢复。",
            confirmText = "永久删除",
            destructive = true,
            busy = purgeBusy,
            busyText = "删除中…",
            onConfirm = {
                purgeBusy = true
                scope.launch {
                    runCatching { ApiClient.purgePhoto(target.id) }
                        .onFailure { error = albumFriendlyError(it) }
                    purgeBusy = false
                    purgeTarget = null
                    load()
                }
            },
            onDismiss = { if (!purgeBusy) purgeTarget = null },
        )
    }

    if (confirmPurgeAll) {
        LxConfirmDialog(
            show = true,
            title = "清空回收站",
            message = "回收站里的 ${photos.size} 张照片将被永久删除，" +
                "服务器上的文件一并清除，之后无法恢复。",
            confirmText = "全部永久删除",
            destructive = true,
            busy = purgeBusy,
            busyText = "清空中…",
            onConfirm = {
                purgeBusy = true
                scope.launch {
                    runCatching { ApiClient.purgeRecycleBin() }
                        .onFailure { error = albumFriendlyError(it) }
                    purgeBusy = false
                    confirmPurgeAll = false
                    load()
                }
            },
            onDismiss = { if (!purgeBusy) confirmPurgeAll = false },
        )
    }

    KernelScreen(
        title = "回收站",
        navigationIcon = { BackAction(onBack) },
        isRefreshing = refreshing,
        onRefresh = { load(pull = true) },
        loading = loading,
    ) {
        if (photos.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    if (keepDays > 0) {
                        Text(
                            "删除的照片在回收站保留 $keepDays 天，之后自动永久删除。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    LxButton(
                        text = "清空回收站",
                        onClick = { confirmPurgeAll = true },
                        variant = LxButtonVariant.Negative,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        error?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(msg, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(12.dp))
                        LxButton(
                            text = "重试",
                            onClick = { load() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (!loading && error == null && photos.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("回收站是空的", style = MiuixTheme.textStyles.headline1)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "删掉的照片会先放到这里，可以随时恢复。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        items(photos, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                    ) {
                        AsyncImage(
                            model = p.displayUrl,
                            imageLoader = AppImageLoader.get(context),
                            contentDescription = p.caption.ifBlank { "已删除的照片" },
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(p.caption.ifBlank { "无描述" }, fontSize = 15.sp)
                        Spacer(Modifier.height(2.dp))
                        val remain = p.recycleRemainingDays
                        if (remain != null && remain >= 0) {
                            Text(
                                if (remain == 0) "即将自动删除" else "还剩 $remain 天自动删除",
                                fontSize = 12.sp,
                                // 快到期时转红，让用户有机会及时恢复。
                                color = if (remain <= 3) BrandRed
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            Text(
                                "${p.width}×${p.height}",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        LxButton(
                            text = if (busyId == p.id) "恢复中…" else "恢复",
                            onClick = {
                                if (busyId != 0L) return@LxButton
                                busyId = p.id
                                scope.launch {
                                    runCatching { ApiClient.restorePhoto(p.id) }
                                        .onSuccess { photos = photos.filterNot { it.id == p.id } }
                                        .onFailure { error = albumFriendlyError(it) }
                                    busyId = 0L
                                }
                            },
                            enabled = busyId == 0L,
                            variant = LxButtonVariant.Positive,
                            cornerRadius = 12,
                        )
                        Spacer(Modifier.height(6.dp))
                        LxButton(
                            text = "彻底删除",
                            onClick = { purgeTarget = p },
                            enabled = busyId == 0L,
                            variant = LxButtonVariant.Negative,
                            cornerRadius = 12,
                        )
                    }
                }
            }
        }
    }
}
