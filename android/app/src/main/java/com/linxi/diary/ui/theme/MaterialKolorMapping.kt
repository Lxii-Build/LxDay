package com.linxi.diary.ui.theme

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/** 将应用外观枚举映射到 MaterialKolor 库枚举，隔离第三方 API。 */
object MaterialKolorMapping {

    fun paletteStyle(style: AppPaletteStyle): PaletteStyle = when (style) {
        AppPaletteStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
        AppPaletteStyle.NEUTRAL -> PaletteStyle.Neutral
        AppPaletteStyle.VIBRANT -> PaletteStyle.Vibrant
        AppPaletteStyle.EXPRESSIVE -> PaletteStyle.Expressive
        AppPaletteStyle.RAINBOW -> PaletteStyle.Rainbow
        AppPaletteStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
        AppPaletteStyle.MONOCHROME -> PaletteStyle.Monochrome
        AppPaletteStyle.FIDELITY -> PaletteStyle.Fidelity
        AppPaletteStyle.CONTENT -> PaletteStyle.Content
    }

    /** 结合样式做 2025→2021 回落后再映射到库枚举。 */
    fun specVersion(spec: AppColorSpec, style: AppPaletteStyle): ColorSpec.SpecVersion =
        when (spec.effectiveFor(style)) {
            AppColorSpec.SPEC_2021 -> ColorSpec.SpecVersion.SPEC_2021
            AppColorSpec.SPEC_2025 -> ColorSpec.SpecVersion.SPEC_2025
        }
}
