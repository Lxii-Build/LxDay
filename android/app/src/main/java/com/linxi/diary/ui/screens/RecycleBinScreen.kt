package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 回收站：软删的照片在这里，可恢复。
 *
 * 服务端的删除一律是软删（status=2），不删磁盘文件——没有这个页面的话，
 * 误删的照片就永远找不回来了（接口早已就绪，只是缺入口）。
 */
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf(0L) }

    fun load(pull: Boolean = false) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            runCatching {
                val arr = ApiClient.recycledPhotos()
                (0 until arr.length()).map { PhotoItem.fromJson(arr.getJSONObject(it)) }
            }.onSuccess { photos = it }
                .onFailure { error = albumFriendlyError(it) }
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { load() }

    KernelScreen(
        title = "回收站",
        navigationIcon = { BackAction(onBack) },
        isRefreshing = refreshing,
        onRefresh = { load(pull = true) },
        loading = loading,
    ) {
        error?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(msg, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { load() }, modifier = Modifier.fillMaxWidth()) { Text("重试") }
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
                        Text(
                            "${p.width}×${p.height}",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Button(
                        onClick = {
                            if (busyId != 0L) return@Button
                            busyId = p.id
                            scope.launch {
                                runCatching { ApiClient.restorePhoto(p.id) }
                                    .onSuccess { photos = photos.filterNot { it.id == p.id } }
                                    .onFailure { error = albumFriendlyError(it) }
                                busyId = 0L
                            }
                        },
                        enabled = busyId == 0L,
                    ) { Text(if (busyId == p.id) "恢复中…" else "恢复", fontSize = 13.sp) }
                }
            }
        }
    }
}
