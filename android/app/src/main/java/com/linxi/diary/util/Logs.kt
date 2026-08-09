package com.linxi.diary.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 统一日志门面：logcat + Android app 私有目录 files/logs。
 * 文件日志是诊断主来源；不写 Android/data 外部目录，需通过设置页主动导出。
 */
object Logs {
    private const val PREFIX = "Linxi"
    private const val ENABLED = true
    private const val MAX_FILE_SIZE = 4L * 1024 * 1024
    private const val RETENTION_DAYS = 7L
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val fileLock = Any()

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        try {
            logDir = File(context.filesDir, "logs").apply { mkdirs() }
            cleanupOldFiles()
            i("Logs", "文件日志初始化完成 dir=$logDir")
        } catch (t: Throwable) {
            Log.e("$PREFIX/Logs", "文件日志初始化失败", t)
        }
    }

    fun d(tag: String, msg: String) = write(LogLevel.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = write(LogLevel.INFO, tag, msg, null)
    fun w(tag: String, msg: String) = write(LogLevel.WARN, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable?) = write(LogLevel.WARN, tag, msg, t)
    fun e(tag: String, msg: String) = write(LogLevel.ERROR, tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable?) = write(LogLevel.ERROR, tag, msg, t)

    private fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (!ENABLED) return
        val logTag = "$PREFIX/$tag"
        when (level) {
            LogLevel.DEBUG -> if (throwable == null) Log.d(logTag, message) else Log.d(logTag, message, throwable)
            LogLevel.INFO -> if (throwable == null) Log.i(logTag, message) else Log.i(logTag, message, throwable)
            LogLevel.WARN -> if (throwable == null) Log.w(logTag, message) else Log.w(logTag, message, throwable)
            LogLevel.ERROR -> if (throwable == null) Log.e(logTag, message) else Log.e(logTag, message, throwable)
        }
        val suffix = throwable?.stackTraceToString()?.let { "\n$it" } ?: ""
        appendFile(level, tag, LogSanitizer.sanitize(message) + suffix)
    }

    private fun appendFile(level: LogLevel, tag: String, message: String) {
        val dir = logDir ?: return
        try {
            val now = OffsetDateTime.now(ZoneId.systemDefault())
            val file = File(dir, "runtime-${now.toLocalDate()}.log")
            val line = "${formatter.format(now)} ${level.name.padEnd(5)} $PREFIX/$tag " +
                "[${Thread.currentThread().name}] ${message.replace("\r", "\\r")}\n"
            synchronized(fileLock) {
                if (file.length() + line.toByteArray(Charsets.UTF_8).size > MAX_FILE_SIZE) {
                    FileOutputStream(file, false).use { it.write("-- log truncated --\n".toByteArray()) }
                }
                file.appendText(line)
            }
        } catch (_: Throwable) {
            // 诊断写盘不能影响业务线程；logcat 已记录原始事件。
        }
    }

    private fun cleanupOldFiles() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - ChronoUnit.DAYS.duration.multipliedBy(RETENTION_DAYS).toMillis()
        dir.listFiles { file -> file.name.startsWith("runtime-") && file.name.endsWith(".log") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    internal enum class LogLevel { DEBUG, INFO, WARN, ERROR }
}
