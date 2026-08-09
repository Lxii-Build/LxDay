package com.linxi.diary.util

/** 日志进入 logcat、文件或导出包前的敏感字段脱敏。 */
object LogSanitizer {
    private val jsonValue = Regex(
        "(?i)(\"(?:token|invite_?code|inviteCode|ssid|api_?key|password)\"\\s*:\\s*\")[^\"]*(\")"
    )
    private val quotedValue = Regex(
        "(?i)(token|invite_?code|inviteCode|ssid|api_?key|password)(\\s*[:=]\\s*)([\"'])[^\"']*\\3"
    )
    private val plainValue = Regex(
        "(?i)(token|invite_?code|inviteCode|ssid|api_?key|password)(\\s*[:=]\\s*)(?![\"'])([^\\s&]+)"
    )
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~-]+")
    private val cookie = Regex("(?i)(Cookie\\s*:\\s*)[^\\s]+")
    private val tokenUrl = Regex("(?i)(https?://[^\\s?]+)\\?[^\\s]*token=[^\\s]+")

    fun sanitize(input: String): String = input
        .replace(tokenUrl, "$1?[REDACTED]")
        .replace(jsonValue, "$1[REDACTED]$2")
        .replace(quotedValue) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}[REDACTED]${it.groupValues[3]}" }
        .replace(plainValue, "$1$2[REDACTED]")
        .replace(bearer, "$1[REDACTED]")
        .replace(cookie, "$1[REDACTED]")
}
