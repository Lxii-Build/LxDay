package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
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
import com.linxi.diary.data.AlbumSummary
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.components.LxConfirmDialog
import com.linxi.diary.ui.components.LxFormDialog
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 相册列表。
 *
 * 「未归类」是一个虚拟相册（album_id=0）：直接上传而不指定相册的照片都在那儿，
 * 否则用户必须先建相册才能传第一张照片，多一道无谓的门槛。
 * 它的**显示名可以改**（管理员要求），但不能删——删了那些照片就没有容身之处了。
 *
 * 管理动作的入口是卡片右侧的「⋯」按钮（Q19=A）。
 * 此前只能长按，界面上零提示，用户根本发现不了——管理员就因此以为「分组删不掉」。
 * 长按同时保留，作为熟练用户的快捷方式。
 */
@Composable
fun AlbumListScreen(
    onBack: () -> Unit,
    onOpenAlbum: (albumId: Long, name: String) -> Unit,
    onOpenOnThisDay: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onThisDayEnabled: Boolean = true,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()

    var albums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var summary by remember { mutableStateOf<AlbumSummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    // 管理弹窗的目标。null=未打开；id=0 表示在管理「未归类」（只能改名）。
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
            // 未归类张数、回收站张数、未归类显示名一律由服务端给，客户端不再做减法。
            runCatching { ApiClient.albumSummaryFull() }
                .onSuccess { summary = it }
                .onFailure { /* 概要失败不影响相册列表本身 */ }
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
            isUnclassified = target.id == 0L,
            onDismiss = { manageTarget = null },
            onRename = { newName ->
                scope.launch {
                    val call = if (target.id == 0L) {
                        runCatching { ApiClient.renameUnclassified(newName) }
                    } else {
                        runCatching { ApiClient.renameAlbum(target.id, newName) }
                    }
                    call.onSuccess { manageTarget = null; load() }
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

    val unclassifiedName = summary?.unclassifiedName ?: "未归类"
    val unclassifiedCount = summary?.unclassifiedCount ?: 0
    val recycledCount = summary?.recycledCount ?: 0

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
                LxButton(
                    text = "新建相册",
                    onClick = { showCreate = true },
                    variant = LxButtonVariant.Positive,
                    modifier = Modifier.weight(1f),
                )
                if (onThisDayEnabled) {
                    LxButton(
                        text = "这一天",
                        onClick = onOpenOnThisDay,
                        variant = LxButtonVariant.Neutral,
                        modifier = Modifier.weight(1f),
                    )
                }
                LxButton(
                    // 带角标：让用户知道回收站里还有东西（也知道它不是空的摆设）。
                    text = if (recycledCount > 0) "回收站 $recycledCount" else "回收站",
                    onClick = onOpenRecycleBin,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.weight(1f),
                )
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
        // 未归类始终在最前：直接上传的照片都落在这里。
        item {
            AlbumCard(
                name = unclassifiedName,
                count = unclassifiedCount,
                coverUrl = summary?.latestThumbUrl.orEmpty(),
                onClick = { onOpenAlbum(0L, unclassifiedName) },
                // 未归类也能进管理（但弹窗里只有改名，没有删除）。
                onManage = {
                    manageTarget = AlbumItem(0L, unclassifiedName, unclassifiedCount, "")
                },
            )
        }
        items(albums, key = { it.id }) { a ->
            AlbumCard(
                name = a.name,
                count = a.photoCount,
                coverUrl = a.coverThumbUrl,
                onClick = { onOpenAlbum(a.id, a.name) },
                onManage = { manageTarget = a },
            )
        }
        if (!loading && error == null && albums.isEmpty() && unclassifiedCount == 0) {
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
    onManage: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            // 长按保留为快捷方式；显式入口是右侧的「⋯」。
            .combinedClickable(onClick = onClick, onLongClick = onManage),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .height(64.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    // 占位底色：空相册与"封面加载失败"都该有个可见方块。
                    // 没有它时封面加载失败是完全透明的（0822 管理员报的症状）。
                    .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
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
            // 显式管理入口：一眼就知道这里能操作，不必猜「要长按」。
            IconButton(onClick = onManage) {
                Icon(
                    imageVector = MiuixIcons.More,
                    contentDescription = "管理",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CreateAlbumDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LxFormDialog(
        show = true,
        title = "新建相册",
        confirmText = "创建",
        confirmEnabled = name.isNotBlank(),
        busy = busy,
        busyText = "创建中…",
        onConfirm = { busy = true; onCreate(name.trim()) },
        onDismiss = onDismiss,
    ) {
        TextField(
            value = name,
            onValueChange = { if (it.length <= 30) name = it },
            label = "相册名称",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 相册管理：改名 / 删除。
 *
 * 「未归类」传 isUnclassified=true：只给改名，不给删除——
 * 它是虚拟相册，删了那些照片就没有容身之处了。
 */
@Composable
private fun ManageAlbumDialog(
    album: AlbumItem,
    isUnclassified: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(album.id) { mutableStateOf(album.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 删除确认单独走 LxConfirmDialog：确认按钮自动是红色 + 1 秒冷静期，
    // 与「保存名称」的蓝色形成明确区分（此前两者完全同色，是管理员点名的问题）。
    if (confirmDelete) {
        LxConfirmDialog(
            show = true,
            title = "删除相册",
            message = "删除后相册消失，但其中 ${album.photoCount} 张照片不会被删除，" +
                "会退回「未归类」，你随时可以再建一个相册把它们放回去。",
            confirmText = "确认删除",
            destructive = true,
            busy = busy,
            busyText = "删除中…",
            onConfirm = { busy = true; onDelete() },
            onDismiss = { if (!busy) confirmDelete = false },
        )
        return
    }

    LxFormDialog(
        show = true,
        title = if (isUnclassified) "重命名" else "管理相册",
        confirmText = "保存名称",
        confirmEnabled = name.isNotBlank() && name.trim() != album.name,
        busy = busy,
        busyText = "保存中…",
        onConfirm = { busy = true; onRename(name.trim()) },
        onDismiss = onDismiss,
    ) {
        TextField(
            value = name,
            onValueChange = { if (it.length <= 30) name = it },
            label = "相册名称",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isUnclassified) {
            Text(
                "「未归类」是系统分组，存放没有指定相册的照片，因此不能删除。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            // 危险动作与常规动作在视觉上分开：红字条目 + 二次确认。
            Text(
                "删除相册",
                fontSize = 15.sp,
                color = com.linxi.diary.ui.theme.BrandRed,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy) { confirmDelete = true }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

internal fun albumFriendlyError(t: Throwable): String = when {
    t is java.net.UnknownHostException -> "无法连接服务器，请检查网络"
    t is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
    !t.message.isNullOrBlank() -> t.message!!
    else -> "加载失败，请稍后重试"
}
