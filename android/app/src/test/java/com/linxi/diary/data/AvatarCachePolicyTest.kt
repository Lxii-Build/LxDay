package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarCachePolicyTest {
    @Test
    fun `缓存键随 URL 变化而变化`() {
        val a = AvatarCachePolicy.cacheKey("https://cdn.invalid/a.webp")
        val b = AvatarCachePolicy.cacheKey("https://cdn.invalid/b.webp")
        assertNotEquals(a, b)
    }

    @Test
    fun `同一 URL 缓存键稳定且文件名安全`() {
        val key = AvatarCachePolicy.cacheKey("https://cdn.invalid/x.webp?v=3")
        assertEquals(key, AvatarCachePolicy.cacheKey("https://cdn.invalid/x.webp?v=3"))
        assertTrue(key.matches(Regex("[a-f0-9]{16,}\\.png")))
    }

    @Test
    fun `空 URL 不产生缓存键`() {
        assertEquals(null, AvatarCachePolicy.cacheKey(""))
        assertEquals(null, AvatarCachePolicy.cacheKey("   "))
    }

    @Test
    fun `失败退避随连续失败次数指数增长并封顶`() {
        assertEquals(0L, AvatarCachePolicy.retryDelayMillis(failures = 0))
        assertEquals(2_000L, AvatarCachePolicy.retryDelayMillis(failures = 1))
        assertEquals(4_000L, AvatarCachePolicy.retryDelayMillis(failures = 2))
        assertEquals(300_000L, AvatarCachePolicy.retryDelayMillis(failures = 20))
    }

    @Test
    fun `退避未到期时不允许重试，到期后允许`() {
        // 第 2 次失败后退避 4s：3s 时不可重试，5s 时可重试。
        assertFalse(AvatarCachePolicy.canRetry(failures = 2, lastAttemptMs = 1_000, nowMs = 4_000))
        assertTrue(AvatarCachePolicy.canRetry(failures = 2, lastAttemptMs = 1_000, nowMs = 6_000))
    }

    @Test
    fun `无历史失败时立即允许下载`() {
        assertTrue(AvatarCachePolicy.canRetry(failures = 0, lastAttemptMs = 0, nowMs = 0))
    }
}
