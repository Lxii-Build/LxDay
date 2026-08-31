package com.linxi.diary.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientRuntimeConfigTest {
    private val defaultBytes = ImagePrepPolicy.MAX_UPLOAD_BYTES

    @After
    fun restoreDefault() {
        ClientRuntimeConfig.apply(JSONObject())
    }

    @Test
    fun `uses server photo limit`() {
        ClientRuntimeConfig.apply(
            JSONObject("{\"upload\":{\"photo_max_bytes\":52428800}}"),
        )
        assertEquals(50L * 1024 * 1024, ClientRuntimeConfig.photoMaxBytes)
    }

    @Test
    fun `clamps malformed server photo limit`() {
        ClientRuntimeConfig.apply(
            JSONObject("{\"upload\":{\"photo_max_bytes\":999999999999}}"),
        )
        assertEquals(100L * 1024 * 1024, ClientRuntimeConfig.photoMaxBytes)

        ClientRuntimeConfig.apply(
            JSONObject("{\"upload\":{\"photo_max_bytes\":1}}"),
        )
        assertEquals(1L * 1024 * 1024, ClientRuntimeConfig.photoMaxBytes)
    }

    @Test
    fun `falls back to built in default`() {
        ClientRuntimeConfig.apply(JSONObject())
        assertEquals(defaultBytes, ClientRuntimeConfig.photoMaxBytes)
    }

    @Test
    fun `applies server feature switches`() {
        ClientRuntimeConfig.apply(
            JSONObject(
                "{\"features\":{\"album\":false,\"photo_social\":false,\"on_this_day\":false}}",
            ),
        )
        assertEquals(false, ClientRuntimeConfig.albumEnabled)
        assertEquals(false, ClientRuntimeConfig.photoSocialEnabled)
        assertEquals(false, ClientRuntimeConfig.onThisDayEnabled)
    }
}
