package com.linxi.diary.ui.theme

import kotlin.math.max

/** 壁纸裁剪与输出的纯几何策略，无 Android 依赖，便于 JVM 单测。 */
object WallpaperCropPolicy {
    const val MAX_OUTPUT_WIDTH = 1440
    const val MAX_OUTPUT_HEIGHT = 3200
    const val MAX_SCALE = 8.0f

    /** 输出宽度按物理屏幕，封顶 1440px。 */
    fun outputWidth(screenWidthPx: Int): Int = screenWidthPx.coerceAtMost(MAX_OUTPUT_WIDTH)

    /** 输出高度按屏幕比例，封顶 3200px。 */
    fun outputHeight(screenWidthPx: Int, screenHeightPx: Int): Int {
        val width = outputWidth(screenWidthPx)
        val scaled = (screenHeightPx.toLong() * width / screenWidthPx).toInt()
        return scaled.coerceAtMost(MAX_OUTPUT_HEIGHT)
    }

    /** 覆盖裁剪框所需最小缩放：宽高两方向取较大者，保证不露白。 */
    fun minScaleToCover(sourceWidth: Int, sourceHeight: Int, frameWidth: Int, frameHeight: Int): Float {
        val scaleX = frameWidth.toFloat() / sourceWidth
        val scaleY = frameHeight.toFloat() / sourceHeight
        return max(scaleX, scaleY)
    }

    /** 缩放钳制：不小于覆盖下限，不超过 MAX_SCALE。 */
    fun clampScale(requested: Float, minScale: Float): Float =
        requested.coerceIn(minScale, max(minScale, MAX_SCALE))

    /** 平移钳制：放大后内容超出裁剪框的部分是可平移余量，越界回夹避免露白。 */
    fun clampTranslation(offset: Float, scaledSize: Float, frameSize: Float): Float {
        val slack = (scaledSize - frameSize) / 2f
        if (slack <= 0f) return 0f
        return offset.coerceIn(-slack, slack)
    }

    /**
     * 解码壁纸时的 `inSampleSize`（2 的幂）。
     *
     * ## 为什么必须有
     *
     * [WallpaperHost] 原先是裸的 `BitmapFactory.decodeFile(path)`：无边界探测、
     * 无降采样、无尺寸校验。而这个 path 只是 SharedPreferences 里的一个字符串
     * （AppearanceStore 读回来的），**本代码里没有任何生产者会去保证它的尺寸**
     * —— 上面那些 MAX_OUTPUT_* 常量在 main 源码里没有调用方。
     *
     * 也就是说它解的是一个尺寸完全不受约束的文件。而这份 ImageBitmap 位于
     * **根 composable**、进程存活期间常驻不释放，直接压缩了相册解码可用的堆余量
     * （Coil 的内存缓存还要再占堆的 25%）。相册那边一旦 OOM，
     * 用户看到的是"照片上传失败/消失"，根因却在壁纸功能里，极难联想。
     *
     * ## 判据是「像素预算」而不是「不许放大」
     *
     * 第一版写成"两个方向都仍然覆盖屏幕才继续降采样"（即绝不放大、绝不发虚）。
     * 那个条件在**长宽比不匹配**时根本不会生效：一张 4000×3000 的横图配
     * 1080×2400 的竖屏，高 3000/2 = 1500 < 2400，于是 sample 停在 1，
     * 老老实实解出 45.8MB —— 等于这道闸完全没起作用。
     *
     * 根本原因是两个目标冲突：Crop 语义下"永不放大"要求保留短边的全部像素，
     * 而短边恰好是被裁掉最多的那条边。既然壁纸还盖着一层遮罩（scrimAlpha），
     * 轻微的软化完全看不出来，那就应当**优先保证内存有上界**。
     *
     * 所以改为：降采样到「解码像素数 <= 屏幕像素数」为止。
     * 这条判据与长宽比无关，任何输入都有确定的内存上界
     * （1440×3200 的屏幕上界即 18.4MB，恰好是"铺满一屏"的诚实成本）。
     *
     * 代价是长宽比不匹配的图会被 Crop 略微放大、看起来软一点。
     * 这个取舍是有意的：壁纸上面盖着 scrimAlpha 遮罩，软化几乎不可见，
     * 而省下来的几十 MB 是相册能不能成功解码的关键。
     *
     * @param reqWidth 目标宽（一般传屏幕宽，封顶 [MAX_OUTPUT_WIDTH]）
     * @param reqHeight 目标高（一般传屏幕高，封顶 [MAX_OUTPUT_HEIGHT]）
     */
    fun decodeSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int = MAX_OUTPUT_WIDTH,
        reqHeight: Int = MAX_OUTPUT_HEIGHT,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        val w = reqWidth.coerceAtLeast(1)
        val h = reqHeight.coerceAtLeast(1)
        val budget = w.toLong() * h.toLong()
        var sample = 1
        // 已在预算内就不动：再降只会让壁纸发虚，白损画质。
        while (
            (srcWidth / sample).toLong() * (srcHeight / sample).toLong() > budget &&
            srcWidth / (sample * 2) >= 1 &&
            srcHeight / (sample * 2) >= 1
        ) {
            sample *= 2
        }
        return sample
    }
}
