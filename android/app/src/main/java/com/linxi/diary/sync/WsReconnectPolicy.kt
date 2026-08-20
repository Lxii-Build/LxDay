package com.linxi.diary.sync

import kotlin.random.Random

/** WebSocket 重连纯策略：与线程和 OkHttp 无关，便于 JVM 单测。 */
object WsReconnectPolicy {
    private const val MAX_BACKOFF_SHIFT = 4

    /** 抖动比例：±20%。 */
    private const val JITTER_RATIO = 0.2

    /** 鉴权失败（401/403）说明 token 失效，重连无意义，交由登录流程处理。 */
    fun shouldReconnect(httpCode: Int?): Boolean = httpCode != 401 && httpCode != 403

    /** 指数退避基值：1s 起，每次翻倍，封顶 16s（更快恢复，降低断连期间的状态同步延迟）。 */
    fun backoffMillis(retry: Int): Long =
        (1L shl retry.coerceIn(0, MAX_BACKOFF_SHIFT)) * 1000L

    /**
     * 带 ±20% 抖动的退避。
     *
     * 为什么必须有抖动：情侣双方常在同一 WiFi 下，断网/路由重启会让两台设备
     * 在同一时刻掉线，纯确定性退避使它们**永远同步重连**（惊群），
     * 既加剧服务端瞬时压力，也可能一起撞上同一次网络未就绪而双双失败。
     *
     * @param random 便于测试注入
     */
    fun backoffWithJitterMillis(retry: Int, random: Random = Random.Default): Long {
        val base = backoffMillis(retry)
        val span = (base * JITTER_RATIO).toLong()
        if (span <= 0) return base
        // 在 [base-span, base+span] 内取值，并保证不小于 100ms。
        val delta = random.nextLong(-span, span + 1)
        return (base + delta).coerceAtLeast(100L)
    }
}
