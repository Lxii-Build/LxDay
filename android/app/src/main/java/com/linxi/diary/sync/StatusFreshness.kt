package com.linxi.diary.sync

/**
 * 伴侣状态的**时效判定**（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * `NowScreen` 此前完全不看 `ts`：2 小时前的状态和 2 秒前的长得一模一样。
 * 于是 WS 一断（地铁/电梯/飞行模式），对方看到的是一个**自信满满的错误信息**——
 * 「正在使用微信 · 亮屏 · 电量 68%」，而这是两小时前的快照。
 * 这比显示"未知"糟糕得多：用户没有任何线索知道该不该相信它。
 *
 * 管理员报的"状态总是显示不好"，很大一部分正是这个——不是同步不到，
 * 而是**同步不到时看不出来**。
 *
 * 加上时效显示后，下一轮排查也有了依据：
 * 管理员能直接告诉我"显示 3 分钟前"还是"显示已过期"，而不是笼统一句"不准"。
 */
object StatusFreshness {

    /** 超过这个时长就认为"可能已过期"，UI 置灰并明确提示。 */
    const val STALE_THRESHOLD_MS = 10 * 60_000L

    /** 超过这个时长就认为"完全失联"，不再展示具体状态值。 */
    const val OFFLINE_THRESHOLD_MS = 60 * 60_000L

    enum class Level {
        /** 新鲜：正常展示。 */
        Fresh,

        /** 可能过期：正常展示但置灰 + 提示。 */
        Stale,

        /** 太久了：不该再把旧值当现状展示。 */
        Offline,
    }

    fun levelOf(tsMs: Long, nowMs: Long): Level {
        if (tsMs <= 0) return Level.Offline
        val age = nowMs - tsMs
        // 客户端与服务端时钟可能有偏差，导致 age 为负。当作新鲜处理，
        // 不能因为对方手机快了几秒就报"过期"。
        if (age < 0) return Level.Fresh
        return when {
            age <= STALE_THRESHOLD_MS -> Level.Fresh
            age <= OFFLINE_THRESHOLD_MS -> Level.Stale
            else -> Level.Offline
        }
    }

    /**
     * 相对时间文案。
     * 刻意用"刚刚/N 分钟前"而非绝对时刻：用户关心的是"多久以前"，
     * 而不是"14:23"（那还得自己算差值）。
     */
    fun relativeLabel(tsMs: Long, nowMs: Long): String {
        if (tsMs <= 0) return "尚未同步"
        val age = nowMs - tsMs
        if (age < 0) return "刚刚"
        val minutes = age / 60_000
        return when {
            age < 60_000 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / (60 * 24)} 天前"
        }
    }

    /** 完整的时效提示文案（含过期警示）。 */
    fun hintText(tsMs: Long, nowMs: Long): String = when (levelOf(tsMs, nowMs)) {
        Level.Fresh -> "更新于 ${relativeLabel(tsMs, nowMs)}"
        Level.Stale -> "${relativeLabel(tsMs, nowMs)}的状态，可能已过期"
        Level.Offline -> if (tsMs <= 0) "尚未收到对方的状态" else "已失联 ${relativeLabel(tsMs, nowMs)}"
    }
}
