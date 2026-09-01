package com.linxi.diary.sync

import org.json.JSONObject

sealed interface WsAction {
    data class RefreshProfile(val changedUserId: Long) : WsAction
    data class Sensitive(val message: JSONObject) : WsAction

    /** 本机发出的动作被服务端拒绝（超频等）。不受共享开关门控，必须让用户看到。 */
    data class Rejected(val action: String, val reason: String) : WsAction
}

object WsEventRouter {
    private val sensitiveTypes = setOf(
        "partner_status",
        "comfort_request",
        "calm_request",
        "comfort_cancel",
        "calm_cancel",
        "ring_request",
        "ring_cancel",
        "ring_stopped",
        "todo_new",
        "todo_completed",
                "low_battery",
        "wifi_joined",
        "todo_remind",
    )

    /**
     * 服务端对本机上行动作的拒绝回执（如响铃超频）。
     *
     * 与 sensitiveTypes 分开：它不受共享总开关门控——用户关掉状态共享后，
     * 自己发出的动作被拒绝仍然应该被告知，否则又变成静默失败。
     */
    private const val TYPE_ACTION_REJECTED = "action_rejected"

    fun route(text: String): WsAction? = runCatching {
        route(JSONObject(text))
    }.getOrNull()

    fun route(message: JSONObject): WsAction? = when (message.optString("type")) {
        "profile_updated" -> {
            val userId = message.getJSONObject("data").getLong("user_id")
            WsAction.RefreshProfile(userId)
        }
        TYPE_ACTION_REJECTED -> {
            val data = message.optJSONObject("data")
            WsAction.Rejected(
                action = data?.optString("action").orEmpty(),
                reason = data?.optString("reason")?.takeIf { it.isNotEmpty() } ?: "操作被拒绝",
            )
        }
        in sensitiveTypes -> WsAction.Sensitive(message)
        else -> null
    }
}
