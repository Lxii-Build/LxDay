package com.linxi.diary.core

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.KeyguardManager
import android.os.Process
import com.linxi.diary.util.TimeUtil

/**
 * 手机状态采集核心。
 * 权限前提：使用情况访问 / 定位（读 WiFi）/ 通知使用权（音乐，见 MediaNotificationListener）。
 */
object StatusCollector {

    /** 电量 + 是否充电 */
    fun battery(c: Context): Pair<Int, Boolean> {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return level to (st == BatteryManager.BATTERY_STATUS_CHARGING ||
                st == BatteryManager.BATTERY_STATUS_FULL)
    }

    /** 前台 APP（包名, 应用名）。无「使用情况访问」授权返回 null */
    fun foregroundApp(c: Context): Pair<String, String>? {
        if (!hasUsageAccess(c)) return null
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 86_400_000L, end) // 近 24h 窗口
        val e = UsageEvents.Event()
        var pkg: String? = null
        var lastTs = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp > lastTs &&
                (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                 e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)) {
                pkg = e.packageName
                lastTs = e.timeStamp
            }
        }
        return pkg?.let { it to appName(c, it) }
    }

    /** 当日各应用使用时长统计（分钟） */
    fun dailyUsage(c: Context): List<AppUsage> {
        if (!hasUsageAccess(c)) return emptyList()
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = TimeUtil.dayStartMillis()
        val map = usm.queryAndAggregateUsageStats(start, start + 86_400_000L)
        return map.values
            .filter { !it.packageName.isNullOrBlank() && it.totalTimeInForeground > 0 }
            .map { AppUsage(it.packageName, appName(c, it.packageName), it.totalTimeInForeground / 60_000) }
            .sortedByDescending { it.minutes }
    }

    /**
     * 网络状态：(WiFi名|null=移动网络, 类型)
     * Android 10+ 读取 WiFi 名称必须已授予定位权限且定位服务开启。
     */
    fun network(c: Context): Pair<String?, String> {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (hasWifi) {
            val wifi = c.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wifi.connectionInfo?.ssid
                ?.removePrefix("\"").removeSuffix("\"")
                ?.takeIf { it != "<unknown ssid>" && it != "unknown" }
            return ssid to "wifi"
        }
        return null to "cellular"
    }

    private fun hasUsageAccess(c: Context): Boolean {
        val am = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try {
            val mode = am.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    private fun appName(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(
            c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { pkg }

    /** 聚合完整状态快照 */
    fun collectAll(c: Context): DeviceStatus {
        val (level, charging) = battery(c)
        val (ssid, net) = network(c)
        return DeviceStatus(
            batteryLevel = level,
            isCharging = charging,
            screenOn = DeviceStatusHolder.screenOn,
            isLocked = (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked,
            foregroundApp = foregroundApp(c),
            music = DeviceStatusHolder.music,
            ssid = ssid,
            network = net,
            usage = dailyUsage(c)
        )
    }
}
