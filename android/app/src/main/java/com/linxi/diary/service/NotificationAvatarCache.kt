package com.linxi.diary.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 通知栏卡片里伴侣头像的解码与缓存。
 *
 * ## 这个对象叫 Cache，但原先并没有缓存
 *
 * `load()` 每次调用都重新 `decodeFile` 出一张新 Bitmap（512×512 ARGB_8888 ≈ 1MB），
 * 塞进 RemoteViews 之后就再也没人回收。而 `buildCard` 的调用频率并不低：
 * 前台档位每 10 秒采集一次状态，加上 onCreate 与每次 ACTION_REFRESH/ACTION_SYNC。
 * 也就是**每 10 秒产生一份 1MB 垃圾**。
 *
 * 严格说这不是泄露（GC 最终会回收），但它是持续的 GC 压力，
 * 而前台服务是常驻进程 —— 与相册解码的大块分配叠加时会显著提高 OOM 概率。
 *
 * 现在按 [fingerprint] 记忆：文件没变就复用同一份 Bitmap。
 * `fingerprint` 本来就存在（[StatusForegroundService] 用它判断卡片要不要重画），
 * 所以缓存键是现成的，不需要新增任何状态。
 *
 * 复用是安全的：RemoteViews 的 `setImageViewBitmap` 会把位图 parcel 给
 * system_server，不持有我们这份的引用，因此同一个 Bitmap 可以反复投递。
 */
object NotificationAvatarCache {
    private const val MAX_DIMENSION = 512
    private const val MAX_BYTES = 2L * 1024 * 1024

    private val lock = Any()
    private var cachedFingerprint = 0L
    private var cached: Bitmap? = null

    fun cacheFile(filesDir: File): File = File(filesDir, "avatar/partner_notification.png")

    fun fingerprint(filesDir: File): Long {
        val file = cacheFile(filesDir)
        return if (file.isFile) file.lastModified() xor file.length() else 0L
    }

    fun load(filesDir: File): Bitmap? {
        val fp = fingerprint(filesDir)
        synchronized(lock) {
            val hit = cached
            // 指纹相同且那份还没被回收 → 直接复用，不再解码。
            if (hit != null && cachedFingerprint == fp && !hit.isRecycled) return hit
        }

        val decoded = decode(filesDir)
        synchronized(lock) {
            // 换头像时旧的那份要主动回收：它已经不会再被投递，
            // 留给 GC 就等于在常驻服务里多压一份 1MB。
            cached?.takeIf { it !== decoded && !it.isRecycled }?.recycle()
            cached = decoded
            cachedFingerprint = fp
        }
        return decoded
    }

    private fun decode(filesDir: File): Bitmap? {
        val file = cacheFile(filesDir)
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (!isSafeThumbnail(bounds.outWidth, bounds.outHeight, file.length())) return null

        return BitmapFactory.decodeFile(file.absolutePath)
    }

    internal fun isSafeThumbnail(width: Int, height: Int, bytes: Long): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && bytes in 1..MAX_BYTES
}
