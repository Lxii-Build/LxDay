package com.linxi.diary.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.data.ImageBucket
import com.linxi.diary.data.LocalImage
import com.linxi.diary.data.MediaStoreImages
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.LoadingRow
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.theme.BrandBlue
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.ZoomOut
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 单次最多选几张。
 * 从 20 提到 100（Q12=B）：上传改并发 3 路后，100 张也在可接受时长内。
 * 服务端日配额 200 张仍是硬上限。
 */
private const val MAX_SELECT = 100

/**
 * 自研 miuix 风格图片选择器。
 *
 * 替代此前头像用的 `ActivityResultContracts.OpenDocument()`——那是 SAF 文件浏览器，
 * 是全 App 观感最突兀的一处。这里用 MediaStore 自己铺网格，与 miuix 皮肤一致。
 *
 * ## 0821 改动
 * - **按相册分桶**（学 QQ）：顶部可横滑切「全部 / 相机 / 截屏 / 微信 / 下载」
 * - **分页加载**：每页 200 条，滚到底续拉。此前一次性读 2000 条元数据，进页面先卡 2~3 秒
 * - **修「图片消失」**：排序改 COALESCE 回退 DATE_ADDED，截图/微信图不再沉底被截断
 * - **角标预览**（Q14=B）：格子右下角放大角标，点它看大图确认是不是那张
 * - 单选模式供头像使用（Q13=C），选完交给调用方去裁剪
 *
 * 兜底：Android 14+ 用户可能只授权「部分照片」，此时 MediaStore 只返回被选中的几张，
 * 用户会以为相册空了。所以顶栏常驻「系统相册」入口，走系统 Photo Picker
 *（它不需要任何读取权限，一定能选到图）。
 */
