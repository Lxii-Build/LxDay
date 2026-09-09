package com.linxi.diary.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material tokens shared by every first-party surface.
 *
 * These are deliberately independent from the generated Material colour scheme:
 * the brand blue remains the semantic action colour, while the canvas and
 * surfaces stay neutral in both themes.  Keeping the values here prevents each
 * screen from inventing its own grey/alpha combination.
 */
@Immutable
data class LxSurfaceTokens(
    val canvas: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val textSecondary: Color,
    val line: Color,
    val controlBoundary: Color,
    val link: Color,
    val raisedShadow: Color,
    val raisedHighlight: Color,
    val insetShadow: Color,
    val insetHighlight: Color,
)

val LxLightSurfaceTokens = LxSurfaceTokens(
    canvas = Color(0xFFEDF0F4),
    surface = Color(0xFFEDF0F4),
    elevated = Color(0xFFF7F9FC),
    text = Color(0xFF1F2937),
    textSecondary = Color(0xFF566273),
    line = Color(0xFFD0D7E2),
    controlBoundary = Color(0xFF788598),
    link = Color(0xFF185BC4),
    raisedShadow = Color(0x6BA3B1C4),
    raisedHighlight = Color(0xDBFFFFFF),
    insetShadow = Color(0x7AA3B1C4),
    insetHighlight = Color(0xE0FFFFFF),
)

val LxDarkSurfaceTokens = LxSurfaceTokens(
    canvas = Color(0xFF202328),
    surface = Color(0xFF272B31),
    elevated = Color(0xFF32373F),
    text = Color(0xFFF1F3F5),
    textSecondary = Color(0xFFB8C0CB),
    line = Color(0xFF424851),
    controlBoundary = Color(0xFF8893A0),
    link = Color(0xFF9CBFFC),
    raisedShadow = Color(0x47000000),
    raisedHighlight = Color(0x0AFFFFFF),
    insetShadow = Color(0x52000000),
    insetHighlight = Color(0x09FFFFFF),
)

val LocalLxSurfaceTokens = staticCompositionLocalOf { LxLightSurfaceTokens }
