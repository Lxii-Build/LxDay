package com.linxi.diary.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 将私有运行日志与崩溃记录打包，并通过 Android Sharesheet 由用户主动导出。 */
object DiagnosticExporter {
    private const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000
    private const val MAX_EXPORT_FILE_BYTES = 8L * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun share(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching { createArchive(appContext) }
                .onSuccess { zip -> withContext(Dispatchers.Main) { openSharesheet(context, zip) } }
                .onFailure { Logs.e("Diagnostics", "导出诊断日志失败", it) }
        }
    }

    fun cleanupCache(context: Context) {
        scope.launch { cleanupExpired(File(context.cacheDir, "diagnostics")) }
    }

    private fun openSharesheet(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("林曦日记诊断日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出诊断日志"))
        Logs.i("Diagnostics", "诊断日志导出界面已打开")
    }

    private fun createArchive(context: Context): File {
        val cache = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        cleanupExpired(cache)
        val output = File(cache, "linxi-diagnostics-${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            addDirectory(zip, File(context.filesDir, "logs"), "logs")
            addDirectory(zip, File(context.filesDir, "crash"), "crash")
        }
        return output
    }

    private fun cleanupExpired(cache: File) {
        cache.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MAX_CACHE_AGE_MS }
            ?.forEach { it.delete() }
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        directory.listFiles()?.filter { it.isFile && it.length() <= MAX_EXPORT_FILE_BYTES }?.forEach { file ->
            zip.putNextEntry(ZipEntry("$prefix/${file.name}"))
            BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    zip.write((LogSanitizer.sanitize(line) + "\n").toByteArray(Charsets.UTF_8))
                }
            }
            zip.closeEntry()
        }
    }
}
