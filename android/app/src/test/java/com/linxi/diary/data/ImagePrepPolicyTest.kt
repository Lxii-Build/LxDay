package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePrepPolicyTest {

    @Test
    fun `降采样保证解码后长边不小于目标`() {
        // 4000x3000 目标 2048：sample=1 时 4000/2=2000 < 2048，故只能取 1。
        assertEquals(1, ImagePrepPolicy.sampleSize(4000, 3000))
        // 8000x6000：8000/2=4000 >= 2048 → 2；8000/4=2000 < 2048 → 停在 2。
        assertEquals(2, ImagePrepPolicy.sampleSize(8000, 6000))
        // 极大图逐级翻倍：40000/16=2500 >= 2048，再翻到 32 就只剩 1250 < 2048。
        assertEquals(16, ImagePrepPolicy.sampleSize(40000, 30000))
    }

    @Test
    fun `小图不降采样`() {
        assertEquals(1, ImagePrepPolicy.sampleSize(800, 600))
        assertEquals(1, ImagePrepPolicy.sampleSize(2048, 1536))
    }

    @Test
    fun `非法尺寸返回安全值而不是崩溃或死循环`() {
        assertEquals(1, ImagePrepPolicy.sampleSize(0, 0))
        assertEquals(1, ImagePrepPolicy.sampleSize(-5, 100))
        assertEquals(1 to 1, ImagePrepPolicy.targetSize(0, 0))
    }

    @Test
    fun `长边压到上限且短边等比`() {
        assertEquals(2048 to 1536, ImagePrepPolicy.targetSize(4000, 3000))
        assertEquals(1536 to 2048, ImagePrepPolicy.targetSize(3000, 4000))
    }

    @Test
    fun `小图不放大`() {
        assertEquals(800 to 600, ImagePrepPolicy.targetSize(800, 600))
    }

    @Test
    fun `极端长条图短边至少为1`() {
        // 4000x3 若直接按比例算短边会得 0，编码器会直接失败。
        val (_, h) = ImagePrepPolicy.targetSize(4000, 3)
        assertTrue("短边=$h 必须 >= 1", h >= 1)
    }

    @Test
    fun `HEIC与未知格式统一转JPEG`() {
        // 服务端纯 Go 解码链不支持 HEIF/AVIF（镜像里没有 libvips）。
        assertEquals("image/jpeg", ImagePrepPolicy.targetMime("image/heic"))
        assertEquals("image/jpeg", ImagePrepPolicy.targetMime("image/heif"))
        assertEquals("image/jpeg", ImagePrepPolicy.targetMime("image/avif"))
        assertEquals("image/jpeg", ImagePrepPolicy.targetMime(null))
        assertEquals("image/jpeg", ImagePrepPolicy.targetMime(""))
        assertEquals("image/jpeg", ImagePrepPolicy.targetMime("application/octet-stream"))
    }

    @Test
    fun `PNG与WebP保留原格式`() {
        // PNG 可能带透明通道，转 JPEG 会把透明区压成黑块。
        assertEquals("image/png", ImagePrepPolicy.targetMime("image/png"))
        assertEquals("image/webp", ImagePrepPolicy.targetMime("image/webp"))
        assertEquals("image/jpeg", ImagePrepPolicy.targetMime("IMAGE/JPEG"))
    }

    @Test
    fun `GIF不重编码以保住动画`() {
        assertEquals("image/gif", ImagePrepPolicy.targetMime("image/gif"))
        assertFalse(ImagePrepPolicy.shouldRecompress("image/gif"))
        assertTrue(ImagePrepPolicy.shouldRecompress("image/jpeg"))
        assertTrue(ImagePrepPolicy.shouldRecompress("image/heic"))
    }

    @Test
    fun `扩展名与目标MIME一致`() {
        assertEquals("jpg", ImagePrepPolicy.extensionFor("image/jpeg"))
        assertEquals("png", ImagePrepPolicy.extensionFor("image/png"))
        assertEquals("webp", ImagePrepPolicy.extensionFor("image/webp"))
        assertEquals("gif", ImagePrepPolicy.extensionFor("image/gif"))
    }

    @Test
    fun `EXIF方向映射覆盖全部八种取值`() {
        // 6=顺时针90（最常见的竖拍），8=逆时针90，3=180。
        assertEquals(90f, ImagePrepPolicy.orientationTransform(6).rotationDegrees)
        assertEquals(180f, ImagePrepPolicy.orientationTransform(3).rotationDegrees)
        assertEquals(270f, ImagePrepPolicy.orientationTransform(8).rotationDegrees)
        assertTrue(ImagePrepPolicy.orientationTransform(2).flipHorizontal)
        assertTrue(ImagePrepPolicy.orientationTransform(4).flipHorizontal)
        assertTrue(ImagePrepPolicy.orientationTransform(5).flipHorizontal)
        assertTrue(ImagePrepPolicy.orientationTransform(7).flipHorizontal)
    }

    @Test
    fun `方向为正常或未知时不做变换`() {
        assertTrue(ImagePrepPolicy.orientationTransform(1).isIdentity)
        assertTrue(ImagePrepPolicy.orientationTransform(0).isIdentity)
        assertTrue(ImagePrepPolicy.orientationTransform(99).isIdentity)
    }

    @Test
    fun `上传上限与服务端一致`() {
        // 服务端 /media 限 20MB，客户端提前拦以免白传一趟。
        assertEquals(20L * 1024 * 1024, ImagePrepPolicy.MAX_UPLOAD_BYTES)
    }
}
