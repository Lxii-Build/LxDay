package com.linxi.diary.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 服务端 DTO 解析（org.json，时间字段兼容 RFC3339 字符串 与 epoch 毫秒/秒） */

/** 解析时间字段：兼容 Go time.Time 的 RFC3339 字符串 或 epoch 毫秒。返回 epoch 毫秒；解析失败返回 0 */
private fun optTimeMs(j: JSONObject, key: String): Long {
    val raw = j.opt(key) ?: return 0L
    return when (raw) {
        is Number -> raw.toLong()
        is String -> runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(raw).time
        }.getOrElse {
            // 备选格式（无时区）
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(raw).time
            }.getOrNull() ?: 0L
        }
        else -> 0L
    }
}

/** 解析秒级时间戳（服务端 todo.remind_at epoch 秒） */
private fun optTimeSec(j: JSONObject, key: String): Long {
    val ms = optTimeMs(j, key)
    // epoch 秒（如 1780000000）会被当作毫秒，需判断量级
    return when {
        ms in 1_000_000_000_000..2_000_000_000_000 -> ms         // 已是毫秒级
        ms in 100_000_000..2_000_000_000 -> ms * 1000            // 秒级 → 毫秒
        else -> ms
    }
}

data class TodoItem(
    val id: Long,
    val pairId: Long,
    val creatorId: Long,
    val assigneeId: Long,
    val title: String,
    val note: String,
    val remindAtMs: Long?,     // epoch 毫秒
    val remindType: Int,       // 0普通 1强提醒
    val status: Int            // 0待办 1完成 2删除
) {
    companion object {
        fun fromJson(j: JSONObject) = TodoItem(
            id = j.optLong("id"),
            pairId = j.optLong("pair_id"),
            creatorId = j.optLong("creator_id"),
            assigneeId = j.optLong("assignee_id"),
            title = j.optString("title"),
            note = j.optString("note"),
            remindAtMs = optTimeSec(j, "remind_at").takeIf { it > 0 },
            remindType = j.optInt("remind_type"),
            status = j.optInt("status")
        )
    }
}

data class DiaryItem(
    val id: Long,
    val authorId: Long,
    val authorName: String,
    val title: String,
    val content: String,
    val diaryDate: String,   // YYYY-MM-DD
    val images: List<String>,
    val createdAtMs: Long
) {
    companion object {
        fun fromJson(j: JSONObject) = DiaryItem(
            id = j.optLong("id"),
            authorId = j.optLong("author_id"),
            authorName = j.optString("author_name"),
            title = j.optString("title"),
            content = j.optString("content"),
            diaryDate = j.optString("diary_date"),
            images = j.optJSONArray("images")?.let {
                (0 until it.length()).map { i -> it.optString(i) }
            } ?: emptyList(),
            createdAtMs = optTimeMs(j, "created_at")
        )
    }
}

/** 历史时间线条目 */
data class HistoryEntry(
    val battery: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    val locked: Boolean,
    val foregroundApp: String,
    val ssid: String,
    val network: String,
    val ts: Long                 // epoch 毫秒
) {
    val timeLabel: String get() =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    companion object {
        fun fromJson(j: JSONObject) = HistoryEntry(
            battery = j.optInt("battery"),
            charging = j.optBoolean("charging"),
            screenOn = j.optBoolean("screen_on"),
            locked = j.optBoolean("locked"),
            foregroundApp = j.optString("foreground_app"),
            ssid = j.optString("ssid"),
            network = j.optString("network"),
            ts = j.optLong("ts")
        )
    }
}

/** 电量曲线点 */
data class BatteryPoint(val battery: Int, val charging: Boolean, val ts: Long) {
    companion object {
        fun fromJson(j: JSONObject) = BatteryPoint(
            battery = j.optInt("battery"),
            charging = j.optBoolean("charging"),
            ts = j.optLong("ts")
        )
    }
}
