package com.linxi.diary.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.theme.WallpaperCropPolicy
import com.linxi.diary.ui.theme.WallpaperProcessor
import com.linxi.diary.ui.theme.rememberThemeState
import com.linxi.diary.util.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 全屏壁纸裁剪页：Photo Picker 选图 → 双指缩放/单指平移预览 → 保存为私有壁纸并触发取色。
 * 裁剪框比例匹配屏幕，缩放钳制保证不露白（WallpaperCropPolicy）。
 */
@Composable
fun WallpaperScreen(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeState = rememberThemeState()
    val appearance by themeState.appearance

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var frameW by remember { mutableStateOf(0) }
    var frameH by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
                if (bmp != null) {
                    source = bmp
                    scale = 1f; offsetX = 0f; offsetY = 0f
                }
            }
        }
    }

    KernelScreen(title = "全局壁纸", navigationIcon = { BackAction(onBack) }) {
        item {
            val displayMetrics = context.resources.displayMetrics
            val screenAspect = displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels
            Card(Modifier.padding(top = 8.dp).fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(screenAspect)
                        .onSizeChanged {
                            frameW = it.width; frameH = it.height
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (source == null) return@detectTransformGestures
                                // 预览已用 Crop 铺满框(=1x 覆盖)，scale 是额外倍率，下限 1.0 保证不露白。
                                scale = WallpaperCropPolicy.clampScale(scale * zoom, 1f)
                                // 元素屏上尺寸为 frame*scale（Crop 后整体缩放），据此夹平移余量。
                                offsetX = WallpaperCropPolicy.clampTranslation(offsetX + pan.x, frameW * scale, frameW.toFloat())
                                offsetY = WallpaperCropPolicy.clampTranslation(offsetY + pan.y, frameH * scale, frameH.toFloat())
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = source
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "壁纸预览",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale, scaleY = scale,
                                    translationX = offsetX, translationY = offsetY,
                                ),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Text("选择一张图片作为壁纸", color = colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiuixButton(
                    onClick = {
                        picker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    cornerRadius = 16.dp,
                ) { Text("选择图片") }
                MiuixButton(
                    onClick = {
                        val bmp = source ?: return@MiuixButton
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    WallpaperProcessor.process(
                                        context = context,
                                        source = bmp,
                                        frameWidth = frameW,
                                        frameHeight = frameH,
                                        scale = scale,
                                        offsetX = offsetX,
                                        offsetY = offsetY,
                                        screenWidthPx = context.resources.displayMetrics.widthPixels,
                                        screenHeightPx = context.resources.displayMetrics.heightPixels,
                                    )
                                }.getOrElse { Logs.w("Wallpaper", "壁纸处理失败", it); null }
                            }
                            if (result != null) {
                                themeState.update { it.copy(wallpaper = result) }
                                onBack()
                            }
                            busy = false
                        }
                    },
                    enabled = source != null && !busy && frameW > 0,
                    modifier = Modifier.weight(1f),
                    cornerRadius = 16.dp,
                ) { Text(if (busy) "处理中…" else "保存壁纸") }
            }
        }
        if (appearance.wallpaper != null) {
            item {
                MiuixButton(
                    onClick = {
                        themeState.update { it.copy(wallpaper = null) }
                        WallpaperProcessor.clear(context)
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    cornerRadius = 16.dp,
                ) { Text("移除壁纸", color = androidx.compose.ui.graphics.Color(0xFFD9412F)) }
            }
        }
    }
}
