package com.linxi.diary

import android.app.Application
import android.util.Log
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.CrashHandler
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
        Log.i("Linxi/App", "App.onCreate 开始 pid=${android.os.Process.myPid()}")
        try {
            CrashHandler.init(this)
            Log.i("Linxi/App", "CrashHandler 已初始化")
        } catch (t: Throwable) {
            Log.e("Linxi/App", "CrashHandler.init 失败", t)
        }
        try {
            UserPrefs.init(this)
            Log.i("Linxi/App", "UserPrefs 已初始化")
        } catch (t: Throwable) {
            Log.e("Linxi/App", "UserPrefs.init 失败", t)
        }
        try {
            StatusSyncManager.init(this)
            Log.i("Linxi/App", "StatusSyncManager 已初始化")
        } catch (t: Throwable) {
            Log.e("Linxi/App", "StatusSyncManager.init 失败", t)
        }
        Log.i("Linxi/App", "App.onCreate 完成")
    }
}
