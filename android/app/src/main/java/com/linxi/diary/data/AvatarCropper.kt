package com.linxi.diary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.linxi.diary.util.Logs
import java.io.File

/**
 * 头像裁剪的坐标换算与导出。
 *
 * 裁剪 UI 里用户看到的是**降采样后的预览图**，而导出必须从**原图**裁，
 * 否则头像会是模糊的预览版本。所以这里要把「预览图上的圆框」换算回原图坐标。
 *
 * 换算链路（预览容器是正方形，边长 = view）：
 *   1. 预览图以 `ContentScale.Fit` 居中显示 → 得到 fit 缩放比与居中偏移
 *   2. 用户的 scale/offset 作用在这之上
 *   3. 圆框固定在容器中心，直径 = view * 0.82
 *   4. 反解出圆框在**预览图像素**中的矩形，再乘上「原图/预览图」的比例
 */
object AvatarCropper {

    /** 圆框直径占容器边长的比例，与 AvatarCropScreen 的绘制保持一致。 */
    const val FRAME_RATIO = 0.82f

    /** 输出边长。服务端头像 MaxDimension 是 512，出 512 正好不浪费也不模糊。 */
    const val OUTPUT_SIZE = 512

    /**
     * 纯计算：求圆框对应到「预览图像素坐标」的正方形区域。
     *
     * @param viewSize 预览容器边长（正方形）
     * @param bmpW/bmpH 预览图尺寸
     * @param scale 用户缩放
     * @param offsetX/offsetY 用户位移（像素，容器坐标系）
     * @return 预览图坐标系下的裁剪矩形（可能超出图片边界，调用方需 clamp）
     */
    fun frameToBitmapRect(
        viewSize: Float,
        bmpW: Int,
        bmpH: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): Rect {
        if (viewSize <= 0f || bmpW <= 0 || bmpH <= 0) return Rect(0, 0, 0, 0)
        // ContentScale.Fit：整图放进容器，取较小的比例
        val fit = minOf(viewSize / bmpW, viewSize / bmpH)
        val drawnW = bmpW * fit * scale
        val drawnH = bmpH * fit * scale
        // 图片中心在容器中心 + 用户位移
        val centerX = viewSize / 2f + offsetX
        val centerY = viewSize / 2f + offsetY
        val left = centerX - drawnW / 2f
        val top = centerY - drawnH / 2f

        val frame = viewSize * FRAME_RATIO
        val frameLeft = (viewSize - frame) / 2f
        val frameTop = (viewSize - frame) / 2f

        // 容器坐标 → 图片像素坐标
        val pxPerUnit = fit * scale
        val bx = ((frameLeft - left) / pxPerUnit)
        val by = ((frameTop - top) / pxPerUnit)
        val bw = frame / pxPerUnit
        return Rect(
            bx.toInt(),
            by.toInt(),
            (bx + bw).toInt(),
            (by + bw).toInt(),
        )
    }

    /** 把矩形收进 [0,w]×[0,h]，并保持正方形（取能容纳的最大正方形）。 */
    fun clampSquare(rect: Rect, w: Int, h: Int): Rect {
        var side = minOf(rect.width(), rect.height(), w, h)
        if (side <= 0) side = minOf(w, h)
        var left = rect.left.coerceIn(0, (w - side).coerceAtLeast(0))
        var top = rect.top.coerceIn(0, (h - side).coerceAtLeast(0))
        // 极端情况下（图片比框小）居中
        if (w < side) left = 0
        if (h < side) top = 0
        return Rect(left, top, left + side, top + side)
    }

    /**
     * 从原图裁出头像并写成 JPEG 临时文件。
     *
     * @param previewBitmap 裁剪 UI 里显示的那张（用于换算比例）
     * @return 临时文件；失败返回 null
     */
    fun crop(
        context: Context,
        uri: Uri,
        previewBitmap: Bitmap,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        viewSizePx: Float = previewBitmap.width.coerceAtLeast(previewBitmap.height).toFloat(),
    ): File? = runCatching {
        val resolver = context.contentResolver

        // 预览图坐标系下的裁剪框
        val inPreview = frameToBitmapRect(
            viewSize = viewSizePx,
            bmpW = previewBitmap.width,
            bmpH = previewBitmap.height,
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
        )

        // 原图尺寸与 EXIF 方向。预览页显示的是旋正后的图，裁剪框也因此处于
        // "旋正坐标系"；BitmapRegionDecoder 却只能按原始像素读取，必须把矩形映回去。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsOpened = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
            true
        } ?: false
        if (!boundsOpened || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("无法读取图片尺寸")
        }
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrElse {
            Logs.i("AvatarCropper", "EXIF unreadable, continuing", it)
            ExifInterface.ORIENTATION_NORMAL
        }

