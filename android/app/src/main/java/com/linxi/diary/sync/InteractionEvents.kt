package com.linxi.diary.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 互动请求（求陪伴/求冷静/响铃找人）的发送方侧状态。
 *
 * 为什么需要它：此前发送方点完按钮只有一个本地 7 秒禁用态，既不知道对方收到没有、
 * 也无法撤回，更收不到"对方已关闭"的回执，于是只能反复点。
 * 这里把发送态收敛成可观测的 StateFlow，供 NowScreen 显示倒计时、【撤回】按钮与回执。
 */
object InteractionEvents {

    /** 一次进行中的互动请求。 */
    data class Pending(
        val type: String,
        val ringId: String,
        val startedAtMs: Long,
        /** 对方是否已确认关闭（收到 ring_stopped 回执）。 */
        val acknowledged: Boolean = false,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    /** 最近一次被服务端拒绝的动作，供 UI 弹出可读原因后自行清除。 */
    private val _rejection = MutableStateFlow<String?>(null)
    val rejection: StateFlow<String?> = _rejection

    fun begin(type: String, ringId: String) {
        _pending.value = Pending(type = type, ringId = ringId, startedAtMs = System.currentTimeMillis())
    }

    /** 收到对方"我已关闭"的回执。ringId 不匹配时忽略（可能是上一次的迟到回执）。 */
    fun onRingStopped(ringId: String?) {
        val cur = _pending.value ?: return
        if (ringId != null && ringId != cur.ringId) return
        _pending.value = cur.copy(acknowledged = true)
    }

    /** 本次请求结束（倒计时到点、撤回成功、或页面主动清理）。 */
    fun clear(ringId: String? = null) {
        val cur = _pending.value ?: return
        if (ringId != null && ringId != cur.ringId) return
        _pending.value = null
    }

    /**
     * 服务端拒绝了本机动作（超频等）：立即结束"进行中"状态并暴露原因。
     * 不做 ringId 匹配——拒绝回执针对的就是刚发出的那一个。
     */
    fun onRejected(action: String, reason: String) {
        _pending.value = null
        _rejection.value = reason
    }

    /** UI 消费完拒绝提示后调用。 */
    fun clearRejection() {
        _rejection.value = null
    }
}
