package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传前预处理的内存足迹回归测试。
 *
 * 背景：管理员报「服务器一张照片都没成功上传过」。服务端 handler 经端到端测试证明通畅，
 * 故怀疑客户端 [ImagePrep] 在解码阶段就 OOM 了（失败时只 `uploadFailed++`，UI 上看不出原因）。
 *
 * [ImagePrepPolicy.sampleSize] 的循环条件是 `longEdge / (sample*2) >= maxEdge`，
 * 即它保证**解码后长边 >= 2048**。对 4000×3000 这种最常见的手机直出尺寸，
 * 4000/2 = 2000 < 2048 → sample 停在 1 → **全尺寸解码**。
 * ARGB_8888 下 4000×3000×4B = 45.8MB，随后 scaleDown 再建一张 2048 长边副本(+16MB)，
 * 单张峰值 ~62MB。一加 15 单进程堆上限约 256~512MB，单张能扛住，
 * 但循环上传时上一张的 Bitmap 未必已被 GC 回收。
 */
class ImagePrepPolicyMemoryTest {

    /** ARGB_8888 每像素 4 字节。走完 sampleSize + decodeDensityScale 后的实际分配量。 */
    private fun decodedBytes(w: Int, h: Int): Long =
        ImagePrepPolicy.decodedPixelCount(w, h) * 4L

    @Test
    fun `常见手机直出尺寸解码后不应超过 24MB`() {
        // 4000x3000（1200 万像素）是绝大多数安卓主摄的默认输出。
        val sizes = listOf(
            4000 to 3000,   // 12MP 4:3
            4096 to 3072,
            4080 to 3060,   // 一加/OPPO 常见
            8160 to 6120,   // 5000 万像素高像素模式
            9248 to 6944,   // 6400 万像素
            3000 to 4000,   // 竖拍
        )
        val failures = mutableListOf<String>()
        for ((w, h) in sizes) {
            val mb = decodedBytes(w, h) / 1024.0 / 1024.0
            val sample = ImagePrepPolicy.sampleSize(w, h)
            val scale = ImagePrepPolicy.decodeDensityScale(w, h, sample)
            val px = ImagePrepPolicy.decodedPixelCount(w, h)
            println(
                "${w}x$h  sample=$sample  density=$scale  " +
                    "解码像素=$px  ${"%.1f".format(mb)}MB"
            )
            if (mb > 24.0) failures += "${w}x$h → ${"%.1f".format(mb)}MB (sample=$sample, density=$scale)"
        }
        assertTrue(
            "以下尺寸解码内存过高，连续上传时会 OOM：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `sampleSize 必须保证解码后长边不小于目标长边`() {
        // 这是 sampleSize 的既有契约：降采样后仍要够清晰，scaleDown 才能精确缩到 2048。
        for (w in listOf(2048, 2049, 3000, 4000, 4096, 6000, 8160, 9248, 12000)) {
            val s = ImagePrepPolicy.sampleSize(w, w * 3 / 4)
            val decodedLong = w / s
            assertTrue(
                "w=$w sample=$s 解码长边=$decodedLong 小于 ${ImagePrepPolicy.MAX_EDGE}",
                decodedLong >= ImagePrepPolicy.MAX_EDGE,
            )
        }
    }
}

/**
 * 「上传显示无法读取图片」的回归测试（0822 查明的真凶）。
 *
 * ## 根因
 *
 * [ImagePrep] 里读边界那步原本写成：
 * ```
 * resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
 *     ?: error("无法读取所选图片")
 * ```
 * `use{}` 返回 lambda 的值，即 `decodeStream` 的返回值；而 `inJustDecodeBounds = true` 时
 * **`decodeStream` 按设计永远返回 null**（只填 outWidth/outHeight，不产出 Bitmap）。
 * 于是 `?:` 恒成立 → **每一张需要重编码的图都在第一步抛异常**
 *（除 GIF 与动态 WebP 走 copyAsIs 之外的全部图片）。
 *
 * 这就是管理员报的「上传显示无法读取图片」，也是 Q57「服务器没成功上传过一张」的
 * **第一道墙**（第二道是服务端中间件不排空 body 导致的 502，已另修）。
 *
 * ## 为什么此前的测试没抓到
 *
 * 上传链路的测试全在 [ImagePrepPolicy] 这层纯策略上（内存足迹、动图判定），
 * 而这个 bug 在 [ImagePrep] 的 Android 调用处，`BitmapFactory` 在 JVM 单测里不可用。
 * 修法是把判定逻辑抽成 [ImagePrepPolicy.boundsFailure] —— 让"该报错吗"这个决定
 * 落在可测的一侧，Android 那侧只负责喂 `streamOpened` 与尺寸。
 */
class BoundsFailureTest {

