package com.linxi.diary.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** KernelSU 外层 BottomBar Scaffold 为页面内容预留的底部间距。 */
val LocalMainBottomPadding = staticCompositionLocalOf<Dp> { 0.dp }

/**
 * Space reserved by the global mini player.  Secondary pages do not use the
 * main tab Scaffold, so keeping this as a separate local prevents the player
 * from covering the last form row/photo/action on those pages.
 */
val LocalPlaybackBottomPadding = staticCompositionLocalOf<Dp> { 0.dp }
