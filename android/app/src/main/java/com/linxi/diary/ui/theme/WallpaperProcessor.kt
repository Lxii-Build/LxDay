package com.linxi.diary.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.get
import java.io.File
import java.io.FileOutputStream

/**
 * 壁纸处理：按裁剪框缩放平移渲染到输出尺寸，写入私有 files/wallpaper/，并提取取色种子。
 * 新文件保存成功后删除旧文件，避免私有目录堆积。
 */
object WallpaperProcessor {
    private const val DIR = "wallpaper"

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun process(
        context: Context,
        source: Bitmap,
        frameWidth: Int,
        frameHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): WallpaperSettings {
        val outW = WallpaperCropPolicy.outputWidth(screenWidthPx)
        val outH = WallpaperCropPolicy.outputHeight(screenWidthPx, screenHeightPx)
        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // 预览到输出的比例：预览框 frameWidth px 对应输出 outW px。
        val frameToOut = outW.toFloat() / frameWidth.coerceAtLeast(1)
        val matrix = Matrix().apply {
            // 预览中图片以 Crop(center) 铺满 frame，再叠加用户 scale 与位移。
            val baseCover = WallpaperCropPolicy.minScaleToCover(source.width, source.height, frameWidth, frameHeight)
            val totalScale = baseCover * scale * frameToOut
            postScale(totalScale, totalScale)
            // 居中后应用平移（换算到输出坐标）。
            val scaledW = source.width * totalScale
            val scaledH = source.height * totalScale
            postTranslate(
                (outW - scaledW) / 2f + offsetX * frameToOut,
                (outH - scaledH) / 2f + offsetY * frameToOut,
            )
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))

        val target = File(dir(context), "wallpaper_${System.currentTimeMillis()}.webp")
        writeWebp(output, target)
        // 删除旧壁纸文件（保留刚写入的）。
        dir(context).listFiles()?.forEach { if (it.name != target.name) it.delete() }

        val seed = extractSeed(output)
        output.recycle()
        return WallpaperSettings(
            processedPath = target.absolutePath,
            outputWidthPx = outW,
            outputHeightPx = outH,
            cachedSeedArgb = seed,
        )
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun writeWebp(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            @Suppress("DEPRECATION")
            val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            if (!bitmap.compress(format, 88, out)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
    }

    /** 缩小到小图后统计合格候选色，返回种子；全部不合格回落品牌粉。 */
    private fun extractSeed(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val counts = HashMap<Int, Int>()
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val c = small[x, y]
                if (SeedColorPolicy.isAcceptable(c)) {
                    // 量化到 4 位/通道降噪聚合。
                    val q = (c and 0xF0F0F0.toInt()) or 0xFF000000.toInt()
                    counts[q] = (counts[q] ?: 0) + 1
                }
            }
        }
        small.recycle()
        val best = counts.maxByOrNull { it.value }?.key
        return best ?: LinxiSeedPink
    }
}
