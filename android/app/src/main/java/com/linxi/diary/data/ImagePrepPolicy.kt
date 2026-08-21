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
     *
     * 注意它只能把尺寸压到「不小于 MAX_EDGE 的最近 2 的幂」，**单靠它不够**：
     * 4000×3000（最常见的手机直出）算出 sample=1（因为 4000/2=2000 < 2048），
     * 于是仍然全尺寸解码 45.8MB。剩下的非整数倍缩放交给 [decodeDensityScale]。
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

    /**
     * 解码期缩放参数（`inDensity` / `inTargetDensity`），配合 `inScaled = true` 使用。
     *
     * 这是 Android 官方推荐的「一次解码直接出目标尺寸」做法：BitmapFactory 在 native 层
     * 边解码边缩放，**只分配最终尺寸的 Bitmap**，不会先在堆里放一张全尺寸大图。
     *
     * 为什么必须这么做（实测数据，见 ImagePrepPolicyMemoryTest）：
     *   4000×3000 → sampleSize=1 → 全尺寸解码 **45.8MB**，再 scaleDown 复制一份 → 峰值 ~58MB
     *   接上 inDensity 缩放后 → 直接解出 2048×1536 = **12.6MB**，且无需再 scaleDown
     * 连续上传十几张时，前者极易 OOM —— 而 ImagePrep 失败只会静默 `uploadFailed++`，
     * 表现就是管理员说的「有些图片会消失」「一张都没成功上传」。
     *
     * @return (inDensity, inTargetDensity)；返回 null 表示无需解码期缩放（图本身已足够小）。
     */
    fun decodeDensityScale(
        srcWidth: Int,
        srcHeight: Int,
        sampleSize: Int,
        maxEdge: Int = MAX_EDGE,
    ): Pair<Int, Int>? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        val s = if (sampleSize < 1) 1 else sampleSize
        val sampledLong = maxOf(srcWidth / s, srcHeight / s)
        // 小图不放大：inTargetDensity > inDensity 会把图拉大，纯属浪费内存与画质。
        if (sampledLong <= maxEdge) return null
        return sampledLong to maxEdge
    }

    /**
     * 走完 sampleSize + decodeDensityScale 之后，解码出的 Bitmap 实际像素数。
     * 供内存回归测试断言，避免"改了缩放逻辑但内存没真的降下来"。
     */
    fun decodedPixelCount(srcWidth: Int, srcHeight: Int, maxEdge: Int = MAX_EDGE): Long {
        if (srcWidth <= 0 || srcHeight <= 0) return 0
        val s = sampleSize(srcWidth, srcHeight, maxEdge)
        var w = srcWidth / s
        var h = srcHeight / s
        decodeDensityScale(srcWidth, srcHeight, s, maxEdge)?.let { (density, target) ->
            // BitmapFactory 的换算：ceil(dim * target / density)
            w = (w.toLong() * target / density).toInt().coerceAtLeast(1)
            h = (h.toLong() * target / density).toInt().coerceAtLeast(1)
        }
        return w.toLong() * h.toLong()
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
     * 一切非 JPEG/PNG/WebP/GIF 的输入（主要是 HEIC/AVIF）都转成 JPEG。
     *
     * 注：服务端 0821 起已能真解 HEIC/AVIF/BMP（纯 Go wasm 解码器），
     * 但客户端仍统一转 JPEG——本机转换比服务端解码快得多，也省上传流量。
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

    /**
     * 是否需要重编码。
     *
     * **动图一律不重编码**：`Bitmap.compress` 只会写出单帧，
     * 结果是用户传上去的动图变成一张静止图——这是实打实的数据损失。
     * GIF 早就排除了，但**动态 WebP 此前会被压成静态 `WEBP_LOSSY`**（0821 修）。
     *
     * @param animated 是否为动图。GIF 由 MIME 即可判定；WebP 必须读文件头的 ANIM chunk
     *   才知道（见 [isAnimatedWebp]），所以由调用方探测后传进来。
     */
    fun shouldRecompress(sourceMime: String?, animated: Boolean = false): Boolean {
        val target = targetMime(sourceMime)
        if (target == "image/gif") return false
        // 动态 WebP：原样上传，保住动画
        if (animated && target == "image/webp") return false
        return true
    }

    /**
     * 从 WebP 文件头判断是否为动图。
     *
     * WebP 是 RIFF 容器：`RIFF....WEBP` 之后是一串 chunk，
     * 动图的标志是存在 `ANIM` chunk（扩展格式 VP8X 的 animation 位也可以，
     * 但 ANIM chunk 更直接可靠）。只需读前 64 字节，不必解码整张图。
     *
     * 与服务端 `avatar_format.go` 的同名判定保持一致的思路（那边也是找 ANIM）。
     *
     * @param head 文件的前若干字节（>=16 才有意义）
     */
    fun isAnimatedWebp(head: ByteArray): Boolean {
        if (head.size < 16) return false
        // RIFF....WEBP
        if (!(head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte())
        ) {
            return false
        }
        if (!(head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
                head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte())
        ) {
            return false
        }
        // 在头部范围内找 "ANIM" 标记
        val marker = byteArrayOf(
            'A'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte()
        )
        outer@ for (i in 12..head.size - 4) {
            for (j in 0..3) {
                if (head[i + j] != marker[j]) continue@outer
            }
            return true
        }
        return false
    }

    fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}
