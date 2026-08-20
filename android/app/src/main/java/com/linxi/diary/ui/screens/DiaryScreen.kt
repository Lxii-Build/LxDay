package com.linxi.diary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.DiaryItem
import com.linxi.diary.ui.components.BackAction
import com.linxi.diary.ui.components.KernelScreen
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 日记页。
 *
 * 这个功能此前处于「服务端全都有、客户端没有入口」的状态：
 * `/diaries` 接口、diary 表、后台「内容审核-日记」页都在，
 * 但 0811 那轮把日记 tab 改成了发现 tab，客户端从此没有任何调用方——
 * 而 App 的名字就叫「林曦日记」。这里把它作为发现页的二级页接回来。
 */
@Composable
fun DiaryScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<DiaryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    fun load(pull: Boolean = false) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            runCatching { parseDiaries(ApiClient.diaries()) }
                .onSuccess { list = it }
                .onFailure { error = diaryFriendlyError(it) }
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { load() }

    if (showEditor) {
        DiaryEditorDialog(
            onDismiss = { showEditor = false },
            onSubmit = { title, content ->
                scope.launch {
                    val body = JSONObject().apply {
                        put("title", title)
                        put("content", content)
                    }
                    runCatching { ApiClient.createDiary(body) }
                        .onSuccess { showEditor = false; load() }
                        .onFailure { error = diaryFriendlyError(it) }
                }
            },
        )
    }

    KernelScreen(
        title = "日记",
        navigationIcon = { BackAction(onBack) },
        isRefreshing = refreshing,
        onRefresh = { load(pull = true) },
        loading = loading,
    ) {
        item {
            Button(
                onClick = { showEditor = true },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("写一篇") }
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
        if (!loading && error == null && list.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("还没有日记", style = MiuixTheme.textStyles.headline1)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "写下的每一篇你们都能看到。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        items(list, key = { it.id }) { d ->
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(d.title.ifBlank { "无标题" }, style = MiuixTheme.textStyles.headline1)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${d.authorName.ifBlank { "对方" }} · ${d.diaryDate}",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (d.content.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(d.content, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryEditorDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = "写日记",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = title,
                onValueChange = { if (it.length <= 60) title = it },
                label = "标题",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = content,
                onValueChange = { if (it.length <= 5000) content = it },
                label = "正文",
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            Text(
                "${content.length} / 5000",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    // busy 门控：防止连点两次发出两篇日记（待办页此前就吃过这个亏）。
                    onClick = { busy = true; onSubmit(title.trim(), content.trim()) },
                    enabled = !busy && content.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "发布中…" else "发布", fontWeight = FontWeight.Medium) }
            }
        }
    }
}

private fun parseDiaries(array: org.json.JSONArray): List<DiaryItem> =
    (0 until array.length()).map { DiaryItem.fromJson(array.getJSONObject(it)) }

private fun diaryFriendlyError(t: Throwable): String = when {
    t is java.net.UnknownHostException -> "无法连接服务器，请检查网络"
    t is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
    !t.message.isNullOrBlank() -> t.message!!
    else -> "加载失败，请稍后重试"
}
