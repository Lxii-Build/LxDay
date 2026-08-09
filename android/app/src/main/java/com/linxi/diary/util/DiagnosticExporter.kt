package com.linxi.diary.util

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 将私有运行日志与崩溃记录打包，并通过 Android Sharesheet 由用户主动导出。 */
object DiagnosticExporter {
    private const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000
    private const val MAX_EXPORT_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_EXPORT_TOTAL_BYTES = 32L * 1024 * 1024
    private const val MAX_EXPORT_ENTRIES = 64
    private val exporting = AtomicBoolean(false)

    suspend fun share(activity: Activity) {
        if (!exporting.compareAndSet(false, true)) {
            Toast.makeText(activity, "诊断日志正在打包", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val zip = withContext(Dispatchers.IO) { createArchive(activity) }
            openSharesheet(activity, zip)
        } catch (t: Throwable) {
            Logs.e("Diagnostics", "导出诊断日志失败", t)
            Toast.makeText(activity, "导出诊断日志失败", Toast.LENGTH_SHORT).show()
        } finally {
            exporting.set(false)
        }
    }

    private fun openSharesheet(activity: Activity, zip: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", zip)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("林曦日记诊断日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "导出诊断日志")
        if (chooser.resolveActivity(activity.packageManager) == null) {
            Toast.makeText(activity, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
            return
        }
        activity.startActivity(chooser)
        Logs.i("Diagnostics", "诊断日志导出界面已打开")
    }

    private fun createArchive(activity: Activity): File {
        val cache = File(activity.cacheDir, "diagnostics").apply { mkdirs() }
        cleanupExpired(cache)
        val output = File(cache, "linxi-diagnostics-${System.currentTimeMillis()}.zip")
        try {
            val files = (Logs.diagnosticFiles() + CrashHandler.crashFiles())
                .distinctBy { it.absolutePath }
                .sortedByDescending { it.lastModified() }
                .take(MAX_EXPORT_ENTRIES)
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                var exportedBytes = 0L
                files.forEach { file ->
                    if (!file.isFile || exportedBytes >= MAX_EXPORT_TOTAL_BYTES) return@forEach
                    val allowed = minOf(file.length(), MAX_EXPORT_FILE_BYTES, MAX_EXPORT_TOTAL_BYTES - exportedBytes)
                    zip.putNextEntry(ZipEntry("${if (file.parentFile?.name == "crash") "crash" else "logs"}/${file.name}"))
                    exportedBytes += writeSanitized(zip, file, allowed)
                    zip.closeEntry()
                }
            }
            return output
        } catch (t: Throwable) {
            output.delete()
            throw t
        }
    }

    private fun writeSanitized(zip: ZipOutputStream, file: File, maxBytes: Long): Long {
        var written = 0L
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                val bytes = (LogSanitizer.sanitize(line) + "\n").toByteArray(Charsets.UTF_8)
                if (written + bytes.size > maxBytes) {
                    val marker = "-- file truncated during export --\n".toByteArray()
                    if (written + marker.size <= maxBytes) zip.write(marker)
                    break
                }
                zip.write(bytes)
                written += bytes.size
            }
        }
        return written
    }

    private fun cleanupExpired(cache: File) {
        cache.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MAX_CACHE_AGE_MS }
            ?.forEach { it.delete() }
    }
}
