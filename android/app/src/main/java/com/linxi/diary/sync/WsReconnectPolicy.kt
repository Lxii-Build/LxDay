package com.linxi.diary.sync

/** WebSocket 重连纯策略：与线程和 OkHttp 无关，便于 JVM 单测。 */
object WsReconnectPolicy {
    private const val MAX_BACKOFF_SHIFT = 6

    /** 鉴权失败（401/403）说明 token 失效，重连无意义，交由登录流程处理。 */
    fun shouldReconnect(httpCode: Int?): Boolean = httpCode != 401 && httpCode != 403

    /** 指数退避：1s 起，每次翻倍，封顶 64s。 */
    fun backoffMillis(retry: Int): Long =
        (1L shl retry.coerceIn(0, MAX_BACKOFF_SHIFT)) * 1000L
}
