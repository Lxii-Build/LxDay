package com.linxi.diary

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.ui.navigation.LinxiApp
import com.linxi.diary.ui.theme.LinxiTheme
import com.linxi.diary.ui.theme.rememberThemeState
import com.linxi.diary.util.Logs

/**
 * 首页：4 Tab 导航（主页/待办/日记/我的）。
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
            ProfileRuntime.connectAndRefreshIfEligible()
        } catch (t: Throwable) {
            Logs.e("Main", "连接或资料刷新失败", t)
        }
        Logs.i("Main", "setContent 之前")
        setContent {
            // SAFE_MODE 已移除（Q49=A）：那是 0813 排查启动闪退时留的二分工具，
            // 问题早已定位修复，留着只会让人以为还有个"安全模式"可用，
            // 而它引入的 material3 MaterialTheme/Text 也与"全 App 统一 miuix"相悖。
            AppTheme {
                LinxiApp()
            }
        }
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        val themeState = rememberThemeState()
        val appearance by themeState.appearance
        val darkTheme = com.linxi.diary.ui.theme.AppThemeResolver.isDark(
            appearance.colorMode,
            androidx.compose.foundation.isSystemInDarkTheme(),
        )
        androidx.compose.runtime.LaunchedEffect(appearance.colorMode) {
            Logs.i("Main", "主题模式=${appearance.colorMode}")
        }
        LinxiTheme(appearance = appearance) {
            com.linxi.diary.ui.theme.WallpaperHost(appearance = appearance, isDark = darkTheme, content = content)
        }
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
