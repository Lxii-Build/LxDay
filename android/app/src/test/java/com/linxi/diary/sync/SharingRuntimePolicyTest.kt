package com.linxi.diary.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingRuntimePolicyTest {
    @Test
    fun `真实绑定且已同意并开启共享时允许运行同步`() {
        assertTrue(
            SharingRuntimePolicy.canRun(
                pairId = 1,
                privacyConsented = true,
                sharingEnabled = true,
                demoMode = false,
            )
        )
    }

    @Test
    fun `调试模式永不运行真实同步与采集`() {
        assertFalse(
            SharingRuntimePolicy.canRun(
                pairId = 1,
                privacyConsented = true,
                sharingEnabled = true,
                demoMode = true,
            )
        )
    }

    @Test
    fun `未同意或未开启共享时不运行真实同步`() {
        assertFalse(SharingRuntimePolicy.canRun(1, false, true, false))
        assertFalse(SharingRuntimePolicy.canRun(1, true, false, false))
        assertFalse(SharingRuntimePolicy.canRun(0, true, true, false))
    }

    @Test
    fun `调试模式同意后仍保持共享关闭`() {
        assertFalse(SharingRuntimePolicy.enableSharingAfterConsent(demoMode = true))
        assertTrue(SharingRuntimePolicy.enableSharingAfterConsent(demoMode = false))
    }
}
