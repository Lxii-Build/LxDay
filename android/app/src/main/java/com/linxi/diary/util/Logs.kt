package com.linxi.diary.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 统一日志门面：脱敏后写 logcat，并异步写 Android app 私有 files/logs。 */
object Logs {
    private const val PREFIX = "Linxi"
    private const val MAX_FILE_SIZE = 4L * 1024 * 1024
    private const val RETENTION_DAYS = 7L
    private const val MAX_ROTATED_FILES = 3
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<FileLog>(capacity = 512)

    @Volatile private var logDir: File? = null
    @Volatile private var writerStarted = false

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        if (!writerStarted) {
            synchronized(this) {
                if (!writerStarted) {
                    writerStarted = true
                    scope.launch {
                        cleanupOldFiles()
                        for (entry in queue) appendFile(entry)
                    }
                }
            }
        }
        i("Logs", "文件日志初始化完成")
    }

    fun d(tag: String, msg: String) = write(LogLevel.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = write(LogLevel.INFO, tag, msg, null)
    fun w(tag: String, msg: String) = write(LogLevel.WARN, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable?) = write(LogLevel.WARN, tag, msg, t)
    fun e(tag: String, msg: String) = write(LogLevel.ERROR, tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable?) = write(LogLevel.ERROR, tag, msg, t)

    fun diagnosticFiles(): List<File> = logDir?.listFiles()
        ?.filter { it.isFile && it.name.startsWith("runtime-") && it.name.contains(".log") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    private fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val suffix = throwable?.stackTraceToString()?.let { "\n$it" } ?: ""
        val sanitized = LogSanitizer.sanitize(message + suffix)
        val logTag = "$PREFIX/$tag"
        when (level) {
            LogLevel.DEBUG -> Log.d(logTag, sanitized)
            LogLevel.INFO -> Log.i(logTag, sanitized)
            LogLevel.WARN -> Log.w(logTag, sanitized)
            LogLevel.ERROR -> Log.e(logTag, sanitized)
        }
        queue.trySend(FileLog(level, tag, sanitized, OffsetDateTime.now(ZoneId.systemDefault()), Thread.currentThread().name))
    }

    private fun appendFile(entry: FileLog) {
        val dir = logDir ?: return
        runCatching {
            var file = File(dir, "runtime-${entry.timestamp.toLocalDate()}.log")
            val line = "${formatter.format(entry.timestamp)} ${entry.level.name.padEnd(5)} $PREFIX/${entry.tag} " +
                "[${entry.thread}] ${entry.message.replace("\r", "\\r")}\n"
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (file.length() + bytes.size > MAX_FILE_SIZE) {
                rotate(file)
                file = File(dir, file.name)
            }
            BufferedOutputStream(FileOutputStream(file, true)).use { it.write(bytes) }
        }
    }

    private fun rotate(base: File) {
        File(base.parentFile, "${base.name}.$MAX_ROTATED_FILES").delete()
        for (index in MAX_ROTATED_FILES - 1 downTo 1) {
            val from = File(base.parentFile, "${base.name}.$index")
            if (from.exists()) from.renameTo(File(base.parentFile, "${base.name}.${index + 1}"))
        }
        if (base.exists()) base.renameTo(File(base.parentFile, "${base.name}.1"))
    }

    private fun cleanupOldFiles() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - ChronoUnit.DAYS.duration.multipliedBy(RETENTION_DAYS).toMillis()
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("runtime-") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private data class FileLog(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val timestamp: OffsetDateTime,
        val thread: String,
    )

    internal enum class LogLevel { DEBUG, INFO, WARN, ERROR }
}
