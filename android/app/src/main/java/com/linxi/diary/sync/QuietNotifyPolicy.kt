package com.linxi.diary.sync

import com.linxi.diary.core.DeviceStatus

/**
 * 伴侣动态「静默通知」的触发判定。
 *
 * 管理员要求：对方息屏/亮屏时给一个静默通知——不弹出、不响铃，但要推送出消息。
 * 决策 Q7=B：只推 息屏/亮屏 + 上线/下线，同类事件 60s 内合并，固定通知 id 覆盖更新。
 * 决策 Q8=A：不做免打扰时段（反正不响）。
 *
 * 纯函数 + 无 Android 依赖，便于单测；副作用（发通知）留给调用方。
 */
object QuietNotifyPolicy {

    /** 同类事件合并窗口：对方频繁亮息屏时不刷屏。 */
    const val DEDUP_WINDOW_MS = 60_000L

    enum class Kind { SCREEN_ON, SCREEN_OFF, ONLINE, OFFLINE }

    data class Event(val kind: Kind, val title: String, val body: String)

    /**
     * 由「上一次伴侣状态」与「新状态」的差异推出要发的静默通知。
     *
     * @param previous 上一次已知的伴侣状态；null 表示本次是首次拿到（不发通知，避免一上线就弹）
     * @param next 新到达的伴侣状态
     */
    fun diff(previous: DeviceStatus?, next: DeviceStatus): Event? {
        // 首次拿到状态不通知：否则每次冷启动/重连都会弹一条，属噪音。
        if (previous == null) return null
        if (previous.screenOn == next.screenOn) return null
        return if (next.screenOn) {
            Event(Kind.SCREEN_ON, "对方 已亮屏", "TA 刚刚拿起手机")
        } else {
            Event(Kind.SCREEN_OFF, "对方 已息屏", "TA 放下手机了")
        }
    }

    /** 在线状态变化（WS 连接/判死）产生的通知。 */
    fun presence(online: Boolean): Event =
        if (online) Event(Kind.ONLINE, "对方 已上线", "TA 现在在线")
        else Event(Kind.OFFLINE, "对方 已离线", "TA 的连接已断开")

    /**
     * 是否允许发出：同类事件在窗口内只发一条。
     *
     * @param lastSentAtMs 该 kind 上一次发出的时刻；null 表示从未发过
     */
    fun shouldSend(
        kind: Kind,
        lastSentAtMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        enabled: Boolean = true,
    ): Boolean {
        if (!enabled) return false
        if (lastSentAtMs == null) return true
        return nowMs - lastSentAtMs >= DEDUP_WINDOW_MS
    }
}

/** 静默通知的去重记账（有状态，与判定逻辑分离便于测试）。 */
class QuietNotifyThrottle(private val clock: () -> Long = System::currentTimeMillis) {

    private val lastSent = mutableMapOf<QuietNotifyPolicy.Kind, Long>()

    /** 通过节流则返回 true 并记账；否则返回 false。 */
    fun tryAcquire(kind: QuietNotifyPolicy.Kind, enabled: Boolean = true): Boolean {
        val now = clock()
        if (!QuietNotifyPolicy.shouldSend(kind, lastSent[kind], now, enabled)) return false
        lastSent[kind] = now
        return true
    }

    fun reset() = lastSent.clear()
}
