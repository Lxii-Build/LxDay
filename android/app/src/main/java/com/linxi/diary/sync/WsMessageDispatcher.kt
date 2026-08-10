package com.linxi.diary.sync

import org.json.JSONObject

class WsMessageDispatcher(
    private val refreshProfile: () -> Unit,
    private val handleSensitive: (JSONObject) -> Unit,
) {
    fun dispatch(text: String, sensitiveEventsAllowed: Boolean): Boolean =
        when (val action = WsEventRouter.route(text)) {
            is WsAction.RefreshProfile -> {
                refreshProfile()
                true
            }
            is WsAction.Sensitive -> {
                if (!sensitiveEventsAllowed) {
                    false
                } else {
                    handleSensitive(action.message)
                    true
                }
            }
            null -> false
        }
}
