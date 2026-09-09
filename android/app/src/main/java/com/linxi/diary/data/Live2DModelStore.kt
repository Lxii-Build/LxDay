package com.linxi.diary.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class Live2DModelRecord(
    val id: String,
    val name: String,
    val directory: File,
    val bytes: Long,
    val installedAt: Long,
    val manifestPath: String = "",
    val textureCount: Int = 0,
    val hasVtubeStudioConfig: Boolean = false,
    val previewImagePath: String? = null,
    val compatibilityWarnings: List<String> = emptyList(),
)

sealed class Live2DImportResult {
    data class Success(
        val model: Live2DModelRecord,
        val report: Live2DImportPolicy.Report,
    ) : Live2DImportResult()

    data class Failure(
        val message: String,
        val report: Live2DImportPolicy.Report? = null,
    ) : Live2DImportResult()
}

/**
 * Device-local Live2D model library.
 *
 * Models are copied from the Android system document picker into noBackupFilesDir;
 * the app never receives broad storage permission and never treats a ZIP as a
 * trusted path.  Rendering is intentionally a separate concern: this store only
 * accepts a complete Cubism model bundle and exposes a deterministic record to a
 * future native renderer.
 */
object Live2DModelStore {
    private const val META = "model.properties"
    private const val PREFS = "live2d-models"
    private const val ACTIVE_ID = "active_model_id"

    suspend fun list(context: Context): List<Live2DModelRecord> = withContext(Dispatchers.IO) {
        root(context).listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.mapNotNull(::readRecord)
            ?.sortedByDescending { it.installedAt }
            ?.toList()
            .orEmpty()
    }

