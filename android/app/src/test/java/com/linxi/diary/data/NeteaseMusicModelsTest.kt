package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseMusicModelsTest {
    @Test
    fun parsesMultipleLrcTimestampsAndSorts() {
        val lines = NeteaseLyricsParser.parseLrc(
            "[00:03.50]second\n[00:01.2][00:02.00]first\n[offset:120]",
        )
        assertEquals(listOf(1_200L, 2_000L, 3_500L), lines.map { it.timeMs })
        assertEquals("first", lines[0].text)
        assertEquals("second", lines[2].text)
    }

    @Test
    fun ignoresInvalidSecondsAndKeepsTranslationAligned() {
        val original = NeteaseLyricsParser.parseLrc("[00:01]hello\n[00:61]bad")
        val translation = NeteaseLyricsParser.parseLrc("[00:01]你好")
        val merged = NeteaseLyricsParser.mergeTranslation(original, translation)
        assertEquals(1, merged.size)
        assertTrue(merged.single().text.contains("hello"))
        assertTrue(merged.single().text.contains("你好"))
    }

    @Test
    fun currentLineUsesLastTimestampNotFutureLine() {
        val lyrics = NeteaseLyrics(
            original = listOf(
                NeteaseLyricLine(0, "a"),
                NeteaseLyricLine(2_000, "b"),
            ),
        )
        assertEquals(0, lyrics.lineAt(1_999))
        assertEquals(1, lyrics.lineAt(2_000))
    }
}
