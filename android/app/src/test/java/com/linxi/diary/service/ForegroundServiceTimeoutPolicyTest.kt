package com.linxi.diary.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceTimeoutPolicyTest {
    @Test
    fun `a timed out background service is not restarted by heartbeat`() {
        assertFalse(ForegroundServiceTimeoutPolicy.canStart(timedOut = true, appInForeground = false))
    }

    @Test
    fun `user returning to app may start a fresh service`() {
        assertTrue(ForegroundServiceTimeoutPolicy.canStart(timedOut = true, appInForeground = true))
        assertTrue(ForegroundServiceTimeoutPolicy.canStart(timedOut = false, appInForeground = false))
    }
}
