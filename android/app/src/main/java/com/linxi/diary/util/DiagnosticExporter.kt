package com.linxi.diary.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 将私有运行日志与崩溃记录打包，并通过 Android Sharesheet 由用户主动导出。 */
object DiagnosticExporter {
    private const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000

    fun share(context: Context) {
        runCatching {
            val zip = createArchive(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zip
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("林曦日记诊断日志", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "导出诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Logs.i("Diagnostics", "诊断日志导出界面已打开")
        }.onFailure { Logs.e("Diagnostics", "导出诊断日志失败", it) }
    }

    private fun createArchive(context: Context): File {
        val cache = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        cache.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MAX_CACHE_AGE_MS }
            ?.forEach { it.delete() }
        val output = File(cache, "linxi-diagnostics-${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            addDirectory(zip, File(context.filesDir, "logs"), "logs")
            addDirectory(zip, File(context.filesDir, "crash"), "crash")
        }
        return output
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        directory.listFiles()?.filter { it.isFile }?.forEach { file ->
            zip.putNextEntry(ZipEntry("$prefix/${file.name}"))
            FileInputStream(file).buffered().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
