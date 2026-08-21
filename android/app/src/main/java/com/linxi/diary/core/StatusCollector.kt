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

    /** 上一次成功取到的前台应用，配合 [ForegroundAppPolicy.CACHE_TTL_MS] 使用。 */
    @Volatile
    private var cachedForeground: Pair<String, String>? = null

    @Volatile
    private var cachedForegroundAt: Long = 0L

    /**
     * 前台 APP（包名, 应用名）。无授权、息屏或异常返回 null。
     *
     * 三处修复（详见 [ForegroundAppPolicy] 的说明）：
     *   ① 查询窗口从 24 小时缩到 60 秒（逐级回退），遍历量从上万条降到几十条；
     *   ② 认 PAUSED/STOPPED —— 回桌面后不再显示上一个应用；
     *   ③ 息屏/AOD 时根本不查，直接返回 null。
     */
    fun foregroundApp(c: Context, screenState: ScreenState = ScreenState.On): Pair<String, String>? {
        if (!ForegroundAppPolicy.shouldQuery(screenState, hasUsageAccess(c))) {
            // 息屏时清掉缓存：否则一旦亮屏，可能先闪一下几分钟前的旧应用。
            if (screenState != ScreenState.On) {
                cachedForeground = null
                cachedForegroundAt = 0L
            }
            return null
        }
        return try {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()

            // 逐级放大窗口：60s → 5min → 30min → 6h。
            // 用户连续停在同一个应用里时，短窗口内一条事件都没有，必须回退才能判对。
            for (window in ForegroundAppPolicy.windowSequence()) {
                val collected = readEvents(usm, now - window, now)
                if (collected.isEmpty()) continue
                val pkg = ForegroundAppPolicy.resolve(collected)
                if (pkg != null) {
                    val result = pkg to appName(c, pkg)
                    cachedForeground = result
                    cachedForegroundAt = now
                    return result
                }
                // 明确判定为"在桌面"（最后一个事件是 PAUSED/STOPPED）：
                // 这是有效结论，不该再放大窗口去翻更早的记录。
                cachedForeground = null
                cachedForegroundAt = now
                return null
            }
            // 所有窗口都没有事件：用缓存兜一下，避免状态在"有/无"之间闪。
            if (ForegroundAppPolicy.cacheUsable(cachedForegroundAt, now)) cachedForeground else null
        } catch (t: Throwable) {
            Logs.w(TAG, "foregroundApp 读取异常", t)
            null
        }
    }

    /** 读一段时间窗内的相关事件，转成与 Android 无关的精简结构。 */
    private fun readEvents(
        usm: UsageStatsManager,
        beginMs: Long,
        endMs: Long,
    ): List<ForegroundAppPolicy.Event> {
        val out = ArrayList<ForegroundAppPolicy.Event>(32)
        val events = usm.queryEvents(beginMs, endMs)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val type = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND,
                -> ForegroundAppPolicy.Type.Resumed

                UsageEvents.Event.ACTIVITY_PAUSED,
                @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND,
                -> ForegroundAppPolicy.Type.Paused

                UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundAppPolicy.Type.Stopped
                else -> null
            } ?: continue
            val pkg = e.packageName ?: continue
            out += ForegroundAppPolicy.Event(pkg, e.timeStamp, type)
        }
        return out
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

    /**
     * 聚合完整状态快照。任一部分失败不影响整体（分布 try/catch）。
     *
     * **屏幕状态与锁屏一律现读现取**（[ScreenStateProbe]），不再读
     * `DeviceStatusHolder.screenOn` 那个初值硬编码为 `true` 的字段——
     * 进程被闹钟/开机在息屏时拉起，旧实现的第一次上报必然是错的"亮屏"。
     *
     * **前台应用只在亮屏时查**：息屏时报 null，不再挂着息屏前那个应用。
     */
    fun collectAll(c: Context): DeviceStatus {
        val (level, charging) = battery(c)
        val (ssid, net) = network(c)
        val screen = ScreenStateProbe.current(c)
        // 顺手同步给 Holder：通知栏与 UI 里仍有读它的地方，保持一致避免两处显示不同。
        DeviceStatusHolder.screenOn = screen.reportAsOn
        val locked = ScreenStateProbe.isLocked(c)
        DeviceStatusHolder.isLocked = locked
        return DeviceStatus(
            batteryLevel = level,
            isCharging = charging,
            screenOn = screen.reportAsOn,
            isLocked = locked,
            foregroundApp = foregroundApp(c, screen),
            music = DeviceStatusHolder.music,
            ssid = ssid,
            network = net,
            usage = dailyUsage(c)
        )
    }
}