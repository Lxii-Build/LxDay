package com.linxi.diary.status

import android.content.Context
import android.os.Build

/**
 * 常驻状态承载 Adapter 抽象。各 Adapter 只报告真实支持状态并尝试呈现，
 * 失败不抛出、不影响前台 Service，由 Controller 自动降级到标准通知。
 * Context 由 Adapter 构造时持有，Controller 保持纯决策、无 Android 依赖。
 */
interface OngoingStatusAdapter {
    val id: AdapterId
    fun support(): SupportState
    fun show(status: OngoingStatus): Boolean
    fun clear()
}

/**
 * 标准横向 RemoteViews 常驻卡：始终兜底可用，复用 StatusForegroundService。
 */
class StandardNotificationAdapter(private val appContext: Context) : OngoingStatusAdapter {
    override val id = AdapterId.STANDARD
    override fun support(): SupportState = SupportState.Supported

    override fun show(status: OngoingStatus): Boolean {
        com.linxi.diary.service.StatusForegroundService.refreshCard(appContext)
        return true
    }

    override fun clear() {
        com.linxi.diary.service.StatusForegroundService.stop(appContext)
    }
}

/**
 * Android 16 Live Update 承载：仅系统版本满足且用于短时事件时可用。
 * 长期伴侣状态不请求 promoted，因此这里对常驻状态返回 Unsupported，交由标准通知兜底。
 */
class AndroidLiveUpdateAdapter : OngoingStatusAdapter {
    override val id = AdapterId.ANDROID_LIVE_UPDATE

    override fun support(): SupportState {
        // Live Update 面向用户发起的时间敏感进度；长期状态卡不符合语义，不冒用。
        @Suppress("UNUSED_EXPRESSION")
        Build.VERSION.SDK_INT
        return SupportState.Unsupported
    }

    override fun show(status: OngoingStatus): Boolean = false
    override fun clear() {}
}

/**
 * ColorOS 专项承载。无公开且普通应用可用的岛位 API，
 * 不使用反射、私有 extras、伪造包名或签名绕过——如实返回 Unsupported。
 */
class ColorOsStatusAdapter : OngoingStatusAdapter {
    override val id = AdapterId.COLOROS
    override fun support(): SupportState = SupportState.Unsupported
    override fun show(status: OngoingStatus): Boolean = false
    override fun clear() {}
}

/**
 * OriginOS 专项承载。同上，无公开普通应用能力，如实返回 Unsupported。
 */
class OriginOsStatusAdapter : OngoingStatusAdapter {
    override val id = AdapterId.ORIGINOS
    override fun support(): SupportState = SupportState.Unsupported
    override fun show(status: OngoingStatus): Boolean = false
    override fun clear() {}
}
