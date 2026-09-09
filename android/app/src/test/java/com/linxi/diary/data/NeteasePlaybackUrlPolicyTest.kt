package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteasePlaybackUrlPolicyTest {
    @Test
    fun upgradesNeteaseHttpAudioToHttps() {
        assertEquals(
            "https://m801.music.126.net/song.mp3?token=abc",
            NeteasePlaybackUrlPolicy.normalize("http://m801.music.126.net/song.mp3?token=abc"),
        )
    }

    @Test
    fun keepsHttpsAddressStable() {
        val url = "https://m801.music.126.net/song.mp3?token=abc"
        assertEquals(url, NeteasePlaybackUrlPolicy.normalize(url))
    }

    @Test
    fun rejectsNonNeteaseOrCredentialBearingAddresses() {
        assertNull(NeteasePlaybackUrlPolicy.normalize("https://example.com/song.mp3"))
        assertNull(NeteasePlaybackUrlPolicy.normalize("https://user:pass@m801.music.126.net/song.mp3"))
        assertNull(NeteasePlaybackUrlPolicy.normalize("file:///data/local/tmp/song.mp3"))
    }
}
