package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.AppImageLoader
import com.linxi.diary.data.PhotoItem
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton as Button
import com.linxi.diary.ui.components.LxButtonVariant
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Calendar

/**
 * 「这一天」：历年同月同日的照片。
 *
 * 依赖照片的 EXIF 拍摄时间（taken_at）；没有 EXIF 的照片服务端会回退到上传时间。
 */
@Composable
fun OnThisDayScreen(
    onBack: () -> Unit,
    onOpenPhoto: (List<PhotoItem>, Int) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val today = remember { Calendar.getInstance() }
    var month by remember { mutableStateOf(today.get(Calendar.MONTH) + 1) }
    var day by remember { mutableStateOf(today.get(Calendar.DAY_OF_MONTH)) }
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(pull: Boolean = false) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            runCatching {
                val arr = ApiClient.photosOnThisDay(month, day)
                (0 until arr.length()).map { PhotoItem.fromJson(arr.getJSONObject(it)) }
            }.onSuccess { photos = it }
                .onFailure { error = albumFriendlyError(it) }
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(month, day) { load() }

    KernelScreen(
        title = "这一天",
        navigationIcon = { BackAction(onBack) },
        isRefreshing = refreshing,
        onRefresh = { load(pull = true) },
        loading = loading,
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("$month 月 $day 日", style = MiuixTheme.textStyles.headline1)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (photos.isEmpty()) "历年的这一天还没有照片"
                        else "历年的这一天，你们留下了 ${photos.size} 张",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { shiftDay(month, day, -1).let { month = it.first; day = it.second } },
                            variant = LxButtonVariant.Neutral,
                            modifier = Modifier.weight(1f),
                        ) { Text("前一天") }
                        Button(
                            onClick = {
                                month = today.get(Calendar.MONTH) + 1
                                day = today.get(Calendar.DAY_OF_MONTH)
                            },
                            variant = LxButtonVariant.Neutral,
                            modifier = Modifier.weight(1f),
                        ) { Text("今天") }
                        Button(
                            onClick = { shiftDay(month, day, 1).let { month = it.first; day = it.second } },
                            variant = LxButtonVariant.Neutral,
                            modifier = Modifier.weight(1f),
                        ) { Text("后一天") }
                    }
                }
            }
        }
        error?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(msg, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { load() }, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                    }
                }
            }
        }
        if (photos.isNotEmpty()) {
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(360.dp).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 用 itemsIndexed 而非 items + photos.indexOf(p)：
                    // 后者是 O(n) 查找放在每个 item 的 lambda 里，整体 O(n²)。
                    // 「这一天」单次最多返回 200 张，滚动时会明显掉帧。
                    // AlbumDetailScreen 修过同一个坑，这里当时没跟上。
                    itemsIndexed(photos, key = { _, p -> p.id }) { index, p ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                // 占位底色：加载失败时是可见的灰格子而非完全透明。
                                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                                .clickable { onOpenPhoto(photos, index) }
                        ) {
                            AsyncImage(
                                model = p.displayUrl,
                                imageLoader = AppImageLoader.get(context),
                                contentDescription = p.caption.ifBlank { "照片" },
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 在「月/日」上平移一天，跨月跨年正确回绕（用 Calendar 而非手算，闰年才不会错）。 */
private fun shiftDay(month: Int, day: Int, delta: Int): Pair<Int, Int> {
    val cal = Calendar.getInstance().apply {
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        add(Calendar.DAY_OF_YEAR, delta)
    }
    return (cal.get(Calendar.MONTH) + 1) to cal.get(Calendar.DAY_OF_MONTH)
}
