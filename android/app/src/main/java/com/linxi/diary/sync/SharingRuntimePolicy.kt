package com.linxi.diary.sync

import com.linxi.diary.util.UserPrefs

object SharingRuntimePolicy {
    fun canRun(
        pairId: Long,
        privacyConsented: Boolean,
        sharingEnabled: Boolean,
        demoMode: Boolean,
    ): Boolean = pairId > 0 && privacyConsented && sharingEnabled && !demoMode

    fun canRunNow(): Boolean = canRun(
        pairId = UserPrefs.pairId,
        privacyConsented = UserPrefs.privacyConsented,
        sharingEnabled = UserPrefs.sharingEnabled,
        demoMode = UserPrefs.demoMode,
    )

    fun enableSharingAfterConsent(demoMode: Boolean): Boolean = !demoMode
}
