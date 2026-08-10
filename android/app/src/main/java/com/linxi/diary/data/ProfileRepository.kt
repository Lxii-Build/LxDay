package com.linxi.diary.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

fun interface PairStatusSource {
    suspend fun get(): JSONObject
}

interface ProfilePreferences {
    var profileCacheJson: String?
    var pairId: Long
    var partnerName: String
}

class ProfileRepository(
    private val source: PairStatusSource,
    private val preferences: ProfilePreferences,
) {
    private val mutableProfile = MutableStateFlow<CoupleProfile?>(null)
    val profile: StateFlow<CoupleProfile?> = mutableProfile

    fun loadCached(): CoupleProfile? {
        val cached = preferences.profileCacheJson
            ?.let { runCatching { CoupleProfile.fromCache(JSONObject(it)) }.getOrNull() }
        mutableProfile.value = cached
        return cached
    }

    suspend fun refresh(): CoupleProfile? {
        val response = source.get()
        if (!response.optBoolean("bound")) {
            clear()
            return null
        }
        val authoritative = CoupleProfile.fromPairStatus(response)
        apply(authoritative)
        return authoritative
    }

    fun apply(authoritative: CoupleProfile) {
        mutableProfile.value = authoritative
        preferences.profileCacheJson = authoritative.toCacheJson().toString()
        preferences.pairId = authoritative.pairId
        preferences.partnerName = authoritative.partner.nickname
    }

    fun clear() {
        mutableProfile.value = null
        preferences.profileCacheJson = null
        preferences.pairId = 0
        preferences.partnerName = ""
    }
}
