package com.linxi.diary

import android.app.Application
import android.util.Log
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.CrashHandler
import com.linxi.diary.util.DiagnosticExporter
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs

/**
 * Application 入口。
 * 崩溃捕获注册到最早期；每个初始化步骤独立 try/catch 并打 logcat，
 * 确保即使某步崩溃也有日志可查（adb logcat -s Linxi:V）。
 */
class App : Application() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // 最早可记录点：崩溃捕获提前到 attachBaseContext，覆盖更早的崩溃
        Log.i("Linxi/App", "attachBaseContext pid=${android.os.Process.myPid()}")
        try {
            CrashHandler.initEarly(this)
            Log.i("Linxi/App", "CrashHandler 提前注册完成")
        } catch (t: Throwable) {
            Log.e("Linxi/App", "CrashHandler.initEarly 失败", t)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 文件日志尽早初始化，其后全部日志写入 Android app 私有 files/logs。
        try {
            Logs.init(this)
        } catch (t: Throwable) {
            Log.e("Linxi/App", "Logs.init 失败", t)
        }
        DiagnosticExporter.cleanupCache(this)
        Logs.i("App", "App.onCreate 开始 pid=${android.os.Process.myPid()}")
        try {
            CrashHandler.init(this)
            Logs.i("App", "CrashHandler 已初始化")
        } catch (t: Throwable) {
            Logs.e("App", "CrashHandler.init 失败", t)
        }
        try {
            UserPrefs.init(this)
            Logs.i("App", "UserPrefs 已初始化")
        } catch (t: Throwable) {
            Logs.e("App", "UserPrefs.init 失败", t)
        }
        try {
            StatusSyncManager.init(this)
            Logs.i("App", "StatusSyncManager 已初始化")
        } catch (t: Throwable) {
            Logs.e("App", "StatusSyncManager.init 失败", t)
        }
        Logs.i("App", "App.onCreate 完成")
    }
}
