package com.linxi.diary.data

/**
 * 上传前图片预处理的纯策略（无 Android 依赖，便于 JVM 单测）。
 *
 * 为什么要在客户端预处理：
 * - 服务端解码链是纯 Go（镜像里没有 libvips），**不支持 HEIF/AVIF**。
 *   而 iPhone 默认拍 HEIC，从对方那里收到的图也可能是 HEIC。
 *   客户端 Android 原生能解 HEIF，所以在这里统一转成 JPEG 再传（决策 Q12=C）。
 * - 原图动辄 4000×3000 / 8MB，直接传既慢又白占服务器磁盘；长边压到 2048 对相册观感无损。
 * - EXIF 方向必须在客户端旋正：服务端纯 Go 解码链不读 EXIF 方向，
 *   否则竖拍照片在相册里会躺倒（这是最常见的"照片方向不对"投诉来源）。
 */
object ImagePrepPolicy {

    /** 上传前的长边上限。2048 在手机屏幕上放大看仍然清晰。 */
    const val MAX_EDGE = 2048

    /** JPEG 压缩质量。85 是肉眼无损与体积的常规平衡点。 */
    const val JPEG_QUALITY = 85

    /** 服务端 /media 的大小上限（20MB）。客户端提前拦，避免白传一趟。 */
    const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

    /**
     * 计算 BitmapFactory 的 inSampleSize：2 的幂，且保证解码后长边 >= MAX_EDGE。
     *
     * 必须先降采样再解码：直接全尺寸 decode 一张 4000×3000 的图要约 48MB 堆内存，
     * 连续几张就 OOM（壁纸页此前就是全尺寸 decode）。
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, maxEdge: Int = MAX_EDGE): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        val longEdge = maxOf(srcWidth, srcHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    /** 目标尺寸：长边缩到 maxEdge，短边等比取整（至少 1）。小图不放大。 */
    fun targetSize(srcWidth: Int, srcHeight: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        if (srcWidth <= maxEdge && srcHeight <= maxEdge) return srcWidth to srcHeight
        return if (srcWidth >= srcHeight) {
            maxEdge to maxOf(1, srcHeight * maxEdge / srcWidth)
        } else {
            maxOf(1, srcWidth * maxEdge / srcHeight) to maxEdge
        }
    }

    /**
     * EXIF orientation 值 → 需要旋转的角度与是否镜像。
     * 参考 ExifInterface.ORIENTATION_* 常量取值（1..8）。
     */
    fun orientationTransform(exifOrientation: Int): Transform = when (exifOrientation) {
        2 -> Transform(0f, flipHorizontal = true)
        3 -> Transform(180f)
        4 -> Transform(180f, flipHorizontal = true)
        5 -> Transform(90f, flipHorizontal = true)
        6 -> Transform(90f)
        7 -> Transform(270f, flipHorizontal = true)
        8 -> Transform(270f)
        else -> Transform(0f) // 1 = 正常，0/未知也按正常处理
    }

    data class Transform(val rotationDegrees: Float, val flipHorizontal: Boolean = false) {
        val isIdentity: Boolean get() = rotationDegrees == 0f && !flipHorizontal
    }

    /**
     * 上传用的目标 MIME。
     * 一切非 JPEG/PNG/WebP/GIF 的输入（主要是 HEIC/AVIF）都转成 JPEG，
     * 因为服务端纯 Go 解码链只认这四种。
     */
    fun targetMime(sourceMime: String?): String {
        val m = sourceMime?.lowercase()?.trim().orEmpty()
        return when {
            m == "image/png" -> "image/png"   // 保留 PNG：可能有透明通道
            m == "image/webp" -> "image/webp"
            m == "image/gif" -> "image/gif"   // 动图保持原样，不做压缩
            else -> "image/jpeg"              // jpeg 与一切未知/HEIC/AVIF
        }
    }

    /** GIF 不做重编码：重编码会丢掉动画，只剩第一帧。 */
    fun shouldRecompress(sourceMime: String?): Boolean =
        targetMime(sourceMime) != "image/gif"

    fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}
