package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Tab ③ 发现：相册 / 一起听 / 一起看 三张入口卡，进入二级“开发中”占位页。
 */
@Composable
fun DiscoverScreen(
    onOpenAlbum: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenWatch: () -> Unit,
) {
    // 与待办/历史页保持一致：主界面进入时先展示同款 miuix 加载动画，短暂加载后再显示入口卡。
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(500)
        loading = false
    }
    KernelScreen(title = "发现") {
        if (loading) {
            item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        } else {
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    DiscoverCard("相册", "记录你们的共同瞬间", Icons.Rounded.PhotoLibrary, onOpenAlbum)
                    Spacer(Modifier.height(12.dp))
                    DiscoverCard("一起听", "分享此刻在听的歌", Icons.Rounded.MusicNote, onOpenListen)
                    Spacer(Modifier.height(12.dp))
                    DiscoverCard("一起看", "同步你们喜欢的影像", Icons.Rounded.Movie, onOpenWatch)
                }
            }
        }
    }
}

@Composable
private fun DiscoverCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            Box(
                Modifier.fillMaxSize().offset(x = 24.dp, y = 26.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.primary.copy(alpha = 0.28f),
                    modifier = Modifier.size(96.dp),
                )
            }
            Box(Modifier.fillMaxSize().padding(18.dp, 16.dp), contentAlignment = Alignment.TopStart) {
                Column {
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onBackground)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

/** 发现二级页“开发中”占位。 */
@Composable
fun DiscoverPlaceholderScreen(title: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    KernelScreen(title = title, navigationIcon = { BackAction(onBack) }) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.Construction,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("当前功能开发中，尽请期待", color = colorScheme.onSurface.copy(alpha = 0.78f))
            }
        }
    }
}
