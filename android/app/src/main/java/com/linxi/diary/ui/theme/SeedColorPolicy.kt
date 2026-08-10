package com.linxi.diary.ui.theme

/** 壁纸取色种子选择的纯策略：过滤过暗/过亮/低饱和灰，选种子并解析来源优先级。 */
object SeedColorPolicy {
    private const val MIN_LUMINANCE = 0.12f
    private const val MAX_LUMINANCE = 0.90f
    private const val MIN_SATURATION = 0.15f

    /** 候选色是否适合作为种子：排除过暗、过亮与低区分度灰色。 */
    fun isAcceptable(argb: Int): Boolean {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        if (luminance < MIN_LUMINANCE || luminance > MAX_LUMINANCE) return false
        val lightness = (maxC + minC) / 2f
        val delta = maxC - minC
        val saturation = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * lightness - 1f))
        return saturation >= MIN_SATURATION
    }

    /** 从候选中选第一个合格色；全部不合格回落到 fallback。 */
    fun pickSeed(candidates: List<Int>, fallback: Int): Int =
        candidates.firstOrNull { isAcceptable(it) } ?: fallback

    /**
     * 解析最终种子色：
     * - MANUAL：手动种子优先，无则 fallback；
     * - WALLPAPER：壁纸种子，无则 fallback；
     * - SYSTEM：不使用种子（由系统动态色驱动），返回 null。
     */
    fun resolveSeed(source: ColorSource, manualArgb: Int?, wallpaperSeed: Int?, fallback: Int): Int? =
        when (source) {
            ColorSource.MANUAL -> manualArgb ?: fallback
            ColorSource.WALLPAPER -> wallpaperSeed ?: fallback
            ColorSource.SYSTEM -> null
        }
}
