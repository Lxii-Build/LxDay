package com.linxi.diary.status

import android.content.Context
import android.os.Build
import com.linxi.diary.util.Logs

/**
 * 常驻状态统一控制器：识别厂商 → 选择可用 Adapter → 内容去重后呈现。
 * 专项 Adapter 失败自动降级到标准通知，绝不把普通通知伪装为系统流体云/灵动岛。
 */
class OngoingStatusController(
    private val adapters: List<OngoingStatusAdapter> = listOf(
        ColorOsStatusAdapter(),
        OriginOsStatusAdapter(),
        AndroidLiveUpdateAdapter(),
        StandardNotificationAdapter(),
    ),
    private val vendor: Vendor = Vendor.fromManufacturer(Build.MANUFACTURER),
) {
    private var lastHash: Int? = null
    private var activeAdapterId: AdapterId? = null

    private fun byId(id: AdapterId): OngoingStatusAdapter =
        adapters.first { it.id == id }

    /** 呈现最新状态；内容未变化时跳过刷新。返回实际承载渠道。 */
    fun present(context: Context, status: OngoingStatus, privacy: LockscreenPrivacy): AdapterId {
        val filtered = privacy.filter(status)
        if (lastHash != null && !OngoingStatusContent.shouldRefresh(lastHash!!, filtered)) {
            return activeAdapterId ?: AdapterId.STANDARD
        }
        val order = OngoingStatusPolicy.adapterOrder(vendor)
        val support = order.associateWith { runCatching { byId(it).support(context) }.getOrDefault(SupportState.Unsupported) }
        val chosen = OngoingStatusPolicy.choose(order, support)
        Logs.i("Ongoing", "vendor=$vendor chosen=$chosen support=$support")

        // 切换承载渠道时清理旧渠道。
        activeAdapterId?.takeIf { it != chosen }?.let { runCatching { byId(it).clear(context) } }

        val shown = runCatching { byId(chosen).show(context, filtered) }.getOrDefault(false)
        if (!shown && chosen != AdapterId.STANDARD) {
            // 专项失败兜底标准通知，不停止前台 Service。
            Logs.w("Ongoing", "$chosen 呈现失败，降级标准通知")
            runCatching { byId(AdapterId.STANDARD).show(context, filtered) }
            activeAdapterId = AdapterId.STANDARD
        } else {
            activeAdapterId = chosen
        }
        lastHash = OngoingStatusContent.hash(filtered)
        return activeAdapterId ?: AdapterId.STANDARD
    }

    fun clear(context: Context) {
        activeAdapterId?.let { runCatching { byId(it).clear(context) } }
        activeAdapterId = null
        lastHash = null
    }
}
