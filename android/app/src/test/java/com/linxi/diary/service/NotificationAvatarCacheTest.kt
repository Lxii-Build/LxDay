package com.linxi.diary.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAvatarCacheTest {
    @Test
    fun `通知头像读取固定私有缓存文件`() {
        val filesDir = File("/app/files")

        assertEquals(
            File(filesDir, "avatar/partner_notification.png"),
            NotificationAvatarCache.cacheFile(filesDir),
        )
    }

    @Test
    fun `不存在缓存时指纹为零`() {
        assertEquals(0L, NotificationAvatarCache.fingerprint(File("/missing/files")))
    }

    @Test
    fun `只接受受限大小的静态缩略图`() {
        assertTrue(NotificationAvatarCache.isSafeThumbnail(256, 256, 512 * 1024))
        assertFalse(NotificationAvatarCache.isSafeThumbnail(0, 256, 512 * 1024))
        assertFalse(NotificationAvatarCache.isSafeThumbnail(1024, 1024, 512 * 1024))
        assertFalse(NotificationAvatarCache.isSafeThumbnail(256, 256, 5L * 1024 * 1024))
    }
}
