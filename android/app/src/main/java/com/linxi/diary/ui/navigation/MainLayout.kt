package com.linxi.diary.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** KernelSU 外层 BottomBar Scaffold 为页面内容预留的底部间距。 */
val LocalMainBottomPadding = staticCompositionLocalOf<Dp> { 0.dp }

/**
 * The global player is a full-width 128dp music card above the main tab bar.
 * Reserve the card, its gap and the tab-bar breathing room so the last list
 * row or form control is never hidden behind the playback chrome.
 */
const val PLAYBACK_CHROME_RESERVED_DP = 160

/**
 * Space reserved by the global mini player.  Secondary pages do not use the
 * main tab Scaffold, so keeping this as a separate local prevents the player
 * from covering the last form row/photo/action on those pages.
 */
val LocalPlaybackBottomPadding = staticCompositionLocalOf<Dp> { 0.dp }
