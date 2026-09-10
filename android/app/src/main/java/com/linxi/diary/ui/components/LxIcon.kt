package com.linxi.diary.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import top.yukonga.miuix.kmp.theme.LocalContentColor

/**
 * Stable vector icon primitive for Linxi controls.
 *
 * Miuix 0.9.3's basic Icon records its drawing into a graphics layer. On the
 * affected light-theme renderer that layer can leave an opaque rectangular
 * backing block behind an icon, especially inside a button. Foundation's
 * Image + vector painter draws only the vector pixels while retaining the
 * same ImageVector assets and Miuix content-color contract.
 */
@Composable
fun LxIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Image(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint ?: LocalContentColor.current),
    )
}
