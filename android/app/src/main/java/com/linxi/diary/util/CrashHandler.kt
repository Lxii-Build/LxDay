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
 * 崩溃日志写到两个可见位置：
 *  1. 内部 files/crash（标准私有目录）
 *  2. 外部 Android/data/<pkg>/files/crash（文件管理器可见，无需权限）
 * 另写「启动标记」确认 App.onCreate 是否执行到。
 */
object CrashHandler {

    private const val TAG = "Linxi/Crash"
    private var logDir: File? = null
    private var extLogDir: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /** 提前注册（attachBaseContext 调用），尽早捕获崩溃 */
    fun initEarly(app: Application) {
        Log.i(TAG, "initEarly 开始")
        val dir = File(app.filesDir, "crash")
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
        extLogDir = app.getExternalFilesDir("crash")
        registerHandler()
        // 标记：进程已启动到 attachBaseContext（证明 CrashHandler 已注册）
        writeMark(app, "attach_pid_${android.os.Process.myPid()}-${System.currentTimeMillis()}.txt", "attachBaseContext OK")
        Log.i(TAG, "initEarly 完成，内部=$logDir 外部=$extLogDir")
    }

    fun init(app: Application) {
        logDir = File(app.filesDir, "crash")
        if (!logDir!!.exists()) logDir!!.mkdirs()
        extLogDir = app.getExternalFilesDir("crash")
        registerHandler()
        // 标记：App.onCreate 执行到
        write("onCreate_pid_${android.os.Process.myPid()}-${System.currentTimeMillis()}.txt", "App.onCreate OK")
        Log.i(TAG, "init 完成")
    }

    private fun registerHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            writeCrash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun write(fileName: String, content: String) {
        try {
            logDir?.let { d -> if (!d.exists()) d.mkdirs() }
            extLogDir?.let { d -> if (!d.exists()) d.mkdirs() }
            // 写到两个位置
            listOfNotNull(logDir, extLogDir).forEach { dir ->
                runCatching { File(dir, fileName).writeText(content) }
            }
            Log.i(TAG, "标记已写: $fileName")
        } catch (_: Throwable) { }
    }

    fun writeCrash(t: Throwable) {
        try {
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            val sb = StringBuilder()
            sb.appendLine("==== 林曦日记 崩溃日志 ${Date()} ====")
            sb.appendLine("PID: ${android.os.Process.myPid()} Thread: not captured")
            sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            sb.appendLine()
            val sw = java.io.StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
            write(name, sb.toString())
        } catch (_: Throwable) { }
    }

    fun crashFiles(): List<File> = (logDir?.listFiles()?.toList() ?: emptyList()) +
            (extLogDir?.listFiles()?.toList() ?: emptyList())

    fun clearCrashes() {
        try {
            logDir?.listFiles()?.forEach { it.delete() }
            extLogDir?.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) { }
    }
}