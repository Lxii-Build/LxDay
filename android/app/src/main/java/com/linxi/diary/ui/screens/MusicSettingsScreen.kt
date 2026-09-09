package com.linxi.diary.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linxi.diary.data.NeteaseAccountStore
import com.linxi.diary.data.MusicNotificationController
import com.linxi.diary.data.NeteasePlaybackManager
import com.linxi.diary.data.NeteaseRepeatMode
import com.linxi.diary.ui.NeteaseQrLoginActivity
import com.linxi.diary.ui.NeteaseWebLoginActivity
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxSurface
import com.linxi.diary.ui.components.LxSurfaceTone
import com.linxi.diary.ui.components.LxArrowPreference
import com.linxi.diary.ui.components.LxChoicePreference
import com.linxi.diary.ui.components.LxSwitchPreference
import com.linxi.diary.util.UserPrefs
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Tune

/**
 * 音乐设置独立页：登录、播放行为和歌词都集中在“我的 → 音乐设置”。
 * 所有设置仅保存在本机；网易云 Cookie 使用 Keystore 加密，房间服务端不可见。
 */
@Composable
fun MusicSettingsScreen(onBack: () -> Unit, onOpenMusic: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf(NeteaseAccountStore.isLoggedIn()) }
    var quality by remember { mutableStateOf(UserPrefs.musicQuality) }
    var repeat by remember { mutableStateOf(UserPrefs.musicRepeatMode) }
    var shuffle by remember { mutableStateOf(UserPrefs.musicShuffle) }
    var autoPause by remember { mutableStateOf(UserPrefs.musicAutoPause) }
    var showLyrics by remember { mutableStateOf(UserPrefs.musicShowLyrics) }
    var translation by remember { mutableStateOf(UserPrefs.musicTranslation) }
    var searchHistory by remember { mutableStateOf(UserPrefs.musicSearchHistory) }
    var playbackCapsule by remember { mutableStateOf(UserPrefs.musicPlaybackCapsuleEnabled) }
    var capsuleLyrics by remember { mutableStateOf(UserPrefs.musicPlaybackCapsuleLyrics) }
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

    KernelScreen(title = "音乐设置", navigationIcon = { BackAction(onBack) }) {
        item {
            SmallTitle("网易云账号")
            LxSurface(Modifier.padding(top = 6.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                LxArrowPreference(
                    title = if (loggedIn) "网易云账号已绑定" else "绑定网易云账号",
                    summary = if (loggedIn) "本机账号可用于搜索、收藏、歌词和播放" else "使用官方网页登录，Cookie 只保存在本机",
                    startAction = { MusicSettingIcon(MiuixIcons.Messages, "网易云账号") },
                    onClick = { loginLauncher.launch(Intent(context, NeteaseWebLoginActivity::class.java)) },
                )
                if (!loggedIn) {
                    LxArrowPreference(
                        title = "用网易云 App 扫码绑定",
                        summary = "扫码轮询也只在本机完成，不经过林曦服务器",
                        startAction = { MusicSettingIcon(MiuixIcons.Messages, "扫码绑定") },
                        onClick = { qrLauncher.launch(Intent(context, NeteaseQrLoginActivity::class.java)) },
                    )
                } else {
                    LxArrowPreference(
                        title = "退出网易云账号",
                        summary = "清除本机 Cookie，不影响林曦登录与伴侣关系",
                        startAction = { MusicSettingIcon(MiuixIcons.Lock, "退出网易云账号") },
                        onClick = {
                            NeteaseAccountStore.clear()
                            NeteaseWebLoginActivity.clearWebLoginCookies()
                            NeteasePlaybackManager.stopAndClear()
                            loggedIn = false
                        },
                    )
                }
                LxArrowPreference(
                    title = "打开音乐库",
                    summary = "搜索歌曲、查看我的收藏、歌词和播放队列",
                    startAction = { MusicSettingIcon(MiuixIcons.Music, "音乐库") },
                    onClick = onOpenMusic,
                )
            }
        }

        item {
            SmallTitle("播放行为")
            LxSurface(Modifier.padding(top = 6.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                val qualityItems = listOf("标准 128k", "高品质 192k", "极高 320k", "无损 FLAC")
                val qualityValues = listOf("standard", "higher", "exhigh", "lossless")
                val qualityIndex = qualityValues.indexOf(quality).coerceAtLeast(0)
                LxChoicePreference(
                    title = "播放音质",
                    summary = qualityItems[qualityIndex],
                    items = qualityItems,
                    selectedIndex = qualityIndex,
                    onSelectedIndexChange = { index ->
                        quality = qualityValues[index]
                        UserPrefs.musicQuality = quality
                    },
                )
                val repeatItems = listOf("不循环", "列表循环", "单曲循环")
                val repeatValues = listOf("off", "all", "one")
                val repeatIndex = repeatValues.indexOf(repeat).coerceAtLeast(0)
                LxChoicePreference(
                    title = "循环模式",
                    summary = repeatItems[repeatIndex],
                    items = repeatItems,
                    selectedIndex = repeatIndex,
                    onSelectedIndexChange = { index ->
                        repeat = repeatValues[index]
                        UserPrefs.musicRepeatMode = repeat
                        NeteasePlaybackManager.setRepeatMode(
                            when (repeat) {
                                "off" -> NeteaseRepeatMode.OFF
                                "one" -> NeteaseRepeatMode.ONE
                                else -> NeteaseRepeatMode.ALL
                            },
                        )
                    },
                )
                LxSwitchPreference(
                    title = "随机播放",
                    summary = "队列中随机选择下一首，保持当前歌曲不变",
                    checked = shuffle,
                    onCheckedChange = { enabled ->
                        shuffle = enabled
                        UserPrefs.musicShuffle = enabled
                        NeteasePlaybackManager.setShuffle(enabled)
                    },
                    startAction = { MusicSettingIcon(MiuixIcons.Music, "随机播放") },
                )
                LxSwitchPreference(
                    title = "切换音频焦点时自动暂停",
                    summary = "接到电话或其他应用抢占音频时暂停，焦点恢复后不强制自动播放",
                    checked = autoPause,
                    onCheckedChange = { autoPause = it; NeteasePlaybackManager.setAutoPause(it) },
                    startAction = { MusicSettingIcon(MiuixIcons.Tune, "音频焦点") },
                )
            }
        }

        item {
            SmallTitle("歌词")
            LxSurface(Modifier.padding(top = 6.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                LxSwitchPreference(
                    title = "显示同步歌词",
                    summary = "播放页按时间轴高亮歌词，点按歌词可跳转",
                    checked = showLyrics,
                    onCheckedChange = { showLyrics = it; UserPrefs.musicShowLyrics = it },
                    startAction = { MusicSettingIcon(MiuixIcons.Messages, "同步歌词") },
                )
                LxSwitchPreference(
                    title = "显示翻译歌词",
                    summary = "有网易云翻译时显示在原文下方",
                    checked = translation,
                    onCheckedChange = { translation = it; UserPrefs.musicTranslation = it },
                    startAction = { MusicSettingIcon(MiuixIcons.Messages, "翻译歌词") },
                )
                LxSwitchPreference(
                    title = "保存搜索历史",
                    summary = "仅保存在本机，可随时关闭",
                    checked = searchHistory,
                    onCheckedChange = {
                        searchHistory = it
                        UserPrefs.musicSearchHistory = it
                        if (!it) UserPrefs.clearMusicSearchHistory()
                    },
                    startAction = { MusicSettingIcon(MiuixIcons.Recent, "搜索历史") },
                )
            }
        }

        item {
            SmallTitle("播放胶囊与通知")
            LxSurface(Modifier.padding(top = 6.dp).fillMaxWidth(), tone = LxSurfaceTone.Raised) {
                LxSwitchPreference(
                    title = "显示播放胶囊",
                    summary = "允许系统根据媒体通知显示播放胶囊、锁屏卡片或普通通知；不申请悬浮窗权限",
                    checked = playbackCapsule,
                    onCheckedChange = {
                        playbackCapsule = it
                        UserPrefs.musicPlaybackCapsuleEnabled = it
                        MusicNotificationController.refresh(NeteasePlaybackManager.stateFlow.value)
                    },
                    startAction = { MusicSettingIcon(MiuixIcons.Messages, "播放胶囊") },
                )
                LxSwitchPreference(
                    title = "胶囊显示当前歌词",
                    summary = "有已缓存歌词时，把当前行显示为媒体通知副文案",
                    checked = capsuleLyrics,
                    onCheckedChange = {
                        capsuleLyrics = it
                        UserPrefs.musicPlaybackCapsuleLyrics = it
                        MusicNotificationController.refresh(NeteasePlaybackManager.stateFlow.value)
                    },
                    startAction = { MusicSettingIcon(MiuixIcons.Messages, "胶囊歌词") },
                )
            }
        }

    }
}

@Composable
private fun MusicSettingIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    top.yukonga.miuix.kmp.basic.Icon(
        imageVector = icon,
        contentDescription = description,
        modifier = Modifier.padding(end = 6.dp),
    )
}
