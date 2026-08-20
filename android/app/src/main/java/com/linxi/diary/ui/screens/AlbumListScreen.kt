package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.linxi.diary.data.AlbumItem
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 相册列表：2 列封面网格。
 *
 * 「未归类」是一个虚拟相册（album_id=0）：直接上传而不指定相册的照片都在那儿，
 * 否则用户必须先建相册才能传第一张照片，多一道无谓的门槛。
 */
@Composable
fun AlbumListScreen(
    onBack: () -> Unit,
    onOpenAlbum: (albumId: Long, name: String) -> Unit,
    onOpenOnThisDay: () -> Unit,
    onOpenRecycleBin: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var albums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var unclassified by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    // 长按相册卡弹出管理（改名/删除）。删除是软删，照片会退回「未归类」而不是一起删掉。
    var manageTarget by remember { mutableStateOf<AlbumItem?>(null) }

    fun load(pull: Boolean = false) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            runCatching {
                val arr = ApiClient.albums()
                (0 until arr.length()).map { AlbumItem.fromJson(arr.getJSONObject(it)) }
            }.onSuccess { albums = it }
                .onFailure { error = albumFriendlyError(it) }
            // 未归类张数：概要接口的 photo_count 减去各相册张数
            runCatching { ApiClient.albumSummary() }
                .onSuccess { total -> unclassified = (total - albums.sumOf { it.photoCount }).coerceAtLeast(0) }
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { load() }

    if (showCreate) {
        CreateAlbumDialog(
            onDismiss = { showCreate = false },
            onCreate = { name ->
                scope.launch {
                    runCatching { ApiClient.createAlbum(name) }
                        .onSuccess { showCreate = false; load() }
                        .onFailure { error = albumFriendlyError(it) }
                }
            },
        )
    }

    manageTarget?.let { target ->
        ManageAlbumDialog(
            album = target,
            onDismiss = { manageTarget = null },
            onRename = { newName ->
                scope.launch {
                    runCatching { ApiClient.renameAlbum(target.id, newName) }
                        .onSuccess { manageTarget = null; load() }
                        .onFailure { error = albumFriendlyError(it) }
                }
            },
            onDelete = {
                scope.launch {
                    runCatching { ApiClient.deleteAlbum(target.id) }
                        .onSuccess { manageTarget = null; load() }
                        .onFailure { error = albumFriendlyError(it) }
                }
            },
        )
    }

    KernelScreen(
        title = "相册",
        navigationIcon = { BackAction(onBack) },
        isRefreshing = refreshing,
        onRefresh = { load(pull = true) },
        loading = loading,
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { showCreate = true }, modifier = Modifier.weight(1f)) {
                    Text("新建相册", fontSize = 14.sp)
                }
                Button(onClick = onOpenOnThisDay, modifier = Modifier.weight(1f)) {
                    Text("这一天", fontSize = 14.sp)
                }
                Button(onClick = onOpenRecycleBin, modifier = Modifier.weight(1f)) {
                    Text("回收站", fontSize = 14.sp)
                }
            }
        }
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
        // 未归类始终在最前：直接上传的照片都落在这里。
        item {
            AlbumCard(
                name = "未归类",
                count = unclassified,
                coverUrl = "",
                onClick = { onOpenAlbum(0L, "未归类") },
            )
        }
        items(albums, key = { it.id }) { a ->
            AlbumCard(
                name = a.name,
                count = a.photoCount,
                coverUrl = a.coverThumbUrl,
                onClick = { onOpenAlbum(a.id, a.name) },
                onLongClick = { manageTarget = a },
            )
        }
        if (!loading && error == null && albums.isEmpty() && unclassified == 0) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("还没有照片", style = MiuixTheme.textStyles.headline1)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "进入任一相册就能上传。你们俩都能看到彼此上传的照片。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(
    name: String,
    count: Int,
    coverUrl: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .height(64.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        imageLoader = AppImageLoader.get(context),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "$count 张",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun CreateAlbumDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = "新建相册",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { if (it.length <= 30) name = it },
                label = "相册名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { busy = true; onCreate(name.trim()) },
                    enabled = !busy && name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "创建中…" else "创建") }
            }
        }
    }
}

/** 相册管理：改名 / 删除。删除是软删，照片退回「未归类」而非一起删掉。 */
@Composable
private fun ManageAlbumDialog(
    album: AlbumItem,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(album.id) { mutableStateOf(album.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = "管理相册",
        onDismissRequest = { if (!busy) onDismiss() },
        renderInRootScaffold = true,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { if (it.length <= 30) name = it },
                label = "相册名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { busy = true; onRename(name.trim()) },
                enabled = !busy && name.isNotBlank() && name.trim() != album.name,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存名称") }

            if (!confirmDelete) {
                Button(
                    onClick = { confirmDelete = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("删除相册") }
            } else {
                Text(
                    "删除后相册消失，但其中 ${album.photoCount} 张照片不会被删除，会退回「未归类」。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { confirmDelete = false },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("取消") }
                    Button(
                        onClick = { busy = true; onDelete() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "删除中…" else "确认删除") }
                }
            }
        }
    }
}

internal fun albumFriendlyError(t: Throwable): String = when {
    t is java.net.UnknownHostException -> "无法连接服务器，请检查网络"
    t is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
    !t.message.isNullOrBlank() -> t.message!!
    else -> "加载失败，请稍后重试"
}
