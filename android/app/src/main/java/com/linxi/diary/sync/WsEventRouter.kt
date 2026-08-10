package com.linxi.diary.sync

import org.json.JSONObject

sealed interface WsAction {
    data class RefreshProfile(val changedUserId: Long) : WsAction
    data class Sensitive(val message: JSONObject) : WsAction
}

object WsEventRouter {
    private val sensitiveTypes = setOf(
        "partner_status",
        "comfort_request",
        "calm_request",
        "ring_request",
        "todo_new",
        "todo_completed",
        "diary_new",
        "low_battery",
        "wifi_joined",
        "todo_remind",
    )

    fun route(text: String): WsAction? = runCatching {
        route(JSONObject(text))
    }.getOrNull()

    fun route(message: JSONObject): WsAction? = when (message.optString("type")) {
        "profile_updated" -> {
            val userId = message.getJSONObject("data").getLong("user_id")
            WsAction.RefreshProfile(userId)
        }
        in sensitiveTypes -> WsAction.Sensitive(message)
        else -> null
    }
}
