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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
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
import com.linxi.diary.data.PhotoItem
import com.linxi.diary.ui.components.BackAction
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
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

    val current = photos[pagerState.currentPage.coerceIn(0, photos.lastIndex)]

    // 翻页即刷新该张的点赞/评论。
    LaunchedEffect(current.id) {
        liked = current.likedByMe
        likeCount = current.likeCount
        caption = current.caption
        captionDraft = current.caption
        editingCaption = false
        runCatching { ApiClient.photoDetail(current.id) }.onSuccess { d ->
            liked = d.optBoolean("liked", liked)
            likeCount = d.optInt("like_count", likeCount)
            val arr = d.optJSONArray("comments")
            comments = if (arr == null) emptyList() else {
                (0 until arr.length()).map { PhotoCommentItem.fromJson(arr.getJSONObject(it)) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "${pagerState.currentPage + 1} / ${photos.size}",
                navigationIcon = { BackAction(onBack) },
                actions = {
                    IconButton(onClick = {
                        if (busy) return@IconButton
                        busy = true
                        scope.launch {
                            runCatching { ApiClient.deletePhoto(current.id) }
                                .onSuccess { onDeleted() }
                            busy = false
                        }
                    }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "删除这张照片",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).background(Color.Black),
            ) { page ->
                ZoomableImage(url = photos[page].url, description = photos[page].caption)
            }

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
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (liked) "取消赞" else "点赞",
                        tint = if (liked) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text("$likeCount", modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                Button(onClick = { showComments = !showComments }) {
                    Text("评论 ${comments.size}", fontSize = 13.sp)
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
                Button(onClick = { editingCaption = true }) { Text("编辑", fontSize = 12.sp) }
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

            if (showComments) {
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
                                        onClick = {
                                            if (busy) return@Button
                                            busy = true
                                            scope.launch {
                                                runCatching {
                                                    ApiClient.deletePhotoComment(current.id, c.id)
                                                }.onSuccess {
                                                    comments = comments.filterNot { it.id == c.id }
                                                }
                                                busy = false
                                            }
                                        },
                                        enabled = !busy,
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

/** 双指缩放 + 拖动。缩放钳制在 1x~4x，避免缩到看不见或放大到失真。 */
@Composable
private fun ZoomableImage(url: String, description: String) {
    val context = LocalContext.current
    var scale by remember(url) { mutableStateOf(1f) }
    var offsetX by remember(url) { mutableStateOf(0f) }
    var offsetY by remember(url) { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(url) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
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
            model = url,
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
