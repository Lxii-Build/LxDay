package com.linxi.diary.ui.theme

/** 外观偏好读写抽象，便于 JVM 单测替换真实 SharedPreferences。 */
interface AppearancePrefs {
    fun getString(key: String): String?
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun hasKey(key: String): Boolean
    fun edit(mutate: AppearancePrefsEditor.() -> Unit)
}

interface AppearancePrefsEditor {
    fun putString(key: String, value: String?)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
}

/** 单一外观状态的持久化：首启从旧 color_mode 迁移，其后读写完整状态。 */
class AppearanceStore(private val prefs: AppearancePrefs) {

    fun load(): AppearanceSettings {
        // 未写过新外观状态：从旧 color_mode 迁移。
        if (!prefs.hasKey(KEY_MIGRATED)) {
            return AppearanceSettings.migrate(prefs.getInt(LEGACY_COLOR_MODE, 0))
        }
        val defaults = AppearanceSettings.migrate(prefs.getInt(LEGACY_COLOR_MODE, 0))
        val wallpaperPath = prefs.getString(KEY_WALLPAPER_PATH)
        val wallpaper = wallpaperPath?.let {
            WallpaperSettings(
                processedPath = it,
                outputWidthPx = prefs.getInt(KEY_WP_WIDTH, 0),
                outputHeightPx = prefs.getInt(KEY_WP_HEIGHT, 0),
                blurRadius = prefs.getInt(KEY_WP_BLUR, 0) / 100f,
                lightScrimAlpha = prefs.getInt(KEY_WP_LIGHT_SCRIM, 20) / 100f,
                darkScrimAlpha = prefs.getInt(KEY_WP_DARK_SCRIM, 35) / 100f,
                cachedSeedArgb = prefs.getInt(KEY_WP_SEED, NO_COLOR).takeIf { s -> s != NO_COLOR },
            )
        }
        return AppearanceSettings(
            colorMode = ColorMode.fromValue(prefs.getInt(LEGACY_COLOR_MODE, 0)),
            monetEnabled = prefs.getBoolean(KEY_MONET, defaults.monetEnabled),
            colorSource = ColorSource.fromName(prefs.getString(KEY_COLOR_SOURCE)),
            keyColorArgb = prefs.getInt(KEY_KEY_COLOR, NO_COLOR).takeIf { it != NO_COLOR },
            paletteStyle = AppPaletteStyle.fromName(prefs.getString(KEY_PALETTE_STYLE)),
            colorSpec = AppColorSpec.fromName(prefs.getString(KEY_COLOR_SPEC)),
            blurEnabled = prefs.getBoolean(KEY_BLUR, defaults.blurEnabled),
            floatingBottomBarEnabled = prefs.getBoolean(KEY_FLOATING_BAR, defaults.floatingBottomBarEnabled),
            floatingGlassEnabled = prefs.getBoolean(KEY_FLOATING_GLASS, defaults.floatingGlassEnabled),
            predictiveBackEnabled = prefs.getBoolean(KEY_PREDICTIVE_BACK, defaults.predictiveBackEnabled),
            pageScalePercent = prefs.getInt(KEY_PAGE_SCALE, defaults.pageScalePercent),
            wallpaper = wallpaper,
        )
    }

    fun save(settings: AppearanceSettings) {
        prefs.edit {
            putBoolean(KEY_MIGRATED, true)
            putInt(LEGACY_COLOR_MODE, settings.colorMode.value)
            putBoolean(KEY_MONET, settings.monetEnabled)
            putString(KEY_COLOR_SOURCE, settings.colorSource.name)
            putInt(KEY_KEY_COLOR, settings.keyColorArgb ?: NO_COLOR)
            putString(KEY_PALETTE_STYLE, settings.paletteStyle.name)
            putString(KEY_COLOR_SPEC, settings.colorSpec.name)
            putBoolean(KEY_BLUR, settings.blurEnabled)
            putBoolean(KEY_FLOATING_BAR, settings.floatingBottomBarEnabled)
            putBoolean(KEY_FLOATING_GLASS, settings.floatingGlassEnabled)
            putBoolean(KEY_PREDICTIVE_BACK, settings.predictiveBackEnabled)
            putInt(KEY_PAGE_SCALE, settings.pageScalePercent)
            val wp = settings.wallpaper
            putString(KEY_WALLPAPER_PATH, wp?.processedPath)
            if (wp != null) {
                putInt(KEY_WP_WIDTH, wp.outputWidthPx)
                putInt(KEY_WP_HEIGHT, wp.outputHeightPx)
                putInt(KEY_WP_BLUR, (wp.blurRadius * 100).toInt())
                putInt(KEY_WP_LIGHT_SCRIM, (wp.lightScrimAlpha * 100).toInt())
                putInt(KEY_WP_DARK_SCRIM, (wp.darkScrimAlpha * 100).toInt())
                putInt(KEY_WP_SEED, wp.cachedSeedArgb ?: NO_COLOR)
            }
        }
    }

    private companion object {
        const val NO_COLOR = 1 // 无效 ARGB 哨兵（真实 ARGB alpha 不为 0）
        const val LEGACY_COLOR_MODE = "color_mode"
        const val KEY_MIGRATED = "appearance_migrated"
        const val KEY_MONET = "appearance_monet"
        const val KEY_COLOR_SOURCE = "appearance_color_source"
        const val KEY_KEY_COLOR = "appearance_key_color"
        const val KEY_PALETTE_STYLE = "appearance_palette_style"
        const val KEY_COLOR_SPEC = "appearance_color_spec"
        const val KEY_BLUR = "appearance_blur"
        const val KEY_FLOATING_BAR = "appearance_floating_bar"
        const val KEY_FLOATING_GLASS = "appearance_floating_glass"
        const val KEY_PREDICTIVE_BACK = "appearance_predictive_back"
        const val KEY_PAGE_SCALE = "appearance_page_scale"
        const val KEY_WALLPAPER_PATH = "appearance_wallpaper_path"
        const val KEY_WP_WIDTH = "appearance_wp_width"
        const val KEY_WP_HEIGHT = "appearance_wp_height"
        const val KEY_WP_BLUR = "appearance_wp_blur"
        const val KEY_WP_LIGHT_SCRIM = "appearance_wp_light_scrim"
        const val KEY_WP_DARK_SCRIM = "appearance_wp_dark_scrim"
        const val KEY_WP_SEED = "appearance_wp_seed"
    }
}
