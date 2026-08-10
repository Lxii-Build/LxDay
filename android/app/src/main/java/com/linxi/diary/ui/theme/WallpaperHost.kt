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
                File(path).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
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
