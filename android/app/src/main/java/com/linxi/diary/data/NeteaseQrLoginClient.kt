package com.linxi.diary.data

import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class NeteaseQrSession(val key: String, val qrContent: String, val chainId: String)

data class NeteaseQrPollResult(
    val code: Int,
    val message: String,
    val cookies: Map<String, String> = emptyMap(),
    val refreshToken: String = "",
) {
    val confirmed: Boolean get() = code == 803
}

/** 与 NeriPlayer 相同的网易云扫码登录协议；Cookie 只在此设备的内存中流转。 */
class NeteaseQrLoginClient {
    private val cookieLock = Any()
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    /**
     * 网易云当前的 Web QR 风控要求浏览器会话上下文。它们不是账号凭据；真正的
     * MUSIC_U 只来自轮询成功响应的 cookie / Set-Cookie，且始终只保存在本机。
     */
    private val webCookies = NeteaseQrLoginPolicy.createWebCookies()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(cookieLock) {
                    cookies.forEach { fresh ->
                        val list = cookieStore.getOrPut(fresh.domain) { mutableListOf() }
                        list.removeAll { it.name == fresh.name && it.path == fresh.path }
                        if (!fresh.expiresAt.let { it <= System.currentTimeMillis() }) list += fresh
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookieLock) {
                val now = System.currentTimeMillis()
                cookieStore.values.forEach { cookies -> cookies.removeAll { it.expiresAt <= now } }
                cookieStore.values.asSequence().flatMap { it.asSequence() }.filter { it.matches(url) }.toList()
            }
        })
        .build()

    suspend fun createSession(): NeteaseQrSession = withContext(Dispatchers.IO) {
        val response = post(
            "/weapi/login/qrcode/unikey",
            JSONObject().apply { put("type", 1); put("noCheckToken", true) },
            extraHeaders = NeteaseQrLoginPolicy.webQrHeaders(),
        )
        val root = JSONObject(response.body)
        val code = root.optInt("code", -1)
        val key = root.optString("unikey")
            .ifBlank { root.optJSONObject("data")?.optString("unikey").orEmpty() }
            .trim()
        if (code != 200 || key.isBlank()) throw IOException("无法创建网易云二维码（$code）")
        val chainId = NeteaseQrLoginPolicy.createChainId()
        val qr = NeteaseQrLoginPolicy.qrContent(key, chainId)
        NeteaseQrSession(key, qr, chainId)
    }

    suspend fun poll(session: NeteaseQrSession): NeteaseQrPollResult = withContext(Dispatchers.IO) {
        val response = post(
            "/weapi/login/qrcode/client/login",
            JSONObject().apply {
                put("key", session.key)
                put("type", 1)
                put("noCheckToken", true)
            },
            extraHeaders = NeteaseQrLoginPolicy.webQrHeaders(session.chainId),
        )
        val root = JSONObject(response.body)
        val refresh = response.headers["x-refresh-token"].orEmpty()
        NeteaseQrPollResult(
            code = root.optInt("code", -1),
            message = root.optString("message").ifBlank { root.optString("msg") },
            // x-refresh-token is not a MUSIC_U cookie. Persist only cookies that
            // arrive in Set-Cookie or the authenticated response's cookie field.
            cookies = currentCookies(root.optString("cookie")),
            refreshToken = refresh,
        )
    }

    fun currentCookies(responseCookie: String = ""): Map<String, String> = synchronized(cookieLock) {
        linkedMapOf<String, String>().apply {
            putAll(webCookies)
            cookieStore.values.forEach { cookies -> cookies.forEach { put(it.name, it.value) } }
            putAll(NeteaseQrLoginPolicy.parseCookieHeader(responseCookie))
        }
    }

    private fun post(path: String, payload: JSONObject, extraHeaders: Map<String, String> = emptyMap()): HttpResult {
        val values = NeteaseCrypto.weApi(payload)
        val form = FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder()
            .url("https://music.163.com$path")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .header("Accept", "*/*")
            .header("Cookie", requestCookieHeader())
            .apply { extraHeaders.forEach { (key, value) -> header(key, value) } }
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("网易云二维码请求失败（${response.code}）")
            return HttpResult(body, response.headers.toMultimap().mapValues { it.value.lastOrNull().orEmpty() })
        }
    }

    private data class HttpResult(val body: String, val headers: Map<String, String>)

    private fun requestCookieHeader(): String = synchronized(cookieLock) {
        val cookies = linkedMapOf<String, String>().apply {
            putAll(webCookies)
            cookieStore.values.forEach { values -> values.forEach { put(it.name, it.value) } }
        }
        NeteaseQrLoginPolicy.cookieHeader(cookies)
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:152.0) Gecko/20100101 Firefox/152.0"
    }
}

/** Pure browser-context policy for NetEase's Web QR login flow. */
internal object NeteaseQrLoginPolicy {
    private val random = SecureRandom()
    private const val TOKEN_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_"
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"

    fun createWebCookies(now: Long = System.currentTimeMillis()): Map<String, String> {
        val nuid = randomHex(16)
        return linkedMapOf(
            "JSESSIONID-WYYY" to randomToken(190),
            "_iuqxldmzr_" to "33",
            "_ntes_nnid" to "$nuid,$now",
            "_ntes_nuid" to nuid,
            "NMTID" to "00${randomToken(39)}",
            "WEVNSM" to "1.0.0",
            "WNMCID" to "${randomLetters(6)}.$now.01.0",
        )
    }

    fun createChainId(now: Long = System.currentTimeMillis()): String = "v1_linxi_web_login_$now"

    fun qrContent(key: String, chainId: String): String = HttpUrl.Builder()
        .scheme("https")
        .host("music.163.com")
        .addPathSegments("st/platform/scanlogin")
        .addQueryParameter("codekey", key)
        .addQueryParameter("chainId", chainId)
        .addQueryParameter("hdw_device", "web")
        .addQueryParameter("hdw_appid", "web")
        .addQueryParameter("hitExp", "1")
        .build()
        .toString()

    fun webQrHeaders(chainId: String? = null): Map<String, String> = buildMap {
        put("x-loginmethod", "QrCode")
        chainId?.takeIf(String::isNotBlank)?.let { put("x-login-chain-id", it) }
        put("x-os", "web")
        put("X-channelSource", "undefined")
        put("Nm-GCore-Status", "1")
    }

    fun parseCookieHeader(raw: String): Map<String, String> = raw.split(';')
        .map(String::trim)
        .mapNotNull { item ->
            val index = item.indexOf('=')
            if (index <= 0) null else item.substring(0, index).trim() to item.substring(index + 1).trim()
        }
        .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
        .toMap()

    fun cookieHeader(cookies: Map<String, String>): String = cookies.entries
        .joinToString("; ") { (key, value) -> "$key=$value" }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun randomToken(length: Int): String = buildString(length) {
        repeat(length) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
    }

    private fun randomLetters(length: Int): String = buildString(length) {
        repeat(length) { append(LETTERS[random.nextInt(LETTERS.length)]) }
    }
}
