package com.linxi.diary.sync

/**
 * 同步节奏分档策略。
 *
 * 管理员要求「信息同步时间间隔再减小一点」。但真正让主页看起来不实时的原因是
 * 伴侣状态此前不是 Compose State（见 DeviceStatusHolder 注释）——那条已修好，
 * WS 到达即刷新（毫秒级）。这里的轮询只是 **WS 不可用时的兜底**，
 * 所以按前台/后台/息屏分档：前台压到 10s 提升体感，后台与息屏放宽以省电。
 *
 * 决策 Q5=A：前台 10s / 后台 60s / 息屏 5min。
 * 决策 Q6=A：WS 心跳 15s（原 20s），服务端判死 45s（原 90s），退避加 ±20% jitter。
 *
 * 纯函数、无 Android 依赖，便于单测。
 */
object SyncIntervalPolicy {

    /** App 可见时的兜底轮询间隔。 */
    const val FOREGROUND_MS = 10_000L

    /** App 退到后台（仍亮屏）时的轮询间隔。 */
    const val BACKGROUND_MS = 60_000L

    /** 息屏时的轮询间隔：只保状态不掉线。 */
    const val SCREEN_OFF_MS = 300_000L

    /** WS ping 心跳间隔。服务端判死 45s ≈ 3 个心跳周期。 */
    const val HEARTBEAT_SECONDS = 15L

    enum class Phase { FOREGROUND, BACKGROUND, SCREEN_OFF }

    /**
     * 当前应采用的轮询间隔。
     *
     * @param appVisible App 是否处于前台可见（ProcessLifecycleOwner）
     * @param screenOn 屏幕是否亮着
     */
    fun intervalMs(appVisible: Boolean, screenOn: Boolean): Long = when {
        // 息屏优先级最高：息屏时即便 Composition 还活着也不该按前台频率轮询。
        !screenOn -> SCREEN_OFF_MS
        appVisible -> FOREGROUND_MS
        else -> BACKGROUND_MS
    }

    fun phase(appVisible: Boolean, screenOn: Boolean): Phase = when {
        !screenOn -> Phase.SCREEN_OFF
        appVisible -> Phase.FOREGROUND
        else -> Phase.BACKGROUND
    }
}
