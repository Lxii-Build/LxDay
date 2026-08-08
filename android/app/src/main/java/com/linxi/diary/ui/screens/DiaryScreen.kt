package com.linxi.diary.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.DiaryItem
import com.linxi.diary.ui.components.KernelScreen
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * Tab ③ 日记（miuix 布局）：双人共同日记，按日期归档，文字+图片。
 */
@Composable
fun DiaryScreen() {
    val scope = rememberCoroutineScope()
    var diaries by remember { mutableStateOf<List<DiaryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showPublish by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching {
                val data = ApiClient.diaries(date = null)
                diaries = (0 until data.length()).map { DiaryItem.fromJson(data.getJSONObject(it)) }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    KernelScreen(
        title = "日记",
        floatingActionButton = {
            FloatingActionButton(onClick = { showPublish = true }) { Text("+") }
        }
    ) {
        if (loading) {
            item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        } else if (diaries.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有日记", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(4.dp))
                    Text("记录你们的第一篇吧", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        } else {
            val grouped = diaries.groupBy { it.diaryDate }
            grouped.keys.sortedDescending().forEach { date ->
                item(key = "date_$date") {
                    SmallTitle(date, Modifier.padding(top = 8.dp))
                }
                items(grouped[date] ?: emptyList(), key = { it.id }) { d ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.title, style = MiuixTheme.textStyles.headline1,
                                    modifier = Modifier.weight(1f))
                                Text(d.authorName,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(d.content, color = MiuixTheme.colorScheme.onSurface)
                            if (d.images.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("${d.images.size} 张图片",
                                    color = MiuixTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPublish) {
        PublishDiaryDialog(
            onDismiss = { showPublish = false },
            onPublished = { showPublish = false; refresh() }
        )
    }
}

@Composable
private fun PublishDiaryDialog(onDismiss: () -> Unit, onPublished: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var imageUrls by remember { mutableStateOf<List<String>>(emptyList()) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                uploading = true
                val file = File(context.cacheDir, "diary_img_${System.currentTimeMillis()}.jpg")
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    val url = ApiClient.uploadImage("/diaries/images", file)
                    imageUrls = imageUrls + url
                }
                uploading = false
            }
        }
    }

    OverlayDialog(show = true, onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("发布日记", style = MiuixTheme.textStyles.title3)
                TextField(
                    value = title, onValueChange = { title = it },
                    label = "标题"
                )
                TextField(
                    value = content, onValueChange = { content = it },
                    label = "内容"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !uploading,
                        onClick = { imagePicker.launch(arrayOf("image/*")) }
                    ) { Text(if (uploading) "上传中…" else "添加图片") }
                    Spacer(Modifier.width(8.dp))
                    Text("已选 ${imageUrls.size} 张", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = title.isNotBlank() && content.isNotBlank(),
                        onClick = {
                            scope.launch {
                                val body = JSONObject().apply {
                                    put("title", title)
                                    put("content", content)
                                    put("date", java.text.SimpleDateFormat("yyyy-MM-dd",
                                        java.util.Locale.getDefault()).format(java.util.Date()))
                                    put("images", org.json.JSONArray(imageUrls))
                                }
                                ApiClient.createDiary(body)
                                onPublished()
                            }
                        }
                    ) { Text("发布") }
                }
            }
        }
    }
}
