package com.linxi.diary.util

/** 诊断日志导出前的敏感字段脱敏。 */
object LogSanitizer {
    private val keyValue = Regex("(?i)(token|invite_?code|ssid)=([^\\s&]+)")
    private val jsonValue = Regex("(?i)(\"(?:token|invite_?code|inviteCode|ssid)\"\\s*:\\s*\")[^\"]*(\")")
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~-]+")
    private val tokenUrl = Regex("(?i)(https?://[^\\s?]+)\\?[^\\s]*token=[^\\s]+")

    fun sanitize(input: String): String = input
        .replace(tokenUrl, "$1?[REDACTED]")
        .replace(jsonValue, "$1[REDACTED]$2")
        .replace(bearer, "$1[REDACTED]")
        .replace(keyValue, "$1=[REDACTED]")
}
