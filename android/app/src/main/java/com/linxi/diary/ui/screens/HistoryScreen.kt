package com.linxi.diary.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 对方状态历史：时间线（按天）+ 24h 电量曲线（自绘 Canvas）。
 */
@Composable
fun HistoryScreen(onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(todayStr()) }
    var timeline by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var curve by remember { mutableStateOf<List<BatteryPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCurve by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            runCatching {
                timeline = parseHistory(ApiClient.historyTimeline(date, 100, 0))
                curve = parseCurve(ApiClient.batteryCurve(date))
            }
            loading = false
        }
    }

    LaunchedEffect(date) { load() }

    KernelScreen(
        title = "历史状态",
        actions = {
            TextButton(onClick = onBack) { Text("返回") }
        }
    ) {
        // 日期切换
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { date = shiftDate(date, -1) }) { Text("前一天") }
                OutlinedButton(onClick = { date = todayStr() }) { Text("今天") }
                OutlinedButton(onClick = { date = shiftDate(date, 1) }) { Text("后一天") }
            }
        }
        // 时间线 / 曲线 切换
        item {
            Row {
                FilterChip(selected = !showCurve, onClick = { showCurve = false },
                    label = { Text("时间线") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = showCurve, onClick = { showCurve = true },
                    label = { Text("电量曲线") })
            }
        }
        if (loading) {
            item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        } else if (showCurve) {
            item { BatteryCurveChart(curve, Modifier.fillMaxWidth().height(200.dp)) }
        } else if (timeline.isEmpty()) {
            item { Text("当日暂无记录", color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        } else {
            itemsIndexed(timeline, key = { index, _ -> index }) { _, h ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(h.timeLabel, style = MiuixTheme.textStyles.headline1)
                        Text("电量 ${h.battery}%${if (h.charging) " · 充电" else ""} · " +
                                "${if (h.screenOn) "亮屏${if (h.locked) "·锁定" else "·解锁"}" else "灭屏"}" +
                                " · ${h.foregroundApp.ifBlank { "无前台" }} · " +
                                (h.ssid.ifBlank { "移动网络" }),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

/** 24h 电量曲线（自绘 Canvas） */
@Composable
private fun BatteryCurveChart(points: List<BatteryPoint>, modifier: Modifier = Modifier) {
    val lineColor = MiuixTheme.colorScheme.primary
    val chargeColor = MiuixTheme.colorScheme.secondaryContainer

    Canvas(modifier = modifier.padding(top = 8.dp, bottom = 8.dp)) {
        if (points.size < 2) {
            return@Canvas
        }
        val minX = points.first().ts.toFloat()
        val maxX = points.last().ts.toFloat()
        val xSpan = (maxX - minX).takeIf { it > 0 } ?: 1f
        val w = size.width
        val h = size.height

        val path = Path()
        points.forEachIndexed { i, p ->
            val x = ((p.ts - minX) / xSpan) * w
            val y = h - (p.battery / 100f) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))

        // 充电区间高亮
        points.zipWithNext().forEach { (a, b) ->
            if (a.charging) {
                val x1 = ((a.ts - minX) / xSpan) * w
                val x2 = ((b.ts - minX) / xSpan) * w
                drawLine(
                    color = chargeColor,
                    start = Offset(x1, 0f),
                    end = Offset(x2, 0f),
                    strokeWidth = 6f
                )
            }
        }
    }
}

private fun todayStr(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date())

private fun shiftDate(date: String, days: Int): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    val d = sdf.parse(date) ?: return todayStr()
    val cal = java.util.Calendar.getInstance().apply { time = d; add(java.util.Calendar.DAY_OF_YEAR, days) }
    return sdf.format(cal.time)
}

private fun parseHistory(arr: org.json.JSONArray): List<HistoryEntry> {
    return (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
}

private fun parseCurve(arr: org.json.JSONArray): List<BatteryPoint> {
    return (0 until arr.length()).map { BatteryPoint.fromJson(arr.getJSONObject(it)) }
}
