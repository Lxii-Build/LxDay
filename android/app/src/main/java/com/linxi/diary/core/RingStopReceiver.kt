package com.linxi.diary.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.Logs

/**
 * 通知栏「停止响铃」按钮的落点。
 *
 * 为什么需要它：此前唯一的停止入口是全屏 RingActivity，而该页面依赖
 * POST_NOTIFICATIONS + 全屏 Intent 才能拉起；权限未授予或被厂商限制时
 * 铃在响但没有任何可点的 UI，用户只能划掉后台杀进程。
 * 通知 Action 走 BroadcastReceiver 不依赖 Activity 能否启动，是更可靠的兜底。
 *
 * 停止后回执 ring_stopped 给发送方，让发送方 UI 显示「对方已知悉」，
 * 避免因为不知道对方关没关而反复响铃。
 */
class RingStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_RING) return
        val ringId = intent.getStringExtra(EXTRA_RING_ID)
        val stopped = RingHelper.stopRing(context.applicationContext, ringId, reason = "notification")
        if (stopped) {
            // 让发送方知道接收方已主动关闭（发送方据此结束"响铃中"倒计时）。
            StatusSyncManager.sendRingStopped(ringId)
        }
        Logs.i("Ring", "Stop action received (stopped=$stopped)")
    }

    companion object {
        const val ACTION_STOP_RING = "com.linxi.diary.action.STOP_RING"
        private const val EXTRA_RING_ID = "ring_id"

        fun intent(c: Context, ringId: String?): Intent =
            Intent(c, RingStopReceiver::class.java).apply {
                action = ACTION_STOP_RING
                putExtra(EXTRA_RING_ID, ringId)
            }
    }
}
