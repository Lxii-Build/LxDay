package com.linxi.diary.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SharedPreferences 封装 */
object UserPrefs {
    private const val PREF = "linxi_prefs"
    private const val TOKEN_KEY = "token_encrypted"
    private const val LEGACY_TOKEN_KEY = "token"
    private const val TOKEN_KEY_ALIAS = "linxi_access_token_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TOKEN_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val TOKEN_SEPARATOR = "."
    private lateinit var sp: SharedPreferences
    private val tokenLock = Any()
    private var tokenCacheInitialized = false
    private var cachedToken: String? = null

    val profilePreferences: com.linxi.diary.data.ProfilePreferences =
        object : com.linxi.diary.data.ProfilePreferences {
            override val profileCacheJson: String?
                get() = sp.getString("couple_profile", null)
            override val pairId: Long
                get() = UserPrefs.pairId
            override val partnerName: String
                get() = UserPrefs.partnerName

            override fun commit(profileCacheJson: String?, pairId: Long, partnerName: String) {
                sp.edit()
                    .putString("couple_profile", profileCacheJson)
                    .putLong("pair_id", pairId)
                    .putString("partner_name", partnerName)
                    .apply()
            }
        }

    fun init(context: Context) {
        synchronized(tokenLock) {
            sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            // Application 重建后重新从 Keystore 解密一次；同一进程内的请求只读内存缓存。
            cachedToken = null
            tokenCacheInitialized = false
        }
    }

    var token: String?
        get() = synchronized(tokenLock) {
            if (!tokenCacheInitialized) {
                cachedToken = readToken()
                tokenCacheInitialized = true
            }
            cachedToken
        }
        set(v) = synchronized(tokenLock) {
            if (v.isNullOrBlank()) {
                clearToken()
                cachedToken = null
            } else if (!writeEncryptedToken(v)) {
                // Never fall back to plaintext if the platform keystore is unavailable.
                clearToken()
                cachedToken = null
            } else {
                cachedToken = v
            }
            tokenCacheInitialized = true
        }

