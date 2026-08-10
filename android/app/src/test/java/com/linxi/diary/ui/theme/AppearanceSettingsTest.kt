package com.linxi.diary.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {

    @Test
    fun `Spec2025 在不支持的样式上回落到 Spec2021`() {
        // 仅 TonalSpot/Neutral/Vibrant/Expressive 支持 2025，其余回落。
        assertEquals(
            AppColorSpec.SPEC_2021,
            AppColorSpec.SPEC_2025.effectiveFor(AppPaletteStyle.RAINBOW),
        )
        assertEquals(
            AppColorSpec.SPEC_2025,
            AppColorSpec.SPEC_2025.effectiveFor(AppPaletteStyle.TONAL_SPOT),
        )
    }

    @Test
    fun `Spec2021 永不被升级`() {
        assertEquals(
            AppColorSpec.SPEC_2021,
            AppColorSpec.SPEC_2021.effectiveFor(AppPaletteStyle.EXPRESSIVE),
        )
    }

    @Test
    fun `样式是否触发 Spec 回落可判定`() {
        assertTrue(AppPaletteStyle.TONAL_SPOT.supportsSpec2025)
        assertTrue(AppPaletteStyle.EXPRESSIVE.supportsSpec2025)
        assertFalse(AppPaletteStyle.MONOCHROME.supportsSpec2025)
        assertFalse(AppPaletteStyle.FIDELITY.supportsSpec2025)
    }

    @Test
    fun `旧 AMOLED 主题值迁移为深色`() {
        val settings = AppearanceSettings.migrate(legacyColorMode = 3)
        assertEquals(ColorMode.DARK, settings.colorMode)
    }

    @Test
    fun `旧主题模式整数完整映射`() {
        assertEquals(ColorMode.SYSTEM, AppearanceSettings.migrate(legacyColorMode = 0).colorMode)
        assertEquals(ColorMode.LIGHT, AppearanceSettings.migrate(legacyColorMode = 1).colorMode)
        assertEquals(ColorMode.DARK, AppearanceSettings.migrate(legacyColorMode = 2).colorMode)
    }

    @Test
    fun `迁移默认开启模糊悬浮栏玻璃与预测返回`() {
        val settings = AppearanceSettings.migrate(legacyColorMode = 0)
        assertTrue(settings.blurEnabled)
        assertTrue(settings.floatingBottomBarEnabled)
        assertTrue(settings.floatingGlassEnabled)
        assertTrue(settings.predictiveBackEnabled)
        assertEquals(100, settings.pageScalePercent)
        assertNull(settings.wallpaper)
    }

    @Test
    fun `默认颜色来源为壁纸自动且无手动种子`() {
        val settings = AppearanceSettings.migrate(legacyColorMode = 0)
        assertEquals(ColorSource.WALLPAPER, settings.colorSource)
        assertNull(settings.keyColorArgb)
    }

    @Test
    fun `未知样式与规格字符串安全回落`() {
        assertEquals(AppPaletteStyle.TONAL_SPOT, AppPaletteStyle.fromName("不存在"))
        assertEquals(AppColorSpec.SPEC_2025, AppColorSpec.fromName(null))
        assertEquals(AppColorSpec.SPEC_2021, AppColorSpec.fromName("SPEC_2021"))
    }

    @Test
    fun `关闭悬浮栏时玻璃被判定为不可用`() {
        val settings = AppearanceSettings.migrate(0).copy(floatingBottomBarEnabled = false)
        // 悬浮栏关闭 → 使用普通导航栏，玻璃开关无意义。
        assertFalse(settings.glassEffective)
    }

    @Test
    fun `页面缩放限制在合理区间`() {
        assertEquals(50, AppearanceSettings.migrate(0).copy(pageScalePercent = 10).clampedScalePercent())
        assertEquals(150, AppearanceSettings.migrate(0).copy(pageScalePercent = 999).clampedScalePercent())
        assertEquals(100, AppearanceSettings.migrate(0).clampedScalePercent())
    }
}
