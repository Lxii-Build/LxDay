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
