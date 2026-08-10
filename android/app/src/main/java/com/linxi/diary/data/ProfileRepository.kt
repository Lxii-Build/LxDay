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
    private var generation = 0L

    fun loadCached(): CoupleProfile? {
        val cached = preferences.profileCacheJson
            ?.let { runCatching { CoupleProfile.fromCache(JSONObject(it)) }.getOrNull() }
        mutableProfile.value = cached
        return cached
    }

    suspend fun refresh(): CoupleProfile? {
        val refreshGeneration = generation
        val response = source.get()
        val bound = requireNotNull(response.opt("bound") as? Boolean) {
            "pair status is missing boolean bound"
        }
        if (refreshGeneration != generation) return null
        if (!bound) {
            clear()
            return null
        }
        val authoritative = CoupleProfile.fromPairStatus(response)
        if (refreshGeneration != generation) return null
        apply(authoritative)
        return authoritative
    }

    fun apply(authoritative: CoupleProfile) {
        mutableProfile.value = authoritative
        preferences.profileCacheJson = authoritative.toCacheJson().toString()
        preferences.pairId = authoritative.pairId
        preferences.partnerName = authoritative.partner.nickname
    }

    fun clearProfileCache() {
        generation++
        mutableProfile.value = null
        preferences.profileCacheJson = null
    }

    fun clear() {
        clearProfileCache()
        preferences.pairId = 0
        preferences.partnerName = ""
    }
}
