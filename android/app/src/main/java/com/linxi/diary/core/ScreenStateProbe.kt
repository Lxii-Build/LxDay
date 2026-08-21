package com.linxi.diary.core

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.linxi.diary.util.Logs

/**
 * 屏幕状态的**权威来源**。每次采集现读现取，不依赖广播是否送达。
 *
 * ## 为什么不能用 `PowerManager.isInteractive()`
 *
 * Android 官方与社区的一致结论：`isScreenOn()`/`isInteractive()` **只表示"设备可交互"**，
 * 与屏幕是否点亮没有必然关系。AOD（息屏显示）亮着时它是 false；
 * Doze 期间的行为也反直觉。要判断屏幕真实状态必须用 [Display.getState]。
 *
 * ## 为什么必须现读而不是缓存广播结果
 *
 * `ACTION_SCREEN_ON`/`OFF` **无法静态注册**，只能在前台服务里动态注册。
 * 旧实现把状态存在 `DeviceStatusHolder.screenOn` 里，**初值硬编码 `true`**——
 * 于是进程被 `SyncHeartbeat`(AlarmManager) 或 `BootReceiver` 在**息屏状态下**拉起时，
 * 第一次采集必然上报"亮屏"，对方看到"他亮着屏"，而人在睡觉。
 * 这个错值会一直持续到下一次真实的亮/息屏广播为止。
 * 广播漏投（进程被杀后重启、Doze 深度休眠）时更会长期错。
 *
 * 一加 15 的 AOD 默认开启，所以 [ScreenState.Aod] 这一档在管理员的测试机上是常态。
 */
enum class ScreenState {
    /** 屏幕点亮且正常显示。 */
    On,

    /** 完全熄灭。 */
    Off,

    /**
     * 息屏显示（AOD）：屏幕在低功耗模式下显示时钟/通知。
     * 既不是"亮屏在用"，也不是"完全黑屏"，单独一档才不会误报。
     */
    Aod,
    ;

    /** 上报给服务端的 `screen_on`。AOD 归为"未亮屏"——用户并没有在用手机。 */
    val reportAsOn: Boolean get() = this == On

    /** 面向用户的中文描述。 */
    val label: String
        get() = when (this) {
            On -> "亮屏"
            Off -> "息屏"
            Aod -> "息屏显示"
        }
}

object ScreenStateProbe {

    private const val TAG = "ScreenState"

    /**
     * 读当前屏幕状态。
     *
     * 多屏设备（折叠屏展开/外接显示）取"任一屏点亮即算亮"：
     * 用户在任何一块屏幕上用手机，都算在用。
     */
    fun current(context: Context): ScreenState = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val displays = dm?.displays
        if (displays == null || displays.isEmpty()) {
            // 拿不到 DisplayManager 时退回 PowerManager。
            // 它语义不精确，但比返回一个写死的值好。
            fallbackByPowerManager(context)
        } else {
            var sawAod = false
            for (d in displays) {
                when (d.state) {
                    Display.STATE_ON -> return ScreenState.On
                    Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> sawAod = true
                    else -> {}
                }
            }
            if (sawAod) ScreenState.Aod else ScreenState.Off
        }
    } catch (t: Throwable) {
        Logs.w(TAG, "read display state failed", t)
        fallbackByPowerManager(context)
    }

    private fun fallbackByPowerManager(context: Context): ScreenState = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm?.isInteractive == true) ScreenState.On else ScreenState.Off
    } catch (t: Throwable) {
        Logs.w(TAG, "read power state failed", t)
        ScreenState.Off // 宁可报"息屏"也不要谎报"亮屏"
    }

    /** 是否锁屏。服务不可用时视为锁屏（保守：不谎报"已解锁"）。 */
    fun isLocked(context: Context): Boolean = try {
        val km = context.getSystemService(KeyguardManager::class.java)
        km?.isKeyguardLocked ?: true
    } catch (t: Throwable) {
        Logs.w(TAG, "read keyguard failed", t)
        true
    }
}
