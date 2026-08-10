package com.linxi.diary.sync

class WsMessageDispatcher(
    private val refreshProfile: () -> Unit,
    private val handleSensitive: (String) -> Unit,
) {
    fun dispatch(text: String, sensitiveEventsAllowed: Boolean): Boolean {
        if (WsEventRouter.route(text) is WsAction.RefreshProfile) {
            refreshProfile()
            return true
        }
        if (!sensitiveEventsAllowed) return false
        handleSensitive(text)
        return true
    }
}
