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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.data.LocalImage
import com.linxi.diary.data.MediaStoreImages
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.LoadingRow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 单次最多选几张：太多会让上传队列过长、失败重试也难管理。 */
private const val MAX_SELECT = 20

/**
 * 自研 miuix 风格图片选择器（决策 Q13=C）。
 *
 * 替代此前头像用的 `ActivityResultContracts.OpenDocument()`——那是 SAF 文件浏览器，
 * 是全 App 观感最突兀的一处。这里用 MediaStore 自己铺网格，与 miuix 皮肤一致，
 * 并支持多选、按月分组。
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

    var images by remember { mutableStateOf<List<LocalImage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var granted by remember { mutableStateOf(hasImagePermission(context)) }
    val selected = remember { mutableStateListOf<Uri>() }

    // 系统 Photo Picker 兜底：无需读取权限，选完直接回调。
    val systemPicker = rememberLauncherForActivityResult(
        if (multiple) {
            ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECT)
        } else {
            // 单选契约返回 Uri?，为统一回调这里也包成 List
            ActivityResultContracts.PickMultipleVisualMedia(1)
        }
    ) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.any { it }
        if (granted) {
            scope.launch {
                images = MediaStoreImages.query(context)
                loading = false
            }
        } else {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (granted) {
            images = MediaStoreImages.query(context)
            loading = false
        } else {
            permissionLauncher.launch(imagePermissions())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = { BackAction(onBack) },
                actions = {
                    Button(
                        onClick = {
                            systemPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text("系统相册", fontSize = 13.sp) }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
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
                        Button(
                            onClick = {
                                systemPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("用系统相册选择") }
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
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            AsyncImage(
                                model = img.uri,
                                imageLoader = AppImageLoader.get(context),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { toggle(selected, img.uri, multiple) },
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "已选中",
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已选 ${selected.size} / $MAX_SELECT",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { onPicked(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                    ) { Text("完成", fontWeight = FontWeight.Medium) }
                }
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
