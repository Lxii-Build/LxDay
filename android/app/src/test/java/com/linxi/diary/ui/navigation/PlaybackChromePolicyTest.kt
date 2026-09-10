package com.linxi.diary.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackChromePolicyTest {
    @Test
    fun `mini player is scoped to the home shell`() {
        assertTrue(targetShowsPlaybackChrome(Screen.Main))
        assertFalse(targetShowsPlaybackChrome(Screen.Main, mainPage = 1))
        assertFalse(targetShowsPlaybackChrome(Screen.Main, mainPage = 2))
        assertFalse(targetShowsPlaybackChrome(Screen.Main, mainPage = 3))
    }

    @Test
    fun `secondary destinations keep their bottom edge free`() {
        listOf(
            Screen.Music,
            Screen.Player,
            Screen.Lyrics,
            Screen.MusicSettings,
            Screen.Appearance,
            Screen.History,
            Screen.PhotoViewer,
            Screen.PhotoPicker,
            Screen.AlbumDetail,
            Screen.Live2D,
        ).forEach { screen ->
            assertFalse("unexpected player chrome on $screen", targetShowsPlaybackChrome(screen))
        }
    }
}
