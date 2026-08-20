package com.linxi.diary.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.linxi.diary.data.ImagePrep
import com.linxi.diary.data.PhotoItem
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.LoadingRow
import com.linxi.diary.util.Logs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val PAGE_SIZE = 60

/**
 * 相册详情：3 列缩略图网格 + 上传。
 *
 * 网格用 Coil 加载 `/media/<id>/thumb`（服务端等比缩放到长边 512，不方裁——
 * 方裁会把竖构图人像的头脚切掉）。
 */
@Composable
fun AlbumDetailScreen(
    albumId: Long,
    albumName: String,
    onBack: () -> Unit,
    onOpenPhoto: (List<PhotoItem>, Int) -> Unit,
    onPickPhotos: () -> Unit,
    pickedUris: List<Uri>,
    onPickedConsumed: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var reachedEnd by remember { mutableStateOf(false) }

    // 上传进度：第 n / 共 m 张。失败不中断整批，逐张记账。
    var uploadTotal by remember { mutableStateOf(0) }
    var uploadDone by remember { mutableStateOf(0) }
    var uploadFailed by remember { mutableStateOf(0) }
    val uploading = uploadTotal > 0 && uploadDone + uploadFailed < uploadTotal

    fun loadFirst() {
        scope.launch {
            loading = true
            error = null
            page = 1
            reachedEnd = false
            runCatching {
                val arr = ApiClient.albumPhotos(albumId, 1, PAGE_SIZE)
                (0 until arr.length()).map { PhotoItem.fromJson(arr.getJSONObject(it)) }
            }.onSuccess {
                photos = it
                if (it.size < PAGE_SIZE) reachedEnd = true
            }.onFailure { error = albumFriendlyError(it) }
            loading = false
        }
    }

    fun loadMore() {
        if (reachedEnd || loading) return
        scope.launch {
            val next = page + 1
            runCatching {
                val arr = ApiClient.albumPhotos(albumId, next, PAGE_SIZE)
                (0 until arr.length()).map { PhotoItem.fromJson(arr.getJSONObject(it)) }
            }.onSuccess { more ->
                if (more.isEmpty() || more.size < PAGE_SIZE) reachedEnd = true
                if (more.isNotEmpty()) {
                    photos = photos + more
                    page = next
                }
            }.onFailure { reachedEnd = true }
        }
    }

    LaunchedEffect(albumId) { loadFirst() }

    // 选图返回后逐张上传。
    LaunchedEffect(pickedUris) {
        if (pickedUris.isEmpty()) return@LaunchedEffect
        uploadTotal = pickedUris.size
        uploadDone = 0
        uploadFailed = 0
        for (uri in pickedUris) {
            val prepared = ImagePrep.prepare(context, uri).getOrNull()
            if (prepared == null) {
                uploadFailed++
                continue
            }
            val result = runCatching {
                ApiClient.uploadMedia(prepared.file, prepared.mime, prepared.takenAtMs)
            }
            prepared.file.delete()
            result.onSuccess { media ->
                // /media 上传即建 photo 行（album_id=0），返回完整 photo 对象。
                // 目标相册非「未归类」时才需要挂接。
                val photoId = media.optLong("id")
                if (albumId != 0L && photoId > 0) {
                    runCatching { ApiClient.attachPhotos(albumId, listOf(photoId)) }
                        .onFailure { Logs.w("Album", "attach photo failed", it) }
                }
                uploadDone++
            }.onFailure {
                uploadFailed++
                Logs.w("Album", "upload photo failed", it)
            }
        }
        onPickedConsumed()
        loadFirst()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = albumName,
                navigationIcon = { BackAction(onBack) },
                actions = {
                    Button(onClick = onPickPhotos, modifier = Modifier.padding(end = 12.dp)) {
                        Text("上传", fontSize = 13.sp)
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            if (uploading || uploadTotal > 0) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (uploading) "正在上传 ${uploadDone + uploadFailed + 1} / $uploadTotal"
                            else "上传完成：成功 $uploadDone 张" +
                                if (uploadFailed > 0) "，失败 $uploadFailed 张" else "",
                        )
                        if (!uploading && uploadFailed > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "失败的照片可以重新选择上传（常见原因：格式不支持或超过 20MB）。",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            when {
                loading -> LoadingRow()
                error != null -> Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(error!!, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { loadFirst() }, modifier = Modifier.fillMaxWidth()) {
                            Text("重试")
                        }
                    }
                }
                photos.isEmpty() -> Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("这个相册还是空的", style = MiuixTheme.textStyles.headline1)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点右上角「上传」把照片放进来。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).overScrollVertical(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(photos, key = { it.id }) { p ->
                        val index = photos.indexOf(p)
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenPhoto(photos, index) }
                        ) {
                            AsyncImage(
                                model = p.displayUrl,
                                imageLoader = AppImageLoader.get(context),
                                contentDescription = p.caption.ifBlank { "照片" },
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (!reachedEnd) {
                        item {
                            LaunchedEffect(photos.size) { loadMore() }
                            Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                                LoadingRow()
                            }
                        }
                    }
                }
            }
        }
    }
}
