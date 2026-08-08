package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.core.TodoAlarmScheduler
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.TodoItem
import com.linxi.diary.ui.components.GlassCard
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Tab ② 待办：双方待办列表 + 给对方添加（可选普通/强提醒，含本地 AlarmManager 兜底）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen() {
    val scope = rememberCoroutineScope()
    var todos by remember { mutableStateOf<List<TodoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching {
                val data = ApiClient.todos(status = 0)
                todos = (0 until data.length()).map { TodoItem.fromJson(data.getJSONObject(it)) }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Text("+") }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text("待办", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp))

            if (loading) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (todos.isEmpty()) {
                Text("暂无待办，点右下角 + 给对方添加",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(todos, key = { it.id }) { t ->
                        GlassCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, style = MaterialTheme.typography.bodyLarge)
                                    t.note.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val remindText = t.remindAtMs?.let {
                                        val sdf = java.text.SimpleDateFormat(
                                            "MM-dd HH:mm", java.util.Locale.getDefault())
                                        "提醒 ${sdf.format(java.util.Date(it))}" +
                                                if (t.remindType == 1) " · 强" else ""
                                    }
                                    remindText?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            ApiClient.completeTodo(t.id)
                                            TodoAlarmScheduler.cancel(applicationContext(), t.id)
                                        }
                                        refresh()
                                    }
                                }) { Text("完成") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddTodoDialog(
            onDismiss = { showAdd = false },
            onAdded = { todo, context ->
                if (todo.remindAtMs != null) {
                    TodoAlarmScheduler.schedule(
                        context, todo.id, todo.title, todo.remindType, todo.remindAtMs)
                }
                showAdd = false
                refresh()
            }
        )
    }
}

@Composable
private fun applicationContext(): android.content.Context {
    return androidx.compose.ui.platform.LocalContext.current.applicationContext
}

@Composable
private fun AddTodoDialog(
    onDismiss: () -> Unit,
    onAdded: (TodoItem, android.content.Context) -> Unit
) {
    val context = applicationContext()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var remindAtMs by remember { mutableStateOf<Long?>(null) }
    var remindType by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("标题") }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it },
                    label = { Text("备注（可选）") })
                // 提醒时间：预设快捷选项（epoch 毫秒；生产可替换为 DatePicker + TimePicker）
                val presets = listOf(
                    "30 分钟后" to System.currentTimeMillis() + 30 * 60_000L,
                    "1 小时后" to System.currentTimeMillis() + 60 * 60_000L,
                    "明天 9 点" to run {
                        val c = java.util.Calendar.getInstance().apply {
                            add(java.util.Calendar.DAY_OF_YEAR, 1)
                            set(java.util.Calendar.HOUR_OF_DAY, 9)
                            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
                        }
                        c.timeInMillis
                    }
                )
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = remindAtMs?.let {
                            java.text.SimpleDateFormat("MM-dd HH:mm",
                                java.util.Locale.getDefault()).format(java.util.Date(it))
                        } ?: "不提醒",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("提醒时间") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("不提醒") },
                            onClick = { remindAtMs = null; expanded = false })
                        presets.forEach { (label, ms) ->
                            DropdownMenuItem(text = { Text(label) },
                                onClick = { remindAtMs = ms; expanded = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒强度")
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = remindType == 0,
                        onClick = { remindType = 0 },
                        label = { Text("普通") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = remindType == 1,
                        onClick = { remindType = 1 },
                        label = { Text("强提醒") })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank()) {
                scope.launch {
                    val body = JSONObject().apply {
                        put("title", title)
                        put("note", note)
                        put("remind_type", remindType)
                        if (remindAtMs != null) {
                            // 服务端 Todo.RemindAt 为 time.Time，需 RFC3339
                            put("remind_at", isoFromMillis(remindAtMs!!))
                        }
                    }
                    val resp = ApiClient.createTodo(body)
                    onAdded(TodoItem.fromJson(resp), context)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** epoch 毫秒 → RFC3339 字符串（服务端 time.Time 解析用） */
private fun isoFromMillis(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",
        java.util.Locale.US).format(java.util.Date(ms))
