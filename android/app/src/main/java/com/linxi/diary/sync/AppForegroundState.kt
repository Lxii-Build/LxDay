package com.linxi.diary.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App 前后台可见性。
 *
 * 此前全仓**没有任何前后台感知**（`ProcessLifecycleOwner` 零命中），
 * `LinxiApp.kt` 里那个 `while(true)` 轮询循环随 Composition 存活，
 * 退到后台仍以前台频率继续跑，只是 UI 看不见 —— 纯耗电。
 * 有了这个状态，[SyncIntervalPolicy] 的分档才有依据。
 *
 * 由 `App.onCreate` 通过 ProcessLifecycleOwner 驱动，避免各 Activity 各写一套。
 */
object AppForegroundState {

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground

    val isForeground: Boolean get() = _foreground.value

    fun onEnterForeground() {
        _foreground.value = true
    }

    fun onEnterBackground() {
        _foreground.value = false
    }
}
