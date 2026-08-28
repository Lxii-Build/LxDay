package com.linxi.diary.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸解码降采样的回归测试。
 *
 * ## 背景
 *
 * [WallpaperHost] 原先是裸的 `BitmapFactory.decodeFile(path)`：
 * 无边界探测、无降采样、无尺寸上限。而那个 path 只是 SharedPreferences 里的字符串，
 * **尺寸不受本代码任何约束**（[WallpaperCropPolicy] 的 MAX_OUTPUT_* 常量在 main 源码里
 * 找不到生产者）。解出来的 ImageBitmap 挂在根 composable 上、整个进程生命周期常驻，
 * 按 1440×3200 算就是 18.4MB 一直不还。
 *
 * 它与 Coil 内存缓存（堆的 25%）叠加后直接吃掉相册解码的余量 ——
 * 相册那边 OOM，用户看到的是"照片上传失败"，根因却在壁纸功能里。
 */
class WallpaperDecodeTest {

    /** ARGB_8888 每像素 4 字节 */
    private fun mbAfterSample(w: Int, h: Int): Double {
        val s = WallpaperCropPolicy.decodeSampleSize(w, h)
        return (w / s).toLong() * (h / s).toLong() * 4L / 1024.0 / 1024.0
    }

    @Test
    fun `屏幕尺寸以内的图不降采样`() {
        // 已经是目标尺寸，再降采样只会让壁纸发虚。
        assertEquals(1, WallpaperCropPolicy.decodeSampleSize(1440, 3200))
        assertEquals(1, WallpaperCropPolicy.decodeSampleSize(1080, 2400))
        assertEquals(1, WallpaperCropPolicy.decodeSampleSize(720, 1600))
    }

    @Test
    fun `超大图必须降采样`() {
        // 相机直出 4000×3000 当壁纸：两个方向都远超需求
        assertTrue(
            "4000x3000 应被降采样",
            WallpaperCropPolicy.decodeSampleSize(4000, 3000, reqWidth = 1080, reqHeight = 2400) > 1,
        )
        // 8K 图
        assertTrue(WallpaperCropPolicy.decodeSampleSize(7680, 4320, 1080, 2400) > 1)
    }

    /**
     * 判据是「像素预算」而非「不许放大」。
     *
     * 最初我写的是"降完必须仍然覆盖屏幕两个方向"，那条在长宽比不匹配时
     * **根本不会触发降采样**：4000×3000 的横图配 1080×2400 竖屏，
     * 高 3000/2 = 1500 < 2400 → sample 停在 1 → 照样解 45.8MB。
     * Crop 语义下"永不放大"要求保住短边全部像素，而短边正是被裁掉最多的那条，
     * 两个目标天然冲突。壁纸还盖着遮罩，轻微软化看不出来，故优先保证内存有上界。
     */
    @Test
    fun `降采样后像素数落在预算内`() {
        val req = 1080 to 2400
        val budget = req.first.toLong() * req.second.toLong()
        for ((w, h) in listOf(
            2160 to 4800, 4320 to 9600, 3000 to 6000, 4000 to 3000, 7680 to 4320,
        )) {
            val s = WallpaperCropPolicy.decodeSampleSize(w, h, req.first, req.second)
            val px = (w / s).toLong() * (h / s).toLong()
            assertTrue(
                "${w}x$h sample=$s 解码像素 $px 超过预算 $budget",
                px <= budget,
            )
        }
    }

    @Test
    fun `已在预算内的图不会被无谓降采样`() {
        // 降过头会让壁纸发虚，白损画质。
        assertEquals(1, WallpaperCropPolicy.decodeSampleSize(1080, 2400, 1080, 2400))
        assertEquals(1, WallpaperCropPolicy.decodeSampleSize(1440, 3200, 1440, 3200))
    }

    @Test
    fun `非法尺寸不崩溃也不除零`() {
        assertEquals(1, WallpaperCropPolicy.decodeSampleSize(0, 0))
        assertEquals(1, WallpaperCropPolicy.decodeSampleSize(-1, 100))
        // 需求尺寸为 0 时不能除零。此时预算被夹到 1×1×2，
        // 于是会一路降到最小 —— 结果不重要，不抛异常且有界才是要点。
        val s = WallpaperCropPolicy.decodeSampleSize(100, 100, 0, 0)
        assertTrue("sample 应为正数，实得 $s", s >= 1)
    }

    /**
     * ★ 这条是本次修复的真正目标：常驻内存要压下来。
     *
     * 壁纸是根 composable 上的常驻对象，它占的每一 MB 都是相册解码永久少掉的余量。
     */
    @Test
    fun `常见超大图解码后不应超过 20MB`() {
        val failures = mutableListOf<String>()
        for ((w, h) in listOf(
            4000 to 3000,
            4096 to 3072,
            7680 to 4320,
            9248 to 6944,
            3000 to 6000,
        )) {
            val mb = mbAfterSample(w, h)
            if (mb > 20.0) failures += "${w}x$h → ${"%.1f".format(mb)}MB"
        }
        assertTrue(
            "以下尺寸作为壁纸会常驻过多内存：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
