package com.linxi.diary.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.linxi.diary.util.UserPrefs

data class NeteaseTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val durationMs: Long,
    val liked: Boolean = false,
) {
    val stableKey: String get() = "netease:$id"
}

data class NeteaseLyricSearchResult(
    val track: NeteaseTrack,
    val lyrics: NeteaseLyrics,
)

/**
 * 设备端网易云客户端。请求携带的是本机加密存储的 Cookie，服务端只收到歌曲 ID。
 * 只实现一起听需要的搜索、歌曲详情和播放地址解析，避免把第三方凭据转发到林曦 API。
 */
object NeteaseClient {
    private const val MUSIC_ORIGIN = "https://music.163.com"
    private const val INTERFACE_ORIGIN = "https://interface.music.163.com"
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    @Volatile
    private var cachedUserId: Long = 0L
    @Volatile
    private var cachedCookieFingerprint: String = ""

    suspend fun searchSongs(keyword: String, limit: Int = 20): List<NeteaseTrack> = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        require(q.length in 1..80) { "请输入 1-80 个字符的歌曲名或歌手" }
        val response = postWeApi(
            "/weapi/cloudsearch/get/web",
            JSONObject().apply {
                put("s", q)
                put("type", 1)
                put("limit", limit.coerceIn(1, 50))
                put("offset", 0)
                put("total", true)
            },
        )
        parseSongs(response)
    }

    suspend fun resolvePlaybackUrl(songId: Long): String = withContext(Dispatchers.IO) {
        require(songId > 0) { "歌曲 ID 无效" }
        val response = postEApi(
            "/eapi/song/enhance/player/url/v1",
            JSONObject().apply {
                put("ids", "[$songId]")
                put("level", UserPrefs.musicQuality.ifBlank { "exhigh" })
                put("encodeType", "mp3")
            },
        )
        val root = JSONObject(response)
        val code = root.optInt("code", -1)
        val data = root.optJSONArray("data")
        val rawUrl = data?.optJSONObject(0)?.optString("url").orEmpty()
        if (code != 200 || rawUrl.isBlank()) {
            throw IOException(
                when (code) {
                    301 -> "网易云账号登录已失效，请重新绑定"
                    -460 -> "该歌曲需要网易云会员或无可用音质"
                    else -> "网易云暂时无法提供这首歌（$code）"
                },
            )
        }
        NeteasePlaybackUrlPolicy.normalize(rawUrl)
            ?: throw IOException("网易云返回了不安全或不受支持的播放地址")
    }

    suspend fun verifyLogin(): Boolean = withContext(Dispatchers.IO) {
        if (!NeteaseAccountStore.isLoggedIn()) return@withContext false
        runCatching {
            val response = JSONObject(postWeApi(
                "/weapi/w/nuser/account/get",
                JSONObject().put("csrf_token", NeteaseAccountStore.cookies()["__csrf"].orEmpty()),
            ))
            val account = response.optJSONObject("account")
            cachedUserId = account?.optLong("id", 0L)?.coerceAtLeast(0L) ?: 0L
            cachedCookieFingerprint = cookieFingerprint()
            response.optInt("code", -1) == 200
        }.getOrDefault(false)
    }

    /** 获取当前网易云账号的收藏歌曲。歌曲详情仍在设备端解析，不进入林曦服务端。 */
    suspend fun fetchFavorites(limit: Int = 100): List<NeteaseTrack> = withContext(Dispatchers.IO) {
        val uid = currentUserId()
        if (uid <= 0L) throw IOException("无法读取网易云账号信息，请重新绑定")
        val likedRoot = JSONObject(
            postWeApi(
                "/weapi/song/like/get",
                JSONObject().put("uid", uid).put("limit", limit.coerceIn(1, 300)),
            ),
        )
        if (likedRoot.optInt("code", 200) != 200) {
            throw IOException("网易云收藏读取失败（${likedRoot.optInt("code", -1)}）")
        }
        val ids = likedRoot.optJSONArray("ids") ?: return@withContext emptyList()
        val safeIds = buildList {
            for (i in 0 until ids.length()) {
                ids.optLong(i, 0L).takeIf { it > 0L }?.let(::add)
                if (size >= limit.coerceIn(1, 300)) break
            }
        }
        if (safeIds.isEmpty()) return@withContext emptyList()
        val idsJson = "[${safeIds.joinToString(",")}]"
        val details = JSONObject(
            postWeApi(
                "/weapi/v3/song/detail",
                JSONObject().put("c", idsJson).put("ids", idsJson),
            ),
        )
        parseSongArray(details.optJSONArray("songs"), liked = true)
    }

    /** 切换网易云喜欢状态；状态只写入网易云，不写入林曦服务器。 */
    suspend fun likeSong(songId: Long, liked: Boolean): Boolean = withContext(Dispatchers.IO) {
        require(songId > 0L) { "歌曲 ID 无效" }
        val csrf = NeteaseAccountStore.cookies()["__csrf"].orEmpty()
        val response = JSONObject(
            postWeApi(
                "/weapi/song/like?id=$songId&like=$liked&csrf_token=$csrf",
                JSONObject().apply {
                    put("trackId", songId)
                    put("userid", currentUserId())
                    put("like", liked)
                    put("alg", "itembased")
                },
            ),
        )
        if (response.optInt("code", -1) != 200) {
            throw IOException("网易云收藏操作失败（${response.optInt("code", -1)}）")
        }
        true
    }

    /** 获取网易云原文、翻译和音译歌词。 */
    suspend fun fetchLyrics(songId: Long): NeteaseLyrics = withContext(Dispatchers.IO) {
        require(songId > 0L) { "歌曲 ID 无效" }
        val root = JSONObject(
            postWeApi(
                "/weapi/song/lyric",
                JSONObject().apply {
                    put("id", songId)
                    put("lv", -1)
                    put("kv", -1)
                    put("tv", -1)
                    put("rv", -1)
                },
            ),
        )
        val originalRaw = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val translatedRaw = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val romanizedRaw = root.optJSONObject("romalrc")?.optString("lyric").orEmpty()
        NeteaseLyrics(
            original = NeteaseLyricsParser.parseLrc(originalRaw),
            translated = NeteaseLyricsParser.parseLrc(translatedRaw),
            romanized = NeteaseLyricsParser.parseLrc(romanizedRaw),
            offsetMs = NeteaseLyricsParser.offsetMs(originalRaw),
        )
    }

    /** 歌词搜索：先用网易云搜索歌曲，再在本机拉取候选歌词并保留有歌词的结果。 */
    suspend fun searchLyrics(keyword: String, limit: Int = 8): List<NeteaseLyricSearchResult> =
        withContext(Dispatchers.IO) {
            searchSongs(keyword, limit.coerceIn(1, 20)).mapNotNull { track ->
                runCatching { fetchLyrics(track.id) }
                    .getOrNull()
                    ?.takeUnless { it.isEmpty }
                    ?.let { lyrics -> NeteaseLyricSearchResult(track, lyrics) }
            }
        }

    private fun parseSongs(raw: String): List<NeteaseTrack> {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) return emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return parseSongArray(songs)
    }

    private fun parseSongArray(songs: org.json.JSONArray?, liked: Boolean = false): List<NeteaseTrack> {
        if (songs == null) return emptyList()
        return buildList(songs.length()) {
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                val id = song.optLong("id", 0L)
                val title = song.optString("name").trim()
                if (id <= 0 || title.isBlank()) continue
                val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
                val names = buildList {
                    if (artists != null) {
                        for (j in 0 until artists.length()) {
                            artists.optJSONObject(j)?.optString("name")?.trim()
                                ?.takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }
                val album = song.optJSONObject("al") ?: song.optJSONObject("album")
                add(
                    NeteaseTrack(
                        id = id,
                        title = title,
                        artist = names.joinToString(" / ").ifBlank { "未知歌手" },
                        album = album?.optString("name").orEmpty(),
                        coverUrl = album?.optString("picUrl").orEmpty().replaceFirst("http://", "https://"),
                        durationMs = song.optLong("dt", song.optLong("duration", 0L)).coerceAtLeast(0L),
                        liked = liked,
                    ),
                )
            }
        }
    }

    private fun currentUserId(): Long {
        val fingerprint = cookieFingerprint()
        if (cachedUserId > 0L && cachedCookieFingerprint == fingerprint) return cachedUserId
        val response = JSONObject(
            postWeApi(
                "/weapi/w/nuser/account/get",
                JSONObject().put("csrf_token", NeteaseAccountStore.cookies()["__csrf"].orEmpty()),
            ),
        )
        if (response.optInt("code", -1) != 200) throw IOException("网易云账号已失效，请重新绑定")
        return response.optJSONObject("account")?.optLong("id", 0L)?.also {
            cachedUserId = it
            cachedCookieFingerprint = fingerprint
        } ?: 0L
    }

    private fun cookieFingerprint(): String = listOf("MUSIC_U", "MUSIC_A", "__csrf")
        .joinToString("\u0000") { key -> "$key=${NeteaseAccountStore.cookies()[key].orEmpty()}" }

    private fun postWeApi(path: String, payload: JSONObject): String =
        postForm(MUSIC_ORIGIN + path, NeteaseCrypto.weApi(payload))

    private fun postEApi(path: String, payload: JSONObject): String =
        postForm(INTERFACE_ORIGIN + path, NeteaseCrypto.eApi(path, payload))

    private fun postForm(url: String, values: Map<String, String>): String {
        val body = FormBody.Builder().apply {
            values.forEach { (key, value) -> add(key, value) }
        }.build()
        val cookies = NeteaseAccountStore.cookies()
            .filterValues(String::isNotBlank)
            .entries.joinToString("; ") { (key, value) -> "$key=$value" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", MUSIC_ORIGIN + "/")
            .header("Origin", MUSIC_ORIGIN)
            .header("Accept", "*/*")
            .header("Cookie", cookies)
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("网易云请求失败（${response.code}）")
            return text
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
}
