package com.linxi.diary.data

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
