package com.linxi.diary.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.linxi.diary.util.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本机相册里的一张图。 */
data class LocalImage(
    /** MediaStore 里的 _ID，用作同时间戳时的稳定排序键。 */
    val id: Long,
    val uri: Uri,
    val takenAtMs: Long,
    val sizeBytes: Long,
    val mime: String,
    /** 分组标签，形如「2026 年 8 月」。 */
    val monthLabel: String,
    /** 所属相册（bucket）名，如「Camera」「Screenshots」「WeiXin」。 */
    val bucketName: String,
)

/** 相册分桶（学 QQ 的「相机 / 截屏 / 微信 / 全部」）。 */
data class ImageBucket(
    val name: String,
    val count: Int,
    /** 该桶最新一张图，用作封面。 */
    val coverUri: Uri?,
)

/**
 * 读取本机相册图片，供自研选择器使用。
 *
 * 为什么自研而不用系统 Photo Picker：管理员明确嫌系统选择器难看
 *（头像此前用的还是更丑的 SAF 文件浏览器 `OpenDocument`）。
 * 自研网格能与全 App 的 miuix 皮肤统一，且相册功能本身也复用这套网格。
 *
 * ## 「有些图片消失、扫不到」的根因与修法（0821）
 *
 * 旧实现有三个叠加的缺陷，共同造成了管理员报的「扫不到」：
 *
 * 1. **写死 `limit = 2000` 并在取满后 break**。
 * 2. **按 `DATE_TAKEN DESC` 主排序**。而 `DATE_TAKEN` 来自 EXIF，**只有相机直出才有**；
 *    截图、微信/QQ 保存的图、浏览器下载的图普遍是 NULL 或 0。
 *    SQLite 里 NULL 在 `DESC` 排序中排**最后** → 这些图全被挤到队尾。
 * 3. 二者叠加：**图片总数一旦超过 2000，所有截图与微信图会被整批截断，一张都看不到。**
 *    管理员手机图片数 >2000（Q57=A），完全命中。
 *
 * 修法：
 *   - 排序改 `COALESCE(DATE_TAKEN, DATE_ADDED*1000) DESC`，无 EXIF 的图也排到正确位置；
 *   - 去掉硬上限，改**分页加载**（每页 [PAGE_SIZE] 条，滚到底续拉），
 *     几万张也不会在进入选择器时卡住；
 *   - 覆盖**全部存储卷**（`getExternalVolumeNames`），不再只看主卷；
 *   - 按 bucket 分桶，用户能直接切到「截屏」或「微信」。
 */
/**
 * 排序与分页的**纯策略**（不依赖任何 Android 类型，可在 JVM 单测里直接验）。
 *
 * 抽出来的理由：「图片消失」的根因就在排序规则上，而 `android.net.Uri` 在
 * 普通单测里不可用（`Uri.parse` 未 mock 会抛 RuntimeException），
 * 把规则和 Android 类型耦在一起就等于永远测不了它。
 */
object MediaSortPolicy {

    /**
     * 有效时间戳：`DATE_TAKEN` 缺失（截图/微信图/下载图）时回退 `DATE_ADDED`。
     *
     * @param dateTakenMs EXIF 拍摄时间（毫秒），缺失传 0
     * @param dateAddedSec 入库时间（秒），缺失传 0
     */
    fun effectiveTimestamp(dateTakenMs: Long, dateAddedSec: Long): Long =
        if (dateTakenMs > 0) dateTakenMs else dateAddedSec * 1000

    /**
     * 排序键比较：时间降序，同刻按 id 降序（保证顺序稳定，翻页不会重复或漏项）。
     * 返回负数表示 a 应排在 b 前面。
     */
    fun compare(aTs: Long, aId: Long, bTs: Long, bId: Long): Int {
        if (aTs != bTs) return if (aTs > bTs) -1 else 1
        return if (aId == bId) 0 else if (aId > bId) -1 else 1
    }

    /** 切页：越界返回空，末页可能不足一页。 */
    fun pageRange(total: Int, offset: Int, pageSize: Int): IntRange? {
        if (offset >= total || offset < 0 || pageSize <= 0) return null
        return offset until minOf(offset + pageSize, total)
    }
}

object MediaStoreImages {

    /** 单页条数。分页是为了避免一次把几万条元数据读进内存。 */
    const val PAGE_SIZE = 200

    /** 「全部」桶的标识（非真实 bucket 名）。 */
    const val BUCKET_ALL = "__all__"

