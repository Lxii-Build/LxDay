package com.linxi.diary.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.linxi.diary.util.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本机相册里的一张图。 */
data class LocalImage(
    val uri: Uri,
    val takenAtMs: Long,
    val sizeBytes: Long,
    val mime: String,
    /** 分组标签，形如「2026 年 8 月」。 */
    val monthLabel: String,
)

/**
 * 读取本机相册图片，供自研选择器使用。
 *
 * 为什么自研而不用系统 Photo Picker：管理员明确嫌系统选择器难看
 *（头像此前用的还是更丑的 SAF 文件浏览器 `OpenDocument`）。
 * 自研网格能与全 App 的 miuix 皮肤统一，且相册功能本身也复用这套网格。
 *
 * Android 14+ 的「仅选择部分照片」：用户选部分授权时，
 * MediaStore 只会返回被授权的那几张 —— 这不是 bug，但会让用户困惑
 *（"我明明有几千张照片"）。所以选择器 UI 必须提供「从系统相册选」的兜底入口。
 */
object MediaStoreImages {

    suspend fun query(context: Context, limit: Int = 2000): List<LocalImage> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<LocalImage>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
            )
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    while (cursor.moveToNext() && out.size < limit) {
                        val id = cursor.getLong(idCol)
                        // DATE_TAKEN 是毫秒且可能为 0（截图/下载的图常缺）；DATE_ADDED 是秒。
                        val taken = takenCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L
                        val added = addedCol.takeIf { it >= 0 }?.let { cursor.getLong(it) * 1000 } ?: 0L
                        val ts = if (taken > 0) taken else added
                        out += LocalImage(
                            uri = Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                            ),
                            takenAtMs = ts,
                            sizeBytes = sizeCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                            mime = mimeCol.takeIf { it >= 0 }?.let { cursor.getString(it) }
                                ?: "image/jpeg",
                            monthLabel = monthLabelOf(ts),
                        )
                    }
                }
            }.onFailure { Logs.w("MediaStore", "query images failed", it) }
            out
        }

    /** 「2026 年 8 月」；时间戳缺失时归入「更早」。 */
    fun monthLabelOf(ms: Long): String {
        if (ms <= 0) return "更早"
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "${cal.get(java.util.Calendar.YEAR)} 年 ${cal.get(java.util.Calendar.MONTH) + 1} 月"
    }
}
