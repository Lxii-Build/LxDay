package com.linxi.diary.ui.navigation

enum class MainFabDestination {
    None, Todo;

    companion object {
        fun forPage(page: Int): MainFabDestination = when (page) {
            1 -> Todo
            else -> None
        }
    }
}
