package com.linxi.diary.ui.screens

/** A rendered changelog fragment. Markdown syntax is removed before it reaches the UI. */
internal data class ChangelogSegment(
    val text: String,
    val bold: Boolean,
)

/**
 * Converts the small Markdown subset used by CHANGELOG.md into styled text
 * fragments. Headings and list items remain readable without leaking `#`,
 * `-`, or `**` into the customer-facing update dialog.
 */
internal fun formatChangelog(markdown: String): List<ChangelogSegment> {
    val result = mutableListOf<ChangelogSegment>()
    var renderedLine = false
    markdown.replace("\r\n", "\n").split('\n').forEach { rawLine ->
        var line = rawLine.trim()
        if (line.isEmpty() || line == "---") return@forEach
        if (line.isMarkdownReferenceDefinition()) return@forEach

        val heading = line.startsWith("#")
        if (heading) {
            line = line.trimStart('#').trim()
        } else {
            line = line.removeListMarker().trim()
        }
        if (line.startsWith(">")) {
            line = line.removePrefix(">").trim()
        }
        line = stripMarkdownLinks(line).replace("`", "")
        if (line.isEmpty()) return@forEach

        if (renderedLine) result += ChangelogSegment("\n", bold = false)
        appendInlineMarkdown(result, line, forceBold = heading)
        renderedLine = true
    }
    return result
}

private fun String.isMarkdownReferenceDefinition(): Boolean {
    if (!startsWith("[")) return false
    val close = indexOf("]:")
    return close > 1 && substring(close + 2).trim().isNotEmpty()
}

private fun String.removeListMarker(): String {
    if (length >= 2 && this[1] == ' ') {
        when (this[0]) {
            '-', '*', '+' -> return substring(2)
        }
    }
    var digits = 0
    while (digits < length && this[digits].isDigit()) digits++
    if (digits > 0 && digits + 1 < length &&
        (this[digits] == '.' || this[digits] == ')') && this[digits + 1] == ' '
    ) {
        return substring(digits + 2)
    }
    return this
}

private fun stripMarkdownLinks(value: String): String {
    val result = StringBuilder(value.length)
    var cursor = 0
    while (cursor < value.length) {
        val open = value.indexOf('[', cursor)
        if (open < 0) {
            result.append(value, cursor, value.length)
            break
        }
        val close = value.indexOf(']', open + 1)
        if (close < 0) {
            result.append(value, cursor, value.length)
            break
        }

        // Support both inline links `[text](url)` and reference links
        // `[text][id]`. Images use the same shape; the leading `!` is markup,
        // not customer-facing text, so drop it together with the URL.
        val destinationEnd = when {
            close + 1 < value.length && value[close + 1] == '(' -> {
                val urlClose = value.indexOf(')', close + 2)
                if (urlClose >= 0) urlClose else -1
            }
            close + 1 < value.length && value[close + 1] == '[' ->
                value.indexOf(']', close + 2)
            else -> -1
        }
        if (destinationEnd < 0) {
            result.append(value, cursor, value.length)
            break
        }
        val textStart = if (open > cursor && value[open - 1] == '!') open - 1 else open
        result.append(value, cursor, textStart)
        result.append(value, open + 1, close)
        cursor = destinationEnd + 1
    }
    return result.toString()
}

private fun appendInlineMarkdown(
    result: MutableList<ChangelogSegment>,
    value: String,
    forceBold: Boolean,
) {
    var cursor = 0
    while (cursor < value.length) {
        val markers = listOf("**", "__", "~~")
            .map { marker -> marker to value.indexOf(marker, cursor) }
            .filter { (_, index) -> index >= 0 }
        val next = markers.minByOrNull { (_, index) -> index }
        val open = next?.second ?: -1
        if (open < 0) {
            result += ChangelogSegment(stripInlineMarkers(value.substring(cursor)), forceBold)
            return
        }
        if (open > cursor) {
            result += ChangelogSegment(stripInlineMarkers(value.substring(cursor, open)), forceBold)
        }
        val marker = next?.first ?: return
        val close = value.indexOf(marker, open + 2)
        if (close < 0) {
            val trailing = stripInlineMarkers(value.substring(open + 2))
            result += ChangelogSegment(
                trailing,
                bold = marker != "~~" || forceBold,
            )
            return
        }
        if (close > open + 2) {
            result += ChangelogSegment(
                stripInlineMarkers(value.substring(open + 2, close)),
                bold = marker != "~~" || forceBold,
            )
        }
        cursor = close + 2
    }
}

private fun stripInlineMarkers(value: String): String =
    value.replace("~~", "").replace("**", "").replace("__", "")
