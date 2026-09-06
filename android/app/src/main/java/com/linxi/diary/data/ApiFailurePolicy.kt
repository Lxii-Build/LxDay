package com.linxi.diary.data

import org.json.JSONObject

/**
 * Separates an HTTP transport status from the API's business status.
 *
 * The server deliberately uses non-2xx statuses for expected states such as
 * "there is no active listen room" (404 / 1035). Callers branch on the
 * business code, so replacing it with 404 makes those recovery paths
 * unreachable.
 */
data class ApiFailure(val httpCode: Int, val bizCode: Int)

object ApiFailurePolicy {
    fun from(httpCode: Int, responseBody: String): ApiFailure {
        val bodyCode = runCatching { JSONObject(responseBody).optInt("code", 0) }
            .getOrNull()
            ?.takeIf { it != 0 }
        return ApiFailure(httpCode = httpCode, bizCode = bodyCode ?: httpCode)
    }
}
