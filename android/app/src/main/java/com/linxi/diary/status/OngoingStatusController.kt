package com.linxi.diary.status

/**
 * 常驻状态统一控制器：识别厂商 → 选择可用 Adapter → 内容去重后呈现。
 * 专项 Adapter 失败自动降级到标准通知，绝不把普通通知伪装为系统流体云/灵动岛。
 * 纯决策，无 Android 依赖：Adapter 各自持有 Context，日志通过 log 注入以便 JVM 单测。
 */
class OngoingStatusController(
    private val adapters: List<OngoingStatusAdapter>,
    private val vendor: Vendor,
    private val log: (String) -> Unit = {},
) {
    private var lastHash: Int? = null
    private var activeAdapterId: AdapterId? = null

    private fun byId(id: AdapterId): OngoingStatusAdapter = adapters.first { it.id == id }

    /** 呈现最新状态；内容未变化时跳过刷新。返回实际承载渠道。 */
    fun present(status: OngoingStatus, privacy: LockscreenPrivacy): AdapterId {
        val filtered = privacy.filter(status)
        if (lastHash != null && !OngoingStatusContent.shouldRefresh(lastHash!!, filtered)) {
            return activeAdapterId ?: AdapterId.STANDARD
        }
        val order = OngoingStatusPolicy.adapterOrder(vendor).filter { id -> adapters.any { it.id == id } }
        val support = order.associateWith {
            runCatching { byId(it).support() }.getOrDefault(SupportState.Unsupported)
        }
        val chosen = OngoingStatusPolicy.choose(order, support)
        log("vendor=$vendor chosen=$chosen support=$support")

        // 切换承载渠道时清理旧渠道。
        activeAdapterId?.takeIf { it != chosen }?.let { runCatching { byId(it).clear() } }

        val shown = runCatching { byId(chosen).show(filtered) }.getOrDefault(false)
        if (!shown && chosen != AdapterId.STANDARD) {
            // 专项失败兜底标准通知，不停止前台 Service。
            log("$chosen 呈现失败，降级标准通知")
            runCatching { byId(AdapterId.STANDARD).show(filtered) }
            activeAdapterId = AdapterId.STANDARD
        } else {
            activeAdapterId = chosen
        }
        lastHash = OngoingStatusContent.hash(filtered)
        return activeAdapterId ?: AdapterId.STANDARD
    }

    fun clear() {
        activeAdapterId?.let { runCatching { byId(it).clear() } }
        activeAdapterId = null
        lastHash = null
    }
}
