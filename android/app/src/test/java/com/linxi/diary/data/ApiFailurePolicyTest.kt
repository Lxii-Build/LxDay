package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiFailurePolicyTest {
    @Test
    fun `non 2xx response preserves the server business code`() {
        val failure = ApiFailurePolicy.from(
            httpCode = 404,
            responseBody = "{\"code\":1035,\"message\":\"伴侣还没有创建一起听房间\"}",
        )

        assertEquals(404, failure.httpCode)
        assertEquals(1035, failure.bizCode)
    }

    @Test
    fun `malformed or zero business code falls back to HTTP status`() {
        assertEquals(429, ApiFailurePolicy.from(429, "not json").bizCode)
        assertEquals(500, ApiFailurePolicy.from(500, "{\"code\":0}").bizCode)
    }
}
