package com.linxi.diary.core

import java.util.Calendar

/**
 * 待办循环提醒的下一次触发时刻计算。
 *
 * 与服务端 `handlers.go` 的 `normalizeRepeat` / `nextRemind` 保持同一语义，
 * 否则本地闹钟与服务端推送会在不同时刻触发（用户会收到两次或错过）：
 * - repeatType: 0=仅一次 1=每天 2=每周指定几天
 * - weekdays 位掩码：**bit0=周一 … bit6=周日**（对应服务端 `(weekday+6)%7`）
 * - 全选（0x7F）等价于每天，归一化为 repeatType=1
 *
 * 纯函数、无 Android 依赖，便于单测。
 */
object TodoRepeatPolicy {

    const val ALL_WEEKDAYS_MASK = 0x7F

    /** 与服务端 normalizeRepeat 等价：把非法/等价组合折叠成规范形式。 */
    fun normalize(repeatType: Int, weekdays: Int): Pair<Int, Int> = when (repeatType) {
        2 -> {
            val masked = weekdays and ALL_WEEKDAYS_MASK
            when (masked) {
                0 -> 0 to 0                     // 一天都没选 → 退化为仅一次
                ALL_WEEKDAYS_MASK -> 1 to 0     // 全选 → 每天
                else -> 2 to masked
            }
        }
        1 -> 1 to 0
        else -> 0 to 0
    }

    /**
     * 由「本次触发时刻」推出「下一次触发时刻」。
     *
     * @param currentMs 本次的提醒时刻（毫秒）
     * @param nowMs 参考现在时刻，默认取系统时间。结果保证严格晚于它。
     * @return 下一次触发的毫秒时间戳；不再重复则返回 null
     */
    fun nextRemindAt(
        currentMs: Long,
        repeatType: Int,
        weekdays: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        val (type, mask) = normalize(repeatType, weekdays)
        val cal = Calendar.getInstance().apply { timeInMillis = currentMs }
        return when (type) {
            1 -> {
                // 每天：从本次时刻起逐日推进，直到晚于 now。
                // 上限 400 次与服务端一致，防脏数据（如 remindAt 是很久以前）导致死循环。
                var guard = 0
                while (cal.timeInMillis <= nowMs && guard < 400) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    guard++
                }
                cal.timeInMillis.takeIf { it > nowMs }
            }
            2 -> {
                if (mask == 0) return null
                // 每周指定几天：最多向前找 14 天（覆盖两轮，必然命中）。
                repeat(14) {
                    val idx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一=0 … 周日=6
                    if (cal.timeInMillis > nowMs && (mask and (1 shl idx)) != 0) {
                        return cal.timeInMillis
                    }
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                null
            }
            else -> null
        }
    }
}
