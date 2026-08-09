package com.linxi.diary.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import com.linxi.diary.debug.DemoContent
import com.linxi.diary.debug.DemoMode
import com.linxi.diary.ui.navigation.LocalMainFabState
import com.linxi.diary.util.UserPrefs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.diary.core.TodoAlarmScheduler
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.TodoItem
import com.linxi.diary.ui.components.KernelScreen
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tab ② 待办（照抄 KernelSU 列表布局）：KernelScreen 骨架 + Card 包裹的 BasicComponent 列表行。
 */
@Composable
fun TodoScreen() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
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

    val demo = DemoMode.shouldUseDemo(UserPrefs.demoMode)
    val mainFabState = LocalMainFabState.current
    DisposableEffect(mainFabState, demo) {
        mainFabState.todoAction = if (demo) null else ({ showAdd = true })
        onDispose { mainFabState.todoAction = null }
    }
    LaunchedEffect(demo) {
        if (demo) {
            todos = DemoContent.todos
            loading = false
        } else {
            refresh()
        }
    }

    KernelScreen(title = "待办") {
        if (loading) {
            item { androidx.compose.material3.CircularProgressIndicator(Modifier.padding(24.dp)) }
        } else if (todos.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有待办", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f))
                    Spacer(Modifier.height(4.dp))
                    Text("点右下角 + 给对方添加", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f))
                }
            }
        } else {
            items(todos, key = { it.id }) { t ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = if (demo) "${t.title} · 示例" else t.title,
                        summary = buildSummary(t),
                        endActions = if (demo) null else ({
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        ApiClient.completeTodo(t.id)
                                        TodoAlarmScheduler.cancel(context, t.id)
                                    }
                                    refresh()
                                }
                            }) {
                                Icon(Icons.Default.Check,
                                    contentDescription = "完成",
                                    tint = MiuixTheme.colorScheme.primary)
                            }
                        })
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddTodoDialog(
            onDismiss = { showAdd = false },
            onAdded = { todo, ctx ->
                if (todo.remindAtMs != null) {
                    TodoAlarmScheduler.schedule(
                        ctx, todo.id, todo.title, todo.remindType, todo.remindAtMs)
                }
                showAdd = false
                refresh()
            }
        )
    }
}

private fun buildSummary(t: TodoItem): String {
    val remind = t.remindAtMs?.let {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        "提醒 ${sdf.format(java.util.Date(it))}" + if (t.remindType == 1) " · 强" else ""
    } ?: ""
    val note = t.note.takeIf { it.isNotBlank() }?.let { it } ?: ""
    return listOf(remind, note).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
private fun AddTodoDialog(
    onDismiss: () -> Unit,
    onAdded: (TodoItem, android.content.Context) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var remindAtMs by remember { mutableStateOf<Long?>(null) }
    var remindType by remember { mutableStateOf(0) }

    OverlayDialog(show = true, onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("添加待办", style = MiuixTheme.textStyles.title3)
                TextField(
                    value = title, onValueChange = { title = it },
                    label = "标题"
                )
                TextField(
                    value = note, onValueChange = { note = it },
                    label = "备注（可选）"
                )
                // 提醒时间预设
                val presets = listOf(
                    "不提醒" to null,
                    "30 分钟后" to (System.currentTimeMillis() + 30 * 60_000L),
                    "1 小时后" to (System.currentTimeMillis() + 60 * 60_000L),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f))
                    Spacer(Modifier.weight(1f))
                    presets.forEach { (label, ms) ->
                        Button(
                            onClick = { remindAtMs = ms },
                            cornerRadius = 12.dp
                        ) { Text(label) }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                // 提醒强度
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("强度", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f))
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { remindType = 0 },
                        cornerRadius = 12.dp
                    ) { Text("普通") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { remindType = 1 },
                        cornerRadius = 12.dp
                    ) { Text("强提醒") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismiss,
                        cornerRadius = 12.dp
                    ) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = title.isNotBlank(),
                        onClick = {
                            scope.launch {
                                val body = JSONObject().apply {
                                    put("title", title)
                                    put("note", note)
                                    put("remind_type", remindType)
                                    if (remindAtMs != null) {
                                        put("remind_at", isoFromMillis(remindAtMs!!))
                                    }
                                }
                                val resp = ApiClient.createTodo(body)
                                onAdded(TodoItem.fromJson(resp), context)
                            }
                        },
                        cornerRadius = 12.dp
                    ) { Text("添加") }
                }
            }
        }
    }
}

private fun isoFromMillis(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",
        java.util.Locale.US).format(java.util.Date(ms))
