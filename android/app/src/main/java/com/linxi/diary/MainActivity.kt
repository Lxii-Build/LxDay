package com.linxi.diary

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.navigation.LinxiApp
import com.linxi.diary.ui.theme.LinxiTheme
import com.linxi.diary.ui.theme.rememberThemeState
import com.linxi.diary.util.Logs

/**
 * 首页：4 Tab 导航（此刻/待办/日记/我的）。
 * 启动前置：请求运行时权限（通知 13+、定位 10+）；未授权时应用仍可打开，
 * 采集在「已授权 + 共享开启」时才由前台服务执行，避免闪退。
 * 主题：LinxiTheme（跟随系统 / 浅色 / 深色，SharedPreferences 即时刷新）。
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Logs.w("Main", "权限被拒: $denied")
                Toast.makeText(
                    this,
                    "部分权限未授予，可在「我的」中补开",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logs.i("Main", "onCreate 开始 pid=${android.os.Process.myPid()}") // 关键：确认 Activity 走到这里
        try {
            requestRuntimePermissions()
        } catch (t: Throwable) {
            Logs.e("Main", "请求权限失败", t)
        }
        try {
            StatusSyncManager.connect()
        } catch (t: Throwable) {
            Logs.e("Main", "连接失败", t)
        }
        Logs.i("Main", "setContent 之前")
        setContent {
            if (com.linxi.diary.BuildConfig.SAFE_MODE) {
                // 极简安全模式：跳过主题/backdrop/miuix，用于闪退二分定位
                MaterialTheme {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("林曦日记 · 安全模式\n如果这里显示说明基础启动正常")
                    }
                }
            } else {
                AppTheme {
                    LinxiApp()
                }
            }
        }
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        Logs.i("Main", "AppTheme 组合开始")
        val themeState = rememberThemeState()
        val settings by themeState.appSettings
        Logs.i("Main", "themeState 就绪 colorMode=${settings.colorMode}")
        LinxiTheme(appSettings = settings, content = content)
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
        ) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}
