# LxDay Android

Android 客户端使用 Kotlin、Jetpack Compose 和 miuix，当前 `minSdk 33`、`targetSdk 37`。它负责双人登录与绑定、状态采集、实时同步、互动、待办、本机提醒、共同相册、网易云音乐和一起听。

## 模块地图

```text
app/src/main/java/com/linxi/diary/
├── core/       设备状态、权限、前台服务辅助、响铃和闹钟
├── data/       REST、DTO、图片加载/预处理、上传和 URL 策略
├── service/    常驻通知卡和前台服务
├── status/     Android 常驻状态适配
├── sync/       WebSocket、状态、互动和重连策略
├── ui/
│   ├── navigation/  主界面和二级页导航
│   ├── screens/     登录、绑定、此刻、待办、发现、相册、音乐、歌词、一起听和设置等页面
│   ├── components/  KernelScreen、LxButton、LxDialog 等公共组件
│   └── theme/       深浅色主题与配色计算兼容层
└── util/       UserPrefs、日志和诊断工具
```

图片上传链路先在解码期限制尺寸、像素、帧数和内存，再进行 EXIF 旋正与缩放；服务端会重复校验。`AppImageLoader` 集中处理鉴权头、相对媒体地址补全、缓存和失败占位。

## 一起听

“一起听”采用 NeriPlayer 同款边界：已绑定的情侣打开页面会自动进入同一房间（尚未创建时自动创建），无需日常输入口令；双方先在各自设备通过网易云官方网页登录或扫码绑定账号，音乐库支持搜索、我的收藏、收藏切换、歌词/歌词搜索、队列、循环、随机、音质和音频焦点设置，解析和 Media3 播放都在本机完成。WSS 只同步网易云歌曲 ID、元数据、成员控制与播放时间轴，服务端不保存 Cookie、密码或播放地址；管理员可在后台查看并关闭活动房间。

## 音乐设置与账号边界

“我的 → 音乐设置”是音乐功能的单一入口：账号绑定、音质、列表/单曲循环、随机播放、
自动暂停、歌词翻译、搜索历史和灵动岛/播放胶囊都在这里。`NeteaseAccountStore` 使用
Android Keystore + AES/GCM 保存 Cookie；任何林曦 API 请求都不会携带 `MUSIC_U`，一起听
房间也只接受服务端白名单允许的网易云歌曲 ID。

关于页会显示 `VERSION_NAME`、`VERSION_CODE`、发布频道和 `COMMIT_SHORT_HASH`，反馈问题时
请完整提供这四项构建信息。

## 构建要求

- JDK 21
- Android SDK 37 和 build-tools 37.0.0
- Gradle 9.7
- 不要提交 `local.properties`；它只保存本机 SDK 路径

版本和依赖的唯一来源是 `android/build.gradle.kts` 与 `android/gradle/libs.versions.toml`。构建时可注入 `BASE_URL`、`WS_URL`、`VERSION_NAME` 和 `VERSION_CODE`；正式版与测试版共用一个更新流，旧 `APP_KEY` 参数不会写入 APK。

验证命令：

```bash
gradle :app:compileDebugKotlin --no-daemon
gradle :app:testDebugUnitTest --no-daemon
```

## 权限与降级

| 权限/能力 | 用途 | 被拒后的行为 |
| --- | --- | --- |
| 通知 | 状态卡、互动和待办提醒 | 提醒展示受限，其余功能继续 |
| 使用情况访问 | 前台应用和用量 | 对应字段为空 |
| 通知使用权 | 音乐识别和通知卡重拉 | 音乐字段为空，状态同步继续 |
| 定位/定位服务 | Android 读取 WiFi 名称 | 网络字段降级为移动网络/未知 |
| 精确闹钟 | 本地待办兜底 | 服务端提醒继续工作 |
| 电池优化白名单/厂商自启动 | 保活 | 系统可能延迟后台采集 |

权限只影响对应能力，不应阻断登录、相册或其他业务。Android 14+ 的照片选择优先使用系统 Photo Picker，并正确处理“仅选择部分照片”。

## 安全边界

- APK 可以被解包，不能把共享通讯密钥或服务端信任凭据编译进去；客户端使用 HTTPS/WSS 与用户 JWT。
- 用户访问令牌使用 Android Keystore 的 AES/GCM 加密保存。旧版本明文令牌只在能成功加密时迁移，否则立即清除。
- 二级页面必须接入统一返回处理和全局导航动画；列表页使用 `KernelScreen`，弹窗使用 `LxConfirmDialog`/`LxFormDialog`，按钮使用 `LxButton`。
- `material3` 仅用于 `ui/theme` 的配色计算桥；业务 UI 和图标使用 miuix。

## 真机检查

在至少一台 Android 设备上验证：启动服务、默认开启状态共享、绑定授权、网络断开重连、通知卡、互动撤回、待办提醒、相册多图左右滑动、缩略图失败占位和更新弹窗。权限与保活行为受系统版本和厂商 ROM 影响，不能只用 JVM 单测代替。
