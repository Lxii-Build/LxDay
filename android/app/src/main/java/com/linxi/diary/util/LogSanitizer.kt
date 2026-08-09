package com.linxi.diary.util

/** 诊断日志导出前的敏感字段脱敏。 */
object LogSanitizer {
    private val keyValue = Regex("(?i)(token|invite_code|ssid)=([^\\s&]+)")
    private val tokenUrl = Regex("(?i)(https?://[^\\s?]+)\\?[^\\s]*token=[^\\s]+")

    fun sanitize(input: String): String = input
        .replace(tokenUrl, "$1?[REDACTED]")
        .replace(keyValue, "$1=[REDACTED]")
}
