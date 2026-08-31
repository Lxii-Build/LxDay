package com.linxi.diary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.linxi.diary.util.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    suspend fun prepare(
        context: Context,
        uri: Uri,
        maxUploadBytes: Long = ImagePrepPolicy.MAX_UPLOAD_BYTES,
    ): Result<Prepared> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val sourceMime = resolver.getType(uri)
            val targetMime = ImagePrepPolicy.targetMime(sourceMime)

            // 动图原样上传：重编码只会写出第一帧，动图就没了（实打实的数据损失）。
            // GIF 看 MIME 就够；**WebP 必须读文件头才知道是不是动图**——
            // 此前动态 WebP 一律被压成静态 WEBP_LOSSY，动画全丢。
            val animated = targetMime == "image/webp" && isAnimatedWebpUri(resolver, uri)
            if (!ImagePrepPolicy.shouldRecompress(sourceMime, animated)) {
                return@runCatching copyAsIs(context, resolver, uri, targetMime, maxUploadBytes)
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
                    takenAtMs = exifTakenAtMs(exif)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                Logs.i("ImagePrep", "EXIF unreadable, continuing without it")
            }

            // ★★ 从这里到 finally 之间的一切都必须保证 bitmap 被回收 ★★
            //
            // 原实现把 recycle() 写在正常流程的末尾，于是任何一处抛异常都会跳过它：
            // 磁盘满导致 outputStream() 抛 IO、compress 失败、createScaledBitmap
            // 在内存紧张时抛 OOM —— 每一次都留下一份 12.6MB 的 Bitmap 等 GC。
            //
            // 而这恰好构成一条**雪崩链**：PhotoUploader 把异常吞成 retryable=true，
            // 用户看到"失败 N 张"就会点「重试失败项」，于是在内存已经紧张的情况下
            // 又走一遍同样的路径、又漏一份 —— 每次重试都让下一次更容易 OOM。
            // 用户侧的现象就是管理员报过的"照片会消失"。
            //
            // 注意 applyOrientation/scaleDown 内部已经各自回收了它们替换掉的中间图，
            // 这里的 finally 只负责"当前 bitmap 变量指向的那一份"。
            try {
                // 旋正与缩放合并成一次 createBitmap，见 normalize 的注释。
                bitmap = normalize(bitmap, orientation)

                val ext = ImagePrepPolicy.extensionFor(targetMime)
                val out = newUploadTempFile(context, ext)
                val format = when (targetMime) {
                    "image/png" -> Bitmap.CompressFormat.PNG
                    "image/webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                    }
                    else -> Bitmap.CompressFormat.JPEG
                }
                val w = bitmap.width
                val h = bitmap.height
                // 压缩失败要把半截文件删掉：留着它既占空间，
                // 又可能被下面的 length() 判成"过大"从而给出误导性的错误文案。
                val compressed = runCatching {
                    out.outputStream().use { bitmap.compress(format, ImagePrepPolicy.JPEG_QUALITY, it) }
                }.getOrElse {
                    out.delete()
                    throw it
                }
                if (!compressed) {
                    out.delete()
                    error("图片编码失败，请换一张")
                }

                if (out.length() > maxUploadBytes) {
                    out.delete()
                    error("图片过大（处理后仍超过 ${maxUploadBytes / (1024 * 1024)}MB）")
                }
                Prepared(file = out, mime = targetMime, width = w, height = h, takenAtMs = takenAtMs)
            } finally {
                // 已被 applyOrientation/scaleDown 回收过的那些不会走到这里
                //（bitmap 已指向新对象）；isRecycled 判断只是防御重复回收。
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    // ExifInterface 的 dateTime/dateTimeOriginal Kotlin 属性标成库内受限 API；直接读取
    // 标准 EXIF 标签再解析，既保持“读不到就留空”的上传语义，也避免依赖隐藏实现。
    private fun exifTakenAtMs(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return runCatching {
            LocalDateTime.parse(raw, EXIF_DATE_TIME_FORMAT)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private val EXIF_DATE_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

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
        maxUploadBytes: Long,
    ): Prepared {
        val ext = ImagePrepPolicy.extensionFor(mime)
        val out = newUploadTempFile(context, ext)
        try {
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: error("无法读取所选图片")
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        if (out.length() > maxUploadBytes) {
            out.delete()
            error("图片过大（超过 ${maxUploadBytes / (1024 * 1024)}MB）")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, bounds)
        return Prepared(out, mime, bounds.outWidth, bounds.outHeight, null)
    }

    /**
     * 每次预处理都必须拿到唯一的临时文件名。时间戳在并发调用下会碰撞，
     * 一个任务可能覆盖另一个任务尚未上传的文件。
     */
    private fun newUploadTempFile(context: Context, ext: String): File =
        File.createTempFile("upload_", ".$ext", context.cacheDir)

    /**
     * EXIF 旋正 + 缩到长边上限，**合并为一次 [Bitmap.createBitmap]**。
     *
     * ## 为什么必须合并（内存）
     *
     * `createBitmap(src, ..., matrix, true)` 在返回之前源图与目标图同时驻留堆上，
     * 这是 API 语义决定的、无法规避。原实现拆成 applyOrientation + scaleDown 两次调用，
     * 于是这个双份峰值要走两遍：
     *
     *   解码 12.6MB → 旋正峰值约 25MB → 再缩放又一次峰值
     *
     * 而 orientation=6（竖着拿手机拍）是**常态而非边缘情况**，
     * 且一次最多选 100 张、逐张串行处理，每张都撞一次。
     * 合并后只有一次双份峰值，且缩放通常无需发生（解码期的 density 缩放已经到位）。
     *
     * ## 顺序
     *
     * 先按缩放系数 postScale，再 postRotate：Matrix 的变换是左乘叠加，
     * 两者都作用于同一次采样，先后不影响结果，但缩放写在前面更直观
     * —— 系数是按「旋转后的尺寸」算的（见 swapsDimensions），
     * 因为长边上限约束的是最终产物。
     */
    private fun normalize(src: Bitmap, exifOrientation: Int): Bitmap {
        val t = ImagePrepPolicy.orientationTransform(exifOrientation)
        // 缩放系数按旋转后的尺寸计算：90°/270° 会让宽高互换，
        // 用旋转前的尺寸算会把约束加在错误的那条边上。
        val rotatedW = if (ImagePrepPolicy.swapsDimensions(exifOrientation)) src.height else src.width
        val rotatedH = if (ImagePrepPolicy.swapsDimensions(exifOrientation)) src.width else src.height
        val scale = ImagePrepPolicy.scaleFactor(rotatedW, rotatedH)

        // 既不用转也不用缩：直接返回原图，一次分配都不做。
        // 解码期已按 density 缩到目标尺寸，所以这是最常见的分支。
        if (t.isIdentity && scale == 1f) return src

        val m = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (t.rotationDegrees != 0f) postRotate(t.rotationDegrees)
            if (t.flipHorizontal) postScale(-1f, 1f)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }
}