    private fun readToken(): String? {
        val encrypted = sp.getString(TOKEN_KEY, null)
        if (!encrypted.isNullOrBlank()) {
            runCatching { decryptToken(encrypted) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            // A restored/corrupted ciphertext must not keep causing failures on every read.
            sp.edit().remove(TOKEN_KEY).apply()
        }

        // One-time migration for versions that stored the access token in plaintext.
        val legacy = sp.getString(LEGACY_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
        if (legacy != null) {
            if (writeEncryptedToken(legacy)) return legacy
            // Security takes priority over keeping a stale session alive.
            sp.edit().remove(LEGACY_TOKEN_KEY).apply()
        }
        return null
    }

    private fun writeEncryptedToken(value: String): Boolean {
        return runCatching {
            val cipher = Cipher.getInstance(TOKEN_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, tokenKey())
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val body = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            sp.edit()
                .putString(TOKEN_KEY, "$iv$TOKEN_SEPARATOR$body")
                .remove(LEGACY_TOKEN_KEY)
                .apply()
        }.onFailure { error ->
            Log.e("Linxi/UserPrefs", "Unable to protect access token", error)
        }.isSuccess
    }

    private fun decryptToken(value: String): String {
        val encoded = value.split(TOKEN_SEPARATOR, limit = 2)
        require(encoded.size == 2) { "invalid token payload" }
        val iv = Base64.decode(encoded[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(encoded[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TOKEN_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, tokenKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun tokenKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(TOKEN_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                TOKEN_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun clearToken() {
        sp.edit()
            .remove(TOKEN_KEY)
            .remove(LEGACY_TOKEN_KEY)
            .apply()
    }

    var partnerName: String
        get() = sp.getString("partner_name", "") ?: ""
        set(v) { sp.edit().putString("partner_name", v).apply() }

    var demoMode: Boolean
        get() = sp.getBoolean("demo_mode", false)
        set(v) { sp.edit().putBoolean("demo_mode", v).apply() }

    var statusCardEnabled: Boolean
        get() = sp.getBoolean("status_card", true)
        set(v) { sp.edit().putBoolean("status_card", v).apply() }

    /**
     * 伴侣动态静默通知开关：对方息屏/亮屏、上线/下线时在通知栏留一条
     * （不弹横幅、不响铃、不振动）。默认开。
     */
    var quietNotifyEnabled: Boolean
        get() = sp.getBoolean("quiet_notify", true)
        set(v) { sp.edit().putBoolean("quiet_notify", v).apply() }

    // 触发即时推送的 WiFi 白名单（连接该 WiFi 时提醒）
    var watchSsid: String
        get() = sp.getString("watch_ssid", "") ?: ""
        set(v) { sp.edit().putString("watch_ssid", v).apply() }

    // 主题模式（ColorMode.value）：0跟随系统 1浅色 2深色 3深色AMOLED
    var colorMode: Int
        get() = sp.getInt("color_mode", 0)
        set(v) { sp.edit().putInt("color_mode", v).apply() }


    // 状态共享总开关：默认开启；只有用户明确关闭过才保持关闭。
    // 读取缺失键时返回 true，修复升级后用户明明默认开启却被提示"请先开启"的问题。
    var sharingEnabled: Boolean
        get() = sp.getBoolean("sharing_enabled", true)
        set(v) { sp.edit().putBoolean("sharing_enabled", v).apply() }

    // 是否已完成双方知情授权
    var privacyConsented: Boolean
        get() = sp.getBoolean("privacy_consented", false)
        set(v) { sp.edit().putBoolean("privacy_consented", v).apply() }

    // 绑定信息
    var pairId: Long
        get() = sp.getLong("pair_id", 0)
        set(v) { sp.edit().putLong("pair_id", v).apply() }

    var myUserId: Long
        get() = sp.getLong("my_user_id", 0)
        set(v) { sp.edit().putLong("my_user_id", v).apply() }

    // ============ 音乐（NeriPlayer 风格）设置 ============
    // 这些值只影响本机播放器与歌词展示，不会上传到林曦服务端。
    var musicQuality: String
        get() = sp.getString("music_quality", "exhigh") ?: "exhigh"
        set(v) { sp.edit().putString("music_quality", v).apply() }

    var musicShuffle: Boolean
        get() = sp.getBoolean("music_shuffle", false)
        set(v) { sp.edit().putBoolean("music_shuffle", v).apply() }

    /** off / all / one，与网易云播放器的循环语义一致。 */
    var musicRepeatMode: String
        get() = sp.getString("music_repeat_mode", "all") ?: "all"
        set(v) { sp.edit().putString("music_repeat_mode", v).apply() }

    var musicAutoPause: Boolean
        get() = sp.getBoolean("music_auto_pause", false)
        set(v) { sp.edit().putBoolean("music_auto_pause", v).apply() }

    var musicShowLyrics: Boolean
        get() = sp.getBoolean("music_show_lyrics", true)
        set(v) { sp.edit().putBoolean("music_show_lyrics", v).apply() }

    var musicTranslation: Boolean
        get() = sp.getBoolean("music_translation", true)
        set(v) { sp.edit().putBoolean("music_translation", v).apply() }

    var musicSearchHistory: Boolean
        get() = sp.getBoolean("music_search_history", true)
        set(v) { sp.edit().putBoolean("music_search_history", v).apply() }

    /** 最近搜索只保存在本机，最多保留 8 条；关闭开关时调用 clearMusicSearchHistory。 */
    val musicSearchHistoryEntries: List<String>
        get() = sp.getString("music_search_history_entries", "")
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(8)
            .toList()

    fun rememberMusicSearch(value: String) {
        if (!musicSearchHistory) return
        val normalized = value.trim().take(80)
        if (normalized.isBlank()) return
        val entries = (listOf(normalized) + musicSearchHistoryEntries)
            .distinct()
            .take(8)
        sp.edit().putString("music_search_history_entries", entries.joinToString("\n")).apply()
    }

    fun clearMusicSearchHistory() {
        sp.edit().remove("music_search_history_entries").apply()
    }

    // 灵动岛/悬浮歌词是 Android 系统通知能力的外观开关；关闭后仍可在应用内看歌词。
    var dynamicIslandEnabled: Boolean
        get() = sp.getBoolean("music_dynamic_island", false)
        set(v) { sp.edit().putBoolean("music_dynamic_island", v).apply() }

    var dynamicIslandShowLyrics: Boolean
        get() = sp.getBoolean("music_dynamic_island_lyrics", true)
        set(v) { sp.edit().putBoolean("music_dynamic_island_lyrics", v).apply() }

    var dynamicIslandCompact: Boolean
        get() = sp.getBoolean("music_dynamic_island_compact", false)
        set(v) { sp.edit().putBoolean("music_dynamic_island_compact", v).apply() }
}

object TimeUtil {
    fun nowTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    fun dayStartMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
