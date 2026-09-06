package com.linxi.diary.util

/** Pure crash-loop decision logic; Android storage and process shutdown stay in [CrashHandler]. */
data class CrashLoopDecision(
    val signature: String,
    /** Number of repeats after the first crash inside the rolling window. */
    val repeatCount: Int,
    /** Delay before delegating to Android's crash handler. Zero for a new crash. */
    val delayMillis: Long,
)

object CrashLoopPolicy {
    const val WINDOW_MILLIS = 60_000L
    private const val MAX_DELAY_MILLIS = 30_000L

    fun signature(throwable: Throwable): String {
        val appFrame = throwable.stackTrace.firstOrNull { it.className.startsWith("com.linxi.diary.") }
        val location = appFrame?.let { "${it.className}.${it.methodName}" } ?: "unknown"
        return "${throwable.javaClass.name}@$location"
    }

    fun decide(
        signature: String,
        previousSignature: String?,
        previousAtMillis: Long,
        previousRepeatCount: Int,
        nowMillis: Long,
    ): CrashLoopDecision {
        val repeats = if (
            signature == previousSignature &&
            nowMillis >= previousAtMillis &&
            nowMillis - previousAtMillis <= WINDOW_MILLIS
        ) {
            previousRepeatCount + 1
        } else {
            0
        }
        val delay = if (repeats == 0) {
            0L
        } else {
            (1_000L shl (repeats - 1).coerceAtMost(4)).coerceAtMost(MAX_DELAY_MILLIS)
        }
        return CrashLoopDecision(signature, repeats, delay)
    }
}
