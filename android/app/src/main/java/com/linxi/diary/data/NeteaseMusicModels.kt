package com.linxi.diary.data

/** 网易云逐行歌词。时间轴来自 LRC/YRC 的毫秒时间戳。 */
data class NeteaseLyricLine(
    val timeMs: Long,
    val text: String,
)

data class NeteaseLyrics(
    val original: List<NeteaseLyricLine> = emptyList(),
    val translated: List<NeteaseLyricLine> = emptyList(),
    val romanized: List<NeteaseLyricLine> = emptyList(),
    val offsetMs: Long = 0L,
) {
    val isEmpty: Boolean
        get() = original.isEmpty() && translated.isEmpty() && romanized.isEmpty()

    fun lineAt(positionMs: Long): Int {
        if (original.isEmpty()) return -1
        val target = positionMs + offsetMs
        var selected = -1
        original.forEachIndexed { index, line ->
            if (line.timeMs <= target) selected = index
        }
        return selected
    }
}

/**
 * 纯 Kotlin 的 LRC 解析器，避免把歌词时间轴规则耦合到 Android UI，便于 JVM 单测。
 * 支持网易云普通 LRC、翻译歌词和带小数秒的时间标签；无效行会被安全忽略。
 */
object NeteaseLyricsParser {
    private val tag = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val offsetTag = Regex("^\\[offset:([-+]?\\d+)\\]$", RegexOption.IGNORE_CASE)

    fun parseLrc(raw: String): List<NeteaseLyricLine> {
        if (raw.isBlank()) return emptyList()
        return buildList {
            raw.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || offsetTag.matches(line)) return@forEach
                val matches = tag.findAll(line).toList()
                if (matches.isEmpty()) return@forEach
                val text = line.substring(matches.last().range.last + 1).trim()
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                    if (seconds >= 60) return@forEach
                    val fraction = match.groupValues[3]
                    val fractionMs = when (fraction.length) {
                        1 -> (fraction.toLongOrNull() ?: 0L) * 100L
                        2 -> (fraction.toLongOrNull() ?: 0L) * 10L
                        3 -> fraction.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    add(NeteaseLyricLine(minutes * 60_000L + seconds * 1_000L + fractionMs, text))
                }
            }
        }.sortedWith(compareBy<NeteaseLyricLine> { it.timeMs }.thenBy { it.text })
            .distinctBy { it.timeMs to it.text }
    }

    fun offsetMs(raw: String): Long = raw.lineSequence()
        .map(String::trim)
        .mapNotNull { offsetTag.matchEntire(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        .firstOrNull() ?: 0L

    /** 网易云翻译行通常与原歌词同时间戳，按时间合并为展示文本。 */
    fun mergeTranslation(
        original: List<NeteaseLyricLine>,
        translation: List<NeteaseLyricLine>,
    ): List<NeteaseLyricLine> {
        if (translation.isEmpty()) return original
        val translationByTime = translation.associateBy { it.timeMs }
        return original.map { line ->
            val translated = translationByTime[line.timeMs]?.text.orEmpty()
            if (translated.isBlank() || translated == line.text) line
            else line.copy(text = "${line.text}\n$translated")
        }
    }
}

enum class NeteaseRepeatMode {
    OFF,
    ALL,
    ONE,
}
