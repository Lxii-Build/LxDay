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
}
