package com.linxi.diary.core

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * 全量权限与保活引导。
 * 特殊授权（使用情况访问/通知使用权/勿扰访问/电池优化白名单）+ 厂商自启动白名单（vivo/OPPO）。
 */
object PermissionHelper {

    /** 使用情况访问是否已授权 */
    fun hasUsageAccess(context: Context): Boolean {
        val am = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try {
            val mode = am.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    /** 通知使用权是否已授权 */
    fun hasNotificationListener(context: Context): Boolean {
        val cn = ComponentName(context, MediaNotificationListener::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners")
        return flat?.split(':')?.contains(cn.flattenToString()) == true
    }

    /** 勿扰访问是否已授权 */
    fun hasNotificationPolicyAccess(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
        return (nm as android.app.NotificationManager).isNotificationPolicyAccessGranted
    }

    /** 电池优化白名单 */
    fun hasIgnoreBattery(context: Context): Boolean {
        val pm = context.packageManager
        val name = context.packageName
        return pm.isIgnoringBatteryOptimizations(name)
    }

    // ============ 引导跳转 ============

    fun toUsageAccess(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    fun toNotificationListener(context: Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun toNotificationPolicy(context: Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    fun toBatteryOptimization(context: Context) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 厂商自启动白名单引导（重点 vivo / OPPO，见决策 Q22） */
    fun toVendorAutoStart(context: Context): Boolean {
        val intents = arrayOf(
            Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(i)
                return true
            } catch (_: Exception) { }
        }
        // 兜底：跳转应用设置
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")))
        return false
    }
}
