package com.linxi.diary.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
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
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HistoryScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(todayStr()) }
    var timeline by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var curve by remember { mutableStateOf<List<BatteryPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var showCurve by remember { mutableStateOf(false) }

    fun load(pull: Boolean = false) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            runCatching {
                timeline = parseHistory(ApiClient.historyTimeline(date, 100, 0))
                curve = parseCurve(ApiClient.batteryCurve(date))
            }
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(date) { load() }
    androidx.activity.compose.BackHandler(onBack = onBack)

    KernelScreen(
        title = "伴侣状态历史",
        navigationIcon = { com.linxi.diary.ui.components.BackAction(onBack) },
        isRefreshing = refreshing,
        onRefresh = { load(pull = true) },
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { date = shiftDate(date, -1) }, modifier = Modifier.weight(1f)) { Text("前一天") }
                    Button(onClick = { date = todayStr() }, modifier = Modifier.weight(1f)) { Text("今天") }
                    Button(onClick = { date = shiftDate(date, 1) }, modifier = Modifier.weight(1f)) { Text("后一天") }
                }
            }
        }
        // 加载动画紧贴日期切换组件下方显示（原先位于时间线/曲线切换卡之后，位置偏下需下拉才能看到）
        if (loading) {
            item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showCurve = false }, enabled = showCurve, modifier = Modifier.weight(1f)) { Text("时间线") }
                    Button(onClick = { showCurve = true }, enabled = !showCurve, modifier = Modifier.weight(1f)) { Text("电量曲线") }
                }
            }
        }
        if (!loading) {
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
                        Text("当日暂无记录", color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(20.dp))
                    }
                }
            } else {
                itemsIndexed(timeline, key = { index, _ -> index }) { _, h ->
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
