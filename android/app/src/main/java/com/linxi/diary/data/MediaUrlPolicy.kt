package com.linxi.diary.data

/**
 * 把服务端返回的图片地址补成可请求的绝对 URL（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么需要这一层（0822 查明的「缩略图是透明的」根因）
 *
 * 服务端的图片地址是**条件绝对**的：后台设置项 `site.url` 配了就返回
 * `https://域名/media/<id>/thumb`，**没配就回退相对路径** `/media/<id>/thumb`
 *（见 `server/album_media.go` 的 `mediaPathURL` 与 `handlers.go` 的 `siteBaseURL`）。
 * 而 `app_setting` 表没有种子数据，`site.url` 默认就是空 —— 于是默认形态是相对路径。
 *
 * 客户端此前**没有任何补全逻辑**：`AppImageLoader` 的拦截器只加鉴权头不改 URL，
 * 网格直接把 `/media/1/thumb` 喂给 Coil。Coil 拿到无 scheme 的字符串不会走网络 fetcher，
 * 会当本机文件路径去找 → 找不到 → `AsyncImage` 没配 error 占位就渲染成空白，
 * 在毛玻璃卡片上看起来**就是透明的**。这正是管理员报的症状。
 *
 * 为什么「点进大图还能看到」不能证明链路是好的：大图页优先读 [LocalPhotoIndex] 记的
 * **本机原图 uri**（他是上传者本人），压根没请求服务端。换伴侣的账号看同一张就会是空白。
 *
 * ## 为什么在客户端修而不是让服务端总是返回绝对地址
 *
 * 服务端返回相对路径本身是合理的（同源部署时更省事，也有测试锁着这个行为），
 * 而「图片能不能显示」不该取决于运维有没有在后台填过站点地址。
 * 客户端补全是自洽的：它本来就知道自己在连哪个服务器。
 */
object MediaUrlPolicy {

    /**
     * 取 API 基地址的 **origin**（`scheme://host[:port]`），丢掉路径部分。
     *
     * **这一步是必须的**：`BuildConfig.BASE_URL` 形如
     * `https://love.lxii.cc/api/v1`，而图片挂在根路径 `/media/...`。
     * 直接拿 BASE_URL 拼会得到 `https://love.lxii.cc/api/v1/media/1/thumb` → 404。
     *
     * @return 无尾斜杠的 origin；解析不出来返回空串
     */
    fun originOf(baseUrl: String): String {
        val s = baseUrl.trim()
        if (s.isEmpty()) return ""
        val sep = s.indexOf("://")
        if (sep < 0) return ""
        // 从 "://" 之后找第一个 '/'，它之前的都是 authority
        val pathStart = s.indexOf('/', sep + 3)
        val origin = if (pathStart < 0) s else s.substring(0, pathStart)
        return origin.trimEnd('/')
    }

    /**
     * 把可能是相对路径的图片地址补成绝对 URL。
     *
     * - 已带 scheme（`http://` / `https://`）→ 原样返回
     * - `content://` / `file://` 等本机 uri → 原样返回（自己传的照片走本机原图）
     * - 空串 → 原样返回空串（调用方据此走占位图，不要变成一个指向域名根的假 URL）
     * - 以 `/` 开头 → 拼上 origin
     * - 其它（不以 `/` 开头的相对路径，如 `upload/x.png`）→ 补 `/` 再拼
     *
     * @param url 服务端返回的地址
     * @param baseUrl 通常传 `BuildConfig.BASE_URL`
     */
    fun absolutize(url: String, baseUrl: String): String {
        val u = url.trim()
        if (u.isEmpty()) return u
        // 任何带 scheme 的都不动：http(s) 是已经绝对了，content/file 是本机原图。
        if (u.contains("://")) return u
        val origin = originOf(baseUrl)
        if (origin.isEmpty()) return u // 没法补就原样返回，至少不比现在更糟
        return if (u.startsWith("/")) origin + u else "$origin/$u"
    }
}
