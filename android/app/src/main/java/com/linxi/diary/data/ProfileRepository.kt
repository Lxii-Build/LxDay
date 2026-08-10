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
    private var refreshSequence = 0L

    fun loadCached(): CoupleProfile? {
        val cached = preferences.profileCacheJson
            ?.let { runCatching { CoupleProfile.fromCache(JSONObject(it)) }.getOrNull() }
        mutableProfile.value = cached
        return cached
    }

    suspend fun refresh(): CoupleProfile? {
        val refreshGeneration: Long
        val sequence: Long
        synchronized(this) {
            refreshGeneration = generation
            refreshSequence++
            sequence = refreshSequence
        }
        val response = source.get()
        val bound = requireNotNull(response.opt("bound") as? Boolean) {
            "pair status is missing boolean bound"
        }
        synchronized(this) {
            if (refreshGeneration != generation || sequence != refreshSequence) return null
            if (!bound) {
                clearLocked()
                return null
            }
            val authoritative = CoupleProfile.fromPairStatus(response)
            applyLocked(authoritative)
            return authoritative
        }
    }

    @Synchronized
    fun apply(authoritative: CoupleProfile) {
        applyLocked(authoritative)
    }

    private fun applyLocked(authoritative: CoupleProfile) {
        mutableProfile.value = authoritative
        preferences.profileCacheJson = authoritative.toCacheJson().toString()
        preferences.pairId = authoritative.pairId
        preferences.partnerName = authoritative.partner.nickname
    }

    @Synchronized
    fun clearProfileCache() {
        generation++
        refreshSequence++
        mutableProfile.value = null
        preferences.profileCacheJson = null
    }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    private fun clearLocked() {
        generation++
        refreshSequence++
        mutableProfile.value = null
        preferences.profileCacheJson = null
        preferences.pairId = 0
        preferences.partnerName = ""
    }
}
