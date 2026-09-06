package com.linxi.diary.util

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
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
    private const val LOOP_PREFS = "linxi_crash_loop"
    private const val LAST_SIGNATURE = "last_signature"
    private const val LAST_AT_MILLIS = "last_at_millis"
    private const val LAST_REPEAT_COUNT = "last_repeat_count"
    private var logDir: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var loopPrefs: android.content.SharedPreferences? = null
    @Volatile private var handlerRegistered = false

    /** 提前注册（attachBaseContext 调用），尽早捕获崩溃 */
    fun initEarly(app: Application) {
        Log.i(TAG, "initEarly begin")
        val dir = File(app.filesDir, "crash")
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
        loopPrefs = app.getSharedPreferences(LOOP_PREFS, Application.MODE_PRIVATE)
        registerHandler()
        Log.i(TAG, "initEarly done, dir=$logDir")
    }

    fun init(app: Application) {
        logDir = File(app.filesDir, "crash")
        if (!logDir!!.exists()) logDir!!.mkdirs()
        loopPrefs = app.getSharedPreferences(LOOP_PREFS, Application.MODE_PRIVATE)
        registerHandler()
        Log.i(TAG, "init done")
    }

    private fun registerHandler() {
        synchronized(this) {
            // initEarly and init both run during a normal launch. Re-wrapping our
            // own handler would recurse indefinitely when a crash arrives.
            if (handlerRegistered) return
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
                val decision = recordCrash(throwable)
                writeCrash(throwable, thread.name, decision)
                if (decision.delayMillis > 0) {
                    try {
                        Thread.sleep(decision.delayMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                defaultHandler?.uncaughtException(thread, throwable)
            }
            handlerRegistered = true
        }
    }

    private fun write(fileName: String, content: String) {
        try {
            val dir = logDir ?: return
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, fileName)
            val temporary = File(dir, ".${fileName}.${android.os.Process.myPid()}.tmp")
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return
            }
            trimCrashFiles()
            Log.i(TAG, "crash file written: $fileName")
        } catch (_: Throwable) { }
    }

    fun writeCrash(t: Throwable, threadName: String = Thread.currentThread().name) {
        writeCrash(t, threadName, recordCrash(t))
    }

    private fun writeCrash(t: Throwable, threadName: String, decision: CrashLoopDecision) {
        try {
            val name = if (decision.repeatCount == 0) {
                SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".txt"
            } else {
                // Keep the first crash plus one atomically replaced "latest" file
                // instead of filling the diagnostic export with identical stacks.
                "repeat_${decision.signature.hashCode().toUInt().toString(16)}.txt"
            }
            val sb = StringBuilder()
            sb.appendLine("==== Linxi Diary crash ${Date()} ====")
            sb.appendLine("PID: ${android.os.Process.myPid()} Thread: $threadName")
            sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (decision.repeatCount > 0) {
                sb.appendLine("Repeated crash #${decision.repeatCount} within ${CrashLoopPolicy.WINDOW_MILLIS / 1000}s; retry delay=${decision.delayMillis}ms")
            }
            sb.appendLine()
            val sw = java.io.StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
            write(name, LogSanitizer.sanitize(sb.toString()))
        } catch (_: Throwable) { }
    }

    private fun recordCrash(throwable: Throwable): CrashLoopDecision {
        val signature = CrashLoopPolicy.signature(throwable)
        val now = System.currentTimeMillis()
        val prefs = loopPrefs ?: return CrashLoopPolicy.decide(signature, null, 0, 0, now)
        val decision = CrashLoopPolicy.decide(
            signature = signature,
            previousSignature = prefs.getString(LAST_SIGNATURE, null),
            previousAtMillis = prefs.getLong(LAST_AT_MILLIS, 0),
            previousRepeatCount = prefs.getInt(LAST_REPEAT_COUNT, 0),
            nowMillis = now,
        )
        // commit is deliberate: the process is about to terminate, so apply()
        // may never flush the information needed to stop the next crash loop.
        prefs.edit()
            .putString(LAST_SIGNATURE, decision.signature)
            .putLong(LAST_AT_MILLIS, now)
            .putInt(LAST_REPEAT_COUNT, decision.repeatCount)
            .commit()
        return decision
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
