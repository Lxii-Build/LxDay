# Android 功能索引

这里列出当前入口和实现文件，新增或移动功能后应同步更新。服务端接口的完整约束见 [ALBUM.md](ALBUM.md) 和 [server/README.md](../server/README.md)。

| 功能 | 页面/入口 | 主要实现 | 服务端或系统能力 |
| --- | --- | --- | --- |
| 登录/注册 | `screens/LoginScreen.kt`、`RegisterScreen.kt` | `data/ApiClient.kt` | `/auth/*` |
| 绑定与授权 | `screens/BindScreen.kt`、`PrivacyConsentScreen.kt` | `sync/SharingRuntimePolicy.kt`、`util/Utils.kt` | `/pair/*`；双方知情授权 |
| 此刻 | `screens/NowScreen.kt` | `core/DeviceStatus.kt`、`sync/StatusFreshness.kt` | 状态 REST + WSS |
| 待办 | `screens/TodoScreen.kt` | `core/TodoAlarmReceiver.kt`、`core/TodoRepeatPolicy.kt` | `/todos*` + AlarmManager |
| 发现 | `screens/DiscoverScreen.kt` | `ui/navigation/LinxiApp.kt` | 相册、这一天、回收站入口 |
| 相册列表/详情 | `screens/AlbumListScreen.kt`、`AlbumDetailScreen.kt` | `data/AlbumModels.kt`、`PhotoUploader.kt` | `/albums*`、`/media` |
| 多图查看 | `screens/PhotoViewerScreen.kt` | `data/AppImageLoader.kt`、`LocalPhotoIndex.kt` | Pager、预览图和鉴权原图 |
| 回收站/这一天 | `screens/RecycleBinScreen.kt`、`OnThisDayScreen.kt` | `data/ApiClient.kt` | `/photos/recycled`、`/photos/on-this-day` |
| 图片选择与上传 | `screens/PhotoPickerScreen.kt` | `MediaStoreImages.kt`、`ImagePrep.kt`、`ImagePrepPolicy.kt` | Photo Picker、像素/帧/内存预算 |
| 资料/头像 | `screens/ProfileEditScreen.kt`、`AvatarCropScreen.kt` | `data/AvatarCropper.kt` | `/profile*` |
| 状态历史 | `screens/HistoryScreen.kt` | `core/DeviceStatus.kt`、`data/ApiClient.kt` | `/status/history*` |
| 设置/自检 | `screens/SettingsScreen.kt`、`KeepAliveCheckScreen.kt` | `core/PermissionHelper.kt` | 系统权限和保活检查 |
| 音乐库/收藏 | `screens/MusicHomeScreen.kt`、`MusicSettingsScreen.kt` | `data/NeteaseClient.kt`、`NeteaseAccountStore.kt`、`NeteasePlaybackManager.kt` | 网易云搜索、喜欢状态、播放队列和设备端 Cookie |
| 歌词/歌词搜索 | `screens/LyricsScreen.kt` | `data/NeteaseMusicModels.kt`、`NeteaseClient.kt` | LRC/YRC 解析、翻译行、点击跳转和候选搜索 |
| 一起听 | `screens/ListenTogetherScreen.kt` | `data/NeteaseClient.kt`、`data/NeteasePlaybackManager.kt`、`data/NeteaseAccountStore.kt`、`data/ApiClient.kt` | 网易云官方登录 + 本机解析/Media3 播放；`/listen/rooms*` + 房间 WSS 只同步 song ID 与时间轴 |
| 更新日志/检查更新 | `screens/AboutScreen.kt` | `data/ApiClient.kt`、`ChangelogFormatter.kt`、`BuildConfig.COMMIT_SHORT_HASH` | `/app/latest`、GitHub Release、构建提交定位 |
| 实时同步 | 无独立页面 | `sync/StatusSyncManager.kt`、`WsEventRouter.kt` | WSS `/ws` + Bearer JWT |
| 常驻通知 | 系统通知栏 | `service/StatusForegroundService.kt`、`NotificationCardState.kt` | 前台服务、RemoteViews |
| 互动/响铃 | 此刻页和通知 Action | `sync/InteractionEvents.kt`、`core/RingHelper.kt` | `/interactions/*`、震动和闹钟音频 |
| 诊断导出 | 设置页 | `util/Logs.kt`、`DiagnosticExporter.kt` | 私有目录 + FileProvider |

## 共享约束

- 所有二级页面接入系统返回和统一导航动画。
- 列表页使用 `KernelScreen`；按钮使用 `LxButton`；确认/表单弹窗使用 `LxDialog.kt` 的统一组件。
- 图片位必须有底色和失败占位；图片 URL 统一经 `AppImageLoader` 补全和鉴权。
- 状态共享关闭、令牌失效或关系解除时，采集、同步和本地缓存都必须停止或清理。
- 客户端隐藏入口不构成权限控制，服务端必须再次校验账号、pair 和功能开关。

## 日志标签

- `Linxi/App`：Application 初始化
- `Linxi/Main`：Activity、权限、主题启动
- `Linxi/Nav`：页面与底栏
- `Linxi/Service`：前台服务和状态刷新
- `Linxi/Sync`：WebSocket、消息和互动
- `Linxi/Diagnostics`：诊断日志导出
