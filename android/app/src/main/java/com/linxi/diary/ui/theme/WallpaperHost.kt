package com.linxi.diary.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 根层壁纸宿主：位于所有页面之后，只解码一次。
 * 顺序：壁纸位图 → 主题遮罩（浅/深）→ 页面内容。
 * 无壁纸时不绘制背景，回落标准 Miuix surface。
 */
@Composable
fun WallpaperHost(
    appearance: AppearanceSettings,
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val wallpaper = appearance.wallpaper
    val bitmap by produceState<ImageBitmap?>(initialValue = null, wallpaper?.processedPath) {
        val path = wallpaper?.processedPath
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                File(path).takeIf { it.isFile }?.let { decodeWallpaper(it) }
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp == null || wallpaper == null) {
        content()
        return
    }

    val scrimAlpha = (if (isDark) wallpaper.darkScrimAlpha else wallpaper.lightScrimAlpha).coerceIn(0f, 1f)
    val scrimColor = (if (isDark) Color.Black else Color.White).copy(alpha = scrimAlpha)

    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(scrimColor))
        content()
    }
}

/**
 * 解码壁纸文件：先探边界，再按需降采样。
 *
 * ★ 原实现是裸的 `BitmapFactory.decodeFile(path)` ★
 * 无边界探测、无降采样、无尺寸上限，而 path 来自 SharedPreferences、
 * 尺寸不受本代码任何约束（[WallpaperCropPolicy] 的 MAX_OUTPUT_* 在 main 源码里
 * 没有生产者）。解出来的 ImageBitmap 挂在**根 composable** 上、
 * 整个进程生命周期常驻，按 1440×3200 算就是 18.4MB 一直不还。
 *
 * 它与 Coil 的内存缓存（堆的 25%）叠加，直接吃掉相册解码的可用余量 ——
 * 相册那边一旦 OOM，用户看到的是"照片上传失败/消失"，
 * 而根因在一个八竿子打不着的壁纸功能里，极难联想。
 *
 * 壁纸最终 `ContentScale.Crop` 铺满屏幕，解到超过屏幕尺寸没有任何收益。
 */
private fun decodeWallpaper(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = WallpaperCropPolicy.decodeSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}
