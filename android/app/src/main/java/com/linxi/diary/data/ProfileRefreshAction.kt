package com.linxi.diary.data

sealed interface ProfileRefreshAction {
    val disconnectSession: Boolean
        get() = false
    val navigateToBind: Boolean
        get() = false

    data class Updated(val profile: CoupleProfile) : ProfileRefreshAction

    data object Unbound : ProfileRefreshAction {
        override val disconnectSession: Boolean = true
        override val navigateToBind: Boolean = true
    }

    companion object {
        fun fromResult(result: ProfileRefreshResult): ProfileRefreshAction? = when (result) {
            is ProfileRefreshResult.Updated -> Updated(result.profile)
            ProfileRefreshResult.Unbound -> Unbound
            ProfileRefreshResult.Superseded -> null
        }
    }
}
