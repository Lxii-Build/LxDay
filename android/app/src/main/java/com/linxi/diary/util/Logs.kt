package com.linxi.diary.util

import android.util.Log

/**
 * 轻量日志门面：统一 TAG 前缀，方便 logcat 过滤（adb logcat -s Linxi:V）。
 * 调试阶段全量输出；发布可在此集中开关。
 */
object Logs {
    private const val PREFIX = "Linxi"
    private const val ENABLED = true

    fun d(tag: String, msg: String) {
        if (ENABLED) Log.d("$PREFIX/$tag", msg)
    }

    fun i(tag: String, msg: String) {
        if (ENABLED) Log.i("$PREFIX/$tag", msg)
    }

    fun w(tag: String, msg: String) {
        if (ENABLED) Log.w("$PREFIX/$tag", msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (ENABLED) Log.e("$PREFIX/$tag", msg, t)
    }
}