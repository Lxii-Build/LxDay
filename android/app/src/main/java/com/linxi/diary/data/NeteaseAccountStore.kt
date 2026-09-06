package com.linxi.diary.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 网易云登录状态只保存在本机。一起听服务端永远不会收到 MUSIC_U 或密码。
 *
 * NeriPlayer 的边界也是这样：登录发生在设备上，房间只交换歌曲 ID 和播放状态。
 * Cookie 是第三方账号凭据，使用 Android Keystore + AES/GCM 加密，且不写入日志、
 * 诊断包或应用自己的服务端。
 */
object NeteaseAccountStore {
    private const val PREFS = "linxi_netease_auth"
    private const val PAYLOAD_KEY = "cookie_payload_v1"
    private const val KEY_ALIAS = "linxi_netease_cookie_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val SEP = "."
    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    private var initialized = false
    private var cookieCache: Map<String, String> = emptyMap()

    fun init(context: Context) {
        synchronized(lock) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            cookieCache = readLocked()
            initialized = true
        }
    }

    fun cookies(): Map<String, String> = synchronized(lock) {
        ensureInitialized()
        cookieCache.toMap()
    }

    fun isLoggedIn(): Boolean = cookies()["MUSIC_U"].orEmpty().isNotBlank()

    /** 保存 WebView/二维码登录得到的 Cookie，过滤掉不能放进请求头的值。 */
    fun saveCookies(raw: Map<String, String>): Boolean = synchronized(lock) {
        ensureInitialized()
        val sanitized = sanitize(raw)
        if (sanitized["MUSIC_U"].isNullOrBlank()) return@synchronized false
        val payload = JSONObject().apply {
            sanitized.forEach { (key, value) -> put(key, value) }
        }.toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android 16's AndroidKeyStore rejects a caller-supplied GCM IV for this
        // key configuration. Let Keystore generate the nonce, then persist the
        // generated value alongside the ciphertext for the decrypt operation.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = requireNotNull(cipher.iv) { "Keystore did not generate a GCM IV" }
        val body = cipher.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(iv, Base64.NO_WRAP) + SEP +
            Base64.encodeToString(body, Base64.NO_WRAP)
        prefs.edit().putString(PAYLOAD_KEY, encoded).apply()
        cookieCache = sanitized
        true
    }

    fun clear() = synchronized(lock) {
        ensureInitialized()
        prefs.edit().remove(PAYLOAD_KEY).apply()
        cookieCache = emptyMap()
    }

    private fun sanitize(raw: Map<String, String>): Map<String, String> {
        val keyPattern = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        return linkedMapOf<String, String>().apply {
            raw.forEach { (rawKey, rawValue) ->
                val key = rawKey.trim()
                val value = rawValue.trim()
                if (
                    key.isNotBlank() && keyPattern.matches(key) && value.isNotBlank() &&
                    value.length <= 8192 && value.none(Char::isISOControl) && ';' !in value
                ) {
                    put(key, value)
                }
            }
            putIfAbsent("os", "pc")
            putIfAbsent("appver", "8.10.35")
        }
    }

    private fun readLocked(): Map<String, String> {
        val encoded = prefs.getString(PAYLOAD_KEY, null).orEmpty()
        if (encoded.isBlank()) return emptyMap()
        return runCatching {
            val parts = encoded.split(SEP, limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(body), StandardCharsets.UTF_8))
            val values = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                values[key] = json.optString(key, "")
            }
            sanitize(values).takeIf { it["MUSIC_U"].orEmpty().isNotBlank() }.orEmpty()
        }.getOrElse {
            // Keystore 恢复/应用数据损坏时删除不可用凭据，不降级到明文。
            prefs.edit().remove(PAYLOAD_KEY).apply()
            emptyMap()
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ensureInitialized() {
        check(initialized) { "NeteaseAccountStore.init must be called from Application" }
    }
}
