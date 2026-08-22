# 功能实现索引

| 功能 | UI/入口 | 核心实现 | 权限或协议 |
|---|---|---|---|
| 绑定 | `screens/BindScreen.kt` | `data/ApiClient.kt` | REST `/pair/create-invite`、`/pair/bind` |
| 知情授权 | 根级 Miuix `OverlayDialog` / `screens/PrivacyConsentScreen.kt` | `UserPrefs.privacyConsented`、`sync/SharingRuntimePolicy.kt` | 同意后才开启真实共享 |
| 主页 | `screens/NowScreen.kt` | `core/DeviceStatusHolder.kt`、`sync/StatusFreshness.kt` | 状态同步 WS；数据时效分 Fresh/Stale/Offline 三档 |
| 待办 | `screens/TodoScreen.kt` / 主层 FAB | `core/TodoAlarmReceiver.kt` | REST + WS + AlarmManager |
| 相册列表 | 发现页 → `Screen.DiscoverAlbum` / `screens/AlbumListScreen.kt` | `data/ApiClient.kt` | REST `/albums`、`/albums/summary`；见 [ALBUM.md](ALBUM.md) |
| 相册详情（网格） | `screens/AlbumDetailScreen.kt` | Coil 3 加载 `/media/<id>/thumb`；长按进多选 | REST `/albums/:id/photos`、批量删/移 |
| 大图查看 | `screens/PhotoViewerScreen.kt` | Pager + 双指缩放；先 `/preview` 再 `/media/<id>`；`data/LocalPhotoIndex.kt` 优先读本机原图 | REST `/photos/:id`、点赞/评论接口 |
| 回收站 | 相册列表 → `screens/RecycleBinScreen.kt` | `data/ApiClient.kt` | REST `/photos/recycled`、`restore`、`purge`、`purge-all` |
| 「这一天」 | `screens/OnThisDayScreen.kt` | `data/ApiClient.kt` | REST `/photos/on-this-day` |
| 图片选择器 | `screens/PhotoPickerScreen.kt` | `data/MediaStoreImages.kt`、`data/MediaSortPolicy`；顶栏兜底系统 Photo Picker | READ_MEDIA_IMAGES + READ_MEDIA_VISUAL_USER_SELECTED（Photo Picker 免权限） |
| 照片上传 | 选择器 → 上传 | `data/ImagePrep.kt`、`data/ImagePrepPolicy.kt`、`data/PhotoUploader.kt` | EXIF 旋正 + 解码期缩到 2048 + HEIC→JPEG；REST `POST /media`；失败逐张给原因 |
| 头像 | 我的 → 编辑资料 → 点头像 | 同一个选图器 → `screens/AvatarCropScreen.kt` + `data/AvatarCropper.kt` | 圆形裁剪后 `POST /me/avatar` |
| 图片地址补全 | 无独立页面 | `data/MediaUrlPolicy.kt`，收口在 `data/AppImageLoader.kt` 的 Coil mapper | 服务端 `site.url` 未配置时返回相对路径，客户端补 origin |
| 同步自检 | 我的 → `screens/KeepAliveCheckScreen.kt` | 逐项探测保活权限 | 每项写明「不开会怎样」 |
| 伴侣状态历史 | 我的 → `screens/HistoryScreen.kt` | `data/ApiClient.kt` | REST `/status/history?who=me\|partner`、`/status/battery-curve` |
| 状态采集 | 无独立页面 | `core/StatusCollector.kt` | Usage Access、定位、通知使用权 |
| 实时同步 | 无独立页面 | `sync/StatusSyncManager.kt` | WSS `/ws?token=JWT` |
| 常驻通知 | 我的页开关/前台服务 | `service/StatusForegroundService.kt` | 前台服务、通知权限 |
| 强制响铃 | 此刻页/通知 Action | `core/RingHelper.kt` | 闹钟音频、震动、全屏通知 |
| 主题 | 我的页主题模式 | `ui/theme/Theme.kt` | SYSTEM/LIGHT/DARK |
| 悬浮玻璃 | 主界面固定开启 | `ui/liquid/miuix/` | Miuix blur/Backdrop |
| 诊断日志 | 我的 → 导出诊断日志 | `util/Logs.kt`、`DiagnosticExporter.kt` | FileProvider Sharesheet |

## 日志标签

- `Linxi/App`：Application 初始化
- `Linxi/Main`：Activity、权限、主题启动
- `Linxi/Nav`：页面与底栏装配
- `Linxi/Service`：前台服务和状态刷新
- `Linxi/Sync`：WebSocket 连接、消息和推送
- `Linxi/Diagnostics`：日志导出

## 修改规则

涉及 UI、通知或日志行为时，必须同步更新 `android-ui.md`、`foreground-notification.md` 或 `diagnostics.md`；新增功能入口更新本索引。文档中的路径与代码入口必须通过 CI 前静态核查。
