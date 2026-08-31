package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.data.PhotoCommentItem
import com.linxi.diary.ui.components.LxButton as Button
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.data.PhotoItem
import com.linxi.diary.data.PhotoLoadSource
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.theme.BrandRed
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 大图查看：左右滑翻页 + 双指缩放 + 点赞 + 评论 + 删除。
 *
 * 这里加载的是原图 `/media/<id>`（非缩略图），由 Coil 做磁盘缓存与自动降采样。
 */
@Composable
fun PhotoViewerScreen(
    photos: List<PhotoItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    photoSocialEnabled: Boolean = true,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (photos.isEmpty()) {
        onBack()
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.lastIndex),
        pageCount = { photos.size },
    )
    // 点赞态与评论按当前页独立维护：翻页后要重新拉。
    var liked by remember { mutableStateOf(false) }
    var likeCount by remember { mutableStateOf(0) }
    var comments by remember { mutableStateOf<List<PhotoCommentItem>>(emptyList()) }
    var commentDraft by remember { mutableStateOf("") }
    var showComments by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val myId = remember { com.linxi.diary.util.UserPrefs.myUserId }
    var caption by remember { mutableStateOf("") }
    var captionDraft by remember { mutableStateOf("") }
    var editingCaption by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var commentToDelete by remember { mutableStateOf<PhotoCommentItem?>(null) }
    // 一次性提示（设封面成功/失败）。用文字条而非 Toast，避免与 miuix 观感割裂。
    var hintText by remember { mutableStateOf<String?>(null) }

    val current = photos[pagerState.currentPage.coerceIn(0, photos.lastIndex)]

    // 提示 2 秒后自动消失
    LaunchedEffect(hintText) {
        if (hintText != null) {
            kotlinx.coroutines.delay(2000)
            hintText = null
        }
    }

    // 预加载相邻两张的预览图：此前左右滑动每次都白屏等下载。
    // 只预热 Coil 缓存、不渲染，命中后翻页即刻出图。
    LaunchedEffect(pagerState.currentPage) {
        val loader = AppImageLoader.get(context)
        listOf(pagerState.currentPage - 1, pagerState.currentPage + 1)
            .filter { it in photos.indices }
            .forEach { idx ->
                runCatching {
                    loader.enqueue(
                        coil3.request.ImageRequest.Builder(context)
                            .data(photos[idx].viewerUrl)
                            .build()
                    )
                }
            }
    }

    if (confirmDelete) {
        com.linxi.diary.ui.components.LxConfirmDialog(
            show = true,
            title = "删除照片",
            message = "这张照片会移到回收站，可以在回收站里恢复。",
            confirmText = "移到回收站",
            destructive = true,
            busy = busy,
            busyText = "删除中…",
            onConfirm = {
                busy = true
                scope.launch {
                    runCatching { ApiClient.deletePhoto(current.id) }
                        .onSuccess { confirmDelete = false; onDeleted() }
                        .onFailure { confirmDelete = false; hintText = "删除失败，请重试" }
                    busy = false
                }
            },
            onDismiss = { if (!busy) confirmDelete = false },
        )
    }

    commentToDelete?.let { target ->
        com.linxi.diary.ui.components.LxConfirmDialog(
            show = true,
            title = "删除评论",
            message = "删除后无法恢复。",
            confirmText = "删除评论",
            destructive = true,
            busy = busy,
            busyText = "删除中…",
            onConfirm = {
                busy = true
                scope.launch {
                    runCatching { ApiClient.deletePhotoComment(current.id, target.id) }
                        .onSuccess {
                            comments = comments.filterNot { it.id == target.id }
                            commentToDelete = null
                        }
                        .onFailure { hintText = "删除失败，请重试" }
                    busy = false
                }
            },
            onDismiss = { if (!busy) commentToDelete = null },
        )
    }

    // 翻页即刷新该张的点赞/评论。
    LaunchedEffect(current.id, photoSocialEnabled) {
        liked = if (photoSocialEnabled) current.likedByMe else false
        likeCount = if (photoSocialEnabled) current.likeCount else 0
        caption = current.caption
        captionDraft = current.caption
        editingCaption = false
        comments = emptyList()
        if (!photoSocialEnabled) showComments = false
        if (photoSocialEnabled) {
            runCatching { ApiClient.photoDetail(current.id) }.onSuccess { d ->
                liked = d.optBoolean("liked", liked)
                likeCount = d.optInt("like_count", likeCount)
                val arr = d.optJSONArray("comments")
                comments = if (arr == null) emptyList() else {
                    (0 until arr.length()).map { PhotoCommentItem.fromJson(arr.getJSONObject(it)) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "${pagerState.currentPage + 1} / ${photos.size}",
                navigationIcon = { BackAction(onBack) },
                actions = {
                    // 设为相册封面：接口早就有（PUT /albums/:id 的 cover_photo_id），
                    // 但一直没有任何 UI 入口，等于白写。未归类（album_id=0）没有封面概念。
                    if (current.albumId != 0L) {
                        IconButton(onClick = {
                            if (busy) return@IconButton
                            busy = true
                            scope.launch {
                                runCatching { ApiClient.setAlbumCover(current.albumId, current.id) }
                                    .onSuccess { hintText = "已设为相册封面" }
                                    .onFailure { hintText = "设置封面失败" }
                                busy = false
                            }
                        }) {
                            Icon(
                                imageVector = MiuixIcons.Album,
                                contentDescription = "设为相册封面",
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    }
                    // 删除必须二次确认：照片是不可再生数据，此前点一下就删、零确认，误触零成本。
                    IconButton(onClick = { if (!busy) confirmDelete = true }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "删除这张照片",
                            tint = BrandRed,
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            // 一次性提示条（设封面成功/删除失败等），2 秒自动消失。
            hintText?.let { hint ->
                Text(
                    hint,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 8.dp, horizontal = 16.dp),
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).background(Color.Black),
            ) { page ->
                // 本机有原图就直接读本机（自己传的照片），否则走云端 preview→origin 两档。
                ZoomableImage(
                    previewModel = PhotoLoadSource.viewerModel(context, photos[page]),
                    originModel = PhotoLoadSource.originModel(context, photos[page]),
                    cacheKey = photos[page].id,
                    description = photos[page].caption,
                )
            }

            if (photoSocialEnabled) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (busy) return@IconButton
                        busy = true
                        val wasLiked = liked
                        // 乐观更新：先动 UI，失败再回滚。点赞是高频轻量操作，等网络往返会显得迟钝。
                        liked = !wasLiked
                        likeCount = (likeCount + if (wasLiked) -1 else 1).coerceAtLeast(0)
                        scope.launch {
                            val r = if (wasLiked) {
                                runCatching { ApiClient.unlikePhoto(current.id) }
                            } else {
                                runCatching { ApiClient.likePhoto(current.id) }
                            }
                            r.onSuccess { d ->
                                liked = d.optBoolean("liked", liked)
                                likeCount = d.optInt("like_count", likeCount)
                            }.onFailure {
                                liked = wasLiked
                                likeCount = (likeCount + if (wasLiked) 1 else -1).coerceAtLeast(0)
                            }
                            busy = false
                        }
                    }) {
                        Icon(
                            if (liked) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
                            contentDescription = if (liked) "取消赞" else "点赞",
                            tint = if (liked) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text("$likeCount", modifier = Modifier.padding(start = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { showComments = !showComments }, variant = LxButtonVariant.Neutral) {
                        Text("评论 ${comments.size}", fontSize = 13.sp)
                    }
                }
            }

            // 描述：可就地编辑（PUT /photos/:id）。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    caption.ifBlank { "还没有描述" },
                    modifier = Modifier.weight(1f),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Button(onClick = { editingCaption = true }, variant = LxButtonVariant.Neutral) { Text("编辑", fontSize = 12.sp) }
            }

            if (editingCaption) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextField(
                            value = captionDraft,
                            onValueChange = { if (it.length <= 200) captionDraft = it },
                            label = "照片描述",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { editingCaption = false; captionDraft = caption },
                                variant = LxButtonVariant.Neutral,
                                modifier = Modifier.weight(1f),
                            ) { Text("取消") }
                            Button(
                                onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    val text = captionDraft.trim()
                                    scope.launch {
                                        runCatching {
                                            ApiClient.updatePhotoCaption(current.id, text)
                                        }.onSuccess {
                                            caption = text
                                            editingCaption = false
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (busy) "保存中…" else "保存") }
                        }
                    }
                }
            }

            if (showComments && photoSocialEnabled) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(
                        Modifier.padding(12.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (comments.isEmpty()) {
                            Text(
                                "还没有评论",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        comments.forEach { c ->
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.userName.ifBlank { "对方" }, fontSize = 13.sp)
                                    Text(
                                        c.content,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                // 只能删自己的（服务端同样会校验，这里只是不显示无用按钮）。
                                if (c.userId == myId) {
                                    Button(
                                        onClick = { if (!busy) commentToDelete = c },
                                        enabled = !busy,
                                        variant = LxButtonVariant.Negative,
                                    ) { Text("删除", fontSize = 12.sp) }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextField(
                            value = commentDraft,
                            onValueChange = { if (it.length <= 200) commentDraft = it },
                            label = "说点什么",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (busy) return@Button
                                busy = true
                                val text = commentDraft.trim()
                                scope.launch {
                                    runCatching { ApiClient.commentPhoto(current.id, text) }
                                        .onSuccess {
                                            commentDraft = ""
                                            runCatching { ApiClient.photoDetail(current.id) }
                                                .onSuccess { d ->
                                                    val arr = d.optJSONArray("comments")
                                                    comments = if (arr == null) emptyList() else {
                                                        (0 until arr.length()).map {
                                                            PhotoCommentItem.fromJson(arr.getJSONObject(it))
                                                        }
                                                    }
                                                }
                                        }
                                    busy = false
                                }
                            },
                            enabled = !busy && commentDraft.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("发送") }
                    }
                }
            }
        }
    }
}

/**
 * 双指缩放 + 拖动。缩放钳制在 1x~4x，避免缩到看不见或放大到失真。
 *
 * **两档加载**：首屏用 preview（长边 1080，秒出），一旦用户放大就切原图。
 * 此前直接加载 2048 长边原图，弱网下点开要白屏等 3~5 秒；
 * 而 1080 在手机屏幕上未放大时与原图肉眼无差。
 */
@Composable
private fun ZoomableImage(
    previewModel: Any,
    originModel: Any,
    cacheKey: Long,
    description: String,
) {
    val context = LocalContext.current
    var scale by remember(cacheKey) { mutableStateOf(1f) }
    var offsetX by remember(cacheKey) { mutableStateOf(0f) }
    var offsetY by remember(cacheKey) { mutableStateOf(0f) }
    // 放大过就一直用原图：来回切会让画质忽好忽坏，比一直清晰更难受。
    var wantOrigin by remember(cacheKey) { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(cacheKey) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        wantOrigin = true
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = if (wantOrigin) originModel else previewModel,
            imageLoader = AppImageLoader.get(context),
            contentDescription = description.ifBlank { "照片" },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}
