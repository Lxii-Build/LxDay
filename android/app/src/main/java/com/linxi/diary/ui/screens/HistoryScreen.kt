package com.linxi.diary.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.BatteryPoint
import com.linxi.diary.data.HistoryEntry
import com.linxi.diary.ui.components.KernelScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 单页条数：滚到底自动加载下一页（此前写死 limit=100 且无分页）。 */
private const val PAGE_SIZE = 50

@Composable
fun HistoryScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(todayStr()) }
    var timeline by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var curve by remember { mutableStateOf<List<BatteryPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var showCurve by remember { mutableStateOf(false) }
    // 失败态与空态必须分开：此前 runCatching 吞掉异常，请求失败与「当日无记录」
    // 共用一句文案，用户完全无法判断是没数据还是网络挂了，且没有任何重试入口。
    var error by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val isToday = date == todayStr()

    fun load(pull: Boolean = false) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            reachedEnd = false
            runCatching {
                timeline = parseHistory(ApiClient.historyTimeline(date, PAGE_SIZE, 0))
                curve = parseCurve(ApiClient.batteryCurve(date))
            }.onFailure {
                error = friendlyError(it)
                timeline = emptyList()
                curve = emptyList()
            }
            if (timeline.size < PAGE_SIZE) reachedEnd = true
            loading = false
            refreshing = false
        }
    }

    fun loadMore() {
        if (loadingMore || reachedEnd || loading || error != null) return
        scope.launch {
            loadingMore = true
            runCatching {
                parseHistory(ApiClient.historyTimeline(date, PAGE_SIZE, timeline.size))
            }.onSuccess { more ->
                if (more.isEmpty() || more.size < PAGE_SIZE) reachedEnd = true
                if (more.isNotEmpty()) timeline = timeline + more
            }.onFailure {
                // 加载更多失败不清空已有数据，仅标记到底避免反复重试打接口
                reachedEnd = true
            }
            loadingMore = false
        }
    }

    LaunchedEffect(date) { load() }
    androidx.activity.compose.BackHandler(onBack = onBack)

    if (showDatePicker) {
        HistoryDatePickerDialog(
            current = date,
            onDismiss = { showDatePicker = false },
            onPick = { picked -> date = picked; showDatePicker = false },
        )
    }

    KernelScreen(
        title = "伴侣状态历史",
        navigationIcon = { com.linxi.diary.ui.components.BackAction(onBack) },
        isRefreshing = refreshing,
        onRefresh = { load(pull = true) },
        loading = loading,
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { date = shiftDate(date, -1) }, modifier = Modifier.weight(1f)) { Text("前一天") }
                        Button(onClick = { date = todayStr() }, enabled = !isToday, modifier = Modifier.weight(1f)) { Text("今天") }
                        // 禁止翻到未来：未来日期永远是空的，此前可以无限往后点。
                        Button(
                            onClick = { date = shiftDate(date, 1) },
                            enabled = !isToday,
                            modifier = Modifier.weight(1f),
                        ) { Text("后一天") }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 日期选择器：此前只能一天一天点过去，跨月查历史极其难用。
                    Button(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("选择日期：$date")
                    }
                }
            }
        }
        // 失败态 + 重试：全 App 此前零个重试按钮。
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
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showCurve = false }, enabled = showCurve, modifier = Modifier.weight(1f)) { Text("时间线") }
                    Button(onClick = { showCurve = true }, enabled = !showCurve, modifier = Modifier.weight(1f)) { Text("电量曲线") }
                }
            }
        }
        if (!loading && error == null) {
            if (showCurve) {
                item {
                    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        if (curve.size < 2) {
                            Text(
                                "当日电量数据不足",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(20.dp),
                            )
                        } else {
                            BatteryCurveChart(curve, Modifier.fillMaxWidth().height(220.dp).padding(16.dp))
                        }
                    }
                }
            } else if (timeline.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                if (isToday) "今天还没有记录" else "这一天没有记录",
                                style = MiuixTheme.textStyles.headline1,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "状态历史来自对方的后台上报。若对方刚安装或长时间未联网，可能暂时没有数据。",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            } else {
                // key 用记录自身的时间戳而非下标：换日期后下标会错位复用旧 item 状态。
                items(timeline, key = { it.ts }) { h ->
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(h.timeLabel, style = MiuixTheme.textStyles.headline1)
                            Text(
                                "电量 ${h.battery}%${if (h.charging) " · 充电" else ""} · " +
                                    "${if (h.screenOn) "亮屏${if (h.locked) "·锁定" else "·解锁"}" else "灭屏"}" +
                                    " · ${h.foregroundApp.ifBlank { "无前台" }} · " + h.ssid.ifBlank { "移动网络" },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
                if (!reachedEnd) {
                    item(key = "__load_more__") {
                        // 滚到底自动拉下一页；同时给一个可点的兜底入口。
                        LaunchedEffect(timeline.size) { loadMore() }
                        if (loadingMore) {
                            com.linxi.diary.ui.components.LoadingRow()
                        } else {
                            Button(
                                onClick = { loadMore() },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            ) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryCurveChart(points: List<BatteryPoint>, modifier: Modifier = Modifier) {
    val lineColor = MiuixTheme.colorScheme.primary
    val chargeColor = MiuixTheme.colorScheme.secondaryContainer
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val minX = points.first().ts.toFloat()
        val maxX = points.last().ts.toFloat()
        val xSpan = (maxX - minX).takeIf { it > 0 } ?: 1f
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = ((point.ts - minX) / xSpan) * size.width
            val y = size.height - (point.battery / 100f) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
        points.zipWithNext().forEach { (start, end) ->
            if (start.charging) {
                drawLine(
                    chargeColor,
                    Offset(((start.ts - minX) / xSpan) * size.width, 0f),
                    Offset(((end.ts - minX) / xSpan) * size.width, 0f),
                    strokeWidth = 6f
                )
            }
        }
    }
}

/**
 * 日期选择：miuix 没有现成的日历组件，这里用「年月日三段步进」的轻量弹窗，
 * 与全 App 的 miuix 观感一致，且不必为一个二级页引入 Material DatePicker。
 * 未来日期一律禁止（历史数据不可能存在于未来）。
 */
@Composable
private fun HistoryDatePickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    val isFuture = draft > todayStr()

    // 沿用项目既有弹窗写法（AddTodoDialog / PrivacyConsentDialog 都是 OverlayDialog）
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = "选择日期",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { draft = shiftDate(draft, -30) }, modifier = Modifier.weight(1f)) { Text("-30 天") }
                Button(onClick = { draft = shiftDate(draft, -7) }, modifier = Modifier.weight(1f)) { Text("-7 天") }
                Button(onClick = { draft = shiftDate(draft, -1) }, modifier = Modifier.weight(1f)) { Text("-1 天") }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { draft = shiftDate(draft, 1) },
                    enabled = shiftDate(draft, 1) <= todayStr(),
                    modifier = Modifier.weight(1f),
                ) { Text("+1 天") }
                Button(
                    onClick = { draft = shiftDate(draft, 7) },
                    enabled = shiftDate(draft, 7) <= todayStr(),
                    modifier = Modifier.weight(1f),
                ) { Text("+7 天") }
                Button(onClick = { draft = todayStr() }, modifier = Modifier.weight(1f)) { Text("今天") }
            }
            Spacer(Modifier.height(12.dp))
            Text(draft, style = MiuixTheme.textStyles.headline1)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { onPick(draft) },
                    enabled = !isFuture,
                    modifier = Modifier.weight(1f),
                ) { Text("查看") }
            }
        }
    }
}

/**
 * 把异常翻译成用户能看懂的中文。
 * ApiClient 已把 HTTP 失败翻成中文 message，这里主要兜网络层异常。
 */
private fun friendlyError(t: Throwable): String {
    val msg = t.message?.takeIf { it.isNotBlank() }
    return when {
        t is java.net.UnknownHostException -> "无法连接服务器，请检查网络"
        t is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
        t is java.io.IOException && msg == null -> "网络异常，请稍后重试"
        msg != null -> msg
        else -> "加载失败，请稍后重试"
    }
}

private fun todayStr(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

private fun shiftDate(date: String, days: Int): String {
    val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    val parsed = format.parse(date) ?: return todayStr()
    val calendar = java.util.Calendar.getInstance().apply { time = parsed; add(java.util.Calendar.DAY_OF_YEAR, days) }
    return format.format(calendar.time)
}

private fun parseHistory(array: org.json.JSONArray): List<HistoryEntry> =
    (0 until array.length()).map { HistoryEntry.fromJson(array.getJSONObject(it)) }

private fun parseCurve(array: org.json.JSONArray): List<BatteryPoint> =
    (0 until array.length()).map { BatteryPoint.fromJson(array.getJSONObject(it)) }
