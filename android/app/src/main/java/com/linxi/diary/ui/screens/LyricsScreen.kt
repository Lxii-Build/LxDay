package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linxi.diary.data.NeteaseClient
import com.linxi.diary.data.NeteaseLyricLine
import com.linxi.diary.data.NeteaseLyrics
import com.linxi.diary.data.NeteaseLyricSearchResult
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteaseTrack
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** miuix 风格全屏歌词页：同步高亮、点击跳转、翻译和歌词候选搜索。 */
@Composable
fun LyricsScreen(track: NeteaseTrack, onBack: () -> Unit) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val playback by NeteasePlaybackManager.stateFlow.collectAsStateWithLifecycle()
    var lyrics by remember(track.id) { mutableStateOf<NeteaseLyrics?>(null) }
    var loading by remember(track.id) { mutableStateOf(true) }
    var query by remember(track.id) { mutableStateOf("${track.title} ${track.artist}") }
    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<NeteaseLyricSearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showingSearch by remember { mutableStateOf(false) }
    var retrySearch by remember { mutableStateOf(false) }
    val currentTrack = playback.track?.takeIf { it.id == track.id } ?: track
    val activeIndex = lyrics?.lineAt(playback.positionMs) ?: -1

    fun loadLyrics(target: NeteaseTrack) {
        retrySearch = false
        loading = true
        error = null
        scope.launch {
            runCatching { NeteaseClient.fetchLyrics(target.id) }
                .onSuccess {
                    lyrics = it
                    NeteasePlaybackManager.cacheLyrics(target.id, it)
                }
                .onFailure { error = it.message ?: "歌词加载失败" }
            loading = false
        }
    }

    fun searchLyrics() {
        retrySearch = true
        val text = query.trim()
        if (text.isBlank()) {
            error = "请输入歌词搜索关键词"
            return
        }
        searching = true
        error = null
        scope.launch {
            runCatching { NeteaseClient.searchLyrics(text) }
                .onSuccess {
                    candidates = it
                    if (it.isEmpty()) error = "没有找到带歌词的歌曲"
                }
                .onFailure { error = it.message ?: "歌词搜索失败" }
            searching = false
        }
    }

    LaunchedEffect(track.id) { loadLyrics(track) }

    KernelScreen(title = "歌词", navigationIcon = { BackAction(onBack) }, loading = loading) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(currentTrack.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(currentTrack.artist, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LxButton(
                            text = if (showingSearch) "收起搜索" else "搜索歌词",
                            onClick = { showingSearch = !showingSearch },
                            variant = LxButtonVariant.Neutral,
                            modifier = Modifier.weight(1f),
                        )
                        LxButton(
                            text = if (playback.playing) "暂停" else "播放",
                            onClick = { if (playback.playing) NeteasePlaybackManager.pause() else NeteasePlaybackManager.resume() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (showingSearch) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(
                                value = query,
                                onValueChange = { query = it.take(100) },
                                label = "歌曲名 / 歌手",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            LxButton(
                                text = if (searching) "搜索中…" else "搜索",
                                onClick = ::searchLyrics,
                                enabled = !searching,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        if (showingSearch) {
            candidates.forEach { candidate ->
                item {
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(candidate.track.title, fontWeight = FontWeight.Medium)
                            Text(candidate.track.artist, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Spacer(Modifier.height(6.dp))
                            LxButton(
                                text = "使用这份歌词",
                                onClick = {
                                    lyrics = candidate.lyrics
                                    NeteasePlaybackManager.cacheLyrics(candidate.track.id, candidate.lyrics)
                                    query = "${candidate.track.title} ${candidate.track.artist}"
                                    showingSearch = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
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
                        onClick = { if (retrySearch) searchLyrics() else loadLyrics(track) },
                        variant = LxButtonVariant.Neutral,
                    )
                }
            }
        }
        if (!loading) {
            val lines = lyrics?.let { visibleLines(it, UserPrefs.musicTranslation) }.orEmpty()
            if (lines.isEmpty()) {
                item { Text("这首歌暂时没有歌词", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 28.dp)) }
            } else {
                lines.forEachIndexed { index, line ->
                    item {
                        val active = index == activeIndex
                        Text(
                            text = line.text.ifBlank { "♪" },
                            fontSize = if (active) 21.sp else 16.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { NeteasePlaybackManager.seekTo(line.timeMs - (lyrics?.offsetMs ?: 0L)) }
                                .padding(horizontal = 18.dp, vertical = if (active) 10.dp else 7.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun visibleLines(lyrics: NeteaseLyrics, includeTranslation: Boolean): List<NeteaseLyricLine> {
    if (lyrics.original.isEmpty()) return lyrics.translated
    if (!includeTranslation || lyrics.translated.isEmpty()) return lyrics.original
    return lyrics.original.map { original ->
        val translation = lyrics.translated.minByOrNull { kotlin.math.abs(it.timeMs - original.timeMs) }
            ?.takeIf { kotlin.math.abs(it.timeMs - original.timeMs) <= 1_000L }
        if (translation == null || translation.text.isBlank()) original
        else original.copy(text = "${original.text}\n${translation.text}")
    }
}
