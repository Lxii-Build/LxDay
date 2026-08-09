package com.linxi.diary.sync

import org.json.JSONObject

sealed interface WsAction {
    data class RefreshProfile(val changedUserId: Long) : WsAction
}

object WsEventRouter {
    fun route(text: String): WsAction? = runCatching {
        val message = JSONObject(text)
        when (message.optString("type")) {
            "profile_updated" -> {
                val userId = message.getJSONObject("data").getLong("user_id")
                WsAction.RefreshProfile(userId)
            }
            else -> null
        }
    }.getOrNull()
}
