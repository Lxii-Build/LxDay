package com.linxi.diary.data

import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

object ProfileRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableActions = MutableSharedFlow<ProfileRefreshAction>(extraBufferCapacity = 1)
    val actions = mutableActions.asSharedFlow()

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

    fun connectAndRefreshIfEligible() {
        if (!com.linxi.diary.sync.ProfileSyncPolicy.canConnectNow()) return
        com.linxi.diary.sync.StatusSyncManager.connect()
        refreshAsync()
    }

    fun refreshAsync() {
        if (!com.linxi.diary.sync.ProfileSyncPolicy.canConnectNow()) return
        scope.launch {
            runCatching { ApiClient.clientConfig() }
                .onSuccess { ClientRuntimeConfig.apply(it) }
                .onFailure { Logs.w("Config", "客户端运行配置刷新失败，继续使用上次快照", it) }
            runCatching { repository.refresh() }
                .onSuccess { result ->
                    ProfileRefreshAction.fromResult(result)?.let { action ->
                        if (action.disconnectSession) {
                            com.linxi.diary.sync.StatusSyncManager.disconnect()
                        }
                        mutableActions.emit(action)
                    }
                }
                .onFailure { Logs.w("Profile", "刷新情侣资料失败", it) }
        }
    }

    fun clearProfileCache() {
        repository.clearProfileCache()
    }

    fun clearSession() {
        repository.clear()
    }

    /** 将资料变更接口返回的权威响应写入 Repository。仅在非 Demo 且已绑定时生效。 */
    fun applyAuthoritative(response: JSONObject) {
        if (!com.linxi.diary.sync.ProfileSyncPolicy.canConnectNow()) return
        runCatching { CoupleProfile.fromPairStatus(response) }
            .onSuccess { repository.apply(it) }
            .onFailure { Logs.w("Profile", "应用权威资料失败", it) }
    }
}
