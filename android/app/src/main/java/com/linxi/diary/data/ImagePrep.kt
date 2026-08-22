package com.linxi.diary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.linxi.diary.util.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 上传前的图片预处理实现：降采样解码 → EXIF 旋正 → 长边压到 2048 → 按目标 MIME 重编码。
 * 策略常量与纯计算见 [ImagePrepPolicy]。
 */
object ImagePrep {

    data class Prepared(
        val file: File,
        val mime: String,
        val width: Int,
        val height: Int,
        /** EXIF 拍摄时间（毫秒）；读不到则为 null，服务端会留空 taken_at。 */
        val takenAtMs: Long?,
    )

    /**
     * 把 [uri] 处理成可直接上传的临时文件。
     *
     * 全程在 IO 线程：解码是重活，放主线程会直接掉帧甚至 ANR
     *（此前的 NetworkAvatar 就是在主线程 decode）。
     */
    suspend fun prepare(context: Context, uri: Uri): Result<Prepared> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val sourceMime = resolver.getType(uri)
            val targetMime = ImagePrepPolicy.targetMime(sourceMime)

            // 动图原样上传：重编码只会写出第一帧，动图就没了（实打实的数据损失）。
            // GIF 看 MIME 就够；**WebP 必须读文件头才知道是不是动图**——
            // 此前动态 WebP 一律被压成静态 WEBP_LOSSY，动画全丢。
            val animated = targetMime == "image/webp" && isAnimatedWebpUri(resolver, uri)
            if (!ImagePrepPolicy.shouldRecompress(sourceMime, animated)) {
                return@runCatching copyAsIs(context, resolver, uri, targetMime)
            }

            // 先只读边界拿原始尺寸，据此算 inSampleSize——直接全尺寸解码大图会 OOM。
            //
            // **注意这里的判定不能靠 decodeStream 的返回值**：`inJustDecodeBounds = true` 时
            // 它永远返回 null，写成 `?.use { decodeStream(...) } ?: error(...)` 会让
            // 每一张图都在这里抛「无法读取所选图片」。判定逻辑与理由见
            // [ImagePrepPolicy.boundsFailure]。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
                true
            } ?: false
            ImagePrepPolicy.boundsFailure(opened, bounds.outWidth, bounds.outHeight)
                ?.let { error(it) }

            val sample = ImagePrepPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                // RGB_565 省一半内存，但会丢透明通道与色彩精度；只有 JPEG 目标才安全。
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // 解码期直接缩到目标尺寸：inSampleSize 只能做 2 的幂，
                // 4000×3000 会算出 sample=1 从而全尺寸解码 45.8MB（实测），
                // 连续上传必 OOM。density 缩放让 native 层边解码边缩，只分配最终尺寸。
                ImagePrepPolicy.decodeDensityScale(bounds.outWidth, bounds.outHeight, sample)
                    ?.let { (density, target) ->
                        inScaled = true
                        inDensity = density
                        inTargetDensity = target
                    }
            }
            var bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: error("图片解码失败")

            // EXIF：方向 + 拍摄时间。HEIC 也能被 ExifInterface 读取。
            var takenAtMs: Long? = null
            var orientation = ExifInterface.ORIENTATION_NORMAL
            runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                    takenAtMs = exif.dateTimeOriginal ?: exif.dateTime
                }
            }.onFailure { Logs.i("ImagePrep", "EXIF unreadable, continuing without it") }

            bitmap = applyOrientation(bitmap, orientation)
            bitmap = scaleDown(bitmap)

            val ext = ImagePrepPolicy.extensionFor(targetMime)
            val out = File(context.cacheDir, "upload_${System.currentTimeMillis()}.$ext")
            val format = when (targetMime) {
                "image/png" -> Bitmap.CompressFormat.PNG
                "image/webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                }
                else -> Bitmap.CompressFormat.JPEG
            }
            out.outputStream().use { bitmap.compress(format, ImagePrepPolicy.JPEG_QUALITY, it) }

            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()

            if (out.length() > ImagePrepPolicy.MAX_UPLOAD_BYTES) {
                out.delete()
                error("图片过大（处理后仍超过 20MB）")
            }
            Prepared(file = out, mime = targetMime, width = w, height = h, takenAtMs = takenAtMs)
        }
    }

    /**
     * 读 WebP 文件头判断是否动图。只读前 64 字节，不解码。
     * 读失败按"静态"处理——最坏结果是动图被压成静图，与修复前一致，不会更糟。
     */
    private fun isAnimatedWebpUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64)
            val n = input.read(head)
            if (n <= 0) return@use false
            ImagePrepPolicy.isAnimatedWebp(head.copyOf(n))
        } ?: false
    }.getOrElse { false }

    /** GIF 等不重编码的情形：原样拷进缓存目录，仅做大小校验。 */
    private fun copyAsIs(
        context: Context,
        resolver: android.content.ContentResolver,
        uri: Uri,
        mime: String,
    ): Prepared {
        val ext = ImagePrepPolicy.extensionFor(mime)
        val out = File(context.cacheDir, "upload_${System.currentTimeMillis()}.$ext")
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("无法读取所选图片")
        if (out.length() > ImagePrepPolicy.MAX_UPLOAD_BYTES) {
            out.delete()
            error("图片过大（超过 20MB）")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, bounds)
        return Prepared(out, mime, bounds.outWidth, bounds.outHeight, null)
    }

    private fun applyOrientation(src: Bitmap, exifOrientation: Int): Bitmap {
        val t = ImagePrepPolicy.orientationTransform(exifOrientation)
        if (t.isIdentity) return src
        val m = Matrix().apply {
            if (t.rotationDegrees != 0f) postRotate(t.rotationDegrees)
            if (t.flipHorizontal) postScale(-1f, 1f)
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val (tw, th) = ImagePrepPolicy.targetSize(src.width, src.height)
        if (tw == src.width && th == src.height) return src
        val scaled = Bitmap.createScaledBitmap(src, tw, th, true)
        if (scaled !== src) src.recycle()
        return scaled
    }
}
