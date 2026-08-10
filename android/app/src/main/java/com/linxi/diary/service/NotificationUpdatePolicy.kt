package com.linxi.diary.service

data class NotificationRenderState(
    val card: NotificationCardState,
    val avatarFingerprint: Long,
)

object NotificationUpdatePolicy {
    fun shouldUpdate(
        previous: NotificationRenderState?,
        current: NotificationRenderState,
    ): Boolean = previous != current
}
