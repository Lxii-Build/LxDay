package com.linxi.diary.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogFormatterTest {
    @Test
    fun removesMarkdownMarkersAndKeepsMeaningfulEmphasis() {
        val segments = formatChangelog("### 修复\n- **更新日志**改为读取根文件\n\n---")
        val text = segments.joinToString(separator = "") { it.text }

        assertFalse(text.contains("###"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("- "))
        assertTrue(segments.any { it.text == "修复" && it.bold })
        assertTrue(segments.any { it.text == "更新日志" && it.bold })
    }

    @Test
    fun removesOrderedListAndAlternateMarkdownMarkers() {
        val segments = formatChangelog("1. **首项**\n2) __次项__ ~~旧字~~ 与 **~~醒目~~**")
        val text = segments.joinToString(separator = "") { it.text }

        assertFalse(text.contains("1."))
        assertFalse(text.contains("2)"))
        assertFalse(text.contains("__"))
        assertFalse(text.contains("~~"))
        assertTrue(segments.any { it.text == "首项" && it.bold })
        assertTrue(segments.any { it.text == "次项" && it.bold })
        assertTrue(text.contains("旧字"))
        assertTrue(segments.any { it.text == "醒目" && it.bold })
    }

	@Test
	fun removesMarkdownReferenceDefinitions() {
		val segments = formatChangelog("### 版本\n- **修复**\n\n[版本]: https://example.invalid/version")
		val text = segments.joinToString(separator = "") { it.text }

		assertTrue(text.contains("修复"))
		assertFalse(text.contains("https://example.invalid"))
		assertFalse(text.contains("[版本]"))
	}

	@Test
	fun removesReferenceLinkAndImageSyntax() {
		val segments = formatChangelog(
			"- [查看详情][history]\n\n![截图](https://example.invalid/image)\n\n" +
				"[history]: https://example.invalid/history",
		)
		val text = segments.joinToString(separator = "") { it.text }

		assertTrue(text.contains("查看详情"))
		assertTrue(text.contains("截图"))
		assertFalse(text.contains("[history]"))
		assertFalse(text.contains("!["))
		assertFalse(text.contains("https://example.invalid"))
	}
}
