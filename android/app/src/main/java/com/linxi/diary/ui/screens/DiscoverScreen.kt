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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.linxi.diary.data.ApiClient
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.RecordingTape
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Tab ③ 发现：相册 / 一起听 / 一起看 入口卡。
 *
 * 「日记」功能已于 0821 整体移除（管理员 Q31=D：客户端、服务端接口、
 * 数据表、后台页面、文档全部清掉，彻底断根）。
 * 0811 那轮只删了客户端入口、留下服务端孤儿接口，结果 0820 又接回来了——
 * 留着半截实现，将来看到接口还在就会以为功能该有。
 *
 * App 名字仍叫「林曦日记」、包名仍是 com.linxi.diary，这两个不受影响。
 */
@Composable
fun DiscoverScreen(
    onOpenAlbum: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenWatch: () -> Unit,
    albumEnabled: Boolean = true,
) {
    // 真实数据：相册张数与日记篇数。
    // 此前这里是 `delay(500)` 的**假加载**——转半秒圈只为「看起来像在加载」，
    // 既没有任何请求，也没有下拉刷新，纯装饰。
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var albumCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun fetch() {
        // 单项失败不影响另一项：任一为 null 时卡片只是不显示数量，不报错。
        runCatching { ApiClient.albumSummary() }
            .onSuccess { albumCount = it }
            .onFailure { albumCount = null }
    }

    LaunchedEffect(albumEnabled) {
        if (albumEnabled) fetch()
        else albumCount = null
        loading = false
    }

    KernelScreen(
        title = "发现",
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                fetch()
                refreshing = false
            }
        },
        loading = loading,
    ) {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                if (albumEnabled) {
                    DiscoverCard(
                        "相册",
                        albumCount?.let { "已收藏 $it 张照片" } ?: "记录你们的共同瞬间",
                        MiuixIcons.Photos,
                        onOpenAlbum,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                DiscoverCard("一起听", "分享此刻在听的歌", MiuixIcons.Music, onOpenListen)
                Spacer(Modifier.height(12.dp))
                DiscoverCard("一起看", "同步你们喜欢的影像", MiuixIcons.RecordingTape, onOpenWatch)
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
                    MiuixIcons.Tune,
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

/** 服务端关闭功能时的统一提示，避免旧页面继续发起必然被拒绝的请求。 */
@Composable
fun FeatureDisabledScreen(title: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    KernelScreen(title = title, navigationIcon = { BackAction(onBack) }) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    MiuixIcons.Tune,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("该功能暂时不可用，请联系管理员", color = colorScheme.onSurface.copy(alpha = 0.78f))
            }
        }
    }
}
