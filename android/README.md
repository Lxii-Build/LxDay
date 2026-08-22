# 林曦日记 · 安卓端

Kotlin 原生，**minSdk 33 / targetSdk 37**（版本号集中在 `android/build.gradle.kts` 的 `extra[...]`，
不要在子模块里另写）。UI 用 **miuix**（HyperOS 风格）：UI 层不用 material3 组件、
图标一律 `MiuixIcons`，`material3` 仅保留在 `ui/theme` 的配色链里。通知卡仍是 XML。

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
    │   ├── ApiClient.kt                   # OkHttp REST 客户端
    │   ├── Models.kt                      # Todo/History/BatteryPoint/Profile DTO
    │   ├── AlbumModels.kt                 # Album/Photo/AlbumSummary DTO
    │   ├── MediaStoreImages.kt            # 本机相册读取（排序/分页策略抽出可单测）
    │   ├── ImagePrep.kt / ImagePrepPolicy.kt  # 上传前预处理（解码期缩放 + EXIF 旋正）
    │   ├── PhotoUploader.kt               # 单张上传链路 + 失败原因分类
    │   ├── LocalPhotoIndex.kt             # 「照片 id → 本机原图 uri」，自己传的不走网络
    │   ├── AvatarCropper.kt               # 圆形裁剪的坐标换算（纯逻辑）
    │   ├── MediaUrlPolicy.kt              # 相对图片地址补成绝对 URL
    │   └── AppImageLoader.kt              # Coil 3 全局实例（鉴权头 + URL 补全 mapper）
    ├── core/
    │   ├── DeviceStatus.kt                # 数据模型 + 状态配色
    │   ├── StatusCollector.kt             # 电量/前台APP/用量/WiFi 采集（在 IO 线程）
    │   ├── ScreenStateProbe.kt            # Display.getState() 权威判定，单独识别 AOD
    │   ├── ForegroundAppPolicy.kt         # 前台应用查询窗口与逐级回退（纯策略，可单测）
    │   ├── ScreenStateReceiver.kt         # 亮屏/解锁事件监听
    │   ├── MediaNotificationListener.kt   # 音乐识别 + 卡片重拉兜底
    │   ├── RingHelper.kt                  # 强制响铃 + 待办强提醒
    │   ├── TodoAlarmReceiver.kt           # 待办本地 AlarmManager 兜底
    │   ├── PermissionHelper.kt            # 权限/保活引导
    │   ├── BootReceiver.kt / NetworkReceiver.kt
    ├── ui/
    │   ├── theme/                         # MilkGlass Compose 主题（浅+深）
    │   ├── components/Glass.kt            # 玻璃卡片组件
    │   ├── components/LxButton.kt LxDialog.kt  # 按钮与弹窗语义（含 48dp 最小触达、冷静期）
    │   ├── navigation/LinxiApp.kt         # 绑定→授权→4Tab→历史 状态机导航
    │   └── screens/                       # Bind/Consent/Now/Todo/Settings/History
    │                                      # + AlbumList/AlbumDetail/PhotoViewer/PhotoPicker
    │                                      # + RecycleBin/OnThisDay/AvatarCrop/KeepAliveCheck
    └── util/Utils.kt                      # UserPrefs / TimeUtil
```

## 构建配置

以 `android/build.gradle.kts`、`android/app/build.gradle.kts` 与
`android/gradle/libs.versions.toml` 为唯一真源，此处不复制版本号（抄一份必然会过期）。
要点：

- **SDK 与语言版本集中在根 `build.gradle.kts` 的 `extra[...]`**：
  minSdk 33 / targetSdk 37 / compileSdk 37 / build-tools 37.0.0 / Java 21。
- 依赖走 **version catalog**（`libs.versions.toml`）+ Compose BOM，不在模块里写死版本。
- 构建期可注入 `BASE_URL` / `WS_URL` / `APP_KEY` / `VERSION_NAME` / `VERSION_CODE`
  （`-P` 参数或 `gradle.properties`），缺省有默认值，本地无参也能构建。
- UI 库是 **miuix**；`material3` 只出现在 `ui/theme` 的配色链里。
- 图片加载 **Coil 3**（`coil-compose` + `coil-network-okhttp`）。
- 本地编译与测试命令见 [../docs/DEVELOPMENT.md](../docs/DEVELOPMENT.md)。

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

## 权限被拒后的降级行为

除通知外都是「拒绝了只影响这一项，不阻断 App」：

- 使用情况访问未授予 → 「正在使用的应用」为空，其余状态照常
- 照片权限未授予 → 选图器给出提示，并引导走**系统 Photo Picker**（那条路免权限）
- 精确闹钟未授予 → 本地兜底提醒失效，服务端扫描推送照常
- 电池优化白名单未加 → Doze 期间同步会被拉长，「同步自检页」会把这条标红

Android 14+ 选图必须**同时**申请 `READ_MEDIA_IMAGES` 与
`READ_MEDIA_VISUAL_USER_SELECTED`，否则用户选「仅部分照片」会被当成完全拒绝。

## 后台保活

前台服务(dataSync|location)为主 + 电池白名单 + 开机自启 + vivo/OPPO 自启动白名单引导。
不接商业推送（私人直装唯一零门槛路线），离线靠 WS 重连补拉 + 本地 AlarmManager 兜底。

## iOS 限制（结论）

iOS 状态采集几乎全不可用（无 API 读他 App 前台/电量后台/音乐/WiFi/常驻卡/强制响铃），
仅纯业务（绑定/待办/相册/情绪按钮/APNs）可行。设计决策为**不开发 iOS**，仅本说明。
