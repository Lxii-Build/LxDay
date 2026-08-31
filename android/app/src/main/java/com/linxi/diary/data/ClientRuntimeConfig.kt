package com.linxi.diary.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 服务端下发的客户端运行参数。
 *
 * 只缓存本进程中的安全快照；请求失败时继续使用内置默认值，不能因为配置接口
 * 暂时不可用而阻断登录或相册。服务端范围是 1~100MB，这里再次收敛，避免脏响应
 * 让本地预处理产生异常大文件。
 */
object ClientRuntimeConfig {
    private const val MIN_UPLOAD_BYTES = 1L * 1024 * 1024
    private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

    @Volatile
    var photoMaxBytes: Long = ImagePrepPolicy.MAX_UPLOAD_BYTES
        private set

    /** 服务端功能开关需要是 Compose 可观察状态，配置变更后入口才会立即收敛。 */
    var albumEnabled by mutableStateOf(true)
        private set

    var photoSocialEnabled by mutableStateOf(true)
        private set

    var onThisDayEnabled by mutableStateOf(true)
        private set

    fun apply(json: JSONObject) {
        val raw = json.optJSONObject("upload")?.optLong(
            "photo_max_bytes", ImagePrepPolicy.MAX_UPLOAD_BYTES,
        ) ?: ImagePrepPolicy.MAX_UPLOAD_BYTES
        photoMaxBytes = raw.coerceIn(MIN_UPLOAD_BYTES, MAX_UPLOAD_BYTES)

        val features = json.optJSONObject("features")
        albumEnabled = features?.optBoolean("album", true) ?: true
        photoSocialEnabled = features?.optBoolean("photo_social", true) ?: true
        onThisDayEnabled = features?.optBoolean("on_this_day", true) ?: true
    }
}
