package com.linxi.diary.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLoopPolicyTest {
    @Test
    fun `only same crash inside one minute backs off exponentially`() {
        val first = CrashLoopPolicy.decide("same", null, 0, 0, 10_000)
        val second = CrashLoopPolicy.decide("same", "same", 10_000, first.repeatCount, 11_000)
        val third = CrashLoopPolicy.decide("same", "same", 11_000, second.repeatCount, 12_000)

        assertEquals(0L, first.delayMillis)
        assertEquals(1_000L, second.delayMillis)
        assertEquals(2_000L, third.delayMillis)
    }

    @Test
    fun `different or stale crashes do not delay a normal restart`() {
        assertEquals(
            0L,
            CrashLoopPolicy.decide("new", "old", 10_000, 4, 10_001).delayMillis,
        )
        assertEquals(
            0L,
            CrashLoopPolicy.decide("same", "same", 10_000, 4, 70_001).delayMillis,
        )
    }

    @Test
    fun `signature includes exception type and first app frame`() {
        val error = IllegalStateException("test").apply {
            stackTrace = arrayOf(
                StackTraceElement("other.Library", "call", "Library.kt", 1),
                StackTraceElement("com.linxi.diary.data.ApiClient", "get", "ApiClient.kt", 2),
            )
        }

        assertEquals(
            "java.lang.IllegalStateException@com.linxi.diary.data.ApiClient.get",
            CrashLoopPolicy.signature(error),
        )
    }
}