    /** The selected model is a local preference; it is never sent to the server. */
    fun activeId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE_ID, null)

    /** Select only an installed model, so a stale or forged id cannot become active. */
    fun setActive(context: Context, id: String?): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (id == null) {
            preferences.edit().remove(ACTIVE_ID).apply()
            return true
        }
        if (!id.matches(Regex("model-[0-9]+"))) return false
        val target = File(root(context), id)
        if (!target.isDirectory || !File(target, META).isFile) return false
        preferences.edit().putString(ACTIVE_ID, id).apply()
        return true
    }

    suspend fun importZip(context: Context, uri: Uri, displayName: String): Live2DImportResult =
        withContext(Dispatchers.IO) {
            val opened = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            importOpened(context, displayName, opened)
        }

    /** Import a downloaded server bundle without going through a FileProvider URI. */
    suspend fun importFile(context: Context, file: File, displayName: String): Live2DImportResult =
        withContext(Dispatchers.IO) {
            val opened = runCatching { file.inputStream() }.getOrNull()
            importOpened(context, displayName, opened)
        }

    /**
     * Compatibility-first import path. It accepts nested-root exports, keeps
     * VTube Studio sidecar files, and returns the exact preflight report when
     * a package is rejected. Rendering remains a separate adapter, so an
     * accepted bundle is not silently presented as a Cubism frame.
     */
    private fun importOpened(context: Context, displayName: String, opened: InputStream?): Live2DImportResult {
        val base = root(context)
        val existing = base.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0
        if (existing >= Live2DImportPolicy.maxModels) {
            opened?.close()
            return Live2DImportResult.Failure(
                "模型库已达到 " + Live2DImportPolicy.maxModels + " 个上限，请先删除不用的模型",
            )
        }
        if (opened == null) return Live2DImportResult.Failure("无法读取所选文件")

        val id = "model-" + System.currentTimeMillis()
        val staging = File(base, ".staging-" + id)
        val destination = File(base, id)
        try {
            staging.mkdirs()
            val analysis = opened.use { extractArchive(it, staging) }
            val report = analysis.report
                ?: throw ModelImportException("没有找到 model3.json（可接受外层目录或嵌套目录）")
            if (report.hasBlockingIssue || report.textureWarnings.isNotEmpty()) {
                throw ReportedImportException(report.userMessage(), report)
            }
            val fallbackName = displayName.substringBeforeLast('.').trim()
                .takeIf { it.isNotBlank() && !it.contains(':') }
            val modelName = (fallbackName ?: report.modelName ?: "未命名模型").take(80)
            val metadata = Properties().apply {
                setProperty("id", id)
                setProperty("name", modelName)
                setProperty("bytes", analysis.unpackedBytes.toString())
                setProperty("installedAt", System.currentTimeMillis().toString())
                setProperty("manifestPath", report.manifestPath.orEmpty())
                setProperty("textureCount", report.textureCount.toString())
                setProperty("vtubeStudioConfig", report.hasVtubeStudioConfig.toString())
                setProperty("previewImagePath", report.previewImagePath.orEmpty())
                setProperty("warnings", report.warnings.joinToString("\n"))
            }
            File(staging, META).outputStream().use { metadata.store(it, "Linxi Live2D model") }
            if (!staging.renameTo(destination)) throw ModelImportException("无法完成模型安装")
            Live2DImportResult.Success(
                readRecord(destination) ?: throw ModelImportException("模型记录写入失败"),
                report,
            )
        } catch (e: ReportedImportException) {
            staging.deleteRecursively()
            Live2DImportResult.Failure(e.message ?: "模型导入失败", e.report)
        } catch (e: ModelImportException) {
            staging.deleteRecursively()
            Live2DImportResult.Failure(e.message ?: "模型导入失败")
        } catch (e: IOException) {
            staging.deleteRecursively()
            Live2DImportResult.Failure("模型文件读取失败，请重新选择 ZIP")
        } catch (e: SecurityException) {
            staging.deleteRecursively()
            Live2DImportResult.Failure("系统拒绝读取这个文件")
        }
    }

    private data class ArchiveAnalysis(
        val report: Live2DImportPolicy.Report?,
        val unpackedBytes: Long,
    )

    private fun extractArchive(source: InputStream, staging: File): ArchiveAnalysis {
        val entries = linkedSetOf<String>()
        val collisionKeys = mutableSetOf<String>()
        val textureInfo = mutableMapOf<String, Live2DImportPolicy.TextureInfo>()
        var manifestPath: String? = null
        var manifestText: String? = null
        var vtubeText: String? = null
        var previewPath: String? = null
        var unpackedBytes = 0L
        var entryCount = 0
        // The decompressed cap protects disk usage; this second cap also keeps
        // an intentionally huge compressed archive from tying up the import
        // coroutine while ZipInputStream scans it.
        ZipInputStream(CappedInputStream(source, Live2DImportPolicy.maxArchiveBytes).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > Live2DImportPolicy.maxEntries) {
                    throw ModelImportException("模型文件过多，无法导入")
                }
                val safeName = safeEntryName(entry)
                    ?: throw ModelImportException("ZIP 包含不安全的文件路径")
                if (!entries.add(safeName) ||
                    !collisionKeys.add(Live2DImportPolicy.collisionKey(safeName))
                ) {
                    throw ModelImportException("模型 ZIP 包含重复或大小写冲突的文件名")
                }
                if (entry.isDirectory) continue
                val output = File(staging, safeName)
                output.parentFile?.mkdirs()
                var entryBytes = 0L
                output.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        entryBytes += read
                        if (entryBytes > Live2DImportPolicy.maxEntryBytes) {
                            throw ModelImportException("模型单个文件不能超过 64 MiB")
                        }
                        if (safeName.endsWith(".moc3", ignoreCase = true) &&
                            entryBytes > Live2DImportPolicy.maxMocBytes
                        ) {
                            throw ModelImportException("模型 moc3 文件不能超过 20 MiB")
                        }
                        unpackedBytes += read
                        if (unpackedBytes > Live2DImportPolicy.maxUnpackedBytes) {
                            throw ModelImportException("模型解压后不能超过 300 MiB")
                        }
                        sink.write(buffer, 0, read)
                    }
                }
                when {
                    Live2DImportPolicy.isManifest(safeName) -> {
                        if (manifestPath != null) {
                            throw ModelImportException("模型包包含多个角色，请拆分后导入")
                        }
                        manifestPath = safeName
                        manifestText = readBoundedText(output, Live2DImportPolicy.maxManifestBytes)
                    }
                    Live2DImportPolicy.isVtubeConfig(safeName) -> {
                        vtubeText = readBoundedText(output, Live2DImportPolicy.maxManifestBytes)
                    }
                    safeName.endsWith(".moc3", ignoreCase = true) -> {
                        validateMocHeader(output)
                    }
                    safeName.substringAfterLast('/').lowercase().endsWith(".png") -> {
                        textureInfo[safeName] = readTextureInfo(output)
                        val baseName = safeName.substringAfterLast('/').lowercase()
                        if (previewPath == null && baseName in setOf("yumi.png", "icon.png", "preview.png")) {
                            previewPath = safeName
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val vtubeNameAndIcon = vtubeText?.let(Live2DImportPolicy::vtubeMetadata)
        if (previewPath == null) {
            val candidate = vtubeNameAndIcon?.second
            if (!candidate.isNullOrBlank()) {
                previewPath = Live2DImportPolicy.normalizeEntry(candidate)
                    ?.takeIf { it in entries }
            }
        }
        val report = if (manifestPath != null && manifestText != null) {
            Live2DImportPolicy.analyzeManifest(
                manifestPath = manifestPath,
                manifestText = manifestText,
                availableEntries = entries,
                textureInfo = textureInfo,
                hasVtubeStudioConfig = vtubeText != null,
                previewImagePath = previewPath,
                modelName = vtubeNameAndIcon?.first,
            )
        } else {
            Live2DImportPolicy.Report(
                manifestPath = null,
                modelName = vtubeNameAndIcon?.first,
                hasMoc3 = false,
                textureCount = 0,
                hasVtubeStudioConfig = vtubeText != null,
                previewImagePath = previewPath,
                optionalResourceCount = 0,
                references = emptyList(),
                missingRequired = listOf("model3.json"),
                missingOptional = emptyList(),
                textureWarnings = emptyList(),
                warnings = emptyList(),
            )
        }
        return ArchiveAnalysis(report, unpackedBytes)
    }

    private fun readBoundedText(file: File, maxBytes: Long): String? {
        if (!file.isFile || file.length() > maxBytes) return null
        return runCatching { file.readText().removePrefix("\uFEFF") }.getOrNull()
    }

    private fun readTextureInfo(file: File): Live2DImportPolicy.TextureInfo {
        val header = ByteArray(24)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) throw ModelImportException("PNG 纹理文件不完整")
                offset += read
            }
        }
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        if (!header.copyOfRange(0, 8).contentEquals(signature) ||
            header[12] != 'I'.code.toByte() || header[13] != 'H'.code.toByte() ||
            header[14] != 'D'.code.toByte() || header[15] != 'R'.code.toByte()
        ) {
            throw ModelImportException("纹理不是有效 PNG")
        }
        return Live2DImportPolicy.TextureInfo(
            width = pngUInt32(header, 16),
            height = pngUInt32(header, 20),
        )
    }

    private fun validateMocHeader(file: File) {
        val header = ByteArray(8)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) throw ModelImportException("moc3 文件不完整")
                offset += read
            }
        }
        if (!Live2DImportPolicy.hasMoc3Header(header)) {
            throw ModelImportException("moc3 文件头无效，请提供 Cubism 导出的运行模型")
        }
    }

    private class ReportedImportException(
        message: String,
        val report: Live2DImportPolicy.Report,
    ) : ModelImportException(message)

    suspend fun delete(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        // IDs are generated locally; reject arbitrary path components before resolving.
        if (!id.matches(Regex("model-[0-9]+"))) return@withContext false
        val target = File(root(context), id)
        val deleted = target.canonicalFile.parentFile == root(context).canonicalFile && target.deleteRecursively()
        if (deleted && activeId(context) == id) setActive(context, null)
        deleted
    }

    private fun root(context: Context): File =
        File(context.noBackupFilesDir, "live2d-models").apply { mkdirs() }

    private fun readRecord(directory: File): Live2DModelRecord? {
        val file = File(directory, META)
        if (!file.isFile) return null
        return runCatching {
            val values = Properties().apply { file.inputStream().use { load(it) } }
            Live2DModelRecord(
                id = values.getProperty("id") ?: directory.name,
                name = values.getProperty("name") ?: directory.name,
                directory = directory,
                bytes = values.getProperty("bytes")?.toLongOrNull() ?: 0L,
                installedAt = values.getProperty("installedAt")?.toLongOrNull() ?: 0L,
                manifestPath = values.getProperty("manifestPath").orEmpty(),
                textureCount = values.getProperty("textureCount")?.toIntOrNull() ?: 0,
                hasVtubeStudioConfig = values.getProperty("vtubeStudioConfig") == "true",
                previewImagePath = values.getProperty("previewImagePath")
                    ?.takeIf { it.isNotBlank() },
                compatibilityWarnings = values.getProperty("warnings")
                    ?.split('\n')
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    .orEmpty(),
            )
        }.getOrNull()
    }

    private fun safeEntryName(entry: ZipEntry): String? {
        return Live2DImportPolicy.normalizeEntry(entry.name)
    }

    private fun pngUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private open class ModelImportException(message: String) : IOException(message)

    /**
     * Limits bytes consumed from the compressed source itself.  ZIP headers
     * are not trusted because a document provider may report an unknown size.
     */
    private class CappedInputStream(
        source: InputStream,
        private val cap: Long,
    ) : FilterInputStream(source) {
        private var consumed = 0L

        override fun read(): Int {
            if (consumed >= cap) {
                if (super.read() >= 0) throw ModelImportException("模型 ZIP 不能超过 50 MiB")
                return -1
            }
            val value = super.read()
            if (value >= 0) consumed++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (consumed >= cap) {
                if (super.read() >= 0) throw ModelImportException("模型 ZIP 不能超过 50 MiB")
                return -1
            }
            val allowed = minOf(length.toLong(), cap - consumed).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) consumed += count
            return count
        }
    }
}
