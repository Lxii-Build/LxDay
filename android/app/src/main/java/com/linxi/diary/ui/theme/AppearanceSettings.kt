package com.linxi.diary.ui.theme

/** 调色板样式，映射 MaterialKolor PaletteStyle；仅部分样式支持 Spec 2025。 */
enum class AppPaletteStyle {
    TONAL_SPOT, NEUTRAL, VIBRANT, EXPRESSIVE, RAINBOW, FRUIT_SALAD, MONOCHROME, FIDELITY, CONTENT;

    /** 与 KernelSU 对齐：仅这四种样式支持 Spec 2025。 */
    val supportsSpec2025: Boolean
        get() = this == TONAL_SPOT || this == NEUTRAL || this == VIBRANT || this == EXPRESSIVE

    companion object {
        fun fromName(name: String?): AppPaletteStyle =
            entries.firstOrNull { it.name == name } ?: TONAL_SPOT
    }
}

/** 动态取色规格版本。 */
enum class AppColorSpec {
    SPEC_2021, SPEC_2025;

    /** Spec 2025 在不支持的样式上回落到 2021；2021 永不升级。 */
    fun effectiveFor(style: AppPaletteStyle): AppColorSpec =
        if (this == SPEC_2025 && !style.supportsSpec2025) SPEC_2021 else this

    companion object {
        fun fromName(name: String?): AppColorSpec =
            entries.firstOrNull { it.name == name } ?: SPEC_2025
    }
}

/** 主题色来源。 */
enum class ColorSource {
    WALLPAPER, // 壁纸自动取色
    SYSTEM,    // 系统动态色（Monet）
    MANUAL;    // 手动种子色

    companion object {
        fun fromName(name: String?): ColorSource =
            entries.firstOrNull { it.name == name } ?: WALLPAPER
    }
}

/** 壁纸设置：源、处理文件、裁剪缩放平移、输出尺寸、模糊与遮罩、取色缓存。 */
data class WallpaperSettings(
    val processedPath: String,
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val blurRadius: Float = 0f,
    val lightScrimAlpha: Float = 0.20f,
    val darkScrimAlpha: Float = 0.35f,
    val cachedSeedArgb: Int? = null,
)

/** 单一外观状态，UI 只观察此对象。 */
data class AppearanceSettings(
    val colorMode: ColorMode,
    val monetEnabled: Boolean,
    val colorSource: ColorSource,
    val keyColorArgb: Int?,
    val paletteStyle: AppPaletteStyle,
    val colorSpec: AppColorSpec,
    val blurEnabled: Boolean,
    val floatingBottomBarEnabled: Boolean,
    val floatingGlassEnabled: Boolean,
    val predictiveBackEnabled: Boolean,
    val pageScalePercent: Int,
    val wallpaper: WallpaperSettings?,
) {
    /** 悬浮栏关闭时使用普通导航栏，玻璃开关无意义。 */
    val glassEffective: Boolean
        get() = floatingBottomBarEnabled && floatingGlassEnabled

    fun clampedScalePercent(): Int = pageScalePercent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)

    /** 供渲染使用的最终规格：结合样式做回落。 */
    fun effectiveColorSpec(): AppColorSpec = colorSpec.effectiveFor(paletteStyle)

    companion object {
        const val MIN_SCALE_PERCENT = 50
        const val MAX_SCALE_PERCENT = 150

        /** 由旧偏好迁移；仅有 color_mode，其余取默认值。 */
        fun migrate(legacyColorMode: Int): AppearanceSettings = AppearanceSettings(
            colorMode = ColorMode.fromValue(legacyColorMode),
            monetEnabled = true,
            colorSource = ColorSource.WALLPAPER,
            keyColorArgb = null,
            paletteStyle = AppPaletteStyle.TONAL_SPOT,
            colorSpec = AppColorSpec.SPEC_2025,
            blurEnabled = true,
            floatingBottomBarEnabled = true,
            floatingGlassEnabled = true,
            predictiveBackEnabled = true,
            pageScalePercent = 100,
            wallpaper = null,
        )
    }
}
