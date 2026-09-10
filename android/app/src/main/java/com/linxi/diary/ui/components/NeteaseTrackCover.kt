package com.linxi.diary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.ui.theme.LocalLxSurfaceTokens
import com.linxi.diary.ui.components.LxIcon as Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared cover treatment for search rows, the queue and the global player. */
@Composable
fun NeteaseTrackCover(
    track: NeteaseTrack,
    modifier: Modifier = Modifier,
    description: String = "歌曲封面",
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val context = LocalContext.current
    val tokens = LocalLxSurfaceTokens.current
    Box(
        modifier = modifier
            .clip(shape)
            // Keep a visible material behind slow/failed remote images.
            .background(tokens.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (track.coverUrl.isBlank()) {
            Icon(MiuixIcons.Music, contentDescription = description, tint = MiuixTheme.colorScheme.primary)
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(track.coverUrl)
                    .build(),
                imageLoader = AppImageLoader.get(context),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
