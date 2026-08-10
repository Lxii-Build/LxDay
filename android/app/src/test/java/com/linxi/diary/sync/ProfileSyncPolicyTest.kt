package com.linxi.diary.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncPolicyTest {
    @Test
    fun `登录级资料同步不依赖状态共享或知情同意`() {
        assertTrue(
            ProfileSyncPolicy.canConnect(
                token = "jwt",
                pairId = 7,
                demoMode = false,
            )
        )
    }

    @Test
    fun `缺少认证绑定或处于示例模式时不连接资料同步`() {
        assertFalse(ProfileSyncPolicy.canConnect(token = null, pairId = 7, demoMode = false))
        assertFalse(ProfileSyncPolicy.canConnect(token = "jwt", pairId = 0, demoMode = false))
        assertFalse(ProfileSyncPolicy.canConnect(token = "jwt", pairId = 7, demoMode = true))
    }
}
