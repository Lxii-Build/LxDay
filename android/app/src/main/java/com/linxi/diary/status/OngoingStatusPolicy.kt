package com.linxi.diary.status

/** 常驻状态承载数据，不含位置。锁屏隐私级过滤后传给各 Adapter。 */
data class OngoingStatus(
    val partnerName: String,
    val foregroundApp: String?,
    val screenOn: Boolean,
    val batteryLevel: Int?,
    val charging: Boolean,
    val network: String?,
    val syncLabel: String,
    val updateTimeMillis: Long,
)

/** 常驻状态承载渠道。 */
enum class AdapterId { COLOROS, ORIGINOS, ANDROID_LIVE_UPDATE, STANDARD }

/** 厂商识别。 */
enum class Vendor {
    COLOROS, ORIGINOS, OTHER;

    companion object {
        fun fromManufacturer(manufacturer: String?): Vendor {
            val m = manufacturer?.lowercase().orEmpty()
            return when {
                m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> COLOROS
                m.contains("vivo") -> ORIGINOS
                else -> OTHER
            }
        }
    }
}

/** Adapter 支持状态，如实反映真实能力。 */
enum class SupportState {
    Supported,               // 公开且普通应用可用
    Unsupported,             // 无公开能力
    RequiresUserSetting,     // 需用户在系统设置开启
    RequiresVendorApproval,  // 需厂商资格/白名单
    TemporarilyUnavailable,  // 临时不可用
}

/** 锁屏隐私级别。 */
enum class LockscreenPrivacy {
    FULL, BRIEF, HIDDEN;

    /** 按隐私级过滤敏感内容：不显示 SSID、锁屏默认不显示具体前台 App。 */
    fun filter(status: OngoingStatus): OngoingStatus = when (this) {
        FULL -> status
        BRIEF -> status.copy(foregroundApp = null, network = null)
        HIDDEN -> status.copy(foregroundApp = null, network = null, batteryLevel = null)
    }
}

/** Adapter 选择与厂商顺序的纯策略。 */
object OngoingStatusPolicy {

    /** 承载优先级：厂商专项 → Android16 Live Update → 标准通知兜底。 */
    fun adapterOrder(vendor: Vendor): List<AdapterId> = when (vendor) {
        Vendor.COLOROS -> listOf(AdapterId.COLOROS, AdapterId.ANDROID_LIVE_UPDATE, AdapterId.STANDARD)
        Vendor.ORIGINOS -> listOf(AdapterId.ORIGINOS, AdapterId.ANDROID_LIVE_UPDATE, AdapterId.STANDARD)
        Vendor.OTHER -> listOf(AdapterId.ANDROID_LIVE_UPDATE, AdapterId.STANDARD)
    }

    /** 按顺序选第一个 Supported 的 Adapter。 */
    fun choose(order: List<AdapterId>, support: Map<AdapterId, SupportState>): AdapterId =
        order.firstOrNull { support[it] == SupportState.Supported } ?: AdapterId.STANDARD

    /** 标准横向 RemoteViews 常驻卡始终兜底可用。 */
    fun standardSupport(): SupportState = SupportState.Supported
}

/** 内容哈希与刷新去重：仅时间变化不刷新。 */
object OngoingStatusContent {
    fun hash(status: OngoingStatus): Int {
        // 排除 updateTimeMillis，只对真实状态字段取哈希。
        var result = status.partnerName.hashCode()
        result = 31 * result + (status.foregroundApp?.hashCode() ?: 0)
        result = 31 * result + status.screenOn.hashCode()
        result = 31 * result + (status.batteryLevel ?: -1)
        result = 31 * result + status.charging.hashCode()
        result = 31 * result + (status.network?.hashCode() ?: 0)
        result = 31 * result + status.syncLabel.hashCode()
        return result
    }

    fun shouldRefresh(lastHash: Int, next: OngoingStatus): Boolean = hash(next) != lastHash
}
