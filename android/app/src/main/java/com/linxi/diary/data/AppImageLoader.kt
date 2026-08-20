package com.linxi.diary.data

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局 ImageLoader。
 *
 * 相册图片走的是**鉴权代理** `/media/<id>`（真实磁盘路径不出服务端），
 * 所以每个图片请求都必须带 `Authorization` 头 —— 这正是要自建 ImageLoader 而非用
 * Coil 默认实例的原因。同时带上 `X-App-Key`，与 ApiClient 的通讯密钥校验保持一致。
 *
 * 缓存策略：相册网格一次要铺几十张缩略图，磁盘缓存必须给足，
 * 否则每次进页面都重新下载（此前自撸的 NetworkAvatar 就是零缓存 + 主线程解码）。
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
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .memoryCache {
                // 25% 可用内存：网格滚动时避免反复解码，同时不至于挤爆应用。
                MemoryCache.Builder().maxSizePercent(appContext, 0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /** 给每个图片请求补上鉴权头。token 每次实时读取，避免登出后仍用旧 token。 */
    private class AuthHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val builder = chain.request().newBuilder()
            com.linxi.diary.util.UserPrefs.token?.takeIf { it.isNotBlank() }?.let {
                builder.header("Authorization", "Bearer $it")
            }
            com.linxi.diary.BuildConfig.APP_KEY.takeIf { it.isNotBlank() }?.let {
                builder.header("X-App-Key", it)
            }
            return chain.proceed(builder.build())
        }
    }
}
