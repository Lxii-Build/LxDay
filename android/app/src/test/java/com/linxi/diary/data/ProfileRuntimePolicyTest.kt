package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRuntimePolicyTest {
    @Test
    fun `权威未绑定结果要求断开会话并返回绑定页`() {
        val action = requireNotNull(ProfileRefreshAction.fromResult(ProfileRefreshResult.Unbound))

        assertEquals(ProfileRefreshAction.Unbound, action)
        assertTrue(action.disconnectSession)
        assertTrue(action.navigateToBind)
    }

    @Test
    fun `过期刷新结果不终止当前会话`() {
        assertNull(ProfileRefreshAction.fromResult(ProfileRefreshResult.Superseded))
    }

    @Test
    fun `权威资料结果不终止当前会话`() {
        val profile = CoupleProfile(
            pairId = 7,
            me = ProfileUser(1, "林曦", null, null),
            partner = ProfileUser(2, "伴侣", null, null),
            anniversaryDate = null,
        )

        assertEquals(
            ProfileRefreshAction.Updated(profile),
            ProfileRefreshAction.fromResult(ProfileRefreshResult.Updated(profile)),
        )
    }
}
