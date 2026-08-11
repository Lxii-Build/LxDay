package com.linxi.diary.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
class MainFabState {
    var todoAction: (() -> Unit)? by mutableStateOf(null)

    /** FAB 是否可见（待办列表随滚动渐隐时由页面更新）。 */
    var fabVisible: Boolean by mutableStateOf(true)

    fun actionFor(destination: MainFabDestination): (() -> Unit)? = when (destination) {
        MainFabDestination.Todo -> todoAction
        MainFabDestination.None -> null
    }
}

val LocalMainFabState = staticCompositionLocalOf<MainFabState> { error("MainFabState not provided") }
