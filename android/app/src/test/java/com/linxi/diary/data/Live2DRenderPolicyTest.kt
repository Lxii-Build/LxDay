package com.linxi.diary.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Live2DRenderPolicyTest {
    private val visible = Live2DRenderPolicy.Visibility(
        appStarted = true,
        hostVisible = true,
        selected = true,
        covered = false,
        widthPx = 390,
        heightPx = 180,
    )

    @Test
    fun hiddenOrCoveredHostStopsDrawAndAnimation() {
        val covered = visible.copy(covered = true)
        val decision = Live2DRenderPolicy.decide(covered, hasMotion = true)
        assertFalse(decision.draw)
        assertFalse(decision.animate)
    }

    @Test
    fun staticModelDoesNotStartAFreeRunningTicker() {
        val decision = Live2DRenderPolicy.decide(visible, hasMotion = false)
        assertTrue(decision.draw)
        assertFalse(decision.animate)
        assertFalse(Live2DRenderPolicy.frameDue(10_000L, null, hasMotion = false))
    }

    @Test
    fun animatedFramesAreCappedAtThirtyFps() {
        assertTrue(Live2DRenderPolicy.frameDue(0L, null, hasMotion = true))
        assertFalse(Live2DRenderPolicy.frameDue(32L, 0L, hasMotion = true))
        assertTrue(Live2DRenderPolicy.frameDue(33L, 0L, hasMotion = true))
    }
}
