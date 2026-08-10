package com.linxi.diary.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
class MainFabState {
    var todoAction: (() -> Unit)? by mutableStateOf(null)
    var diaryAction: (() -> Unit)? by mutableStateOf(null)

    fun actionFor(destination: MainFabDestination): (() -> Unit)? = when (destination) {
        MainFabDestination.Todo -> todoAction
        MainFabDestination.Diary -> diaryAction
        MainFabDestination.None -> null
    }
}

val LocalMainFabState = staticCompositionLocalOf<MainFabState> { error("MainFabState not provided") }
