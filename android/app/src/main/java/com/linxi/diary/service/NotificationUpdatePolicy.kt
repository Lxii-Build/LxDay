package com.linxi.diary.service

object NotificationUpdatePolicy {
    fun shouldUpdate(
        previous: NotificationCardState?,
        current: NotificationCardState,
    ): Boolean = previous != current
}
