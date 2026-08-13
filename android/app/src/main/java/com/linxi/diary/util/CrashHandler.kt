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
 * 全局崩溃捕获。
 * 崩溃日志写入内部 files/crash（Android app 私有目录），正文经 LogSanitizer 脱敏。
 * 需要读取时通过设置页导出诊断包。
 */
object CrashHandler {

    private const val TAG = "Linxi/Crash"
    private const val MAX_CRASH_FILES = 20
    private var logDir: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /** 提前注册（attachBaseContext 调用），尽早捕获崩溃 */
    fun initEarly(app: Application) {
        Log.i(TAG, "initEarly begin")
        val dir = File(app.filesDir, "crash")
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
        registerHandler()
        Log.i(TAG, "initEarly done, dir=$logDir")
    }

    fun init(app: Application) {
        logDir = File(app.filesDir, "crash")
        if (!logDir!!.exists()) logDir!!.mkdirs()
        registerHandler()
        Log.i(TAG, "init done")
    }

    private fun registerHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            writeCrash(throwable, thread.name)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun write(fileName: String, content: String) {
        try {
            logDir?.let { d -> if (!d.exists()) d.mkdirs() }
            logDir?.let { dir -> runCatching { File(dir, fileName).writeText(content) } }
            trimCrashFiles()
            Log.i(TAG, "crash file written: $fileName")
        } catch (_: Throwable) { }
    }

    fun writeCrash(t: Throwable, threadName: String = Thread.currentThread().name) {
        try {
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            val sb = StringBuilder()
            sb.appendLine("==== Linxi Diary crash ${Date()} ====")
            sb.appendLine("PID: ${android.os.Process.myPid()} Thread: $threadName")
            sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            sb.appendLine()
            val sw = java.io.StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
            write(name, LogSanitizer.sanitize(sb.toString()))
        } catch (_: Throwable) { }
    }

    private fun trimCrashFiles() {
        logDir?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_FILES)?.forEach { it.delete() }
    }

    fun crashFiles(): List<File> = logDir?.listFiles()?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clearCrashes() {
        try {
            logDir?.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) { }
    }
}