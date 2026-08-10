package com.linxi.diary.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsMessageDispatcherTest {
    @Test
    fun `资料更新在状态共享关闭时仍触发权威刷新`() {
        var refreshCount = 0
        var sensitiveCount = 0
        val dispatcher = WsMessageDispatcher(
            refreshProfile = { refreshCount++ },
            handleSensitive = { sensitiveCount++ },
        )

        val handled = dispatcher.dispatch(
            text = """{"type":"profile_updated","data":{"user_id":2,"nickname":"不应信任"}}""",
            sensitiveEventsAllowed = false,
        )

        assertTrue(handled)
        assertEquals(1, refreshCount)
        assertEquals(0, sensitiveCount)
    }

    @Test
    fun `状态共享关闭时敏感事件不进入现有处理链`() {
        var sensitiveCount = 0
        val dispatcher = WsMessageDispatcher(
            refreshProfile = {},
            handleSensitive = { sensitiveCount++ },
        )

        assertFalse(
            dispatcher.dispatch(
                text = """{"type":"partner_status","data":{"battery":80}}""",
                sensitiveEventsAllowed = false,
            )
        )
        assertEquals(0, sensitiveCount)
    }

    @Test
    fun `状态共享开启时非资料事件交给现有处理链`() {
        var sensitiveType = ""
        val dispatcher = WsMessageDispatcher(
            refreshProfile = {},
            handleSensitive = { sensitiveType = it.getString("type") },
        )
        val payload = """{"type":"partner_status","data":{"battery":80}}"""

        assertTrue(dispatcher.dispatch(payload, sensitiveEventsAllowed = true))
        assertEquals("partner_status", sensitiveType)
    }

    @Test
    fun `状态共享开启时损坏与未知事件不会进入敏感处理链`() {
        var sensitiveCount = 0
        val dispatcher = WsMessageDispatcher(
            refreshProfile = {},
            handleSensitive = { sensitiveCount++ },
        )

        assertFalse(dispatcher.dispatch("not-json", sensitiveEventsAllowed = true))
        assertFalse(
            dispatcher.dispatch(
                """{"type":"unknown","data":{}}""",
                sensitiveEventsAllowed = true,
            ),
        )
        assertEquals(0, sensitiveCount)
    }
}
