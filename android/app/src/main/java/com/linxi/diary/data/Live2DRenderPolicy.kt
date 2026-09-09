package com.linxi.diary.data

/**
 * Rendering decisions that do not depend on Android or the Cubism SDK.
 *
 * Keeping this policy pure makes the expensive part of the Live2D lifecycle
 * testable: a hidden card must stop both the animation ticker and draw calls,
 * while a visible model is capped at a predictable mobile frame rate.
 */
object Live2DRenderPolicy {
    const val animatedFrameIntervalMs = 33L // <= 30fps for a companion card

    data class Visibility(
        val appStarted: Boolean,
        val hostVisible: Boolean,
        val selected: Boolean,
        val covered: Boolean,
        val widthPx: Int,
        val heightPx: Int,
    )

    data class Decision(
        val draw: Boolean,
        val animate: Boolean,
    )

    fun decide(visibility: Visibility, hasMotion: Boolean): Decision {
        val visible = visibility.appStarted &&
            visibility.hostVisible &&
            visibility.selected &&
            !visibility.covered &&
            visibility.widthPx > 0 &&
            visibility.heightPx > 0
        return Decision(draw = visible, animate = visible && hasMotion)
    }

    /**
     * Returns whether an animated frame is due.  Static models should be
     * rendered only on load/resize/theme changes, never from a free-running
     * ticker.  The first animated frame is always allowed.
     */
    fun frameDue(nowMs: Long, lastFrameMs: Long?, hasMotion: Boolean): Boolean {
        if (!hasMotion) return false
        if (lastFrameMs == null) return true
        return nowMs - lastFrameMs >= animatedFrameIntervalMs
    }
}
