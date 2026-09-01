package com.linxi.diary.data

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.map.Mapper
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局 ImageLoader。
 *
 * 相册图片走的是**鉴权代理** `/media/<id>`（真实磁盘路径不出服务端），
 * 所以每个图片请求都必须带 `Authorization` 头 —— 这正是要自建 ImageLoader 而非用
 * Coil 默认实例的原因。请求只携带当前登录态 token，不携带任何编译进 APK 的共享密钥。
 *
 * 缓存策略：相册网格一次要铺几十张缩略图，磁盘缓存必须给足，
 * 否则每次进页面都重新下载（此前自撸的 NetworkAvatar 就是零缓存 + 主线程解码）。
 *
 * **自己上传的照片不走网络**：LocalPhotoIndex 记着「照片 id → 本机原图 uri」，
 * 显示时优先读本机文件，本机被删才回退云端。对方的照片才需要下载+缓存。
 */
object AppImageLoader {

    @Volatile
    private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        loader?.let { return it }
        return synchronized(this) {
            loader ?: build(context.applicationContext).also { loader = it }
        }
    }

    private fun build(appContext: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthHeaderInterceptor())
            .build()

        return ImageLoader.Builder(appContext as PlatformContext)
            .components {
                // 相对路径补成绝对 URL —— **收口在这里而不是每个调用点**。
                //
                // 服务端在后台 site.url 未配置时返回相对路径 `/media/<id>/thumb`
                //（默认就是未配置，app_setting 表没有种子数据）。Coil 拿到无 scheme
                // 的字符串不走网络 fetcher，会当本机文件找 → 失败 → 渲染空白，
                // 看起来就是管理员报的「缩略图是透明的」。
                //
                // mapper 对**所有**图片请求生效：相册网格、相册封面、大图页、头像
                // 一处修全部受益，也不会有人新写一个调用点时忘了补。
                add(RelativeUrlMapper())
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .memoryCache {
                // 25% 可用内存：网格滚动时避免反复解码，同时不至于挤爆应用。
                MemoryCache.Builder().maxSizePercent(appContext, 0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache"))
                    // 128MB（Q24=A）：约能存 1500~2500 张缩略图，日常翻相册几乎不重复下载。
                    // 此前 256MB 对手机存储是不必要的占用；设置页另有「清除图片缓存」入口。
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /**
     * 把服务端返回的相对图片地址映射成绝对 URL。
     *
     * 只处理 String 类型的 data —— 本机图片是 `Uri` 类型（相册大图走 MediaStore uri），
     * 不经过这里，也不该被改动。判定规则见 [MediaUrlPolicy.absolutize]。
     */
    private class RelativeUrlMapper : Mapper<String, String> {
        override fun map(data: String, options: Options): String =
            MediaUrlPolicy.absolutize(data, com.linxi.diary.BuildConfig.BASE_URL)
    }

    /** 给每个图片请求补上鉴权头。token 每次实时读取，避免登出后仍用旧 token。 */
    private class AuthHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val builder = chain.request().newBuilder()
            com.linxi.diary.util.UserPrefs.token?.takeIf { it.isNotBlank() }?.let {
                builder.header("Authorization", "Bearer $it")
            }
            return chain.proceed(builder.build())
        }
    }
}
