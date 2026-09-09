package com.linxi.diary.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, format-level rules shared by the Android importer and its JVM tests.
 *
 * A Live2D ZIP is not identified by its outer folder name.  The model3
 * manifest is the entry point and every path it references is resolved
 * relative to that manifest, exactly as Cubism runtimes do.  VTube Studio
 * metadata is useful for a display name/preview and for optional controls,
 * but it is never a replacement for the model3 + moc3 runtime pair.
 */
object Live2DImportPolicy {
    const val maxModels = 10
    /** Compressed input cap.  A valid archive must still be small enough to inspect promptly. */
    const val maxArchiveBytes = 50L * 1024L * 1024L
    const val maxUnpackedBytes = 300L * 1024L * 1024L
    const val maxEntryBytes = 64L * 1024L * 1024L
    const val maxMocBytes = 20L * 1024L * 1024L
    const val maxEntries = 1_000
    const val maxManifestBytes = 2L * 1024L * 1024L
    const val maxTextureEdge = 4096
    const val maxTexturePixels = 16_777_216L
    const val maxTotalTexturePixels = 16_777_216L

    data class TextureInfo(
        val width: Int,
        val height: Int,
    ) {
        val pixels: Long get() = width.toLong() * height.toLong()
        val maxEdge: Int get() = maxOf(width, height)
    }

    data class Reference(
        val path: String,
        val kind: Kind,
        val requiredForFirstFrame: Boolean,
    )

    enum class Kind {
        Moc,
        Texture,
        Physics,
        Pose,
        Expressions,
        Motions,
        UserData,
        DisplayInfo,
        Sound,
    }

    data class Report(
        val manifestPath: String?,
        val modelName: String?,
        val hasMoc3: Boolean,
        val textureCount: Int,
        val hasVtubeStudioConfig: Boolean,
        val previewImagePath: String?,
        val optionalResourceCount: Int,
        val references: List<Reference>,
        val missingRequired: List<String>,
        val missingOptional: List<String>,
        val textureWarnings: List<String>,
        val warnings: List<String>,
    ) {
        val canRender: Boolean
            get() = manifestPath != null && missingRequired.isEmpty() && hasMoc3 &&
                textureCount > 0 && textureWarnings.isEmpty()

        val hasBlockingIssue: Boolean get() = !canRender

        fun userMessage(): String = when {
            missingRequired.any { it.endsWith(".moc3", ignoreCase = true) } ->
                "缺少 Cubism moc3 运行文件：请从模型作者处取得与 model3.json 配套的 .moc3"
            manifestPath == null -> "没有找到 model3.json（可接受外层目录或嵌套目录）"
            missingRequired.isNotEmpty() -> "模型缺少必需资源：${missingRequired.joinToString("、")}"
            textureWarnings.isNotEmpty() -> textureWarnings.first()
            warnings.isNotEmpty() -> warnings.first()
            else -> "模型包检查通过"
        }
    }

    fun normalizeEntry(raw: String): String? {
        val name = raw.replace('\\', '/')
        if (name.isBlank() || name.startsWith('/') || name.contains('\u0000')) return null
        val segments = name.split('/').filter(String::isNotBlank)
        if (segments.any { it == "." || it == ".." }) return null
        return segments.joinToString("/").takeIf { it.isNotBlank() }
    }

    fun collisionKey(path: String): String = path.lowercase()

    fun isManifest(path: String): Boolean {
        val base = path.substringAfterLast('/').lowercase()
        return base == "model3.json" || base.endsWith(".model3.json")
    }

    fun isVtubeConfig(path: String): Boolean =
        path.substringAfterLast('/').lowercase().endsWith(".vtube.json")