    private val PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )

    /**
     * 排序表达式。
     *
     * **这是「图片消失」的修复核心**：`DATE_TAKEN` 缺失时回退 `DATE_ADDED`（秒→毫秒），
     * 让截图/微信图/下载图与相机直出图混在同一条时间轴上正确排序，
     * 而不是因为 NULL 被 DESC 排到最末尾、再被条数上限截掉。
     */
    private const val ORDER_BY =
        "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, " +
            "${MediaStore.Images.Media.DATE_ADDED} * 1000) DESC, " +
            "${MediaStore.Images.Media._ID} DESC"

    /**
     * 要查询的所有内容 URI。
     *
     * Android 10+ 用 `getExternalVolumeNames()` 拿全部卷（主存储 + SD 卡）；
     * 旧实现只查 `EXTERNAL_CONTENT_URI`（仅主卷），SD 卡上的照片一张都看不到。
     */
    private fun contentUris(context: Context): List<Uri> =
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                MediaStore.getExternalVolumeNames(context).map { MediaStore.Images.Media.getContentUri(it) }
            }.getOrElse { listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) }
                .ifEmpty { listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) }
        } else {
            listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }

    /**
     * 分页查询。
     *
     * @param bucket 相册名；[BUCKET_ALL] 表示不过滤
     * @param offset 已加载条数
     * @return 本页数据；返回条数 < [PAGE_SIZE] 表示到底了
     */
    suspend fun queryPage(
        context: Context,
        bucket: String = BUCKET_ALL,
        offset: Int = 0,
        pageSize: Int = PAGE_SIZE,
    ): List<LocalImage> = withContext(Dispatchers.IO) {
        // 多卷时无法在 SQL 层跨卷分页，故逐卷全量读元数据后统一排序再切页。
        // 元数据本身很轻（每条几十字节），几万条也只有几 MB；
        // 真正重的是解码图片，那由 Coil 按需做。
        val all = mutableListOf<LocalImage>()
        for (uri in contentUris(context)) {
            all += queryVolume(context, uri, bucket)
        }
        // 复用 MediaSortPolicy：测的规则与跑的规则必须是同一份，否则测试形同虚设。
        all.sortWith { a, b -> MediaSortPolicy.compare(a.takenAtMs, a.id, b.takenAtMs, b.id) }
        val range = MediaSortPolicy.pageRange(all.size, offset, pageSize) ?: return@withContext emptyList()
        all.slice(range)
    }

    /** 列出全部分桶（含「全部」），按张数降序。 */
    suspend fun queryBuckets(context: Context): List<ImageBucket> = withContext(Dispatchers.IO) {
        val all = mutableListOf<LocalImage>()
        for (uri in contentUris(context)) {
            all += queryVolume(context, uri, BUCKET_ALL)
        }
        all.sortByDescending { it.takenAtMs }
        val grouped = all.groupBy { it.bucketName.ifBlank { "其他" } }
        val buckets = grouped.map { (name, items) ->
            ImageBucket(name = name, count = items.size, coverUri = items.firstOrNull()?.uri)
        }.sortedByDescending { it.count }
        // 「全部」固定在最前
        listOf(ImageBucket(BUCKET_ALL, all.size, all.firstOrNull()?.uri)) + buckets
    }

    private fun queryVolume(context: Context, contentUri: Uri, bucket: String): List<LocalImage> {
        val out = mutableListOf<LocalImage>()
        val selection: String?
        val args: Array<String>?
        if (bucket == BUCKET_ALL) {
            selection = null
            args = null
        } else {
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
            args = arrayOf(bucket)
        }
        runCatching {
            context.contentResolver.query(contentUri, PROJECTION, selection, args, ORDER_BY)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        // DATE_TAKEN 是毫秒且可能为 0/NULL（截图、下载、微信图常缺）；DATE_ADDED 是秒。
                        val taken = takenCol.takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let { cursor.getLong(it) } ?: 0L
                        val added = addedCol.takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let { cursor.getLong(it) * 1000 } ?: 0L
                        val ts = MediaSortPolicy.effectiveTimestamp(taken, added / 1000)
                        out += LocalImage(
                            id = id,
                            uri = android.content.ContentUris.withAppendedId(contentUri, id),
                            takenAtMs = ts,
                            sizeBytes = sizeCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                            mime = mimeCol.takeIf { it >= 0 }?.let { cursor.getString(it) }
                                ?: "image/jpeg",
                            monthLabel = monthLabelOf(ts),
                            bucketName = bucketCol.takeIf { it >= 0 }
                                ?.let { cursor.getString(it) } ?: "",
                        )
                    }
                }
        }.onFailure { Logs.w("MediaStore", "query images failed for $contentUri", it) }
        return out
    }

    /** 旧签名保留：一次性取前 N 张（供不需要分页的场景）。 */
    suspend fun query(context: Context, limit: Int = PAGE_SIZE): List<LocalImage> =
        queryPage(context, BUCKET_ALL, 0, limit)

    /** 「2026 年 8 月」；时间戳缺失时归入「更早」。 */
    fun monthLabelOf(ms: Long): String {
        if (ms <= 0) return "更早"
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "${cal.get(java.util.Calendar.YEAR)} 年 ${cal.get(java.util.Calendar.MONTH) + 1} 月"
    }

    /** 桶名的展示文案：把常见英文目录名翻成中文（学 QQ 的观感）。 */
    fun bucketLabel(name: String): String = when {
        name == BUCKET_ALL -> "全部"
        name.equals("Camera", true) || name.equals("DCIM", true) -> "相机"
        name.equals("Screenshots", true) || name.equals("Screenshot", true) -> "截屏"
        name.equals("Screen recordings", true) -> "录屏"
        name.equals("Download", true) || name.equals("Downloads", true) -> "下载"
        name.contains("WeiXin", true) || name.equals("MicroMsg", true) -> "微信"
        name.contains("QQ", true) -> "QQ"
        name.equals("Pictures", true) -> "图片"
        name.equals("Saver", true) -> "保存的图片"
        name.equals("Weibo", true) -> "微博"
        else -> name
    }
}
