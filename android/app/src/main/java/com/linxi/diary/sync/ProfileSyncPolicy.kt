package com.linxi.diary.sync

import com.linxi.diary.util.UserPrefs

object ProfileSyncPolicy {
    fun canConnect(token: String?, pairId: Long, demoMode: Boolean): Boolean =
        !token.isNullOrBlank() && pairId > 0 && !demoMode

    fun canConnectNow(): Boolean = canConnect(
        token = UserPrefs.token,
        pairId = UserPrefs.pairId,
        demoMode = UserPrefs.demoMode,
    )
}