    /** Lightweight preflight only; Cubism Core still performs full consistency validation. */
    fun hasMoc3Header(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == 'M'.code.toByte() &&
            bytes[1] == 'O'.code.toByte() && bytes[2] == 'C'.code.toByte() &&
            bytes[3] == '3'.code.toByte()

    /**
     * Read the model3 references without touching the file system.  Missing
     * optional resources are reported as warnings so a model can still render
     * its first frame when only a VTS hotkey/animation is stale.
     */
    fun analyzeManifest(
        manifestPath: String,
        manifestText: String,
        availableEntries: Set<String>,
        textureInfo: Map<String, TextureInfo> = emptyMap(),
        hasVtubeStudioConfig: Boolean = false,
        previewImagePath: String? = null,
        modelName: String? = null,
    ): Report {
        val root = runCatching { JSONObject(manifestText) }.getOrNull()
            ?: return Report(
                manifestPath = manifestPath,
                modelName = modelName,
                hasMoc3 = false,
                textureCount = 0,
                hasVtubeStudioConfig = hasVtubeStudioConfig,
                previewImagePath = previewImagePath,
                optionalResourceCount = 0,
                references = emptyList(),
                missingRequired = listOf("model3.json"),
                missingOptional = emptyList(),
                textureWarnings = emptyList(),
                warnings = listOf("model3.json 格式无效"),
            )
        val refs = root.optJSONObject("FileReferences")
            ?: return Report(
                manifestPath = manifestPath,
                modelName = modelName,
                hasMoc3 = false,
                textureCount = 0,
                hasVtubeStudioConfig = hasVtubeStudioConfig,
                previewImagePath = previewImagePath,
                optionalResourceCount = 0,
                references = emptyList(),
                missingRequired = listOf("FileReferences"),
                missingOptional = emptyList(),
                textureWarnings = emptyList(),
                warnings = emptyList(),
            )

        val references = mutableListOf<Reference>()
        val requiredMissing = mutableListOf<String>()
        val optionalMissing = mutableListOf<String>()
        val textureWarnings = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val rootEntries = availableEntries.toSet()

        val manifestDirectory = manifestPath.substringBeforeLast('/', "")

        fun addReference(raw: String?, kind: Kind, required: Boolean, suffix: String? = null) {
            val path = raw?.trim().orEmpty()
            if (path.isBlank()) {
                if (required) requiredMissing += kind.name
                return
            }
            // Cubism resolves FileReferences relative to the directory that
            // contains model3.json, not relative to the ZIP root.  Joining
            // before normalization keeps nested-root exports compatible while
            // still rejecting ../ traversal.
            val normalized = resolveReference(manifestDirectory, path)
            if (normalized == null || (suffix != null && !normalized.endsWith(suffix, ignoreCase = true))) {
                if (required) requiredMissing += path else optionalMissing += path
                return
            }
            references += Reference(normalized, kind, required)
            if (normalized !in rootEntries) {
                if (required) requiredMissing += normalized else optionalMissing += normalized
            }
        }

        addReference(refs.optString("Moc"), Kind.Moc, required = true, suffix = ".moc3")
        val textures = refs.optJSONArray("Textures")
        if (textures == null || textures.length() == 0 || textures.length() > 128) {
            requiredMissing += "Textures"
        } else {
            for (index in 0 until textures.length()) {
                addReference(textures.optString(index), Kind.Texture, required = true, suffix = ".png")
            }
        }

        addReference(refs.optString("Physics"), Kind.Physics, required = false, suffix = ".json")
        addReference(refs.optString("Pose"), Kind.Pose, required = false, suffix = ".json")
        addReference(refs.optString("UserData"), Kind.UserData, required = false, suffix = ".json")
        addReference(refs.optString("DisplayInfo"), Kind.DisplayInfo, required = false, suffix = ".json")

        val expressions = refs.optJSONArray("Expressions")
        if (expressions != null) {
            for (index in 0 until expressions.length()) {
                addReference(expressions.optJSONObject(index)?.optString("File"), Kind.Expressions, false, ".json")
            }
        }
        val motions = refs.optJSONObject("Motions")
        if (motions != null) {
            val keys = motions.keys()
            while (keys.hasNext()) {
                val array = motions.optJSONArray(keys.next()) ?: continue
                for (index in 0 until array.length()) {
                    val motion = array.optJSONObject(index) ?: continue
                    addReference(motion.optString("File"), Kind.Motions, false, ".json")
                    addReference(motion.optString("Sound"), Kind.Sound, false)
                }
            }
        }

        val textureRefs = references.filter { it.kind == Kind.Texture }
        var totalTexturePixels = 0L
        textureRefs.forEach { reference ->
            val info = textureInfo[reference.path]
            if (info == null) return@forEach
            if (info.width <= 0 || info.height <= 0) {
                textureWarnings += "PNG 纹理尺寸无效：${reference.path}"
            } else if (info.maxEdge > maxTextureEdge || info.pixels > maxTexturePixels) {
                textureWarnings += "PNG 纹理 ${info.width}×${info.height} 超过移动端预算（${maxTextureEdge}px / ${maxTexturePixels} 像素）"
            } else {
                totalTexturePixels += info.pixels
            }
        }
        if (totalTexturePixels > maxTotalTexturePixels) {
            textureWarnings += "模型贴图合计 ${totalTexturePixels} 像素，超过移动端总预算（${maxTotalTexturePixels} 像素）"
        }

        if (hasVtubeStudioConfig) {
            warnings += "检测到 VTube Studio 配置；它只提供动作/表情/位置元数据，不能替代 moc3"
        }
        if (optionalMissing.isNotEmpty()) {
            warnings += "有 ${optionalMissing.size} 个可选动作或物理资源缺失，首帧仍可继续验证"
        }
        val optionalCount = references.count { !it.requiredForFirstFrame }
        return Report(
            manifestPath = manifestPath,
            modelName = modelName,
            hasMoc3 = references.any { it.kind == Kind.Moc && it.path !in requiredMissing },
            textureCount = textureRefs.count { it.path !in requiredMissing },
            hasVtubeStudioConfig = hasVtubeStudioConfig,
            previewImagePath = previewImagePath,
            optionalResourceCount = optionalCount,
            references = references,
            missingRequired = requiredMissing.distinct(),
            missingOptional = optionalMissing.distinct(),
            textureWarnings = textureWarnings.distinct(),
            warnings = warnings.distinct(),
        )
    }

    /** Extract the display name and preview icon from VTube Studio metadata. */
    fun vtubeMetadata(text: String): Pair<String?, String?> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null to null
        val name = root.optString("Name").trim().takeIf(String::isNotBlank)
        val icon = root.optJSONObject("FileReferences")?.optString("Icon")
            ?.trim()?.takeIf(String::isNotBlank)
        return name to icon
    }

    /** Resolve a model3 FileReference while allowing safe `../` siblings. */
    private fun resolveReference(base: String, raw: String): String? {
        val path = raw.replace('\\', '/')
        if (path.startsWith('/') || path.contains('\u0000') || path.contains("://")) return null
        val stack = base.split('/').filter(String::isNotBlank).toMutableList()
        for (segment in path.split('/')) {
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else return null
                else -> stack += segment
            }
        }
        return stack.joinToString("/").takeIf { it.isNotBlank() }
    }

    /** Read a text field from either a JSON object or array without throwing. */
    fun jsonFileArray(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.optString("File")?.takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