@Composable
fun PhotoPickerScreen(
    title: String = "选择照片",
    multiple: Boolean = true,
    onBack: () -> Unit,
    onPicked: (List<Uri>) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var buckets by remember { mutableStateOf<List<ImageBucket>>(emptyList()) }
    var currentBucket by remember { mutableStateOf(MediaStoreImages.BUCKET_ALL) }
    var images by remember { mutableStateOf<List<LocalImage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(hasImagePermission(context)) }
    val selected = remember { mutableStateListOf<Uri>() }
    // 预览大图（角标点击）
    var previewImage by remember { mutableStateOf<LocalImage?>(null) }

    val systemPicker = rememberLauncherForActivityResult(
        if (multiple) {
            ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECT)
        } else {
            ActivityResultContracts.PickMultipleVisualMedia(1)
        }
    ) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }

    suspend fun loadBucket(bucket: String) {
        loading = true
        reachedEnd = false
        images = MediaStoreImages.queryPage(context, bucket, 0)
        if (images.size < MediaStoreImages.PAGE_SIZE) reachedEnd = true
        loading = false
    }

    suspend fun loadAll() {
        buckets = MediaStoreImages.queryBuckets(context)
        loadBucket(currentBucket)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.any { it }
        if (granted) {
            scope.launch { loadAll() }
        } else {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (granted) loadAll() else permissionLauncher.launch(imagePermissions())
    }

    fun loadMore() {
        if (loadingMore || reachedEnd || loading) return
        scope.launch {
            loadingMore = true
            val more = MediaStoreImages.queryPage(context, currentBucket, images.size)
            if (more.size < MediaStoreImages.PAGE_SIZE) reachedEnd = true
            if (more.isNotEmpty()) images = images + more
            loadingMore = false
        }
    }

    previewImage?.let { img ->
        PhotoPickPreviewDialog(
            image = img,
            selected = selected.contains(img.uri),
            onToggle = { toggle(selected, img.uri, multiple) },
            onDismiss = { previewImage = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = { BackAction(onBack) },
                actions = {
                    LxButton(
                        text = "系统相册",
                        onClick = {
                            systemPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        variant = LxButtonVariant.Neutral,
                        cornerRadius = 12,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            // 分桶切换：学 QQ，进来先能挑「截屏」或「微信」，不必在混合大列表里翻。
            if (granted && buckets.size > 1) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(buckets, key = { it.name }) { b ->
                        val isCurrent = b.name == currentBucket
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isCurrent) BrandBlue
                                    else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                                )
                                .clickable {
                                    if (!isCurrent) {
                                        currentBucket = b.name
                                        scope.launch { loadBucket(b.name) }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                "${MediaStoreImages.bucketLabel(b.name)} ${b.count}",
                                fontSize = 13.sp,
                                color = if (isCurrent) Color.White
                                else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (loading) {
                LoadingRow()
            } else if (!granted) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("没有相册访问权限", style = MiuixTheme.textStyles.headline1)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "可以去系统设置里允许访问照片，或直接用「系统相册」选择——那种方式不需要授权。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                        LxButton(
                            text = "用系统相册选择",
                            onClick = {
                                systemPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else if (images.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("没有找到照片", style = MiuixTheme.textStyles.headline1)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "如果你只允许了访问「部分照片」，这里就只会显示那几张。可点右上角「系统相册」重新选择。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).overScrollVertical(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(images, key = { it.uri.toString() }) { img ->
                        val isSelected = selected.contains(img.uri)
                        PickerCell(
                            image = img,
                            isSelected = isSelected,
                            onToggle = { toggle(selected, img.uri, multiple) },
                            onPreview = { previewImage = img },
                        )
                    }
                    if (!reachedEnd) {
                        item {
                            LaunchedEffect(images.size) { loadMore() }
                            Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                                LoadingRow()
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (multiple) "已选 ${selected.size} / $MAX_SELECT"
                        else if (selected.isEmpty()) "点一张照片选中" else "已选 1 张",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    LxButton(
                        text = "完成",
                        onClick = { onPicked(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                        variant = LxButtonVariant.Positive,
                        cornerRadius = 12,
                    )
                }
            }
        }
    }
}

/** 单个格子：点击选中，右下角角标进预览（Q14=B）。 */
@Composable
private fun PickerCell(
    image: LocalImage,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
    ) {
        AsyncImage(
            model = image.uri,
            imageLoader = AppImageLoader.get(context),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clickable { onToggle() },
        )
        if (isSelected) {
            Box(Modifier.fillMaxSize().background(BrandBlue.copy(alpha = 0.24f)))
            Icon(
                imageVector = MiuixIcons.Ok,
                contentDescription = "已选中",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(BrandBlue)
                    .padding(4.dp),
            )
        }
        // 放大角标：单独的点击区，避免"想看大图却选中了"。
        Icon(
            imageVector = MiuixIcons.ZoomOut,
            contentDescription = "预览",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable { onPreview() }
                .padding(5.dp),
        )
    }
}

/** 预览大图：确认是不是要传的那张，并可直接在这里选中/取消。 */
@Composable
private fun PhotoPickPreviewDialog(
    image: LocalImage,
    selected: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = image.monthLabel,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = image.uri,
                    imageLoader = AppImageLoader.get(context),
                    contentDescription = "预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LxButton(
                    text = "关闭",
                    onClick = onDismiss,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.weight(1f),
                )
                LxButton(
                    text = if (selected) "取消选中" else "选中这张",
                    onClick = { onToggle(); onDismiss() },
                    variant = if (selected) LxButtonVariant.Neutral else LxButtonVariant.Positive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun toggle(selected: MutableList<Uri>, uri: Uri, multiple: Boolean) {
    if (selected.contains(uri)) {
        selected.remove(uri)
        return
    }
    if (!multiple) {
        selected.clear()
        selected.add(uri)
        return
    }
    if (selected.size < MAX_SELECT) selected.add(uri)
}

/**
 * 需要申请的读取权限。
 * Android 14+ 必须同时申请 READ_MEDIA_VISUAL_USER_SELECTED，
 * 否则用户选「仅部分照片」会被当成完全拒绝。
 */
private fun imagePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 34) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    } else {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    }

private fun hasImagePermission(context: Context): Boolean =
    imagePermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
