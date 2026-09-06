package com.linxi.diary.service

/**
 * Android's dataSync foreground-service budget is exhausted until the user
 * brings the app to the foreground again. Do not have heartbeat broadcasts
 * immediately start the same service into another platform failure.
 */
object ForegroundServiceTimeoutPolicy {
    fun canStart(timedOut: Boolean, appInForeground: Boolean): Boolean =
        !timedOut || appInForeground
}