    @Test
    fun `流打开且尺寸正常时绝不能报错`() {
        // **这条就是 bug 的直接复现**：正常图片必须放行。
        // 旧写法在这种情形下也会抛「无法读取所选图片」。
        assertNull(
            "4000×3000 的正常照片被判为失败——这正是「一张都传不上去」的原因",
            ImagePrepPolicy.boundsFailure(streamOpened = true, outWidth = 4000, outHeight = 3000),
        )
        assertNull(ImagePrepPolicy.boundsFailure(true, 1, 1))
        assertNull(ImagePrepPolicy.boundsFailure(true, 9248, 6944))
    }

    @Test
    fun `流打不开才是读不到`() {
        // 权限被撤、文件已删、uri 失效
        assertEquals(
            "无法读取所选图片",
            ImagePrepPolicy.boundsFailure(streamOpened = false, outWidth = 0, outHeight = 0),
        )
        // 流没打开时即便尺寸字段有值（不可能，但防御），也应报"读不到"而非"损坏"
        assertEquals("无法读取所选图片", ImagePrepPolicy.boundsFailure(false, 4000, 3000))
    }

    @Test
    fun `流能开但尺寸为零是格式或损坏问题`() {
        // 两种失败必须给不同文案：用户才能分辨该换一张，还是该去给权限。
        assertEquals("图片已损坏或格式不支持", ImagePrepPolicy.boundsFailure(true, 0, 0))
        assertEquals("图片已损坏或格式不支持", ImagePrepPolicy.boundsFailure(true, 4000, 0))
        assertEquals("图片已损坏或格式不支持", ImagePrepPolicy.boundsFailure(true, 0, 3000))
        assertEquals("图片已损坏或格式不支持", ImagePrepPolicy.boundsFailure(true, -1, -1))
    }

    @Test
    fun `两种失败文案必须不同`() {
        val cantRead = ImagePrepPolicy.boundsFailure(false, 0, 0)
        val broken = ImagePrepPolicy.boundsFailure(true, 0, 0)
        assertTrue("文案相同则用户无法分辨该换图还是该给权限", cantRead != broken)
    }
}

/**
 * 动图保真回归（Q10=A）。
 *
 * 此前 GIF 走 copyAsIs 保住了动画，但**动态 WebP 会被重编码成静态 WEBP_LOSSY**
 * （ImagePrep 里 `Bitmap.compress(WEBP_LOSSY)` 只写单帧），
 * 用户传上去的动图变成一张静止图——实打实的数据损失。
 */
class AnimatedWebpTest {

    /** 造一个最小的 WebP 头：RIFF....WEBP + 可选的 ANIM chunk。 */
    private fun webpHead(animated: Boolean): ByteArray {
        val out = ArrayList<Byte>()
        "RIFF".forEach { out.add(it.code.toByte()) }
        repeat(4) { out.add(0) }                    // 文件长度占位
        "WEBP".forEach { out.add(it.code.toByte()) }
        if (animated) {
            "VP8X".forEach { out.add(it.code.toByte()) }
            repeat(10) { out.add(0) }
            "ANIM".forEach { out.add(it.code.toByte()) }
            repeat(6) { out.add(0) }
        } else {
            "VP8 ".forEach { out.add(it.code.toByte()) }
            repeat(16) { out.add(0) }
        }
        return out.toByteArray()
    }

    @Test
    fun `动态WebP必须被识别出来`() {
        assertTrue(
            "带 ANIM chunk 的 WebP 应判为动图",
            ImagePrepPolicy.isAnimatedWebp(webpHead(animated = true)),
        )
    }

    @Test
    fun `静态WebP不应误判为动图`() {
        assertTrue(
            "不带 ANIM 的 WebP 应判为静态",
            !ImagePrepPolicy.isAnimatedWebp(webpHead(animated = false)),
        )
    }

    @Test
    fun `非WebP数据不应误判`() {
        // JPEG 头（0xD8/0xE0 超出 Byte 范围，必须显式 toByte）
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        assertTrue("JPEG 不应被判为动态 WebP", !ImagePrepPolicy.isAnimatedWebp(jpeg))
        // 过短的数据不应崩溃
        assertTrue(!ImagePrepPolicy.isAnimatedWebp(byteArrayOf(1, 2, 3)))
        assertTrue(!ImagePrepPolicy.isAnimatedWebp(ByteArray(0)))
    }

    @Test
    fun `动图一律不重编码`() {
        // GIF：看 MIME 就够
        assertTrue(!ImagePrepPolicy.shouldRecompress("image/gif"))
        // 动态 WebP：必须不重编码，否则动画丢失
        assertTrue(
            "动态 WebP 重编码会只剩一帧",
            !ImagePrepPolicy.shouldRecompress("image/webp", animated = true),
        )
        // 静态 WebP：可以重编码（压尺寸省流量）
        assertTrue(ImagePrepPolicy.shouldRecompress("image/webp", animated = false))
        // 其它格式不受影响
        assertTrue(ImagePrepPolicy.shouldRecompress("image/jpeg"))
        assertTrue(ImagePrepPolicy.shouldRecompress("image/heic"))
    }
}
