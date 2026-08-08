package com.linxi.diary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.navigation.LinxiApp
import com.linxi.diary.ui.theme.MilkGlassTheme

/** 首页：4 Tab 导航（此刻/待办/日记/我的）+ 启动常驻卡片 + 连接实时通道 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusForegroundService.start(this)
        StatusSyncManager.connect()
        setContent {
            MilkGlassTheme {
                LinxiApp()
            }
        }
    }
}
