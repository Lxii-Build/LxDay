package com.linxi.diary.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局登录失效事件：任何 API 收到 HTTP 401（token 失效 / 数据库重建后用户不存在等）时发出，
 * 由导航层监听后清空本地会话并跳回登录页，避免"用旧 token 直接进去又报错"的情况。
 */
object AuthEvents {
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized = _unauthorized.asSharedFlow()

    fun signalUnauthorized() {
        _unauthorized.tryEmit(Unit)
    }
}
