package com.linxi.diary.data

import android.content.Context
import android.net.Uri
import com.linxi.diary.util.Logs
import org.json.JSONObject
import java.io.File

/**
 * 「照片 id → 本机原图 uri」索引。
 *
 * 这是管理员对 Q24 的自定义答复：
 * 「缓存优先记住原图片的路径吧？直接调用，如果原图片删了再从云端拉取(如果是自己的情况下)
 *   对方的直接拉图片就好了，然后存到内部存储，记住路径，即为缓存」
 *
 * 落地方式：
 *   - **自己上传的照片**：上传成功后把 `photoId → 本机 MediaStore uri` 记下来。
 *     之后查看这张照片时直接读本机原图（零流量、零等待、画质是真原图而非压过的 2048）。
 *     本机原图被用户删掉时 uri 不可读，自动回退到云端。
 *   - **对方上传的照片**：本机没有原始文件，走 Coil 的磁盘缓存（内部存储），
 *     由 LRU 自行淘汰，见 [AppImageLoader]。
 *
 * 存储用 SharedPreferences 里的一个 JSON 字符串：条目是 `id -> uri` 的短字符串，
 * 几千条也只有几百 KB，没必要为它引入 Room（多一个编译期注解处理器与迁移负担）。
 */
object LocalPhotoIndex {

    private const val PREFS = "local_photo_index"
    private const val KEY_MAP = "id_to_uri"

    /** 上限：超过就丢最早的。防止索引无限膨胀（用户可能传几万张）。 */
    private const val MAX_ENTRIES = 5000

    // 内存镜像，避免每次显示照片都读一次 SharedPreferences + 解析 JSON。
    @Volatile
    private var cache: LinkedHashMap<Long, String>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): LinkedHashMap<Long, String> {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: run {
                val map = LinkedHashMap<Long, String>()
                runCatching {
                    val raw = prefs(context).getString(KEY_MAP, null) ?: "{}"
                    val obj = JSONObject(raw)
                    // JSONObject 不保证顺序，但插入顺序对 LRU 淘汰只是近似需求，
                    // 淘汰掉"较早的一批"就够了，不必严格按时间。
                    obj.keys().forEach { k ->
                        k.toLongOrNull()?.let { id -> map[id] = obj.optString(k) }
                    }
                }.onFailure { Logs.w("LocalPhotoIndex", "load failed", it) }
                cache = map
                map
            }
        }
    }

    private fun persist(context: Context, map: LinkedHashMap<Long, String>) {
        runCatching {
            val obj = JSONObject()
            map.forEach { (id, uri) -> obj.put(id.toString(), uri) }
            prefs(context).edit().putString(KEY_MAP, obj.toString()).apply()
        }.onFailure { Logs.w("LocalPhotoIndex", "persist failed", it) }
    }

    /** 记下「这张照片对应本机哪个 uri」。上传成功后调用。 */
    fun remember(context: Context, photoId: Long, localUri: Uri) {
        if (photoId <= 0) return
        synchronized(this) {
            val map = load(context)
            map.remove(photoId) // 重新插入以更新顺序
            map[photoId] = localUri.toString()
            while (map.size > MAX_ENTRIES) {
                val oldest = map.keys.firstOrNull() ?: break
                map.remove(oldest)
            }
            persist(context, map)
        }
    }

    /**
     * 取本机原图 uri。返回 null 表示没有记录、或本机文件已被删除。
     *
     * **必须实际探测可读性**：用户在系统相册里删掉照片后，记录还在但 uri 已失效；
     * 若直接返回给 Coil，会得到一个加载失败的空白格——比回退云端更糟。
     */
    fun localUriIfUsable(context: Context, photoId: Long): Uri? {
        val raw = load(context)[photoId] ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val usable = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrElse { false }
        if (!usable) {
            // 顺手清掉失效记录，避免每次显示都做一次无用的探测。
            forget(context, photoId)
            return null
        }
        return uri
    }

    fun forget(context: Context, photoId: Long) {
        synchronized(this) {
            val map = load(context)
            if (map.remove(photoId) != null) persist(context, map)
        }
    }

    /** 清空索引（设置页「清除图片缓存」时连带清理）。 */
    fun clear(context: Context) {
        synchronized(this) {
            cache = LinkedHashMap()
            prefs(context).edit().remove(KEY_MAP).apply()
        }
    }

    fun size(context: Context): Int = load(context).size
}

/**
 * 决定一张照片该用什么源加载。
 *
 * 优先级（管理员 Q24 的方案）：
 *   1. 本机原图（只有自己上传的才有记录，且文件还在）→ 零流量、真原图
 *   2. 云端地址 → 由 Coil 磁盘缓存兜住重复访问
 *
 * 网格用 [gridModel]（缩略图尺寸就够，本机原图反而要解码大图，得不偿失）；
 * 大图页用 [viewerModel]（这里本机原图最有价值）。
 */
object PhotoLoadSource {

    /**
     * 网格：一律用服务端缩略图。
     *
     * 不用本机原图——网格里一次铺几十张，解码本机 4000×3000 原图会瞬间吃掉几百 MB，
     * 正是 0821 修掉的那个 OOM 的翻版。缩略图只有几十 KB，且服务端已经生成好了。
     */
    fun gridModel(photo: PhotoItem): Any = photo.displayUrl

    /**
     * 大图：先看本机有没有原图，没有才走云端 preview。
     * 返回 Uri 或 String，两者 Coil 都能直接吃。
     */
    fun viewerModel(context: Context, photo: PhotoItem): Any =
        LocalPhotoIndex.localUriIfUsable(context, photo.id) ?: photo.viewerUrl

    /** 双指放大后的原图源：本机优先，否则云端原图。 */
    fun originModel(context: Context, photo: PhotoItem): Any =
        LocalPhotoIndex.localUriIfUsable(context, photo.id) ?: photo.url
}

/**
 * 图片缓存用量与清理。设置页的「清除图片缓存」入口用它。
 *
 * 磁盘缓存上限已从 256MB 降到 128MB（Q24=A）：128MB 大约能存 1500~2500 张缩略图，
 * 日常翻相册几乎不会重复下载，而 256MB 对手机存储是不必要的占用。
 */
object ImageCacheManager {

    /** 当前磁盘缓存占用字节数。 */
    fun diskUsageBytes(context: Context): Long {
        val dir = File(context.applicationContext.cacheDir, "image_cache")
        if (!dir.exists()) return 0
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** 清空图片磁盘缓存与本机原图索引。 */
    fun clearAll(context: Context) {
        val app = context.applicationContext
        runCatching {
            AppImageLoader.get(app).diskCache?.clear()
            AppImageLoader.get(app).memoryCache?.clear()
        }.onFailure { Logs.w("ImageCache", "clear coil cache failed", it) }
        // 目录里可能还有 Coil 未托管的残留文件，兜底删一遍。
        runCatching {
            File(app.cacheDir, "image_cache").deleteRecursively()
        }.onFailure { Logs.w("ImageCache", "delete cache dir failed", it) }
        LocalPhotoIndex.clear(app)
    }

    fun humanReadable(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
