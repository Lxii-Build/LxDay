package com.linxi.diary.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object NotificationAvatarCache {
    private const val MAX_DIMENSION = 512
    private const val MAX_BYTES = 2L * 1024 * 1024

    fun cacheFile(filesDir: File): File = File(filesDir, "avatar/partner_notification.png")

    fun fingerprint(filesDir: File): Long {
        val file = cacheFile(filesDir)
        return if (file.isFile) file.lastModified() xor file.length() else 0L
    }

    fun load(filesDir: File): Bitmap? {
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
