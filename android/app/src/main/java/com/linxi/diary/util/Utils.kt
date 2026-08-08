package com.linxi.diary.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SharedPreferences 封装 */
object UserPrefs {
    private const val PREF = "linxi_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = sp.getString("token", null)
        set(v) { sp.edit().putString("token", v).apply() }

    var partnerName: String
        get() = sp.getString("partner_name", "") ?: ""
        set(v) { sp.edit().putString("partner_name", v).apply() }

    var statusCardEnabled: Boolean
        get() = sp.getBoolean("status_card", true)
        set(v) { sp.edit().putBoolean("status_card", v).apply() }

    // 触发即时推送的 WiFi 白名单（连接该 WiFi 时提醒）
    var watchSsid: String
        get() = sp.getString("watch_ssid", "") ?: ""
        set(v) { sp.edit().putString("watch_ssid", v).apply() }

    // 深色模式：默认跟随系统；null 表示跟随系统
    var darkMode: Int // 0跟随系统 1浅色 2深色
        get() = sp.getInt("dark_mode", 0)
        set(v) { sp.edit().putInt("dark_mode", v).apply() }

    // 主题模式（ColorMode.value）：0跟随系统 1浅色 2深色 3深色AMOLED
    var colorMode: Int
        get() = sp.getInt("color_mode", 0)
        set(v) { sp.edit().putInt("color_mode", v).apply() }

    // 种子色：0=跟随系统动态色；否则为 ARGB Int
    var keyColor: Int
        get() = sp.getInt("key_color", 0)
        set(v) { sp.edit().putInt("key_color", v).apply() }

    // 诊断开关：液态玻璃 Tab 栏（backdrop AGSL 可能在部分 GPU 崩溃）
    // 默认关闭：先确保基础可用，再逐步开启定位
    var liquidGlassEnabled: Boolean
        get() = sp.getBoolean("liquid_glass", false)
        set(v) { sp.edit().putBoolean("liquid_glass", v).apply() }

    // 状态共享总开关：false = 停止采集 + 本地清空
    var sharingEnabled: Boolean
        get() = sp.getBoolean("sharing_enabled", false) // 默认关，绑定+授权后才开
        set(v) { sp.edit().putBoolean("sharing_enabled", v).apply() }

    // 是否已完成双方知情授权
    var privacyConsented: Boolean
        get() = sp.getBoolean("privacy_consented", false)
        set(v) { sp.edit().putBoolean("privacy_consented", v).apply() }

    // 绑定信息
    var pairId: Long
        get() = sp.getLong("pair_id", 0)
        set(v) { sp.edit().putLong("pair_id", v).apply() }

    var myUserId: Long
        get() = sp.getLong("my_user_id", 0)
        set(v) { sp.edit().putLong("my_user_id", v).apply() }
}

object TimeUtil {
    fun nowTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    fun dayStartMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
