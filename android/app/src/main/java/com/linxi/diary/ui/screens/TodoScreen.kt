package com.linxi.diary.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.core.TodoAlarmScheduler
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.data.TodoItem
import com.linxi.diary.debug.DemoContent
import com.linxi.diary.debug.DemoMode
import com.linxi.diary.ui.components.KernelScreen
import com.linxi.diary.ui.components.LxButton
import com.linxi.diary.ui.components.LxButtonVariant
import com.linxi.diary.ui.navigation.LocalMainFabState
import com.linxi.diary.ui.theme.BrandBlue
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tab ② 待办：顶部搜索框（渐显取消）+ 仿 KernelSU 模块卡（标题/频率/可展开详情）+ 随滚动渐隐的 FAB。
 */
@Composable
fun TodoScreen() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val focusManager = LocalFocusManager.current
    var todos by remember { mutableStateOf<List<TodoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var rawQuery by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val demo = DemoMode.shouldUseDemo(UserPrefs.demoMode)
    val profile = if (demo) null else ProfileRuntime.repository.profile.collectAsState().value
    val meId = profile?.me?.id ?: UserPrefs.myUserId
    val meName = (profile?.me?.nickname ?: "").ifBlank { "我" }
    val partnerId = profile?.partner?.id ?: 0L
    val partnerName = (profile?.partner?.nickname ?: UserPrefs.partnerName).ifBlank { "对方" }
    fun nameOf(id: Long): String = when (id) {
        meId -> meName
        partnerId -> partnerName
        else -> "对方"
    }

    // 搜索去首尾空格 + 150ms 防抖，避免每次按键都重算过滤。
    LaunchedEffect(rawQuery) {
        delay(150)
        query = rawQuery.trim()
    }

    fun refresh(pull: Boolean = false) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            runCatching {
                val data = ApiClient.todos(status = 0)
                todos = (0 until data.length()).map { TodoItem.fromJson(data.getJSONObject(it)) }
            }
            loading = false
            refreshing = false
        }
    }

    val mainFabState = LocalMainFabState.current
    DisposableEffect(mainFabState) {
        mainFabState.todoAction = { showAdd = true }
        onDispose {
            mainFabState.todoAction = null
            mainFabState.fabVisible = true
        }
    }
    // FAB 随列表滚动渐隐：向下滚动隐藏，回到顶部或向上滚动显示。
    LaunchedEffect(listState) {
        var lastIndex = 0
        var lastOffset = 0
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                mainFabState.fabVisible = when {
                    index == 0 && offset < 60 -> true
                    index < lastIndex -> true
                    index == lastIndex && offset < lastOffset -> true
                    index > lastIndex || offset > lastOffset -> false
                    else -> mainFabState.fabVisible
                }
                lastIndex = index; lastOffset = offset
            }
    }
    LaunchedEffect(demo) {
        if (demo) {
            todos = DemoContent.todos
            loading = false
        } else {
            refresh()
        }
    }

    val filtered = remember(todos, query, meId, partnerId) {
        if (query.isBlank()) todos
        else todos.filter {
            it.title.contains(query, true) || it.note.contains(query, true) ||
                nameOf(it.creatorId).contains(query, true) || nameOf(it.assigneeId).contains(query, true)
        }
    }

    KernelScreen(
        title = "待办",
        listState = listState,
        isRefreshing = refreshing,
        onRefresh = if (demo) null else ({ refresh(pull = true) }),
        header = {
            SearchRow(
                query = rawQuery,
                onQueryChange = { rawQuery = it },
                showCancel = searchFocused || rawQuery.isNotEmpty(),
                onFocusChange = { searchFocused = it },
                onCancel = {
                    rawQuery = ""
                    query = ""
                    searchFocused = false
                    focusManager.clearFocus()
                },
            )
        },
    ) {
        when {
            loading -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            todos.isEmpty() -> item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有待办", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f))
                    Spacer(Modifier.height(4.dp))
                    Text("点右下角 + 给对方添加", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f))
                }
            }
            filtered.isEmpty() -> item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("没有匹配「$query」的待办", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f))
                }
            }
            else -> items(filtered, key = { it.id }) { t ->
                TodoCard(
                    todo = t,
                    demo = demo,
                    creatorName = nameOf(t.creatorId),
                    assigneeName = nameOf(t.assigneeId),
                    onToggleRemind = { enabled ->
                        scope.launch {
                            runCatching {
                                ApiClient.updateTodo(t.id, JSONObject().put("remind_enabled", enabled))
                                if (!enabled) {
                                    TodoAlarmScheduler.cancel(context, t.id)
                                } else if (t.remindAtMs != null) {
                                    TodoAlarmScheduler.schedule(context, t.id, t.title, t.remindType, t.remindAtMs)
                                }
                            }
                            refresh()
                        }
                    },
                    onComplete = {
                        scope.launch {
                            runCatching {
                                ApiClient.completeTodo(t.id)
                                TodoAlarmScheduler.cancel(context, t.id)
                            }
                            refresh()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            runCatching {
                                ApiClient.deleteTodo(t.id)
                                TodoAlarmScheduler.cancel(context, t.id)
                            }
                            refresh()
                        }
                    },
                )
            }
        }
    }

    if (showAdd) {
        AddTodoDialog(
            meId = meId,
            partnerId = partnerId,
            meName = meName,
            partnerName = partnerName,
            onDismiss = { showAdd = false },
            onAdded = { todo, ctx ->
                if (todo.remindEnabled && todo.remindAtMs != null) {
                    TodoAlarmScheduler.schedule(ctx, todo.id, todo.title, todo.remindType, todo.remindAtMs)
                }
                showAdd = false
                refresh()
            }
        )
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    showCancel: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            label = "搜索待办",
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = "搜索",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChange(it.isFocused) },
        )
        AnimatedVisibility(visible = showCancel) {
            Text(
                "取消",
                color = MiuixTheme.colorScheme.primary,
                fontSize = 15.sp,
                modifier = Modifier.clickable { onCancel() }.padding(start = 12.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun TodoCard(
    todo: TodoItem,
    demo: Boolean,
    creatorName: String,
    assigneeName: String,
    onToggleRemind: (Boolean) -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasNote = todo.note.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (hasNote) ({ expanded = !expanded }) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (demo) "${todo.title} · 示例" else todo.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "提出者：$creatorName · 被提醒：$assigneeName",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    val schedule = buildScheduleLine(todo)
                    if (schedule.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            schedule,
                            fontSize = 13.sp,
                            color = if (todo.remindEnabled) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
                if (!demo) {
                    Switch(checked = todo.remindEnabled, onCheckedChange = onToggleRemind)
                }
            }
            if (hasNote) {
                Spacer(Modifier.height(6.dp))
                Text(
                    todo.note,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!demo) {
                HorizontalDivider(
                    Modifier.padding(vertical = 8.dp),
                    thickness = 0.5.dp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onComplete) {
                        Icon(Icons.Filled.Check, contentDescription = "完成", tint = MiuixTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(MiuixIcons.Delete, contentDescription = "删除", tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

private val WEEKDAY_CHARS = listOf("一", "二", "三", "四", "五", "六", "日")

/** bit0=周一..bit6=周日 → “一三五” */
private fun weekdayLabel(mask: Int): String =
    (0..6).filter { (mask shr it) and 1 == 1 }.joinToString("") { WEEKDAY_CHARS[it] }

private fun buildScheduleLine(t: TodoItem): String {
    if (t.remindAtMs == null) return ""
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t.remindAtMs))
    val mdhm = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t.remindAtMs))
    val base = when (t.repeatType) {
        1 -> "每天 $hm"
        2 -> "每周${weekdayLabel(t.weekdays)} $hm"
        else -> "仅一次 $mdhm"
    }
    return base + if (t.remindType == 1) " · 强提醒" else ""
}

@Composable
private fun AddTodoDialog(
    meId: Long,
    partnerId: Long,
    meName: String,
    partnerName: String,
    onDismiss: () -> Unit,
    onAdded: (TodoItem, android.content.Context) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var assigneeToPartner by remember { mutableStateOf(true) }   // 被提醒者：true=对方 false=我自己
    var remindEnabled by remember { mutableStateOf(false) }
    var strong by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(0) }   // 0 仅一次, 1 重复
    var weekdays by remember { mutableStateOf(0) }
    var customTime by remember { mutableStateOf(false) }
    val now = remember { Calendar.getInstance() }
    var year by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(now.get(Calendar.MONTH) + 1) }
    var day by remember { mutableStateOf(now.get(Calendar.DAY_OF_MONTH)) }
    var hour by remember { mutableStateOf(now.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableStateOf(0) }

    val repeatType = when {
        repeatMode == 0 -> 0
        weekdays == 0b1111111 -> 1
        else -> 2
    }
    val canAdd = title.isNotBlank() && (!remindEnabled || repeatMode == 0 || weekdays != 0)

    fun applyPreset(cal: Calendar) {
        repeatMode = 0
        year = cal.get(Calendar.YEAR); month = cal.get(Calendar.MONTH) + 1; day = cal.get(Calendar.DAY_OF_MONTH)
        hour = cal.get(Calendar.HOUR_OF_DAY); minute = cal.get(Calendar.MINUTE)
    }

    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = "添加待办",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(value = title, onValueChange = { title = it }, label = "事件", singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = note, onValueChange = { note = it }, label = "详情（可选）", modifier = Modifier.fillMaxWidth())

            Text("被提醒者", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChipToggle(partnerName.ifBlank { "对方" }, assigneeToPartner) { assigneeToPartner = true }
                ChipToggle(meName.ifBlank { "我自己" }, !assigneeToPartner) { assigneeToPartner = false }
            }

            LabeledSwitchRow("是否提醒", remindEnabled) { remindEnabled = it }

            if (remindEnabled) {
                Text("提醒频率", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChipToggle("仅一次", repeatMode == 0) { repeatMode = 0 }
                    ChipToggle("自定义", repeatMode == 1) { repeatMode = 1; if (weekdays == 0) weekdays = 0b1111111 }
                }
                if (repeatMode == 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..6).forEach { i ->
                            WeekdayCircle(WEEKDAY_CHARS[i], (weekdays shr i) and 1 == 1) {
                                weekdays = weekdays xor (1 shl i)
                            }
                        }
                    }
                    Text(
                        if (weekdays == 0b1111111) "全选 = 每天" else if (weekdays == 0) "请选择至少一天" else "每周${weekdayLabel(weekdays)}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                Text("提醒时间", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChipToggle("30 分钟后", false) { applyPreset((now.clone() as Calendar).apply { timeInMillis = System.currentTimeMillis() + 30 * 60_000L }) }
                    ChipToggle("1 小时后", false) { applyPreset((now.clone() as Calendar).apply { timeInMillis = System.currentTimeMillis() + 60 * 60_000L }) }
                    ChipToggle("今晚 20:00", false) { applyPreset(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0) }) }
                    ChipToggle("明早 08:00", false) { applyPreset(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0) }) }
                    ChipToggle(if (customTime) "自定义 ▲" else "自定义 ▼", customTime) { customTime = !customTime }
                }
                Text(
                    "已选：" + selectedTimeLabel(repeatType, year, month, day, hour, minute, weekdays),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
                if (customTime) {
                    if (repeatType == 0) {
                        StepperRow("年", year, 2020, 2100) { year = it }
                        StepperRow("月", month, 1, 12) { month = it }
                        StepperRow("日", day, 1, 31) { day = it }
                    }
                    StepperRow("时", hour, 0, 23) { hour = it }
                    StepperRow("分", minute, 0, 59) { minute = it }
                }

                LabeledSwitchRow("强提醒（闹钟音量）", strong) { strong = it }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LxButton("取消", onClick = onDismiss, variant = LxButtonVariant.Neutral, modifier = Modifier.weight(1f))
                LxButton(
                    text = "添加",
                    onClick = {
                        scope.launch {
                            val ms: Long? = if (!remindEnabled) null else {
                                val cal = Calendar.getInstance()
                                if (repeatType == 0) {
                                    cal.set(year, month - 1, day, hour, minute, 0); cal.set(Calendar.MILLISECOND, 0)
                                } else {
                                    cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, minute)
                                    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                                    if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
                                }
                                cal.timeInMillis
                            }
                            val assigneeId = if (assigneeToPartner) partnerId else meId
                            val body = JSONObject().apply {
                                put("title", title)
                                put("note", note)
                                if (assigneeId > 0) put("assignee_id", assigneeId)
                                put("remind_enabled", remindEnabled)
                                put("remind_type", if (remindEnabled && strong) 1 else 0)
                                put("repeat_type", if (remindEnabled) repeatType else 0)
                                if (remindEnabled && repeatType == 2) put("weekdays", weekdays)
                                if (ms != null) put("remind_at", isoFromMillis(ms))
                            }
                            runCatching { ApiClient.createTodo(body) }
                                .onSuccess { onAdded(TodoItem.fromJson(it), context) }
                        }
                    },
                    enabled = canAdd,
                    variant = LxButtonVariant.Positive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun selectedTimeLabel(repeatType: Int, year: Int, month: Int, day: Int, hour: Int, minute: Int, weekdays: Int): String {
    val hm = "%02d:%02d".format(hour, minute)
    return when (repeatType) {
        1 -> "每天 $hm"
        2 -> "每周${weekdayLabel(weekdays)} $hm"
        else -> "%04d-%02d-%02d %s".format(year, month, day, hm)
    }
}

@Composable
private fun LabeledSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipToggle(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) BrandBlue else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WeekdayCircle(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (selected) BrandBlue else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.weight(1f))
        StepBtn("−", value > min) { onChange((value - 1).coerceAtLeast(min)) }
        Text("  %02d  ".format(value), color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
        StepBtn("+", value < max) { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun StepBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

private fun isoFromMillis(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ms))


