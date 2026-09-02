package com.linxi.diary.sync

import org.json.JSONObject

class WsMessageDispatcher(
    private val refreshProfile: () -> Unit,
    private val handleSensitive: (JSONObject) -> Unit,
    private val handleRejected: (String, String) -> Unit = { _, _ -> },
) {
    fun dispatch(text: String, sensitiveEventsAllowed: Boolean): Boolean =
        when (val action = WsEventRouter.route(text)) {
            is WsAction.RefreshProfile -> {
                refreshProfile()
                true
            }
            is WsAction.Unbound -> {
                // 与 profile_updated 一样走服务端权威状态；不能在消息里
                // 直接相信 pair_id，更不能受状态共享开关门控。
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
            // 拒绝回执不受 sensitiveEventsAllowed 门控：这是"你自己刚发的动作被拒了"，
            // 即便用户关掉了状态共享也必须让他知道，否则又回到静默失败。
            is WsAction.Rejected -> {
                handleRejected(action.action, action.reason)
                true
            }
            null -> false
        }
}
