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
    fun `解除绑定在状态共享关闭时仍触发权威刷新`() {
        var refreshCount = 0
        val dispatcher = WsMessageDispatcher(
            refreshProfile = { refreshCount++ },
            handleSensitive = {},
        )

        assertTrue(
            dispatcher.dispatch(
                text = """{"type":"unbound","data":{"pair_id":7}}""",
                sensitiveEventsAllowed = false,
            )
        )
        assertEquals(1, refreshCount)
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
    fun `动作被拒回执在状态共享关闭时也必须送达UI`() {
        // 这是"你自己刚发的动作被服务端拒了"，与共享开关无关。
        // 若被门控掉，就又回到"UI 显示已发送、其实对方没收到"的静默失败。
        var rejectedAction = ""
        var rejectedReason = ""
        var sensitiveCount = 0
        val dispatcher = WsMessageDispatcher(
            refreshProfile = {},
            handleSensitive = { sensitiveCount++ },
            handleRejected = { a, r -> rejectedAction = a; rejectedReason = r },
        )

        val handled = dispatcher.dispatch(
            text = """{"type":"action_rejected","data":{"action":"ring_request","reason":"对方 10 分钟内已被响铃 3 次"}}""",
            sensitiveEventsAllowed = false,
        )

        assertTrue(handled)
        assertEquals("ring_request", rejectedAction)
        assertEquals("对方 10 分钟内已被响铃 3 次", rejectedReason)
        assertEquals(0, sensitiveCount)
    }

    @Test
    fun `拒绝回执缺少原因时给出兜底文案`() {
        var reason = ""
        val dispatcher = WsMessageDispatcher(
            refreshProfile = {},
            handleSensitive = {},
            handleRejected = { _, r -> reason = r },
        )

        assertTrue(
            dispatcher.dispatch(
                """{"type":"action_rejected","data":{"action":"ring_request"}}""",
                sensitiveEventsAllowed = true,
            )
        )
        assertEquals("操作被拒绝", reason)
    }

    @Test
    fun `撤回与回执事件走敏感处理链`() {
        val seen = mutableListOf<String>()
        val dispatcher = WsMessageDispatcher(
            refreshProfile = {},
            handleSensitive = { seen += it.getString("type") },
        )

        assertTrue(dispatcher.dispatch("""{"type":"ring_cancel","data":{}}""", true))
        assertTrue(dispatcher.dispatch("""{"type":"ring_stopped","data":{}}""", true))
        assertEquals(listOf("ring_cancel", "ring_stopped"), seen)
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
