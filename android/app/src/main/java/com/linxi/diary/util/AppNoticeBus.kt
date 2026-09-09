package com.linxi.diary.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-local bridge from Activity/utility callbacks to the Compose shell. */
object AppNoticeBus {
    data class Notice(
        val id: Long,
        val message: String,
        val isError: Boolean = false,
    )

    private val _events = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    val events: SharedFlow<Notice> = _events.asSharedFlow()

    fun show(message: String, isError: Boolean = false) {
        val clean = message.trim().takeIf { it.isNotBlank() } ?: return
        _events.tryEmit(Notice(System.nanoTime(), clean, isError))
    }
}
