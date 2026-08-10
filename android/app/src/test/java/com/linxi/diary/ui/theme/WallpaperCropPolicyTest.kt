package com.linxi.diary.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperCropPolicyTest {

    @Test
    fun `输出宽度按物理屏幕但封顶 1440`() {
        assertEquals(1080, WallpaperCropPolicy.outputWidth(screenWidthPx = 1080))
        assertEquals(1440, WallpaperCropPolicy.outputWidth(screenWidthPx = 1440))
        assertEquals(1440, WallpaperCropPolicy.outputWidth(screenWidthPx = 3000))
    }

    @Test
    fun `输出高度按屏幕比例且封顶 3200`() {
        // 1080x2400 屏，输出宽 1080 → 高 2400。
        assertEquals(2400, WallpaperCropPolicy.outputHeight(screenWidthPx = 1080, screenHeightPx = 2400))
        // 超高屏：1440x6000 → 宽封顶 1440，等比高 6000 但封顶 3200。
        assertEquals(3200, WallpaperCropPolicy.outputHeight(screenWidthPx = 1440, screenHeightPx = 6000))
    }

    @Test
    fun `最小缩放保证图片铺满裁剪框不露白`() {
        // 源 2000x1000，裁剪框 1000x2000（竖屏），需覆盖高度：scale >= 框高/源高。
        val minScale = WallpaperCropPolicy.minScaleToCover(
            sourceWidth = 2000, sourceHeight = 1000,
            frameWidth = 1000, frameHeight = 2000,
        )
        // 源高 1000 要覆盖框高 2000 → 至少 2.0；宽向已足够。
        assertEquals(2.0f, minScale, 0.001f)
    }

    @Test
    fun `平移偏移被夹在不露白区间内`() {
        // 源被放大到 2000 宽，框 1000 宽 → 最多可平移 (2000-1000)/2 = 500。
        assertEquals(500f, WallpaperCropPolicy.clampTranslation(offset = 900f, scaledSize = 2000f, frameSize = 1000f), 0.001f)
        assertEquals(-500f, WallpaperCropPolicy.clampTranslation(offset = -900f, scaledSize = 2000f, frameSize = 1000f), 0.001f)
        assertEquals(120f, WallpaperCropPolicy.clampTranslation(offset = 120f, scaledSize = 2000f, frameSize = 1000f), 0.001f)
    }

    @Test
    fun `缩放不小于覆盖下限`() {
        assertEquals(2.0f, WallpaperCropPolicy.clampScale(requested = 1.0f, minScale = 2.0f), 0.001f)
        assertEquals(3.5f, WallpaperCropPolicy.clampScale(requested = 3.5f, minScale = 2.0f), 0.001f)
    }

    @Test
    fun `缩放存在上限防止过度放大`() {
        assertTrue(WallpaperCropPolicy.clampScale(requested = 100f, minScale = 1.0f) <= WallpaperCropPolicy.MAX_SCALE)
    }
}
