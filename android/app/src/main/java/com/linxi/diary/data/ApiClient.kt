package com.linxi.diary.data

import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真实 OkHttp REST 客户端（替换骨架占位版）。
 * 统一响应 {"code":0,"message":"ok","data":{...}}；code!=0 抛 ApiException。
 */
data class ApiException(val bizCode: Int, override val message: String) : Exception(message)

object ApiClient {

    private const val BASE = "https://api.linxi.app/api/v1"
    private val json = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun request(method: String, path: String, body: RequestBody?): Request.Builder {
        val b = Request.Builder().url(BASE + path)
        UserPrefs.token?.let { b.header("Authorization", "Bearer $it") }
        b.method(method, body)
        return b
    }

    private fun check(text: String): org.json.JSONObject {
        val o = JSONObject(text)
        val code = o.optInt("code", -1)
        if (code != 0) {
            throw ApiException(code, o.optString("message", "请求失败"))
        }
        return o
    }

    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val resp = client.newCall(request("GET", path, null).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(-1, "HTTP ${resp.code}")
        check(text).optJSONObject("data") ?: JSONObject()
    }

    /** GET 且 data 为数组 */
    suspend fun getArray(path: String): org.json.JSONArray = withContext(Dispatchers.IO) {
        val resp = client.newCall(request("GET", path, null).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(-1, "HTTP ${resp.code}")
        check(text).optJSONArray("data") ?: org.json.JSONArray()
    }

    suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val reqBody = body.toString().toRequestBody(json)
        val resp = client.newCall(request("POST", path, reqBody).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(-1, "HTTP ${resp.code}")
        check(text).optJSONObject("data") ?: JSONObject()
    }

    suspend fun putJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val reqBody = body.toString().toRequestBody(json)
        val resp = client.newCall(request("PUT", path, reqBody).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(-1, "HTTP ${resp.code}")
        check(text).optJSONObject("data") ?: JSONObject()
    }

    suspend fun delete(path: String): JSONObject = withContext(Dispatchers.IO) {
        val resp = client.newCall(request("DELETE", path, null).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(-1, "HTTP ${resp.code}")
        check(text).optJSONObject("data") ?: JSONObject()
    }

    /** 上传图片（multipart）→ 返回 {url} */
    suspend fun uploadImage(path: String, file: File): String = withContext(Dispatchers.IO) {
        val ext = file.extension
        val media = when (ext.lowercase()) {
            "png" -> "image/png".toMediaType()
            "webp" -> "image/webp".toMediaType()
            "gif" -> "image/gif".toMediaType()
            else -> "image/jpeg".toMediaType()
        }
        val mb = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(media))
            .build()
        val resp = client.newCall(request("POST", path, mb).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(-1, "HTTP ${resp.code}")
        check(text).optJSONObject("data")?.optString("url") ?: ""
    }

    // ============ 业务接口 ============

    /** 发送邮箱验证码，返回 {sent,expires_in} */
    suspend fun sendCode(email: String): JSONObject =
        postJson("/auth/send-code", JSONObject().put("email", email))

    /** 注册（username 3-20 位大小写英文），返回 {user_id,token} */
    suspend fun register(
        username: String,
        email: String,
        code: String,
        password: String,
        nickname: String? = null,
    ): JSONObject = postJson("/auth/register", JSONObject().apply {
        put("username", username)
        put("email", email)
        put("code", code)
        put("password", password)
        if (!nickname.isNullOrBlank()) put("nickname", nickname)
    })

    /** 登录（account = 用户名或邮箱），返回 {user_id,token} */
    suspend fun login(account: String, password: String): JSONObject =
        postJson("/auth/login", JSONObject().apply {
            put("account", account)
            put("password", password)
        })

    /** 检查更新，返回 {has_update,force,version:{version_name,version_code,apk_url,notes}} */
    suspend fun checkUpdate(versionCode: Int): JSONObject =
        get("/app/latest?platform=android&version_code=$versionCode")

    suspend fun pairStatus(): JSONObject = get("/pair/status")

    /** 获取本人资料（/profile/me） */
    suspend fun getMyProfile(): JSONObject = get("/profile/me")

    /** 更新本人资料（nickname/gender/signature/birthday），返回同 /profile/me */
    suspend fun updateMyProfile(
        nickname: String,
        gender: Int,
        signature: String,
        birthday: String,
    ): JSONObject = putJson("/profile/me", JSONObject().apply {
        put("nickname", nickname)
        put("gender", gender)
        put("signature", signature)
        put("birthday", birthday)
    })

    /** 下载任意绝对 URL 的字节（用于无三方图片库时显示头像位图）。失败返回 null。 */
    suspend fun downloadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }.getOrNull()
    }

    /** 更新本人昵称，返回双方权威资料 */
    suspend fun updateNickname(nickname: String): JSONObject =
        putJson("/profile", JSONObject().put("nickname", nickname))

    /** 更新纪念日（yyyy-MM-dd），返回双方权威资料 */
    suspend fun updateAnniversary(date: String): JSONObject =
        putJson("/pair/anniversary", JSONObject().put("anniversary_date", date))

    /** 上传头像原文件，返回双方权威资料。服务端按魔数识别格式，客户端不声明具体 MIME。 */
    suspend fun uploadAvatar(file: File): JSONObject = withContext(Dispatchers.IO) {
        val mb = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val resp = client.newCall(request("POST", "/profile/avatar", mb).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(-1, "HTTP ${resp.code}")
        check(text).optJSONObject("data") ?: JSONObject()
    }

    /** 状态历史时间线 */
    suspend fun historyTimeline(date: String?, limit: Int, offset: Int): org.json.JSONArray {
        var path = "/status/history?limit=$limit&offset=$offset"
        if (!date.isNullOrBlank()) path += "&date=$date"
        return getArray(path)
    }

    /** 24h 电量曲线 */
    suspend fun batteryCurve(date: String): org.json.JSONArray {
        return getArray("/status/history/battery?date=$date")
    }

    suspend fun todos(status: Int = 0): org.json.JSONArray = getArray("/todos?status=$status")

    suspend fun createTodo(body: JSONObject): JSONObject = postJson("/todos", body)

    suspend fun completeTodo(id: Long): JSONObject = postJson("/todos/$id/complete", JSONObject())

    suspend fun deleteTodo(id: Long): JSONObject = delete("/todos/$id")

    suspend fun diaries(date: String? = null): org.json.JSONArray {
        return getArray(if (date.isNullOrBlank()) "/diaries" else "/diaries?date=$date")
    }

    suspend fun createDiary(body: JSONObject): JSONObject = postJson("/diaries", body)
}
