package com.linxi.diary.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.linxi.diary.data.AlbumItem
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.data.PhotoItem
import com.linxi.diary.data.PhotoUploader
import com.linxi.diary.data.UploadOutcome
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LoadingRow
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.components.LxConfirmDialog
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.util.Logs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val PAGE_SIZE = 60

/**
 * 相册详情：3 列缩略图网格 + 上传 + 多选管理。
 *
 * 网格用 Coil 加载 `/media/<id>/thumb`（服务端等比缩放到长边 384，不方裁——
 * 方裁会把竖构图人像的头脚切掉）。
 *
 * 多选（Q20=D）：长按任一格进入多选态，可批量删除、移动到其它相册。
 * 此前网格页**完全没有删除入口**，管理员因此以为「照片删不掉」。
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var reachedEnd by remember { mutableStateOf(false) }

    // 多选态
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var showMoveTo by remember { mutableStateOf(false) }
    var batchBusy by remember { mutableStateOf(false) }

    fun exitSelecting() {
        selecting = false
        selected.clear()
    }

    // 多选态下的返回键先退出多选，而不是直接离开页面。
    BackHandler {
        if (selecting) exitSelecting() else onBack()
    }

    // 上传进度：第 n / 共 m 张。失败不中断整批，逐张记账并保留原因。
    var uploadTotal by remember { mutableStateOf(0) }
    var uploadDone by remember { mutableStateOf(0) }
    val uploadFailures = remember { mutableStateListOf<UploadFailure>() }
    val uploading = uploadTotal > 0 && uploadDone + uploadFailures.size < uploadTotal

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
        uploadFailures.clear()
        for ((index, uri) in pickedUris.withIndex()) {
            val outcome = PhotoUploader.uploadOne(context, uri, albumId)
            if (outcome == null) {
                uploadDone++
            } else {
                uploadFailures += UploadFailure(index + 1, outcome)
            }
        }
        onPickedConsumed()
        loadFirst()
    }

    if (confirmBatchDelete) {
        LxConfirmDialog(
            show = true,
            title = "删除照片",
            message = "这 ${selected.size} 张照片会移到回收站，可以在回收站里恢复。",
            confirmText = "移到回收站",
            destructive = true,
            busy = batchBusy,
            busyText = "删除中…",
            onConfirm = {
                batchBusy = true
                scope.launch {
                    runCatching { ApiClient.batchDeletePhotos(selected.toList()) }
                        .onSuccess {
                            batchBusy = false
                            confirmBatchDelete = false
                            exitSelecting()
                            loadFirst()
                        }
                        .onFailure {
                            batchBusy = false
                            confirmBatchDelete = false
                            error = albumFriendlyError(it)
                        }
                }
            },
            onDismiss = { if (!batchBusy) confirmBatchDelete = false },
        )
    }

    if (showMoveTo) {
        MoveToAlbumDialog(
            currentAlbumId = albumId,
            count = selected.size,
            busy = batchBusy,
            onDismiss = { if (!batchBusy) showMoveTo = false },
            onPick = { targetId ->
                batchBusy = true
                scope.launch {
                    runCatching { ApiClient.batchMovePhotos(selected.toList(), targetId) }
                        .onSuccess {
                            batchBusy = false
                            showMoveTo = false
                            exitSelecting()
                            loadFirst()
                        }
                        .onFailure {
                            batchBusy = false
                            showMoveTo = false
                            error = albumFriendlyError(it)
                        }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (selecting) "已选 ${selected.size} 张" else albumName,
                navigationIcon = {
                    BackAction { if (selecting) exitSelecting() else onBack() }
                },
                actions = {
                    if (selecting) {
                        LxButton(
                            text = "取消",
                            onClick = { exitSelecting() },
                            variant = LxButtonVariant.Neutral,
                            cornerRadius = 12,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    } else {
                        LxButton(
                            text = "上传",
                            onClick = onPickPhotos,
                            variant = LxButtonVariant.Positive,
                            cornerRadius = 12,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 多选态的操作条：删除用红色、移动用中性色，语义一眼可辨。
            if (selecting) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LxButton(
                        text = "移动到…",
                        onClick = { showMoveTo = true },
                        enabled = selected.isNotEmpty() && !batchBusy,
                        variant = LxButtonVariant.Neutral,
                        modifier = Modifier.weight(1f),
                    )
                    LxButton(
                        text = "删除",
                        onClick = { confirmBatchDelete = true },
                        enabled = selected.isNotEmpty() && !batchBusy,
                        variant = LxButtonVariant.Negative,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding())
        ) {
            if (uploading || uploadTotal > 0) {
                UploadProgressCard(
                    uploading = uploading,
                    done = uploadDone,
                    failures = uploadFailures,
                    total = uploadTotal,
                    onRetryFailed = {
                        // 失败可重试：把失败项的 uri 重新走一遍上传（Q11=B）。
                        val retryUris = uploadFailures.mapNotNull { it.reason.uri }
                        if (retryUris.isNotEmpty()) {
                            scope.launch {
                                uploadTotal = retryUris.size
                                uploadDone = 0
                                uploadFailures.clear()
                                for ((i, u) in retryUris.withIndex()) {
                                    val o = PhotoUploader.uploadOne(context, u, albumId)
                                    if (o == null) uploadDone++ else uploadFailures += UploadFailure(i + 1, o)
                                }
                                loadFirst()
                            }
                        }
                    },
                    onDismiss = {
                        uploadTotal = 0
                        uploadDone = 0
                        uploadFailures.clear()
                    },
                )
            }
            when {
                loading -> LoadingRow()
                error != null -> Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(error!!, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(12.dp))
                        LxButton(
                            text = "重试",
                            onClick = { loadFirst() },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                else -> PhotoGrid(
                    photos = photos,
                    selecting = selecting,
                    selected = selected,
                    reachedEnd = reachedEnd,
                    onLoadMore = { loadMore() },
                    onOpenPhoto = onOpenPhoto,
                    onEnterSelecting = { id ->
                        selecting = true
                        if (!selected.contains(id)) selected.add(id)
                    },
                    onToggle = { id ->
                        if (selected.contains(id)) selected.remove(id) else selected.add(id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(
    photos: List<PhotoItem>,
    selecting: Boolean,
    selected: List<Long>,
    reachedEnd: Boolean,
    onLoadMore: () -> Unit,
    onOpenPhoto: (List<PhotoItem>, Int) -> Unit,
    onEnterSelecting: (Long) -> Unit,
    onToggle: (Long) -> Unit,
) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 用 itemsIndexed 而非 items + photos.indexOf(p)：
        // 后者是 O(n) 查找放在每个 item 的 lambda 里，整体 O(n²)，
        // 500 张照片滚动时会明显掉帧。下标本来就是现成的，没必要再查一遍。
        itemsIndexed(photos, key = { _, p -> p.id }) { index, p ->
            val isSelected = selected.contains(p.id)
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = {
                            if (selecting) onToggle(p.id) else onOpenPhoto(photos, index)
                        },
                        onLongClick = { onEnterSelecting(p.id) },
                    )
            ) {
                AsyncImage(
                    model = p.displayUrl,
                    imageLoader = AppImageLoader.get(context),
                    contentDescription = p.caption.ifBlank { "照片" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (selecting) {
                    // 选中态：半透明蒙层 + 右上角勾选圈。
                    if (isSelected) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(BrandBlue.copy(alpha = 0.28f))
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) BrandBlue
                                else Color.Black.copy(alpha = 0.35f)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = "已选中",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        if (!reachedEnd) {
            item {
                LaunchedEffect(photos.size) { onLoadMore() }
                Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                    LoadingRow()
                }
            }
        }
    }
}

/** 上传结果卡：逐张列出失败原因，并可一键重试失败项（Q11=B）。 */
@Composable
private fun UploadProgressCard(
    uploading: Boolean,
    done: Int,
    failures: List<UploadFailure>,
    total: Int,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (uploading) "正在上传 ${done + failures.size + 1} / $total"
                else "上传完成：成功 $done 张" +
                    if (failures.isNotEmpty()) "，失败 ${failures.size} 张" else ""
            )
            if (!uploading && failures.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (expanded) "收起失败详情" else "查看失败原因",
                    fontSize = 13.sp,
                    color = BrandBlue,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    failures.forEach { f ->
                        Text(
                            "第 ${f.index} 张：${f.reason.message}",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 只有"可重试"的失败才给重试按钮（格式不支持重试多少次都一样）。
                    if (failures.any { it.reason.retryable }) {
                        LxButton(
                            text = "重试失败项",
                            onClick = onRetryFailed,
                            variant = LxButtonVariant.Positive,
                            cornerRadius = 12,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LxButton(
                        text = "知道了",
                        onClick = onDismiss,
                        variant = LxButtonVariant.Neutral,
                        cornerRadius = 12,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (!uploading) {
                Spacer(Modifier.height(8.dp))
                LxButton(
                    text = "知道了",
                    onClick = onDismiss,
                    variant = LxButtonVariant.Neutral,
                    cornerRadius = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 「移动到」目标相册选择。albumId=0 表示移出相册、退回「未归类」。 */
@Composable
private fun MoveToAlbumDialog(
    currentAlbumId: Long,
    count: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    var albums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var loadingList by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        runCatching {
            val arr = ApiClient.albums()
            (0 until arr.length()).map { AlbumItem.fromJson(arr.getJSONObject(it)) }
        }.onSuccess { albums = it }
            .onFailure { Logs.w("Album", "load albums for move failed", it) }
        loadingList = false
    }

    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = "移动 $count 张照片到",
        onDismissRequest = { if (!busy) onDismiss() },
        renderInRootScaffold = true,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loadingList) {
                LoadingRow()
            } else {
                if (currentAlbumId != 0L) {
                    LxButton(
                        text = "未归类",
                        onClick = { onPick(0L) },
                        enabled = !busy,
                        variant = LxButtonVariant.Neutral,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                albums.filter { it.id != currentAlbumId }.forEach { a ->
                    LxButton(
                        text = a.name,
                        onClick = { onPick(a.id) },
                        enabled = !busy,
                        variant = LxButtonVariant.Neutral,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (albums.none { it.id != currentAlbumId } && currentAlbumId == 0L) {
                    Text(
                        "还没有别的相册，先去相册列表新建一个。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LxButton(
                text = "取消",
                onClick = onDismiss,
                enabled = !busy,
                variant = LxButtonVariant.Neutral,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 一次上传失败的记录：第几张 + 原因。 */
internal data class UploadFailure(val index: Int, val reason: UploadOutcome)
