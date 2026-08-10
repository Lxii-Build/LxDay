package com.linxi.diary.ui.theme

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialKolorMappingTest {

    @Test
    fun `样式枚举逐一映射到 MaterialKolor`() {
        assertEquals(PaletteStyle.TonalSpot, MaterialKolorMapping.paletteStyle(AppPaletteStyle.TONAL_SPOT))
        assertEquals(PaletteStyle.Neutral, MaterialKolorMapping.paletteStyle(AppPaletteStyle.NEUTRAL))
        assertEquals(PaletteStyle.Vibrant, MaterialKolorMapping.paletteStyle(AppPaletteStyle.VIBRANT))
        assertEquals(PaletteStyle.Expressive, MaterialKolorMapping.paletteStyle(AppPaletteStyle.EXPRESSIVE))
        assertEquals(PaletteStyle.Rainbow, MaterialKolorMapping.paletteStyle(AppPaletteStyle.RAINBOW))
        assertEquals(PaletteStyle.FruitSalad, MaterialKolorMapping.paletteStyle(AppPaletteStyle.FRUIT_SALAD))
        assertEquals(PaletteStyle.Monochrome, MaterialKolorMapping.paletteStyle(AppPaletteStyle.MONOCHROME))
        assertEquals(PaletteStyle.Fidelity, MaterialKolorMapping.paletteStyle(AppPaletteStyle.FIDELITY))
        assertEquals(PaletteStyle.Content, MaterialKolorMapping.paletteStyle(AppPaletteStyle.CONTENT))
    }

    @Test
    fun `支持的样式保留 Spec2025`() {
        assertEquals(
            ColorSpec.SpecVersion.SPEC_2025,
            MaterialKolorMapping.specVersion(AppColorSpec.SPEC_2025, AppPaletteStyle.TONAL_SPOT),
        )
    }

    @Test
    fun `不支持的样式在映射时回落 Spec2021`() {
        assertEquals(
            ColorSpec.SpecVersion.SPEC_2021,
            MaterialKolorMapping.specVersion(AppColorSpec.SPEC_2025, AppPaletteStyle.RAINBOW),
        )
    }
}
