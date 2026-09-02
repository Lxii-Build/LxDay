package com.linxi.diary.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.linxi.diary.util.Logs

/**
 * 全部通知渠道的唯一创建入口。
 *
 * 为什么要收敛：此前 4 处各自 `createNotificationChannel`（前台服务、StatusSyncManager 两处、
 * TodoAlarmReceiver），且属性不一致。而 `createNotificationChannel` 对**已存在**的渠道
 * 只能改名称/描述，**改不了 importance 与声音**——谁先创建谁决定行为，属性变成了竞态。
 * 统一在这里一次性建全，其它地方只 `ensure()` 后直接 notify。
 */
object NotificationChannels {

    /** 常驻状态卡：静默、不计角标。 */
    const val CHANNEL_CARD = "status_card"

    /** 互动提醒：求陪伴/求冷静/待办/相册，需要引起注意。 */
    const val CHANNEL_EVENT = "status_event"

    /** 紧急响铃：铃声由 RingHelper 的播放器控制，渠道本身不再叠加系统提示音。 */
    const val CHANNEL_RING = "status_ring"

    /**
     * 伴侣动态（静默）：息屏/亮屏、上线/下线。
     *
     * 管理员要求「给一个静默的通知，不弹出不响铃，但是会推送消息」，故：
     * IMPORTANCE_LOW（不弹 heads-up 横幅）+ setSound(null)（不响）+ 关振动。
     * 注意：低于 IMPORTANCE_DEFAULT 的渠道系统本就不出声，但显式置空可防止
     * 厂商 ROM 的默认行为差异，也避免以后有人误改 importance 时突然开始响。
     */
    const val CHANNEL_QUIET = "status_quiet"

    /** 常驻状态卡通知 id。 */
    const val NOTIFY_ID_CARD = 10001

    /** 伴侣动态静默通知 id：固定值，同类事件覆盖更新而非堆叠一屏。 */
    const val NOTIFY_ID_QUIET = 10003

    /** 待办提醒通知 id：按待办 id 派生，便于覆盖与精确撤销。 */
    fun todoNotifyId(todoId: Long): Int = (20_000 + (todoId % 10_000)).toInt()

    @Volatile
    private var created = false

    /**
     * 确保渠道已创建，返回 NotificationManager。
     * 幂等且线程安全；取不到服务时返回 null（调用方直接放弃发通知，不崩）。
     */
    fun ensure(context: Context): NotificationManager? {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return null
        if (created) return nm
        synchronized(this) {
            if (!created) {
                runCatching { createAll(nm) }
                    .onFailure { Logs.w("Notify", "Failed to create notification channels", it) }
                created = true
            }
        }
        return nm
    }

    private fun createAll(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CARD, "伴侣状态卡", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "常驻显示伴侣实时状态，静默更新"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENT, "互动提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "求陪伴/求冷静/待办/相册等互动提醒"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RING, "紧急响铃", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "强制响铃通知（铃声由播放器控制，渠道本身不再出声避免叠音）"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "伴侣动态（静默）", NotificationManager.IMPORTANCE_LOW).apply {
                description = "对方息屏/亮屏、上线/下线等动态。只在通知栏显示，不弹出、不响铃、不振动"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        )
    }
}
