package com.linxi.diary.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `日志脱敏移除 token 邀请码 ssid 和认证 URL 参数`() {
        val input = "token=secret-token invite_code=123456 ssid=HomeWiFi url=https://api.linxi.app/ws?token=abc"

        assertEquals(
            "token=[REDACTED] invite_code=[REDACTED] ssid=[REDACTED] url=https://api.linxi.app/ws?[REDACTED]",
            LogSanitizer.sanitize(input)
        )
    }

    @Test
    fun `脱敏 JSON Bearer 和邀请码变体`() {
        val input = "{\"token\":\"secret\",\"ssid\":\"HomeWiFi\",\"inviteCode\":\"123456\"} Authorization: Bearer abc.def"

        assertEquals(
            "{\"token\":\"[REDACTED]\",\"ssid\":\"[REDACTED]\",\"inviteCode\":\"[REDACTED]\"} Authorization: Bearer [REDACTED]",
            LogSanitizer.sanitize(input)
        )
    }

    @Test
    fun `脱敏保留普通查询路径但移除认证查询参数`() {
        assertEquals(
            "request https://api.linxi.app/status?date=2026-08-09 url=https://api.linxi.app/ws?[REDACTED]",
            LogSanitizer.sanitize(
                "request https://api.linxi.app/status?date=2026-08-09 url=https://api.linxi.app/ws?token=secret"
            )
        )
    }
}
