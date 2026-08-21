package com.linxi.diary.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/**
 * 全局状态持有者：采集层写，同步层/UI 读。
 *
 * **底层是 StateFlow，不是普通字段。** 此前 `partner` 是普通 `@Volatile var`，
 * Compose 读它不会建立订阅关系 —— 服务端 WS 推到了、客户端也解析写入了，
 * 但主页 UI 根本不重组，只能靠 30s 资料轮询顺带触发重组来"蹭刷"。
 * 这是"状态同步感觉不实时"的真凶；把轮询间隔压小只会更耗电，治不了本。
 *
 * 属性名保持不变（`current` / `partner` / …），旧的直接读写调用点无需改动即可编译；
 * Compose 侧改用 [partnerFlow] / [currentFlow] + `collectAsStateWithLifecycle()`。
 */
object DeviceStatusHolder {

    private val _current = MutableStateFlow<DeviceStatus?>(null)
    private val _partner = MutableStateFlow<DeviceStatus?>(null)

    /** 本机状态（采集层写入）。 */
    val currentFlow: StateFlow<DeviceStatus?> = _current

    /** 伴侣状态（WS 推送写入）。Compose 订阅此 Flow 即可实时刷新。 */
    val partnerFlow: StateFlow<DeviceStatus?> = _partner

    var current: DeviceStatus?
        get() = _current.value
        set(v) { _current.value = v }

    var partner: DeviceStatus?
        get() = _partner.value
        set(v) { _partner.value = v }

    /**
     * 屏幕/锁屏的**缓存值**，仅供通知栏渲染等非关键路径读取。
     *
     * **不要拿它当权威来源**：初值只是个占位，真实状态一律用
     * [com.linxi.diary.core.ScreenStateProbe.current] 现读。
     * 此前初值硬编码 `true`，进程被 AlarmManager/BootReceiver 在**息屏时**拉起，
     * 第一次采集就上报"亮屏"——对方看到"他亮着屏"，而人在睡觉；
     * 这个错值一直持续到下一次真实的亮/息屏广播才纠正。
     *
     * 初值改为 `false`：宁可先报"息屏"（随后被真实值纠正），也不要谎报"亮屏"。
     */
    @Volatile var screenOn: Boolean = false
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
