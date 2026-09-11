package com.linxi.diary.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
 * 行 URI 的形式选择策略（纯逻辑，可 JVM 单测）。
 *
 * 抽出来是因为「同一张图对应哪个 uri」这件事被持久化了：
 * [LocalPhotoIndex] 把「服务端 photoId → 本机原图 uri」写进磁盘，
 * 选择器也用 uri 做选中态判等。**同一张图在不同版本里必须给出同一个 uri**，
 * 否则老索引全部失配、"自己传的照片读本机原图"这条优化直接失效。
 */
object MediaUriPolicy {

    /**
     * 该卷的行 URI 是否应用**规范形式**（`content://media/external/...`）。
     *
     * 主卷用规范形式：这是 `EXTERNAL_CONTENT_URI` 的形态，也是 0821 改成按卷遍历之前
     * 一直在用的形态。保持一致，[LocalPhotoIndex] 里已存的索引才继续有效。
     * 其它卷（SD 卡）保留按卷形式 —— 规范形式定位不到它们的行。
     *
     * @param volumeName `MediaStore.getVolumeName(uri)` 的结果；null 表示取不到
     */
    fun shouldUseCanonical(volumeName: String?): Boolean =
        volumeName == null || volumeName == "external_primary" || volumeName == "external"
}

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

    /**
     * 选择器网格的加载页大小（0829 由「全库快照」改为「真分页」后启用）。
     *
     * 比 [PAGE_SIZE] 小是有意的：管理员 2 万+ 张的库上，选择器此前一进页就
     * 把全库元数据扫完，直接「读取相册失败」。真分页后首屏只取这一页，
     * 滚动接近底部再追加下一页；页越小首屏越快、单次查询越不容易被
     * MediaProvider 拖死。
     */
    const val GRID_PAGE_SIZE = 120

    /** 「全部」桶的标识（非真实 bucket 名）。 */
    const val BUCKET_ALL = "__all__"

    /** 无 bucket 名（NULL/空串）的图在分桶里统一归入这个桶。 */
    private const val UNLABELED_BUCKET = "其他"

    private val PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )

    /**
     * 分桶普查用的窄投影：只取「排序 + 计数 + 封面」所需的列。
     * 大库普查全程只扫这 4 列、不逐行构建 [LocalImage]，开销远小于全投影。
     */
    private val PROJECTION_CENSUS = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
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
     * 行 URI：主卷用规范形式（`content://media/external/images/media/<id>`）。
     *
     * 不直接拿查询用的按卷 URI 拼 id。按卷查询得到的是
     * `content://media/external_primary/images/media/<id>`，
     * 与 0821 改成按卷遍历之前的形态不同 —— 而 uri 是被**持久化**的
     *（[LocalPhotoIndex] 存「photoId → 本机原图 uri」），形态一换老索引就全失配。
     *
     * 主卷（`external_primary` / `EXTERNAL_CONTENT_URI` 指向的那个）转规范形式；
     * 其它卷（SD 卡）保留自己的形式 —— 规范形式定位不到它们的行。
     */
    private fun rowUri(contentUri: Uri, id: Long): Uri {
        val canonical = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val volume = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { MediaStore.getVolumeName(contentUri) }.getOrNull()
        } else {
            null // 旧版本只有主卷
        }
        val base = if (MediaUriPolicy.shouldUseCanonical(volume)) canonical else contentUri
        return android.content.ContentUris.withAppendedId(base, id)
    }

    /**
     * 为一次选择会话读取稳定快照。
     *
     * 多卷照片无法在 SQL 层做全局排序分页，所以无论请求哪一页，旧实现都会先把
     * **全部卷完整扫描一遍**再切片。选择 2000 张照片时就会重复全盘扫描 10 次，
     * 图库越大，滚动越卡。选择器现在进页只调用本方法一次，后续分桶与翻页都在
     * 这份轻量元数据快照上完成；图片像素仍由 Coil 按需解码，不会一起进内存。
     *
     * 若所有卷都读取失败则抛错，让 UI 显示“读取失败 + 重试”，不能再把权限撤销、
     * MediaProvider 异常等故障伪装成“没有找到照片”。部分卷失败时保留其它卷结果。
     */
    suspend fun queryLibrary(context: Context, limitPerVolume: Int? = null): List<LocalImage> = withContext(Dispatchers.IO) {
        val all = mutableListOf<LocalImage>()
        var successfulVolumes = 0
        for (uri in contentUris(context)) {
            val result = queryVolume(context, uri, limitPerVolume)
            if (result.succeeded) {
                successfulVolumes++
                all += result.images
            }
        }
        if (successfulVolumes == 0) error("无法读取系统相册")

        // 复用 MediaSortPolicy：测的规则与跑的规则必须是同一份，否则测试形同虚设。
        all.sortWith { a, b -> MediaSortPolicy.compare(a.takenAtMs, a.id, b.takenAtMs, b.id) }
        all
    }

    /** 从稳定快照里取一个相册桶；选择器切桶时不再访问 MediaProvider。 */
    fun imagesInBucket(all: List<LocalImage>, bucket: String): List<LocalImage> =
        if (bucket == BUCKET_ALL) all
        else all.filter { it.bucketName.ifBlank { UNLABELED_BUCKET } == bucket }

    /**
     * ★ 直接对 MediaStore 分页查一页（0829 大库修复的核心入口）★
     *
     * 为什么不再走 [queryLibrary] 全库快照 + 内存切片：管理员 2 万+ 张的库上，
     * 「一次 query 全库再逐行构建 LocalImage」会拖到超时/整卷查询失败，
     * 表现就是选择器一进页就报「读取相册失败」。真分页后：
     *
     *   · 单卷设备（绝大多数，含管理员的一加 15）：用 Bundle 的
     *     `QUERY_ARG_OFFSET` / `QUERY_ARG_LIMIT` 让 MediaProvider 在服务端
     *     就截断结果，每页只取 [pageSize] 条（排序仍是 [ORDER_BY]，时间倒序）；
     *   · 多卷设备（主存储 + SD 卡）：跨卷做不了全局 OFFSET，退化为每卷取
     *     前 `offset + pageSize` 条再按 [MediaSortPolicy.compare] 合并切片
     *     —— 每次仍是**有界查询**，翻得越深单页开销越大但绝不会一次性
     *     扫全库构建对象。
     *
     * @param bucket [BUCKET_ALL] 或某个真实 bucket 名（含 [UNLABELED_BUCKET]）
     * @param offset 本页起点（= 已加载条数；调用方用 `nextOffset` 推进而非
     *   「列表长度」，媒体库在滚动期间增删时由调用方去重兜底）
     * @throws Exception 所有卷都查询失败时抛出，让 UI 走「失败 + 重试本页」，
     *   不能伪装成空相册
     */
    suspend fun queryPageFromStore(
        context: Context,
        bucket: String = BUCKET_ALL,
        offset: Int = 0,
        pageSize: Int = GRID_PAGE_SIZE,
    ): List<LocalImage> = withContext(Dispatchers.IO) {
        require(offset >= 0) { "offset must be >= 0" }
        require(pageSize > 0) { "pageSize must be > 0" }
        val uris = contentUris(context)
        if (uris.isEmpty()) return@withContext emptyList()

        if (uris.size == 1) {
            val result = queryVolumePage(context, uris[0], bucket, offset, pageSize)
            // 唯一的卷都失败：没有可用数据，必须让上层看到失败而不是空页。
            if (!result.succeeded) error("无法读取系统相册")
            return@withContext result.images
        }

        // 多卷：每卷取前 offset+pageSize 条，合并出全局有序的前缀再切片。
        var succeededVolumes = 0
        val merged = mutableListOf<LocalImage>()
        val windowEnd = offset + pageSize
        for (uri in uris) {
            val result = queryVolumePage(context, uri, bucket, 0, windowEnd)
            if (result.succeeded) {
                succeededVolumes++
                merged += result.images
            }
        }
        if (succeededVolumes == 0) error("无法读取系统相册")
        merged.sortWith { a, b -> MediaSortPolicy.compare(a.takenAtMs, a.id, b.takenAtMs, b.id) }
        val range = MediaSortPolicy.pageRange(merged.size, offset, pageSize)
        if (range == null) emptyList() else merged.slice(range)
    }

    /**
     * 全库分桶普查：每个桶的条数 + 最新一张的封面。
     *
     * 只扫 [PROJECTION_CENSUS] 四个窄列，逐行只做计数与「记住首个（即最新）
     * 桶成员」，**不**构建整库 [LocalImage] —— 这是大库上分桶条仍能正常
     * 显示的前提。普查失败（返回 null）时调用方应直接隐藏分桶条降级，
     * 网格分页本身不依赖它。
     *
     * @return 分桶列表（首项固定是「全部」），按条数降序；全部卷失败返回 null
     */
    suspend fun queryBucketCensus(context: Context): List<ImageBucket>? = withContext(Dispatchers.IO) {
        /** 桶累积器：条数 + 首个成员（按 [ORDER_BY] 遍历，首个即最新）的封面 uri。 */
        class BucketAcc(var count: Int = 0, var coverUri: Uri? = null)

        val accs = LinkedHashMap<String, BucketAcc>()
        var totalCount = 0
        var allCoverUri: Uri? = null
        var succeededVolumes = 0

        for (uri in contentUris(context)) {
            val succeeded = runCatching {
                val bundle = Bundle().apply {
                    // 普查同样按全局排序遍历，「桶内首个成员 = 最新一张」才成立。
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, ORDER_BY)
                }
                context.contentResolver.query(uri, PROJECTION_CENSUS, bundle, null)
                    ?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                        val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                        val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val name = (cursor.getString(bucketCol) ?: "").ifBlank { UNLABELED_BUCKET }
                            val acc = accs.getOrPut(name) { BucketAcc() }
                            acc.count++
                            if (acc.coverUri == null) {
                                acc.coverUri = rowUri(uri, cursor.getLong(idCol))
                            }
                            totalCount++
                            if (allCoverUri == null) {
                                allCoverUri = rowUri(uri, cursor.getLong(idCol))
                            }
                        }
                        true
                    } ?: false
            }.onFailure {
                Logs.w("MediaStore", "bucket census failed for $uri", it)
            }.getOrDefault(false)
            if (succeeded) succeededVolumes++
        }

        if (succeededVolumes == 0) return@withContext null
        val buckets = accs.map { (name, acc) ->
            ImageBucket(name = name, count = acc.count, coverUri = acc.coverUri)
        }.sortedByDescending { it.count }
        listOf(ImageBucket(BUCKET_ALL, totalCount, allCoverUri)) + buckets
    }

    /** 从已按时间排序的快照构建分桶（含固定在最前面的“全部”）。 */
    fun bucketsFrom(all: List<LocalImage>): List<ImageBucket> {
        val grouped = all.groupBy { it.bucketName.ifBlank { "其他" } }
        val buckets = grouped.map { (name, items) ->
            ImageBucket(name = name, count = items.size, coverUri = items.firstOrNull()?.uri)
        }.sortedByDescending { it.count }
        return listOf(ImageBucket(BUCKET_ALL, all.size, all.firstOrNull()?.uri)) + buckets
    }

    /**
     * 分页查询。保留给非会话型调用方；选择器应优先使用 [queryLibrary] 复用快照。
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
    ): List<LocalImage> {
        val bucketImages = imagesInBucket(queryLibrary(context), bucket)
        val range = MediaSortPolicy.pageRange(bucketImages.size, offset, pageSize) ?: return emptyList()
        return bucketImages.slice(range)
    }

    /** 列出全部分桶（含「全部」），按张数降序。 */
    suspend fun queryBuckets(context: Context): List<ImageBucket> = bucketsFrom(queryLibrary(context))

    private data class VolumeQueryResult(
        val images: List<LocalImage>,
        val succeeded: Boolean,
    )

    private data class PageQueryResult(
        val images: List<LocalImage>,
        val succeeded: Boolean,
    )

    /**
     * 指定桶的 WHERE 条件。
     * [UNLABELED_BUCKET] 对应 bucket 名为空（NULL 或 ''）的图 —— 与
     * [imagesInBucket] 的内存过滤规则保持同一份语义（`ifBlank` 归桶）。
     */
    private fun bucketSelection(bucket: String): Pair<String?, Array<String>?> = when {
        bucket == BUCKET_ALL -> null to null
        bucket == UNLABELED_BUCKET -> (
            "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NULL OR " +
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}='')"
            ) to null
        else -> "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" to arrayOf(bucket)
    }

    /**
     * 读取「有效时间戳」：DATE_TAKEN（毫秒，截图/微信图常缺）缺失时回退
     * DATE_ADDED（秒→毫秒）。规则本体在 [MediaSortPolicy.effectiveTimestamp]，
     * 全部查询路径都经由这里取值 —— 测的规则与跑的规则必须是同一份。
     */
    private fun cursorTimestampMs(cursor: Cursor, takenCol: Int, addedCol: Int): Long {
        val taken = takenCol.takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) } ?: 0L
        val added = addedCol.takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) } ?: 0L
        return MediaSortPolicy.effectiveTimestamp(taken, added)
    }

    /**
     * 单卷分页查询：服务端 OFFSET/LIMIT 截断，返回该窗口内的行。
     *
     * Bundle 键（`QUERY_ARG_OFFSET` / `QUERY_ARG_LIMIT`，API 26+，本项目
     * minSdk 33）是 ContentResolver 文档化的分页方式；排序走
     * `QUERY_ARG_SQL_SORT_ORDER` 传 [ORDER_BY] 原文 —— 排序含 COALESCE
     * 表达式，列名式参数（`QUERY_ARG_SORT_COLUMNS`）表达不了它。
     *
     * 单卷失败不抛出（返回 succeeded=false）：与 [queryVolume] 一样，
     * 「部分卷失败保留其它卷结果」是 [queryPageFromStore] 的职责。
     */
    private fun queryVolumePage(
        context: Context,
        contentUri: Uri,
        bucket: String,
        offset: Int,
        limit: Int,
    ): PageQueryResult {
        val out = mutableListOf<LocalImage>()
        var succeeded = false
        runCatching {
            val bundle = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, ORDER_BY)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            val (selection, selectionArgs) = bucketSelection(bucket)
            context.contentResolver.query(contentUri, PROJECTION, bundle, null)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val ts = cursorTimestampMs(cursor, takenCol, addedCol)
                        out += LocalImage(
                            id = id,
                            uri = rowUri(contentUri, id),
                            takenAtMs = ts,
                            sizeBytes = sizeCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                            mime = mimeCol.takeIf { it >= 0 }?.let { cursor.getString(it) }
                                ?: "image/jpeg",
                            monthLabel = monthLabelOf(ts),
                            bucketName = bucketCol.takeIf { it >= 0 }
                                ?.let { cursor.getString(it) } ?: "",
                        )
                    }
                    // 完整走完 cursor 才算成功；0 行是合法的空窗口。
                    succeeded = true
                }
        }.onFailure { Logs.w("MediaStore", "query page failed for $contentUri", it) }
        return PageQueryResult(out, succeeded)
    }

    private fun queryVolume(context: Context, contentUri: Uri, limit: Int? = null): VolumeQueryResult {
        val out = mutableListOf<LocalImage>()
        var succeeded = false
        runCatching {
            val safeLimit = limit?.takeIf { it > 0 }?.coerceAtMost(1000)
            val sortOrder = if (safeLimit == null) ORDER_BY else "$ORDER_BY LIMIT $safeLimit"
            context.contentResolver.query(contentUri, PROJECTION, null, null, sortOrder)
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
                        // 取值规则收口在 cursorTimestampMs，与分页查询共用同一份实现。
                        val ts = cursorTimestampMs(cursor, takenCol, addedCol)
                        out += LocalImage(
                            id = id,
                            uri = rowUri(contentUri, id),
                            takenAtMs = ts,
                            sizeBytes = sizeCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                            mime = mimeCol.takeIf { it >= 0 }?.let { cursor.getString(it) }
                                ?: "image/jpeg",
                            monthLabel = monthLabelOf(ts),
                            bucketName = bucketCol.takeIf { it >= 0 }
                                ?.let { cursor.getString(it) } ?: "",
                        )
                    }
                    // 完整走完 cursor 才算成功；0 行是合法的空相册。
                    // 若列缺失或遍历中途异常，不能把半截结果伪装成完整数据。
                    succeeded = true
                }
        }.onFailure { Logs.w("MediaStore", "query images failed for $contentUri", it) }
        return VolumeQueryResult(out, succeeded)
    }

    /** 旧签名保留：一次性取前 N 张（供不需要分页的场景）。 */
    suspend fun query(context: Context, limit: Int = PAGE_SIZE): List<LocalImage> =
        queryLibrary(context, limitPerVolume = limit).take(limit)

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
