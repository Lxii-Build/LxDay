package com.linxi.diary.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsReconnectPolicyTest {
    @Test
    fun `鉴权失败不再重连`() {
        assertFalse(WsReconnectPolicy.shouldReconnect(httpCode = 401))
        assertFalse(WsReconnectPolicy.shouldReconnect(httpCode = 403))
    }

    @Test
    fun `无响应或服务端错误仍然重连`() {
        assertTrue(WsReconnectPolicy.shouldReconnect(httpCode = null))
        assertTrue(WsReconnectPolicy.shouldReconnect(httpCode = 500))
    }

    @Test
    fun `退避按重试次数指数增长并封顶`() {
        assertEquals(1_000L, WsReconnectPolicy.backoffMillis(retry = 0))
        assertEquals(2_000L, WsReconnectPolicy.backoffMillis(retry = 1))
        assertEquals(16_000L, WsReconnectPolicy.backoffMillis(retry = 4))
        assertEquals(16_000L, WsReconnectPolicy.backoffMillis(retry = 9))
    }
}
