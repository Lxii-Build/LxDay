package com.linxi.diary.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

fun interface PairStatusSource {
    suspend fun get(): JSONObject
}

interface ProfilePreferences {
    val profileCacheJson: String?
    val pairId: Long
    val partnerName: String

    /** 资料缓存与派生偏好一次性原子落盘，避免进程被杀导致字段间不一致。 */
    fun commit(profileCacheJson: String?, pairId: Long, partnerName: String)
}

sealed interface ProfileRefreshResult {
    data class Updated(val profile: CoupleProfile) : ProfileRefreshResult
    data object Unbound : ProfileRefreshResult
    data object Superseded : ProfileRefreshResult
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

    suspend fun refresh(): ProfileRefreshResult {
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
            if (refreshGeneration != generation || sequence != refreshSequence) {
                return ProfileRefreshResult.Superseded
            }
            if (!bound) {
                clearLocked()
                return ProfileRefreshResult.Unbound
            }
            val authoritative = CoupleProfile.fromPairStatus(response)
            applyLocked(authoritative)
            return ProfileRefreshResult.Updated(authoritative)
        }
    }

    @Synchronized
    fun apply(authoritative: CoupleProfile) {
        applyLocked(authoritative)
    }

    private fun applyLocked(authoritative: CoupleProfile) {
        mutableProfile.value = authoritative
        preferences.commit(
            profileCacheJson = authoritative.toCacheJson().toString(),
            pairId = authoritative.pairId,
            partnerName = authoritative.partner.nickname,
        )
    }

    @Synchronized
    fun clearProfileCache() {
        generation++
        refreshSequence++
        mutableProfile.value = null
        preferences.commit(
            profileCacheJson = null,
            pairId = preferences.pairId,
            partnerName = preferences.partnerName,
        )
    }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    private fun clearLocked() {
        generation++
        refreshSequence++
        mutableProfile.value = null
        preferences.commit(profileCacheJson = null, pairId = 0, partnerName = "")
    }
}
