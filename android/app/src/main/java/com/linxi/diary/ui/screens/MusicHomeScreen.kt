package com.linxi.diary.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.linxi.diary.data.NeteaseAccountStore
import com.linxi.diary.data.NeteaseClient
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.ui.NeteaseQrLoginActivity
import com.linxi.diary.ui.NeteaseWebLoginActivity
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.components.NeteaseTrackCover
import com.linxi.diary.ui.components.LxSurface
import com.linxi.diary.ui.components.LxSurfaceTone
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 网易云音乐库：搜索、我的收藏、队列与歌词入口。 */
@Composable
fun MusicHomeScreen(
    onBack: () -> Unit,
    onOpenLyrics: (NeteaseTrack) -> Unit,
    onOpenListenTogether: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(NeteaseAccountStore.isLoggedIn()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NeteaseTrack>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<NeteaseTrack>>(emptyList()) }
    var showingFavorites by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var searchHistory by remember { mutableStateOf(UserPrefs.musicSearchHistoryEntries) }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) loggedIn = NeteaseAccountStore.isLoggedIn()
    }
    val qrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) loggedIn = NeteaseAccountStore.isLoggedIn()
    }

    fun refreshFavorites() {
        if (!loggedIn || loading) return
        loading = true
        error = null
        scope.launch {
            runCatching { NeteaseClient.fetchFavorites() }
                .onSuccess { favorites = it; info = if (it.isEmpty()) "网易云收藏还是空的" else "已加载 ${it.size} 首收藏歌曲" }
                .onFailure { error = it.message ?: "读取网易云收藏失败" }
            loading = false
        }
    }

    fun search() {
        if (!loggedIn) {
            error = "请先绑定网易云账号"
            return
        }
        val text = query.trim()
        if (text.isBlank()) {
            error = "请输入歌曲名或歌手"
            return
        }
        UserPrefs.rememberMusicSearch(text)
        searchHistory = UserPrefs.musicSearchHistoryEntries
        loading = true
        error = null
        info = null
        scope.launch {
            runCatching { NeteaseClient.searchSongs(text) }
                .onSuccess { results = it; info = if (it.isEmpty()) "没有找到匹配歌曲" else "找到 ${it.size} 首歌曲" }
                .onFailure { error = it.message ?: "网易云搜索失败，请检查网络" }
            loading = false
        }
    }

    fun playTrack(track: NeteaseTrack, source: List<NeteaseTrack>) {
        if (!loggedIn) {
            error = "请先绑定网易云账号"
            return
        }
        resolving = track.id
        error = null
        scope.launch {
            runCatching {
                val index = source.indexOfFirst { it.stableKey == track.stableKey }.coerceAtLeast(0)
                NeteasePlaybackManager.setQueue(source.ifEmpty { listOf(track) }, index)
                val url = NeteaseClient.resolvePlaybackUrl(track.id)
                NeteasePlaybackManager.play(track, url)
            }.onSuccess { info = "正在播放《${track.title}》" }
                .onFailure { error = it.message ?: "无法解析这首网易云歌曲" }
            resolving = null
        }
    }

    fun toggleFavorite(track: NeteaseTrack) {
        if (!loggedIn) return
        val currentlyLiked = track.liked || favorites.any { it.id == track.id }
        val liked = !currentlyLiked
        scope.launch {
            runCatching { NeteaseClient.likeSong(track.id, liked) }
                .onSuccess {
                    val replace = { list: List<NeteaseTrack> ->
                        list.map { if (it.id == track.id) it.copy(liked = liked) else it }
                    }
                    results = replace(results)
                    favorites = if (liked) {
                        if (favorites.none { it.id == track.id }) favorites + track.copy(liked = true) else replace(favorites)
                    } else favorites.filterNot { it.id == track.id }
                    info = if (liked) "已收藏《${track.title}》" else "已取消收藏《${track.title}》"
                }
                .onFailure { error = it.message ?: "收藏操作失败" }
        }
    }

    LaunchedEffect(loggedIn, showingFavorites) {
        if (loggedIn && showingFavorites && favorites.isEmpty()) refreshFavorites()
    }

    KernelScreen(title = "音乐", navigationIcon = { BackAction(onBack) }, loading = loading) {
        item {
            LxSurface(Modifier.fillMaxWidth().padding(top = 12.dp), tone = LxSurfaceTone.Raised) {
                Column(Modifier.padding(18.dp)) {
                    Text("网易云音乐", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (loggedIn) "账号已绑定 · 搜索和播放均在本机完成"
                        else "绑定网易云账号后即可搜索、收藏和播放",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!loggedIn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LxButton(
                                text = "网页登录",
                                onClick = { loginLauncher.launch(Intent(context, NeteaseWebLoginActivity::class.java)) },
                                modifier = Modifier.weight(1f),
                            )
                            LxButton(
                                text = "扫码绑定",
                                onClick = { qrLauncher.launch(Intent(context, NeteaseQrLoginActivity::class.java)) },
                                variant = LxButtonVariant.Neutral,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(
                                value = query,
                                onValueChange = { query = it.take(80) },
                                label = "歌曲名或歌手",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            LxButton(
                                text = if (loading && !showingFavorites) "搜索中…" else "搜索",
                                onClick = ::search,
                                enabled = !loading,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LxButton(
                                text = if (showingFavorites) "搜索结果" else "我的收藏",
                                onClick = {
                                    showingFavorites = !showingFavorites
                                    if (!showingFavorites) info = null
                                },
                                variant = if (showingFavorites) LxButtonVariant.Neutral else LxButtonVariant.Positive,
                                modifier = Modifier.weight(1f),
                            )
                            LxButton(
                                text = "一起听",
                                onClick = onOpenListenTogether,
                                variant = LxButtonVariant.Neutral,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        val visible = if (showingFavorites) favorites else results
        if (loggedIn && !showingFavorites && visible.isEmpty() && query.isBlank() && searchHistory.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    Text("最近搜索", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    searchHistory.forEach { term ->
                        LxButton(
                            text = term,
                            onClick = { query = term; search() },
                            variant = LxButtonVariant.Neutral,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        val favoriteIds = favorites.asSequence().map { it.id }.toSet()
        visible.forEach { track ->
            item {
                MusicTrackRow(
                    track = track.copy(liked = track.liked || track.id in favoriteIds),
                    resolving = resolving == track.id,
                    onPlay = { playTrack(track, visible) },
                    onFavorite = { toggleFavorite(track) },
                    onLyrics = { onOpenLyrics(track) },
                )
            }
        }

        error?.let { message ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        message,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    LxButton(
                        text = "重试",
                        onClick = { if (showingFavorites) refreshFavorites() else search() },
                        variant = LxButtonVariant.Neutral,
                    )
                }
            }
        }
        info?.let { item { Text(it, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) } }
        if (loggedIn && visible.isEmpty() && !loading) {
            item {
                Text(
                    if (showingFavorites) "暂无收藏歌曲" else "搜索歌曲，或切换到我的收藏",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 36.dp),
                )
            }
        }
    }
}

@Composable
private fun MusicTrackRow(
    track: NeteaseTrack,
    resolving: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onLyrics: () -> Unit,
) {
    LxSurface(Modifier.fillMaxWidth().padding(top = 8.dp), tone = LxSurfaceTone.Raised) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                NeteaseTrackCover(track, modifier = Modifier.size(56.dp), description = "${track.title}封面")
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(track.title, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LxButton(
                    text = if (resolving) "解析中…" else "播放",
                    onClick = onPlay,
                    enabled = !resolving,
                    modifier = Modifier.weight(1f),
                )
                LxButton("歌词", onClick = onLyrics, variant = LxButtonVariant.Neutral, modifier = Modifier.weight(1f))
                LxButton(
                    text = if (track.liked) "已收藏" else "收藏",
                    onClick = onFavorite,
                    variant = LxButtonVariant.Neutral,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
