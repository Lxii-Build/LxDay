package com.linxi.diary.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ProfileRepositoryTest {
    @Test
    fun `权威资料响应覆盖内存缓存与派生偏好`() = runBlocking {
        val preferences = FakeProfilePreferences()
        val repository = ProfileRepository(
            PairStatusSource {
                JSONObject(
                    """{
                        "bound": true,
                        "pair_id": 7,
                        "me": {
                            "id": 1,
                            "nickname": "林曦",
                            "avatar_url": null,
                            "avatar_thumbnail_url": null
                        },
                        "partner": {
                            "id": 2,
                            "nickname": "伴侣",
                            "avatar_url": "https://example.invalid/partner.webp",
                            "avatar_thumbnail_url": "https://example.invalid/partner.png"
                        },
                        "anniversary_date": "2024-02-29"
                    }""".trimIndent(),
                )
            },
            preferences,
        )

        val profile = repository.refresh()

        assertEquals(7L, profile?.pairId)
        assertEquals(profile, repository.profile.value)
        assertEquals(7L, preferences.pairId)
        assertEquals("伴侣", preferences.partnerName)
        assertTrue(preferences.profileCacheJson.orEmpty().isNotBlank())
        assertEquals(profile, CoupleProfile.fromCache(JSONObject(preferences.profileCacheJson!!)))
    }

    @Test
    fun `刷新失败时保留已加载的资料缓存`() = runBlocking {
        val cached = CoupleProfile(
            pairId = 7,
            me = ProfileUser(1, "林曦", null, null),
            partner = ProfileUser(2, "伴侣", null, null),
            anniversaryDate = null,
        )
        val preferences = FakeProfilePreferences(
            profileCacheJson = cached.toCacheJson().toString(),
            pairId = 7,
            partnerName = "伴侣",
        )
        val repository = ProfileRepository(
            PairStatusSource { throw IOException("offline") },
            preferences,
        )
        repository.loadCached()

        try {
            repository.refresh()
            fail("刷新失败必须向调用方报告")
        } catch (_: IOException) {
            // 预期：运行时层记录失败，Repository 不丢弃上一次成功资料。
        }

        assertEquals(cached, repository.profile.value)
        assertEquals(cached.toCacheJson().toString(), preferences.profileCacheJson)
        assertEquals(7L, preferences.pairId)
        assertEquals("伴侣", preferences.partnerName)
    }

    @Test
    fun `未绑定的权威响应清除资料缓存和派生偏好`() = runBlocking {
        val preferences = FakeProfilePreferences(
            profileCacheJson = CoupleProfile(
                pairId = 7,
                me = ProfileUser(1, "林曦", null, null),
                partner = ProfileUser(2, "伴侣", null, null),
                anniversaryDate = null,
            ).toCacheJson().toString(),
            pairId = 7,
            partnerName = "伴侣",
        )
        val repository = ProfileRepository(
            PairStatusSource { JSONObject("""{"bound":false}""") },
            preferences,
        )
        repository.loadCached()

        assertNull(repository.refresh())

        assertNull(repository.profile.value)
        assertNull(preferences.profileCacheJson)
        assertEquals(0L, preferences.pairId)
        assertEquals("", preferences.partnerName)
    }
}

private class FakeProfilePreferences(
    override var profileCacheJson: String? = null,
    override var pairId: Long = 0,
    override var partnerName: String = "",
) : ProfilePreferences
