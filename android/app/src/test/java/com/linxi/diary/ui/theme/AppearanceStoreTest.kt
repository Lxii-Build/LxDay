package com.linxi.diary.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceStoreTest {

    @Test
    fun `空存储回落到迁移默认值`() {
        val store = AppearanceStore(FakeAppearancePrefs())
        val settings = store.load()
        assertEquals(ColorMode.SYSTEM, settings.colorMode)
        assertEquals(AppPaletteStyle.TONAL_SPOT, settings.paletteStyle)
        assertEquals(AppColorSpec.SPEC_2025, settings.colorSpec)
        assertTrue(settings.blurEnabled)
    }

    @Test
    fun `仅存在旧 color_mode 时迁移为完整外观状态`() {
        val prefs = FakeAppearancePrefs(ints = mutableMapOf("color_mode" to 3))
        val settings = AppearanceStore(prefs).load()
        assertEquals(ColorMode.DARK, settings.colorMode) // 旧 AMOLED=3 → 深色
        assertEquals(ColorSource.WALLPAPER, settings.colorSource)
    }

    @Test
    fun `保存后能完整读回`() {
        val prefs = FakeAppearancePrefs()
        val store = AppearanceStore(prefs)
        val settings = AppearanceSettings.migrate(0).copy(
            colorMode = ColorMode.DARK,
            colorSource = ColorSource.MANUAL,
            keyColorArgb = 0xFF123456.toInt(),
            paletteStyle = AppPaletteStyle.VIBRANT,
            colorSpec = AppColorSpec.SPEC_2021,
            blurEnabled = false,
            floatingBottomBarEnabled = false,
            pageScalePercent = 120,
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun `保存壁纸设置能读回并保留取色缓存`() {
        val prefs = FakeAppearancePrefs()
        val store = AppearanceStore(prefs)
        val settings = AppearanceSettings.migrate(0).copy(
            wallpaper = WallpaperSettings(
                processedPath = "/data/user/0/app/files/wallpaper/w.webp",
                outputWidthPx = 1080,
                outputHeightPx = 2400,
                blurRadius = 12f,
                lightScrimAlpha = 0.25f,
                darkScrimAlpha = 0.40f,
                cachedSeedArgb = 0xFF9C4668.toInt(),
            ),
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun `移除壁纸后读回为空`() {
        val prefs = FakeAppearancePrefs()
        val store = AppearanceStore(prefs)
        store.save(AppearanceSettings.migrate(0).copy(
            wallpaper = WallpaperSettings("/p.webp", 1080, 2400),
        ))
        store.save(store.load().copy(wallpaper = null))
        assertNull(store.load().wallpaper)
    }

    @Test
    fun `未知枚举字符串安全回落`() {
        val prefs = FakeAppearancePrefs(
            strings = mutableMapOf(
                "appearance_palette_style" to "不存在样式",
                "appearance_color_spec" to "垃圾",
                "appearance_color_source" to "?",
            ),
        )
        val settings = AppearanceStore(prefs).load()
        assertEquals(AppPaletteStyle.TONAL_SPOT, settings.paletteStyle)
        assertEquals(AppColorSpec.SPEC_2025, settings.colorSpec)
        assertEquals(ColorSource.WALLPAPER, settings.colorSource)
    }
}

private class FakeAppearancePrefs(
    val strings: MutableMap<String, String> = mutableMapOf(),
    val ints: MutableMap<String, Int> = mutableMapOf(),
    val bools: MutableMap<String, Boolean> = mutableMapOf(),
) : AppearancePrefs {
    override fun getString(key: String): String? = strings[key]
    override fun getInt(key: String, default: Int): Int = ints[key] ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = bools[key] ?: default
    override fun hasKey(key: String): Boolean =
        strings.containsKey(key) || ints.containsKey(key) || bools.containsKey(key)

    override fun edit(mutate: AppearancePrefsEditor.() -> Unit) {
        object : AppearancePrefsEditor {
            override fun putString(key: String, value: String?) {
                if (value == null) strings.remove(key) else strings[key] = value
            }
            override fun putInt(key: String, value: Int) { ints[key] = value }
            override fun putBoolean(key: String, value: Boolean) { bools[key] = value }
        }.mutate()
    }
}
