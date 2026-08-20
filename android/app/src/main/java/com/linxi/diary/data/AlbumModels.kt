package com.linxi.diary.data

import org.json.JSONObject

/** 相册。cover_thumb_url 为空时 UI 回退用第一张照片。 */
data class AlbumItem(
    val id: Long,
    val name: String,
    val photoCount: Int,
    val coverThumbUrl: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = AlbumItem(
            id = j.optLong("id"),
            name = j.optString("name"),
            photoCount = j.optInt("photo_count"),
            coverThumbUrl = j.optString("cover_thumb_url"),
        )
    }
}

/**
 * 照片。
 *
 * 注意 url/thumbUrl 是服务端返回的**鉴权代理地址** `/media/<id>`，
 * 不是磁盘真实路径 —— 真实路径不出服务端（`/upload` 静态目录无鉴权，
 * URL 一旦外泄任何人都能看私密照片）。加载时必须带 Authorization 头，
 * 故统一走 [AppImageLoader]。
 */
data class PhotoItem(
    val id: Long,
    val albumId: Long,
    val uploaderId: Long,
    val url: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val takenAtMs: Long?,
    val caption: String,
    // 注意：like_count / liked 只在**详情接口**（GET /photos/:id）返回，
    // 列表接口不带这两个字段，所以列表里它们恒为默认值——大图页会在打开时单独拉一次详情补上。
    // 服务端没有 comment_count 字段，故不设该字段（UI 用 comments.size）。
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
) {
    /** 网格里优先用缩略图；缺失才回退原图（省流量、避免 OOM）。 */
    val displayUrl: String get() = thumbUrl.ifBlank { url }

    companion object {
        fun fromJson(j: JSONObject) = PhotoItem(
            id = j.optLong("id"),
            albumId = j.optLong("album_id"),
            uploaderId = j.optLong("uploader_id"),
            url = j.optString("url"),
            thumbUrl = j.optString("thumb_url"),
            width = j.optInt("width"),
            height = j.optInt("height"),
            takenAtMs = optTimeMs(j, "taken_at").takeIf { it > 0 },
            caption = j.optString("caption"),
            likeCount = j.optInt("like_count"),
            // 服务端字段名是 liked（不是 liked_by_me），已对照 album_handlers.go 核实。
            likedByMe = j.optBoolean("liked"),
        )
    }
}

/** 照片评论。 */
data class PhotoCommentItem(
    val id: Long,
    val userId: Long,
    val userName: String,
    val content: String,
    val createdAtMs: Long,
) {
    companion object {
        fun fromJson(j: JSONObject) = PhotoCommentItem(
            id = j.optLong("id"),
            userId = j.optLong("user_id"),
            userName = j.optString("user_name"),
            content = j.optString("content"),
            createdAtMs = optTimeMs(j, "created_at"),
        )
    }
}
