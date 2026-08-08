package com.linxi.diary

import android.app.Application
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.util.UserPrefs

/** Application 入口：初始化本地存储、用户偏好、WebSocket、全局单例 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        UserPrefs.init(this)
        StatusSyncManager.init(this)
    }
}