        // 先在旋正坐标系按预览比例换算，再映回原始像素坐标。
        val (orientedWidth, orientedHeight) = ImagePrepPolicy.orientedSize(
            bounds.outWidth, bounds.outHeight, orientation
        )
        val ratioX = orientedWidth.toFloat() / previewBitmap.width
        val ratioY = orientedHeight.toFloat() / previewBitmap.height
        val orientedRect = ImagePrepPolicy.PixelRect(
            (inPreview.left * ratioX).toInt(),
            (inPreview.top * ratioY).toInt(),
            (inPreview.right * ratioX).toInt(),
            (inPreview.bottom * ratioY).toInt(),
        )
        val rawRect = ImagePrepPolicy.orientedRectToRaw(
            orientedRect, bounds.outWidth, bounds.outHeight, orientation
        )
        val inOrigin = Rect(
            rawRect.left,
            rawRect.top,
            rawRect.right,
            rawRect.bottom,
        )
        val safe = clampSquare(inOrigin, bounds.outWidth, bounds.outHeight)

        // 用 BitmapRegionDecoder 只解需要的那块，避免把整张原图读进内存
        //（4000×3000 全解是 45.8MB，正是 0821 修掉的 OOM 来源）。
        //
        // 注意 BitmapRegionDecoder 在 API 31 以下不是 Closeable，不能用 `use{}`，
        // 只能手动 recycle。
        val decoded: Bitmap? = resolver.openInputStream(uri)?.let { input ->
            input.use { stream ->
                @Suppress("DEPRECATION")
                val decoder = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    android.graphics.BitmapRegionDecoder.newInstance(stream)
                } else {
                    android.graphics.BitmapRegionDecoder.newInstance(stream, false)
                }
                if (decoder == null) {
                    null
                } else {
                    try {
                        val opts = BitmapFactory.Options().apply {
                            // 裁出的块再降采样到 ~OUTPUT_SIZE，进一步省内存
                            inSampleSize =
                                ImagePrepPolicy.sampleSize(safe.width(), safe.height(), OUTPUT_SIZE)
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        decoder.decodeRegion(safe, opts)
                    } finally {
                        decoder.recycle()
                    }
                }
            }
        }
        var bmp: Bitmap = decoded ?: error("无法解码所选区域")
        var output: File? = null
        var succeeded = false
        try {
            // EXIF 旋正（相机直出的竖拍图必须转，否则头像躺倒）。这里使用与
            // 裁剪矩形映射相同的 orientation，避免“取对了区域但旋错了图”。
            val t = ImagePrepPolicy.orientationTransform(orientation)
            if (!t.isIdentity) {
                val m = android.graphics.Matrix().apply {
                    if (t.rotationDegrees != 0f) postRotate(t.rotationDegrees)
                    if (t.flipHorizontal) postScale(-1f, 1f)
                }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) {
                    bmp.recycle()
                    bmp = rotated
                }
            }

            // 缩到输出尺寸
            if (bmp.width != OUTPUT_SIZE || bmp.height != OUTPUT_SIZE) {
                val scaled = Bitmap.createScaledBitmap(bmp, OUTPUT_SIZE, OUTPUT_SIZE, true)
                if (scaled !== bmp) {
                    bmp.recycle()
                    bmp = scaled
                }
            }

            output = File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
            output!!.outputStream().use {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)) {
                    error("图片编码失败")
                }
            }
            succeeded = true
            output!!
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
            if (!succeeded) output?.delete()
        }
    }.getOrElse {
        Logs.w("AvatarCropper", "crop failed", it)
        null
    }
}
