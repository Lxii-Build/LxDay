package com.linxi.diary.util

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：未捕获异常 → 写日志文件 + logcat。
 * 应用内置 viewModel 之外的崩溃不直接闪退出系统对话框，但保留原始堆栈。
 */
object CrashHandler {

    private const val TAG = "LinxiCrash"
    private var logDir: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(app: Application) {
        logDir = File(app.filesDir, "crash")
        if (!logDir!!.exists()) logDir!!.mkdirs()

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            writeCrash(throwable)
            // 交给系统默认处理器结束进程（避免吞掉崩溃导致 UI 假死）
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun writeCrash(t: Throwable) {
        try {
            val dir = logDir ?: return
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date()) + ".txt"
            val file = File(dir, name)
            PrintWriter(FileWriter(file)).use { pw ->
                pw.println("==== 林曦日记 崩溃日志 ${Date()} ====")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                pw.println()
                t.printStackTrace(pw)
            }
            Log.i(TAG, "崩溃已写入: ${file.absolutePath}")
        } catch (_: Throwable) {
            // 写日志失败不可再抛
        }
    }

    fun crashFiles(): List<File> = logDir?.listFiles()?.sortedByDescending { it.lastModified() }
        ?.toList() ?: emptyList()

    fun clearCrashes() {
        try {
            logDir?.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) { }
    }
}