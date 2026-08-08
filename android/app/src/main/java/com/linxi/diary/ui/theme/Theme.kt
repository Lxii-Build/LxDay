package com.linxi.diary.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp

/**
 * MilkGlass Compose 主题。
 * 端内跟随系统深浅色；设计规范 ADR-01 无深色，此处用语义反转扩展实现深色，
 * 保留玻璃层级（模糊/描边）只换色板。
 */
private val LightColors = lightColorScheme(
    primary = MilkGlassPrimary,
    onPrimary = MilkGlassText,
    background = MilkGlassBg,
    onBackground = MilkGlassText,
    surface = MilkGlassBg,
    onSurface = MilkGlassText,
    surfaceVariant = MilkGlassGlass2,
    onSurfaceVariant = MilkGlassTextSecondary,
    outline = MilkGlassBorder,
    error = MilkGlassErrorInk,
    onError = Color_White
)

private val DarkColors = darkColorScheme(
    primary = MilkGlassPrimary,
    onPrimary = DarkText,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkGlass,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    error = DarkErrorInk,
    onError = DarkBg
)

/** 圆角：sm14 / md22 / lg34 / xl44 */
private val MilkShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(34.dp),
    extraLarge = RoundedCornerShape(44.dp)
)

/** 标题衬线 600 / 0.06em；正文无衬线 0.04em / 行高1.7。Compose 用 sp 近似，em 转用 letterSpacing */
private val MilkTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, letterSpacing = 1.44.sp),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, letterSpacing = 1.2.sp),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, letterSpacing = 0.96.sp),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp,
        lineHeight = 27.sp, letterSpacing = 0.64.sp),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
        lineHeight = 24.sp, letterSpacing = 0.56.sp),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 12.sp,
        lineHeight = 20.sp, letterSpacing = 0.48.sp),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, letterSpacing = 0.56.sp),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 0.48.sp)
)

private val Color_White = androidx.compose.ui.graphics.Color.White

@Composable
fun MilkGlassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MilkTypography,
        shapes = MilkShapes,
        content = content
    )
}
