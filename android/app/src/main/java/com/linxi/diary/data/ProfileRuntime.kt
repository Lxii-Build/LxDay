package com.linxi.diary.data

import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ProfileRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository by lazy {
        ProfileRepository(
            source = PairStatusSource { ApiClient.pairStatus() },
            preferences = UserPrefs.profilePreferences,
        )
    }

    fun init() {
        if (UserPrefs.demoMode) {
            repository.clearProfileCache()
        } else {
            repository.loadCached()
        }
    }

    fun refreshAsync() {
        if (UserPrefs.demoMode) return
        scope.launch {
            runCatching { repository.refresh() }
                .onFailure { Logs.w("Profile", "刷新情侣资料失败", it) }
        }
    }

    fun clearProfileCache() {
        repository.clearProfileCache()
    }

    fun clearSession() {
        repository.clear()
    }
}
