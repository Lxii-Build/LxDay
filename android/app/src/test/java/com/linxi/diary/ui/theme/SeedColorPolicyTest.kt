package com.linxi.diary.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedColorPolicyTest {

    @Test
    fun `过暗候选色被过滤`() {
        assertFalse(SeedColorPolicy.isAcceptable(0xFF0A0A0A.toInt()))
    }

    @Test
    fun `过亮候选色被过滤`() {
        assertFalse(SeedColorPolicy.isAcceptable(0xFFFAFAFA.toInt()))
    }

    @Test
    fun `低饱和灰色被过滤`() {
        assertFalse(SeedColorPolicy.isAcceptable(0xFF808080.toInt()))
    }

    @Test
    fun `鲜明中间色被接受`() {
        assertTrue(SeedColorPolicy.isAcceptable(0xFF9C4668.toInt())) // 林曦品牌粉
        assertTrue(SeedColorPolicy.isAcceptable(0xFF3F7FD0.toInt()))
    }

    @Test
    fun `候选列表全部不合格时回落到默认种子`() {
        val seed = SeedColorPolicy.pickSeed(
            candidates = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F7F7F.toInt()),
            fallback = 0xFF9C4668.toInt(),
        )
        assertEquals(0xFF9C4668.toInt(), seed)
    }

    @Test
    fun `优先选择第一个合格候选`() {
        val seed = SeedColorPolicy.pickSeed(
            candidates = listOf(0xFF101010.toInt(), 0xFF9C4668.toInt(), 0xFF3F7FD0.toInt()),
            fallback = 0xFF000000.toInt(),
        )
        assertEquals(0xFF9C4668.toInt(), seed)
    }

    @Test
    fun `手动种子色优先于壁纸自动取色`() {
        assertEquals(
            0xFF123456.toInt(),
            SeedColorPolicy.resolveSeed(
                source = ColorSource.MANUAL,
                manualArgb = 0xFF123456.toInt(),
                wallpaperSeed = 0xFF9C4668.toInt(),
                fallback = 0xFF000000.toInt(),
            ),
        )
    }

    @Test
    fun `壁纸来源使用壁纸种子`() {
        assertEquals(
            0xFF9C4668.toInt(),
            SeedColorPolicy.resolveSeed(
                source = ColorSource.WALLPAPER,
                manualArgb = 0xFF123456.toInt(),
                wallpaperSeed = 0xFF9C4668.toInt(),
                fallback = 0xFF000000.toInt(),
            ),
        )
    }

    @Test
    fun `壁纸来源但无壁纸种子时回落`() {
        assertEquals(
            0xFF000000.toInt(),
            SeedColorPolicy.resolveSeed(
                source = ColorSource.WALLPAPER,
                manualArgb = null,
                wallpaperSeed = null,
                fallback = 0xFF000000.toInt(),
            ),
        )
    }

    @Test
    fun `系统动态色来源不使用壁纸或手动种子`() {
        assertNull(
            SeedColorPolicy.resolveSeed(
                source = ColorSource.SYSTEM,
                manualArgb = 0xFF123456.toInt(),
                wallpaperSeed = 0xFF9C4668.toInt(),
                fallback = 0xFF000000.toInt(),
            ),
        )
    }
}
