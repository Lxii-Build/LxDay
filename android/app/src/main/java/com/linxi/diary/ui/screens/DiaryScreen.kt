package com.linxi.diary.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.DiaryItem
import com.linxi.diary.ui.components.GlassCard
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Tab ③ 日记：双人共同日记，按日期归档，文字+图片（本地磁盘上传）。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showPublish = true }) { Text("+") }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text("日记", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp))

            if (loading) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (diaries.isEmpty()) {
                Text("还没有日记，记录你们的第一篇吧",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 按日期分组
                    val grouped = diaries.groupBy { it.diaryDate }
                    grouped.keys.sortedDescending().forEach { date ->
                        item(key = "date_$date") {
                            Text(date, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        items(grouped[date] ?: emptyList(), key = { it.id }) { d ->
                            GlassCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(d.title, style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f))
                                    Text(d.authorName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(d.content, style = MaterialTheme.typography.bodyMedium)
                                if (d.images.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("🖼 ${d.images.size} 张图片",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
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

@OptIn(ExperimentalMaterial3Api::class)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发布日记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("标题") }, singleLine = true)
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("内容") }, minLines = 4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !uploading, onClick = {
                        imagePicker.launch(arrayOf("image/*"))
                    }) { Text(if (uploading) "上传中…" else "添加图片") }
                    Spacer(Modifier.width(8.dp))
                    Text("已选 ${imageUrls.size} 张",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && content.isNotBlank()) {
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
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
