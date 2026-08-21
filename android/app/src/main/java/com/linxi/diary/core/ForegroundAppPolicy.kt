package com.linxi.diary.core

/**
 * 前台应用判定的**纯策略**（无 Android 依赖，可 JVM 单测）。
 *
 * ## 旧实现的三个缺陷（0821 查明）
 *
 * 1. **每次采集遍历过去 24 小时全部事件**，只为取最后一个 RESUMED。
 *    重度使用一天有几千到上万条 UsageEvents，而前台档位是**每 10 秒采集一次**。
 * 2. **只认 `ACTIVITY_RESUMED`/`MOVE_TO_FOREGROUND`，不看 PAUSED/STOPPED**。
 *    用户按 Home 回桌面后，最后一个 RESUMED 仍是微信 → 一直显示"正在使用微信"。
 * 3. **息屏时不清空**：`collectAll` 无条件调 `foregroundApp()`，不看屏幕状态。
 *    息屏后仍报着息屏前那个应用，这就是管理员说的"总是显示不好"最直接的表现。
 *
 * ## 修法
 *
 * - 查询窗口从 24h 缩到 [PRIMARY_WINDOW_MS]（60 秒），遍历量从上万降到几十条
 * - 按时间排序取**最后一个事件**：RESUMED → 有前台；PAUSED/STOPPED → 无前台（回桌面）
 * - 窗口内一条事件都没有时**逐级回退**（60s → 5min → 30min）：
 *   用户连续看小说 20 分钟不切应用，60s 窗口内确实没有事件，
 *   不回退就会误判成"无前台"
 * - 息屏/AOD 时**根本不查**，直接报"无前台"（省电 + 语义正确）
 */
object ForegroundAppPolicy {

    /** 主查询窗口：60 秒。绝大多数情况下这里就能拿到结果。 */
    const val PRIMARY_WINDOW_MS = 60_000L

    /** 逐级回退的窗口。只有前一级查不到才用下一级。 */
    val FALLBACK_WINDOWS_MS = longArrayOf(5 * 60_000L, 30 * 60_000L, 6 * 60 * 60_000L)

    /** 一条 UsageEvents 事件的精简表示。 */
    data class Event(val pkg: String, val timestamp: Long, val type: Type)

    enum class Type {
        /** ACTIVITY_RESUMED / MOVE_TO_FOREGROUND */
        Resumed,

        /** ACTIVITY_PAUSED / MOVE_TO_BACKGROUND */
        Paused,

        /** ACTIVITY_STOPPED */
        Stopped,
    }

    /**
     * 从事件列表推断当前前台包名。
     *
     * @return 包名；null 表示"无前台应用"（回到桌面或无法判定）
     */
    fun resolve(events: List<Event>): String? {
        if (events.isEmpty()) return null
        // 取时间最大的那条。同一毫秒有多条时，Resumed 优先——
        // 切换应用时 A.Paused 与 B.Resumed 常常同刻，此时前台显然是 B。
        val last = events.maxWithOrNull(
            compareBy<Event> { it.timestamp }.thenBy { if (it.type == Type.Resumed) 1 else 0 }
        ) ?: return null
        return when (last.type) {
            Type.Resumed -> last.pkg
            // 最后一个动作是"离开"，且之后没有任何应用 Resumed → 用户在桌面。
            Type.Paused, Type.Stopped -> null
        }
    }

    /**
     * 是否需要查询前台应用。
     *
     * 息屏与 AOD 时不查：既省电，也避免报出"息屏了还在用微信"这种错值。
     * 也不需要「使用情况访问」权限时白跑一趟。
     */
    fun shouldQuery(screenState: ScreenState, hasUsageAccess: Boolean): Boolean =
        hasUsageAccess && screenState == ScreenState.On

    /**
     * 回退窗口序列：主窗口 + 各级回退。
     * 调用方按顺序试，拿到非空事件列表就停。
     */
    fun windowSequence(): LongArray = longArrayOf(PRIMARY_WINDOW_MS) + FALLBACK_WINDOWS_MS

    /**
     * 缓存是否仍然可用。
     *
     * 即便所有窗口都查不到事件（例如用户开机后一直停在同一个应用里），
     * 也不该立刻把前台应用清空——那会让对方看到状态在"有/无"之间闪烁。
     * 缓存在 [CACHE_TTL_MS] 内可继续使用。
     */
    const val CACHE_TTL_MS = 10 * 60_000L

    fun cacheUsable(cachedAtMs: Long, nowMs: Long): Boolean =
        cachedAtMs > 0 && nowMs - cachedAtMs in 0..CACHE_TTL_MS
}
