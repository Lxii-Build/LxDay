package com.linxi.diary.data

import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoupleProfileTest {
    @Test
    fun `解析双方资料与纪念日并可缓存往返`() {
        val source = JSONObject(
            """{
                "bound": true,
                "pair_id": 7,
                "me": {
                    "id": 1,
                    "nickname": "林曦",
                    "avatar_url": "https://example.invalid/me.webp",
                    "avatar_thumbnail_url": "https://example.invalid/me.png"
                },
                "partner": {
                    "id": 2,
                    "nickname": "伴侣",
                    "avatar_url": null,
                    "avatar_thumbnail_url": null
                },
                "anniversary_date": "2024-02-29"
            }""".trimIndent()
        )

        val profile = CoupleProfile.fromPairStatus(source)
        assertEquals(7L, profile.pairId)
        assertEquals("林曦", profile.me.nickname)
        assertNull(profile.partner.avatarUrl)
        assertEquals(LocalDate.of(2024, 2, 29), profile.anniversaryDate)
        assertEquals(profile, CoupleProfile.fromCache(profile.toCacheJson()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未绑定响应不能构造情侣资料`() {
        CoupleProfile.fromPairStatus(JSONObject("""{"bound":false}"""))
    }
}
