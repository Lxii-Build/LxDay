package com.linxi.diary.ui.navigation

enum class MainFabDestination {
    None, Todo, Diary;

    companion object {
        fun forPage(page: Int): MainFabDestination = when (page) {
            1 -> Todo
            2 -> Diary
            else -> None
        }
    }
}
