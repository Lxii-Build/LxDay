package com.linxi.diary.status

import android.content.Context
import android.os.Build

/**
 * 常驻状态承载 Adapter 抽象。各 Adapter 只报告真实支持状态并尝试呈现，
 * 失败不抛出、不影响前台 Service，由 Controller 自动降级到标准通知。
 */
interface OngoingStatusAdapter {
    val id: AdapterId
    fun support(context: Context): SupportState
    fun show(context: Context, status: OngoingStatus): Boolean
    fun clear(context: Context)
}

/**
 * 标准横向 RemoteViews 常驻卡：始终兜底可用，复用 StatusForegroundService。
 */
class StandardNotificationAdapter : OngoingStatusAdapter {
    override val id = AdapterId.STANDARD
    override fun support(context: Context): SupportState = SupportState.Supported

    override fun show(context: Context, status: OngoingStatus): Boolean {
        com.linxi.diary.service.StatusForegroundService.refreshCard(context)
        return true
    }

    override fun clear(context: Context) {
        com.linxi.diary.service.StatusForegroundService.stop(context)
    }
}

/**
 * Android 16 Live Update 承载：仅系统版本满足且用于短时事件时可用。
 * 长期伴侣状态不请求 promoted，因此这里对常驻状态返回 Unsupported，交由标准通知兜底。
 */
class AndroidLiveUpdateAdapter : OngoingStatusAdapter {
    override val id = AdapterId.ANDROID_LIVE_UPDATE

    override fun support(context: Context): SupportState {
        // Live Update 面向用户发起的时间敏感进度；长期状态卡不符合语义，不冒用。
        return if (Build.VERSION.SDK_INT >= 36) SupportState.Unsupported else SupportState.Unsupported
    }

    override fun show(context: Context, status: OngoingStatus): Boolean = false
    override fun clear(context: Context) {}
}

/**
 * ColorOS 专项承载。无公开且普通应用可用的岛位 API，
 * 不使用反射、私有 extras、伪造包名或签名绕过——如实返回 Unsupported。
 */
class ColorOsStatusAdapter : OngoingStatusAdapter {
    override val id = AdapterId.COLOROS
    override fun support(context: Context): SupportState = SupportState.Unsupported
    override fun show(context: Context, status: OngoingStatus): Boolean = false
    override fun clear(context: Context) {}
}

/**
 * OriginOS 专项承载。同上，无公开普通应用能力，如实返回 Unsupported。
 */
class OriginOsStatusAdapter : OngoingStatusAdapter {
    override val id = AdapterId.ORIGINOS
    override fun support(context: Context): SupportState = SupportState.Unsupported
    override fun show(context: Context, status: OngoingStatus): Boolean = false
    override fun clear(context: Context) {}
}
