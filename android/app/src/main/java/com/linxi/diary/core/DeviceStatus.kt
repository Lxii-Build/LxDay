package com.linxi.diary.core

import org.json.JSONObject

data class MusicInfo(val title: String, val artist: String, val playing: Boolean)
data class AppUsage(val pkg: String, val name: String, val minutes: Long)
data class DeviceStatus(
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val screenOn: Boolean = false,
    val isLocked: Boolean = true,
    val foregroundApp: Pair<String, String>? = null,
    val music: MusicInfo? = null,
    val ssid: String? = null,           // null = 移动网络
    val network: String = "wifi",
    val usage: List<AppUsage> = emptyList(),
    val ts: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("battery", batteryLevel)
        put("charging", isCharging)
        put("screen_on", screenOn)
        put("locked", isLocked)
        put("network", network)
        put("ssid", ssid ?: JSONObject.NULL)
        put("ts", ts)
        foregroundApp?.let { put("foreground_app",
            JSONObject().put("pkg", it.first).put("name", it.second)) }
        music?.let { put("music",
            JSONObject().put("title", it.title).put("artist", it.artist).put("playing", it.playing)) }
    }
}

/** 全局状态持有者：采集层写，同步层/UI 读 */
object DeviceStatusHolder {
    @Volatile var current: DeviceStatus? = null      // 本机状态（采集自写）
    @Volatile var partner: DeviceStatus? = null      // 伴侣状态（WS 推送）
    @Volatile var screenOn: Boolean = true
    @Volatile var isLocked: Boolean = true
    @Volatile var music: MusicInfo? = null
}

/** 状态语义配色：充电绿 / 低电红 / 亮屏蓝 / 音乐紫 */
object StatusColor {
    const val GREEN  = 0xFF4CAF50.toInt() // 充电
    const val RED    = 0xFFF44336.toInt() // 低电量 <15%
    const val BLUE   = 0xFF2196F3.toInt() // 亮屏
    const val PURPLE = 0xFF9C27B0.toInt() // 播放音乐
    const val THEME  = 0xFF607D8B.toInt() // 默认蓝灰

    fun of(s: DeviceStatus): Int = when {
        s.isCharging -> GREEN
        s.batteryLevel < 15 -> RED
        s.screenOn -> BLUE
        s.music?.playing == true -> PURPLE
        else -> THEME
    }
}
