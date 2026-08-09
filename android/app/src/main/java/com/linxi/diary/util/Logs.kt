package com.linxi.diary.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志门面：双写
 *  1. logcat（adb logcat -s Linxi:V）
 *  2. 文件：
 *     内部  /data/data/<pkg>/files/logs/yyyyMMdd.log
 *     外部  Android/data/<pkg>/files/logs/yyyyMMdd.log （文件管理器可见，无需 adb）
 *
 * 外部目录用于真机直接看完整日志（不开 USB 调试），崩溃前最后一条往往就在文件末尾。
 */
object Logs {
    private const val PREFIX = "Linxi"
    private const val ENABLED = true
    private const val MAX_FILE_SIZE = 4L * 1024 * 1024 // 4MB 截断
    private const val TRIM_KEEP_LINES = 1000

    @Volatile
    private var fileDirs: List<File> = emptyList()
    private val lock = Any()

    /** App.onCreate 调用；写入目录创建失败不影响主流程 */
    fun init(context: Context) {
        try {
            val dirs = mutableListOf<File>()
            runCatching {
                File(context.filesDir, "logs").apply { mkdirs() }.let { dirs.add(it) }
            }
            runCatching {
                context.getExternalFilesDir("logs")?.apply { mkdirs() }?.let { dirs.add(it) }
            }
            fileDirs = dirs
            i("Logs", "文件日志初始化完成 dirs=$fileDirs")
        } catch (t: Throwable) {
            Log.e("$PREFIX/Logs", "文件日志初始化失败", t)
        }
    }

    fun d(tag: String, msg: String) { if (ENABLED) { Log.d("$PREFIX/$tag", msg); fileLog('D', tag, msg) } }
    fun i(tag: String, msg: String) { if (ENABLED) { Log.i("$PREFIX/$tag", msg); fileLog('I', tag, msg) } }
    fun w(tag: String, msg: String) { if (ENABLED) { Log.w("$PREFIX/$tag", msg); fileLog('W', tag, msg) } }
    fun w(tag: String, msg: String, t: Throwable?) {
        if (ENABLED) { Log.w("$PREFIX/$tag", msg, t); fileLog('W', tag, "$msg\n${t?.stackTraceToString()}") }
    }
    fun e(tag: String, msg: String) { if (ENABLED) { Log.e("$PREFIX/$tag", msg); fileLog('E', tag, msg) } }
    fun e(tag: String, msg: String, t: Throwable?) {
        if (ENABLED) { Log.e("$PREFIX/$tag", msg, t); fileLog('E', tag, "$msg\n${t?.stackTraceToString()}") }
    }

    private fun fileLog(level: Char, tag: String, msg: String) {
        val dirs = fileDirs
        if (dirs.isEmpty()) return
        try {
            val now = Date()
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(now)
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
            val line = "$time $level $PREFIX/$tag: $msg"
            synchronized(lock) {
                dirs.forEach { dir ->
                    runCatching {
                        val f = File(dir, "$day.log")
                        if (f.length() > MAX_FILE_SIZE) {
                            val all = f.readLines()
                            f.writeText((all.takeLast(TRIM_KEEP_LINES) + line).joinToString("\n") + "\n")
                        } else {
                            f.appendText(line + "\n")
                        }
                    }
                }
            }
        } catch (_: Throwable) { }
    }
}
