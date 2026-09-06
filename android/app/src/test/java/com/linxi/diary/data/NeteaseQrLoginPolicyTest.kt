package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseQrLoginPolicyTest {
    @Test
    fun webQrContextContainsRequiredBrowserValues() {
        val cookies = NeteaseQrLoginPolicy.createWebCookies(now = 1_700_000_000_000L)

        assertTrue(cookies["JSESSIONID-WYYY"].orEmpty().length >= 190)
        assertEquals("33", cookies["_iuqxldmzr_"])
        assertTrue(cookies["_ntes_nuid"].orEmpty().matches(Regex("[0-9a-f]{32}")))
        assertEquals("1.0.0", cookies["WEVNSM"])
        val headers = NeteaseQrLoginPolicy.webQrHeaders("chain")
        assertEquals("web", headers["x-os"])
        assertEquals("chain", headers["x-login-chain-id"])
    }

    @Test
    fun pollCookieHeaderIsParsedAndOverridesOnlyMatchingKeys() {
        val cookies = NeteaseQrLoginPolicy.parseCookieHeader("MUSIC_U=real; __csrf=csrf; broken; MUSIC_U=latest")

        assertEquals("latest", cookies["MUSIC_U"])
        assertEquals("csrf", cookies["__csrf"])
        assertEquals("MUSIC_U=latest; __csrf=csrf", NeteaseQrLoginPolicy.cookieHeader(cookies))
    }

    @Test
    fun qrContentUsesWebScanLoginFlow() {
        val content = NeteaseQrLoginPolicy.qrContent("key-value", "chain-value")

        assertTrue(content.startsWith("https://music.163.com/st/platform/scanlogin?"))
        assertTrue(content.contains("codekey=key-value"))
        assertTrue(content.contains("chainId=chain-value"))
        assertTrue(content.contains("hdw_device=web"))
    }
}
