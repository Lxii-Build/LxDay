package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「旋正 + 缩放合并成一次分配」的回归测试。
 *
 * ## 背景
 *
 * `Bitmap.createBitmap(src, ..., matrix, true)` 在返回前源图与目标图**同时驻留堆上**，
 * 这是 API 语义决定的、无法规避。原实现把旋正与缩放拆成两次调用
 * （`applyOrientation` + `scaleDown`），于是这个双份峰值要经历两遍：
 *
 *   解码 12.6MB → 旋正峰值约 25MB → 再缩放又一次峰值
 *
 * 而 EXIF orientation=6（竖着拿手机拍）是**常态而非边缘情况**，
 * 一次选图上限 100 张、逐张串行处理，每张都撞一次。
 *
 * ## 为什么测的是策略函数而不是 ImagePrep
 *
 * `Bitmap` / `Matrix` 在 JVM 单测里不可用（这正是 0822 那个「一张都传不上去」的 bug
 * 能躲过全部测试的原因）。所以把「缩放系数怎么算」「宽高会不会互换」
 * 这两个决定抽进 [ImagePrepPolicy]，让它们落在可测的一侧。
 */
class ImagePrepNormalizeTest {

    @Test
    fun `长边未超上限时不缩放`() {
        // 解码期的 density 缩放已经把图缩到位，这里必须返回 1f，
        // 否则会白做一次 createBitmap（多一次双份峰值）。
        assertEquals(1f, ImagePrepPolicy.scaleFactor(2048, 1536), 0.0001f)
        assertEquals(1f, ImagePrepPolicy.scaleFactor(1024, 768), 0.0001f)
        assertEquals(1f, ImagePrepPolicy.scaleFactor(2048, 2048), 0.0001f)
    }

    @Test
    fun `超过上限时按长边等比缩`() {
        // 4096 长边 → 2048/4096 = 0.5
        assertEquals(0.5f, ImagePrepPolicy.scaleFactor(4096, 3072), 0.0001f)
        // 竖图：长边是高
        assertEquals(0.5f, ImagePrepPolicy.scaleFactor(3072, 4096), 0.0001f)
        // 非整数倍
        assertEquals(2048f / 4000f, ImagePrepPolicy.scaleFactor(4000, 3000), 0.0001f)
    }

    @Test
    fun `非法尺寸不应崩溃或返回负数`() {
        assertEquals(1f, ImagePrepPolicy.scaleFactor(0, 0), 0.0001f)
        assertEquals(1f, ImagePrepPolicy.scaleFactor(-1, 100), 0.0001f)
    }

    @Test
    fun `九十度与二百七十度旋转会互换宽高`() {
        // orientation 6 = 90°，8 = 270°，5/7 带镜像但同样是 90/270
        assertTrue("orientation 6（竖拍常态）必须判为宽高互换", ImagePrepPolicy.swapsDimensions(6))
        assertTrue(ImagePrepPolicy.swapsDimensions(8))
        assertTrue(ImagePrepPolicy.swapsDimensions(5))
        assertTrue(ImagePrepPolicy.swapsDimensions(7))
    }

    @Test
    fun `零度与一百八十度不互换宽高`() {
        assertTrue(!ImagePrepPolicy.swapsDimensions(1)) // 正常
        assertTrue(!ImagePrepPolicy.swapsDimensions(2)) // 镜像
        assertTrue(!ImagePrepPolicy.swapsDimensions(3)) // 180°
        assertTrue(!ImagePrepPolicy.swapsDimensions(4))
        assertTrue(!ImagePrepPolicy.swapsDimensions(0)) // 未知按正常
    }

    /**
     * ★ 这条是合并逻辑的关键正确性约束 ★
     *
     * 缩放系数必须按**旋转后**的尺寸算。用旋转前的尺寸算会把长边约束加在错误的那条边上：
     * 一张 1536×2048 的竖图配 orientation=6（转 90° 后变成 2048×1536），
     * 若按旋转前算，长边是 2048 → 不缩；按旋转后算，长边同样 2048 → 不缩。
     * 但换成 3000×4000 配 orientation=6：旋转后是 4000×3000，长边 4000 必须缩到 2048。
     * 两种算法在这里给出相同答案（因为长边就是长边），
     * 真正会分叉的是**非方形且只有一边超限**的情形，见下面第二组断言。
     */
    @Test
    fun `缩放系数按旋转后的尺寸计算`() {
        // 1000×4000 竖长条，orientation=6 → 旋转后 4000×1000
        // 无论怎么转，长边都是 4000，系数应为 2048/4000
        val swapped = ImagePrepPolicy.swapsDimensions(6)
        val w = if (swapped) 4000 else 1000
        val h = if (swapped) 1000 else 4000
        assertEquals(2048f / 4000f, ImagePrepPolicy.scaleFactor(w, h), 0.0001f)

        // 只有一边超限：3000×1000，长边 3000 → 缩到 2048/3000
        assertEquals(2048f / 3000f, ImagePrepPolicy.scaleFactor(3000, 1000), 0.0001f)
        // 互换后 1000×3000，长边仍是 3000，系数相同——
        // 这正是"按长边算"的性质，保证旋转不会改变最终长边。
        assertEquals(2048f / 3000f, ImagePrepPolicy.scaleFactor(1000, 3000), 0.0001f)
    }

    /**
     * 缩放后的长边必须恰好落在上限内（允许 1px 取整误差）。
     * 这是「合并之后产物尺寸没有变化」的保证——合并只该省内存，不该改结果。
     */
    @Test
    fun `缩放后长边不超过上限`() {
        val sizes = listOf(
            4000 to 3000, 4096 to 3072, 8160 to 6120, 9248 to 6944,
            3000 to 4000, 2049 to 100, 100 to 2049,
        )
        for ((w, h) in sizes) {
            val s = ImagePrepPolicy.scaleFactor(w, h)
            val outLong = (maxOf(w, h) * s).toInt()
            assertTrue(
                "${w}x$h 缩放后长边 $outLong 超过 ${ImagePrepPolicy.MAX_EDGE}",
                outLong <= ImagePrepPolicy.MAX_EDGE + 1,
            )
        }
    }

    /**
     * 合并后与旧的两步法在**最终尺寸**上必须一致。
     * 旧法是 targetSize（长边压到 2048），新法是 scaleFactor 乘上去，
     * 两者结果应当相同——否则这次改动会悄悄改变上传产物的尺寸。
     */
    @Test
    fun `合并前后最终尺寸一致`() {
        for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 2048 to 1536, 1000 to 800)) {
            val (tw, th) = ImagePrepPolicy.targetSize(w, h)
            val s = ImagePrepPolicy.scaleFactor(w, h)
            val nw = (w * s).toInt()
            val nh = (h * s).toInt()
            assertTrue(
                "${w}x$h：旧法 ${tw}x$th vs 新法 ${nw}x$nh 差异过大",
                kotlin.math.abs(tw - nw) <= 1 && kotlin.math.abs(th - nh) <= 1,
            )
        }
    }
}
