package com.linxi.diary.data

import com.linxi.diary.BuildConfig
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

    private val BASE = BuildConfig.BASE_URL
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
        // 通讯密钥：仅当构建期注入了 APP_KEY 时附带，服务端中间件据此放行官方客户端。
        if (BuildConfig.APP_KEY.isNotEmpty()) b.header("X-App-Key", BuildConfig.APP_KEY)
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

    // C6：把 HTTP 状态码翻译成用户能看懂的中文，不再直接抛 "HTTP 400/403"。
    private fun friendlyHttp(code: Int): String = when (code) {
        400 -> "请求有误，请检查后重试"
        401 -> "登录已失效，请重新登录"
        403 -> "没有权限进行该操作"
        404 -> "请求的内容不存在"
        408 -> "网络超时，请稍后重试"
        429 -> "操作过于频繁，请稍后再试"
        in 500..599 -> "服务器开小差了，请稍后重试"
        else -> "请求失败（$code）"
    }

    // 优先用服务端返回体里的中文 message；解析不到再退回状态码文案。
    private fun bodyMessageOr(text: String, fallback: String): String {
        val m = runCatching { JSONObject(text).optString("message") }.getOrNull()
        return if (!m.isNullOrBlank()) m else fallback
    }

    // HTTP 非 2xx 统一处理：401（登录失效/用户不存在）触发全局登出信号；其余抛友好中文错误。
    private fun failUnsuccessful(code: Int, text: String): Nothing {
        if (code == 401) AuthEvents.signalUnauthorized()
        throw ApiException(code, bodyMessageOr(text, friendlyHttp(code)))
    }

    // 统一网络异常处理：连接失败/超时等 IO 异常转成友好中文，业务异常原样上抛。
    private inline fun <T> netCall(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: java.net.SocketTimeoutException) {
        throw ApiException(-1, "网络超时，请稍后重试")
    } catch (e: java.io.IOException) {
        throw ApiException(-1, "网络连接失败，请检查网络后重试")
    }

    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        netCall {
            val resp = client.newCall(request("GET", path, null).build()).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) failUnsuccessful(resp.code, text)
            check(text).optJSONObject("data") ?: JSONObject()
        }
    }

    /** GET 且 data 为数组 */
    suspend fun getArray(path: String): org.json.JSONArray = withContext(Dispatchers.IO) {
        netCall {
            val resp = client.newCall(request("GET", path, null).build()).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) failUnsuccessful(resp.code, text)
            check(text).optJSONArray("data") ?: org.json.JSONArray()
        }
    }

    suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        netCall {
            val reqBody = body.toString().toRequestBody(json)
            val resp = client.newCall(request("POST", path, reqBody).build()).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) failUnsuccessful(resp.code, text)
            check(text).optJSONObject("data") ?: JSONObject()
        }
    }

    suspend fun putJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        netCall {
            val reqBody = body.toString().toRequestBody(json)
            val resp = client.newCall(request("PUT", path, reqBody).build()).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) failUnsuccessful(resp.code, text)
            check(text).optJSONObject("data") ?: JSONObject()
        }
    }

    suspend fun delete(path: String): JSONObject = withContext(Dispatchers.IO) {
        netCall {
            val resp = client.newCall(request("DELETE", path, null).build()).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) failUnsuccessful(resp.code, text)
            check(text).optJSONObject("data") ?: JSONObject()
        }
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
        // 走统一的失败处理：服务端会返回中文原因（如"暂不支持该图片格式"），
        // 此前直接抛 "HTTP 400" 把有用信息丢掉了。
        if (!resp.isSuccessful) failUnsuccessful(resp.code, text)
        check(text).optJSONObject("data") ?: JSONObject()
    }

    /**
     * 相册统一上传入口 `/media`。
     *
     * @param mime 真实 MIME（客户端已把 HEIC 转成 JPEG，见 ImagePrepPolicy）
     * @param takenAtMs 客户端读到的 EXIF 拍摄时间；服务端也会自己解析，两者取其一即可
     */
    suspend fun uploadMedia(file: File, mime: String, takenAtMs: Long?): JSONObject =
        withContext(Dispatchers.IO) {
            netCall {
                val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
                if (takenAtMs != null && takenAtMs > 0) {
                    builder.addFormDataPart("taken_at", takenAtMs.toString())
                }
                val resp = client.newCall(request("POST", "/media", builder.build()).build()).execute()
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) failUnsuccessful(resp.code, text)
                check(text).optJSONObject("data") ?: JSONObject()
            }
        }

    /**
     * 把已上传的照片挂进相册（批量）。
     *
     * `/media` 上传时就已建好 photo 行（album_id=0，即「未归类」），返回完整 photo 对象，
     * 所以这里传 id 列表即可，不必回传 url。albumId=0 表示保持未归类，无需调用本接口。
     */
    suspend fun attachPhotos(albumId: Long, photoIds: List<Long>): JSONObject {
        val arr = org.json.JSONArray()
        photoIds.forEach { arr.put(it) }
        return postJson("/albums/$albumId/photos", JSONObject().put("photo_ids", arr))
    }

    suspend fun deletePhoto(photoId: Long): JSONObject = delete("/photos/$photoId")

    suspend fun unlikePhoto(photoId: Long): JSONObject = delete("/photos/$photoId/like")

    suspend fun updatePhotoCaption(photoId: Long, caption: String): JSONObject =
        putJson("/photos/$photoId", JSONObject().put("caption", caption))

    /**
     * 回收站列表：软删的照片在这里，可恢复。
     * 同样是 `{list,total,page,size}` 包装体，不是裸数组。
     */
    suspend fun recycledPhotos(): org.json.JSONArray =
        get("/photos/recycled").optJSONArray("list") ?: org.json.JSONArray()

    suspend fun restorePhoto(photoId: Long): JSONObject =
        postJson("/photos/$photoId/restore", JSONObject())

    suspend fun renameAlbum(albumId: Long, name: String): JSONObject =
        putJson("/albums/$albumId", JSONObject().put("name", name))

    /** 换封面。cover_photo_id 必须是该相册内的照片。 */
    suspend fun setAlbumCover(albumId: Long, photoId: Long): JSONObject =
        putJson("/albums/$albumId", JSONObject().put("cover_photo_id", photoId))

    /** 删相册（软删）。其中照片不跟着删，会退回「未归类」。 */
    suspend fun deleteAlbum(albumId: Long): JSONObject = delete("/albums/$albumId")

    /** 删评论。服务端只允许删自己的。 */
    suspend fun deletePhotoComment(photoId: Long, commentId: Long): JSONObject =
        delete("/photos/$photoId/comments/$commentId")

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

    /** 更新待办（可传 remind_enabled/title/note/remind_at 等），返回更新后的待办 */
    suspend fun updateTodo(id: Long, body: JSONObject): JSONObject = putJson("/todos/$id", body)

    suspend fun completeTodo(id: Long): JSONObject = postJson("/todos/$id/complete", JSONObject())

    suspend fun deleteTodo(id: Long): JSONObject = delete("/todos/$id")

    suspend fun diaries(date: String? = null): org.json.JSONArray {
        return getArray(if (date.isNullOrBlank()) "/diaries" else "/diaries?date=$date")
    }

    /** 日记篇数（发现页卡片副标题用）。服务端无专门计数接口，直接取列表长度。 */
    suspend fun diaryCount(): Int = diaries().length()

    // ---------- 相册 ----------

    /**
     * 相册概要：照片总数 / 相册数 / 最新缩略图。发现页卡片副标题用。
     * 服务端把 summary 挂在 /albums/:id 通配 handler 下分派（gin 不允许同层静态段与通配段并存）。
     */
    suspend fun albumSummary(): Int = get("/albums/summary").optInt("photo_count")

    suspend fun albums(): org.json.JSONArray = getArray("/albums")

    /**
     * 相册内照片（分页）。
     *
     * 服务端返回的是 `data: {list, total, page, size}` 包装体，**不是裸数组** ——
     * 用 getArray 会因为 `data` 不是 JSONArray 而静默拿到空数组（相册详情永远显示为空）。
     * 这里显式取 `list`。
     */
    suspend fun albumPhotos(albumId: Long, page: Int, size: Int): org.json.JSONArray =
        get("/albums/$albumId/photos?page=$page&size=$size")
            .optJSONArray("list") ?: org.json.JSONArray()

    suspend fun createAlbum(name: String): JSONObject =
        postJson("/albums", JSONObject().put("name", name))

    suspend fun photoDetail(photoId: Long): JSONObject = get("/photos/$photoId")

    suspend fun likePhoto(photoId: Long): JSONObject =
        postJson("/photos/$photoId/like", JSONObject())

    suspend fun commentPhoto(photoId: Long, content: String): JSONObject =
        postJson("/photos/$photoId/comments", JSONObject().put("content", content))

    /**
     * 「这一天」：历年同月同日的照片。
     * 返回 `{month, day, list, total}` 包装体，取 list。
     */
    suspend fun photosOnThisDay(month: Int, day: Int): org.json.JSONArray =
        get("/photos/on-this-day?month=$month&day=$day")
            .optJSONArray("list") ?: org.json.JSONArray()

    suspend fun createDiary(body: JSONObject): JSONObject = postJson("/diaries", body)
}
