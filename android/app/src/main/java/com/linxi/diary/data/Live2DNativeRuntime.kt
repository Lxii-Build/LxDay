package com.linxi.diary.data

import android.util.Log

/**
 * Describes the optional official Cubism Java runtime without taking a hard
 * compile-time dependency on its proprietary Core AAR.
 *
 * Live2DCubismCore.aar is intentionally supplied by the project owner under
 * Live2D's license.  The normal build must remain usable when that artifact is
 * absent; reflection lets the app show a truthful, actionable state instead
 * of crashing at class-load time or pretending a PNG is a Live2D frame.
 */
data class Live2DNativeRuntimeInfo(
    val coreClassPresent: Boolean,
    val frameworkClassPresent: Boolean,
    val rendererClassPresent: Boolean,
    val coreInitializes: Boolean,
    val status: Status,
    val detail: String,
) {
    enum class Status {
        MissingCore,
        MissingFramework,
        CoreUnavailable,
        Ready,
    }

    val canCreateRenderer: Boolean get() = status == Status.Ready
}

object Live2DNativeRuntime {
    private const val TAG = "Live2D"
    private const val CORE = "com.live2d.sdk.cubism.core.Live2DCubismCore"
    private const val FRAMEWORK = "com.live2d.sdk.cubism.framework.CubismFramework"
    private const val RENDERER = "com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid"

    /** Probe only class presence first; loading an absent native library is not fatal. */
    fun probe(classLoader: ClassLoader? = Thread.currentThread().contextClassLoader): Live2DNativeRuntimeInfo {
        val loader = classLoader ?: requireNotNull(Live2DNativeRuntime::class.java.classLoader)
        val corePresent = classExists(CORE, loader)
        val frameworkPresent = classExists(FRAMEWORK, loader)
        val rendererPresent = classExists(RENDERER, loader)
        if (!corePresent) {
            return Live2DNativeRuntimeInfo(
                coreClassPresent = false,
                frameworkClassPresent = frameworkPresent,
                rendererClassPresent = rendererPresent,
                coreInitializes = false,
                status = Live2DNativeRuntimeInfo.Status.MissingCore,
                detail = "未安装 Live2D Cubism Core AAR",
            )
        }
        if (!frameworkPresent || !rendererPresent) {
            return Live2DNativeRuntimeInfo(
                coreClassPresent = true,
                frameworkClassPresent = frameworkPresent,
                rendererClassPresent = rendererPresent,
                coreInitializes = false,
                status = Live2DNativeRuntimeInfo.Status.MissingFramework,
                detail = "Cubism Framework/Android renderer 未接入",
            )
        }

        // initialize=true deliberately verifies that the JNI library can be
        // loaded on this ABI.  It catches an AAR copied to the wrong build
        // variant or a missing native .so before the first GL frame.
        val initializes = runCatching {
            Class.forName(CORE, true, loader)
            true
        }.getOrElse { throwable ->
            Log.w(TAG, "Cubism Core initialization failed", throwable)
            false
        }
        return Live2DNativeRuntimeInfo(
            coreClassPresent = true,
            frameworkClassPresent = true,
            rendererClassPresent = true,
            coreInitializes = initializes,
            status = if (initializes) {
                Live2DNativeRuntimeInfo.Status.Ready
            } else {
                Live2DNativeRuntimeInfo.Status.CoreUnavailable
            },
            detail = if (initializes) "Cubism Core 与 Android renderer 已加载" else "Cubism Core native 库无法加载",
        )
    }

    private fun classExists(name: String, loader: ClassLoader): Boolean =
        runCatching { Class.forName(name, false, loader) }.isSuccess
}
