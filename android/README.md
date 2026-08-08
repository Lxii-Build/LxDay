# 林曦日记 · 安卓端

Kotlin 原生，minSdk 29（Android 10+）。UI 遵循 `MilkGlassDesignScheme.md`（端内 Compose + 通知卡 XML）。

## 目录

```
app/src/main/
├── AndroidManifest.xml
├── res/
│   ├── values/values-night/colors.xml   # 深浅色令牌（通知卡跟随系统）
│   ├── layout/notification_expanded.xml # 展开态卡片（仅「响铃提醒」按钮）
└── java/com/linxi/diary/
    ├── App.kt / MainActivity.kt          # 入口：启动前台服务 + WS + Compose
    ├── RingActivity.kt                   # 强制响铃全屏页
    ├── service/StatusForegroundService.kt # 常驻卡片前台服务（卡片显示伴侣状态）
    ├── sync/StatusSyncManager.kt          # WebSocket 同步 + 事件分发
    ├── data/
    │   ├── ApiClient.kt                   # 真实 OkHttp REST 客户端
    │   └── Models.kt                      # Todo/Diary/History/BatteryPoint DTO
    ├── core/
    │   ├── DeviceStatus.kt                # 数据模型 + 状态配色
    │   ├── StatusCollector.kt             # 电量/前台APP/用量/WiFi 采集
    │   ├── ScreenStateReceiver.kt         # 亮屏/解锁监听
    │   ├── MediaNotificationListener.kt   # 音乐识别 + 卡片重拉兜底
    │   ├── RingHelper.kt                  # 强制响铃 + 待办强提醒
    │   ├── TodoAlarmReceiver.kt           # 待办本地 AlarmManager 兜底
    │   ├── PermissionHelper.kt            # 权限/保活引导
    │   ├── BootReceiver.kt / NetworkReceiver.kt
    ├── ui/
    │   ├── theme/                         # MilkGlass Compose 主题（浅+深）
    │   ├── components/Glass.kt            # 玻璃卡片组件
    │   ├── navigation/LinxiApp.kt         # 绑定→授权→4Tab→历史 状态机导航
    │   └── screens/                       # Bind/Consent/Now/Todo/Diary/Settings/History
    └── util/Utils.kt                      # UserPrefs / TimeUtil
```

## Gradle 依赖（app/build.gradle 关键片段）

```gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'org.jetbrains.kotlin.plugin.compose' version '2.0.0'
}

android {
    namespace 'com.linxi.diary'
    compileSdk 35
    defaultConfig { applicationId "com.linxi.diary"; minSdk 29; targetSdk 34 }
    kotlinOptions { jvmTarget = '17' }
    buildFeatures { compose = true }
}

dependencies {
    implementation platform('androidx.compose:compose-bom:2024.09.03')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.material:material-icons-extended'
    implementation 'androidx.activity:activity-compose:1.9.2'
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'org.json:json:20240303'
}
```

## 权限清单与配置

| 权限 | 类别 | 用途 | 引导入口 |
|---|---|---|---|
| POST_NOTIFICATIONS | 运行时(13+) | 通知 | 系统弹窗 |
| 使用情况访问 (PACKAGE_USAGE_STATS) | 特殊 | 前台APP/用量 | `Settings.ACTION_USAGE_ACCESS_SETTINGS` |
| 通知使用权 | 特殊 | 音乐识别/卡片重拉 | `ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| 勿扰访问 | 特殊 | 强制响铃绕过勿扰 | `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` |
| 定位 精确+后台 | 运行时 | 后台读 WiFi 名 | 系统弹窗 |
| 电池优化白名单 | 特殊 | 防 Doze | `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` |
| 自启动 | 厂商(vivo/OPPO) | 保活 | `PermissionHelper.toVendorAutoStart` |

## 设计决策要点（来自 DESIGN.md）

- **通知卡仅「响铃提醒」按钮**；求陪伴/求冷静仅在首页。
- **共享总开关**：关闭=停止采集+本地清空（`SettingsScreen` + `StatusForegroundService.refreshNow` + `StatusSyncManager.pushNow` 三处门控）。
- **知情授权页**：绑定后强制确认才开启采集（`PrivacyConsentScreen`）。
- **深色模式**：端内语义反转扩展规范；通知卡 `values-night` 跟随系统。
- **待办提醒**：服务端扫描为主 + 本地 AlarmManager 兜底；强提醒=闹钟流 80%+震动+普通通知（非全屏）。
- **状态历史**：5 分钟聚合永久保留；时间线 + 24h 电量曲线（自绘 Canvas）。

## 已知待补（编译前必做）

1. **drawable 资源**：`ic_heart`、`ic_alarm`（通知小图标）、`ic_launcher` 系列 —— 当前代码引用这些资源，需补图标。
2. **主题资源**：`Theme.MilkGlass`（`res/values/themes.xml`）—— Compose 主题已就绪，但 XML 主题用于 Activity 主题与通知卡，需补。
3. **`menuAnchor()` 兼容**：material3 版本差异（1.2 无参 / 1.3+ 需 `MenuAnchorType`），按实际依赖版本微调 TodoScreen/SettingsScreen 的 ExposedDropdownMenu。
4. **绑定 API 数据格式**：服务端 `/pair/bind` 返回 `{pair_id, partner}` 已对齐。
5. **BASE URL**：`ApiClient.BASE` 默认 `https://api.linxi.app`，联调改为你的服务器域名。

## 后台保活

前台服务(dataSync|location)为主 + 电池白名单 + 开机自启 + vivo/OPPO 自启动白名单引导。
不接商业推送（私人直装唯一零门槛路线），离线靠 WS 重连补拉 + 本地 AlarmManager 兜底。

## iOS 限制（结论）

iOS 状态采集几乎全不可用（无 API 读他 App 前台/电量后台/音乐/WiFi/常驻卡/强制响铃），
仅纯业务（绑定/待办/日记/情绪按钮/APNs）可行。设计决策为**不开发 iOS**，仅本说明。
