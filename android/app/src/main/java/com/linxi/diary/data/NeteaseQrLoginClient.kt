package com.linxi.diary.data

import java.io.IOException
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
        )
        val root = JSONObject(response.body)
        val code = root.optInt("code", -1)
        val key = root.optString("unikey").trim()
        if (code != 200 || key.isBlank()) throw IOException("无法创建网易云二维码（$code）")
        val chainId = "v1_linxi_web_login_${System.currentTimeMillis()}"
        val qr = HttpUrl.Builder()
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
            extraHeaders = mapOf(
                "x-loginmethod" to "QrCode",
                "x-login-chain-id" to session.chainId,
            ),
        )
        val root = JSONObject(response.body)
        val refresh = response.headers["x-refresh-token"].orEmpty()
        NeteaseQrPollResult(
            code = root.optInt("code", -1),
            message = root.optString("message").ifBlank { root.optString("msg") },
            // x-refresh-token is not a MUSIC_U cookie.  Do not put it into the
            // cookie map as a guessed credential; only persist actual Set-Cookie
            // values received by this device's CookieJar.
            cookies = currentCookies(),
            refreshToken = refresh,
        )
    }

    fun currentCookies(): Map<String, String> = synchronized(cookieLock) {
        linkedMapOf<String, String>().apply {
            cookieStore.values.forEach { cookies -> cookies.forEach { put(it.name, it.value) } }
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

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    }
}
