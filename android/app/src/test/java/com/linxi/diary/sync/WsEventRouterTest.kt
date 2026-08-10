package com.linxi.diary.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsEventRouterTest {
    @Test
    fun `资料更新事件只携带用户ID并触发重新拉取`() {
        assertEquals(
            WsAction.RefreshProfile(changedUserId = 2),
            WsEventRouter.route(
                """{"type":"profile_updated","data":{"user_id":2,"nickname":"不应信任"}}"""
            ),
        )
    }

    @Test
    fun `已知敏感事件保留已解析消息`() {
        val action = WsEventRouter.route(
            """{"type":"partner_status","data":{"battery":80}}""",
        )

        assertTrue(action is WsAction.Sensitive)
        assertEquals(80, (action as WsAction.Sensitive).message.getJSONObject("data").getInt("battery"))
    }

    @Test
    fun `未知或损坏事件安全忽略`() {
        assertNull(WsEventRouter.route("""{"type":"unknown"}"""))
        assertNull(WsEventRouter.route("not-json"))
    }
}
