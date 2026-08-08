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
import android.os.Process
import com.linxi.diary.util.Logs
import com.linxi.diary.util.TimeUtil

/**
 * 手机状态采集核心。所有方法内部自带异常防护：
 * - 未授予相关权限或系统服务不可用时返回安全默认值，绝不抛异常导致闪退；
 * - 前台 APP / 用量依赖「使用情况访问」，未授权返回 null / 空列表。
 */
object StatusCollector {

    private const val TAG = "StatusCollector"

    /** 电量 + 是否充电。BatteryManager 缺失时返回 (0,false)，不崩溃 */
    fun battery(c: Context): Pair<Int, Boolean> {
        return try {
            val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val st = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            level to (st == BatteryManager.BATTERY_STATUS_CHARGING ||
                    st == BatteryManager.BATTERY_STATUS_FULL)
        } catch (t: Throwable) {
            Logs.w(TAG, "battery 采集异常", t)
            0 to false
        }
    }

    /** 前台 APP（包名, 应用名）。无授权或异常返回 null */
    fun foregroundApp(c: Context): Pair<String, String>? {
        if (!hasUsageAccess(c)) return null
        return try {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 86_400_000L, end)
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
            pkg?.let { it to appName(c, it) }
        } catch (t: Throwable) {
            Logs.w(TAG, "foregroundApp 读取异常", t)
            null
        }
    }

    /** 当日各应用使用时长（分钟）。无授权或异常返回空列表 */
    fun dailyUsage(c: Context): List<AppUsage> {
        if (!hasUsageAccess(c)) return emptyList()
        return try {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = TimeUtil.dayStartMillis()
            val map = usm.queryAndAggregateUsageStats(start, start + 86_400_000L)
            map.values
                .filter { !it.packageName.isNullOrBlank() && it.totalTimeInForeground > 0 }
                .map { AppUsage(it.packageName, appName(c, it.packageName), it.totalTimeInForeground / 60_000) }
                .sortedByDescending { it.minutes }
        } catch (t: Throwable) {
            Logs.w(TAG, "dailyUsage 读取异常", t)
            emptyList()
        }
    }

    /**
     * 网络状态：(WiFi名|null=移动网络, 类型)。
     * Android 10+ 读 WiFi 名需定位权限；未授权或异常返回 (null,"wifi") 不崩溃。
     */
    fun network(c: Context): Pair<String?, String> {
        return try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            if (hasWifi) {
                val wifi = c.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val raw = wifi.connectionInfo?.ssid
                val ssid = raw
                    ?.removePrefix("\"")?.removeSuffix("\"")
                    ?.takeIf { it != "<unknown ssid>" && it != "unknown" }
                ssid to "wifi"
            } else {
                null to "cellular"
            }
        } catch (t: Throwable) {
            Logs.w(TAG, "network 读取异常", t)
            null to "cellular"
        }
    }

    private fun hasUsageAccess(c: Context): Boolean {
        return try {
            val am = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = am.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            Logs.w(TAG, "hasUsageAccess 检查异常", t)
            false
        }
    }

    private fun appName(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(
            c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { pkg }

    /** 当前是否锁屏（KeyguardManager 属 android.app 包）；服务不可用视为锁屏 */
    private fun isDeviceLocked(c: Context): Boolean = try {
        val km = c.getSystemService(android.app.KeyguardManager::class.java)
        km?.isKeyguardLocked ?: true
    } catch (t: Throwable) {
        Logs.w(TAG, "isDeviceLocked 读取异常", t)
        true
    }

    /** 聚合完整状态快照。任一部分失败不影响整体（分布 try/catch） */
    fun collectAll(c: Context): DeviceStatus {
        val (level, charging) = battery(c)
        val (ssid, net) = network(c)
        return DeviceStatus(
            batteryLevel = level,
            isCharging = charging,
            screenOn = DeviceStatusHolder.screenOn,
            isLocked = isDeviceLocked(c),
            foregroundApp = foregroundApp(c),
            music = DeviceStatusHolder.music,
            ssid = ssid,
            network = net,
            usage = dailyUsage(c)
        )
    }
}