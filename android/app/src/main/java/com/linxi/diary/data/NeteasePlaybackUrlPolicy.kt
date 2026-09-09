package com.linxi.diary.data

import java.net.URI

/**
 * 网易云返回的播放地址在不同接口/账号上有时仍是 http://。只允许网易云
 * 音频域名，并在可安全升级时改成 HTTPS；绝不把任意第三方 URL 交给播放器。
 */
object NeteasePlaybackUrlPolicy {
    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.userInfo != null || uri.query.isNullOrBlank() && uri.path.isNullOrBlank()) return null
        if (host != "music.163.com" && !host.endsWith(".music.163.com") &&
            host != "music.126.net" && !host.endsWith(".music.126.net")
        ) return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        return if (scheme == "https") value else {
            URI(
                "https",
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment,
            ).toString()
        }
    }
}
