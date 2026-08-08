package com.linxi.diary

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.navigation.LinxiApp
import com.linxi.diary.ui.theme.MilkGlassTheme
import com.linxi.diary.util.Logs

/**
 * 首页：4 Tab 导航（此刻/待办/日记/我的）。
 * 启动前置：请求运行时权限（通知 13+、定位 10+）；未授权时应用仍可打开，
 * 采集在「已授权 + 共享开启」时才由前台服务执行，避免闪退。
 */
class MainActivity : ComponentActivity() {

    // 通知权限（Android 13+）与 定位（Android 10+ 读 WiFi）运行时申请
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
        try {
            requestRuntimePermissions()
        } catch (t: Throwable) {
            Logs.e("Main", "请求权限失败", t)
        }
        StatusSyncManager.connect() // 连接实时通道（内部有 token/网络保护）
        setContent {
            MilkGlassTheme {
                LinxiApp()
            }
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