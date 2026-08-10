package com.linxi.diary.data

import java.security.MessageDigest

/** 头像缓存与下载退避的纯策略，无 Android 依赖，便于 JVM 单测。 */
object AvatarCachePolicy {
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 300_000L // 5 分钟封顶
    private const val MAX_SHIFT = 20

    /** 由 URL 派生稳定且文件名安全的缓存键；空白 URL 返回 null。 */
    fun cacheKey(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "${hex.take(32)}.png"
    }

    /** 连续失败退避：0 次不等待，此后 2s 起指数翻倍，封顶 5 分钟。 */
    fun retryDelayMillis(failures: Int): Long {
        if (failures <= 0) return 0L
        val shift = failures.coerceIn(1, MAX_SHIFT)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    fun canRetry(failures: Int, lastAttemptMs: Long, nowMs: Long): Boolean =
        nowMs - lastAttemptMs >= retryDelayMillis(failures)
}
