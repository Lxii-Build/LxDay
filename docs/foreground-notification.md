# 常驻通知实现

## 实现位置

- Service：`android/app/src/main/java/com/linxi/diary/service/StatusForegroundService.kt`
- 状态映射：`service/NotificationCardState.kt`
- 刷新去重：`service/NotificationUpdatePolicy.kt`
- 收起布局：`res/layout/notification_status_card_compact.xml`
- 展开布局：`res/layout/notification_status_card.xml`
- 单元测试：`android/app/src/test/java/com/linxi/diary/service/NotificationCardStateTest.kt`、`NotificationUpdatePolicyTest.kt`

## 通知频道

`status_card` 使用 `IMPORTANCE_LOW`，关闭角标，固定通知 ID 为 `10001`。通知设置 `CATEGORY_SERVICE`、`setOngoing(true)`、`setOnlyAlertOnce(true)` 和 `PRIORITY_LOW`。

## 横向 RemoteViews 状态卡

常驻通知使用 `NotificationCompat.DecoratedCustomViewStyle`，保留 Android 12+ 的系统应用图标、时间、圆角和展开装饰区。

- 收起态：48dp 横向布局，显示圆形头像占位、前台 App、同步/电量/网络摘要和更新时间。
- 展开态：Header 显示应用名和更新时间；左侧圆形头像；中部显示前台 App 与同步状态；下方横向显示手机、电量和网络三个组件。
- Action：一个标准 `addAction()`“响铃提醒”，发送 `ACTION_RING` 到前台 Service。
- 不显示位置，不使用多行日志式 `BigTextStyle`。
- 通知只读私有 `files/avatar/partner_notification.png` 静态缩略图缓存；缓存不存在、超 2MB、尺寸无效或大于 512×512 时回退默认圆形资源。阶段 2 负责下载和写入该缓存。

## 生命周期和刷新

- `startForegroundService()` 后先发布占位卡，再执行状态采集，满足前台服务启动时限。
- 只有绑定有效、已完成知情同意、共享开启时，Service 和 WebSocket 才允许运行
  （判定集中在 `sync/SharingRuntimePolicy.kt`）。
- 收到相同业务状态时不重复刷新通知；更新时间不参与业务状态去重。
- 关闭共享或退出登录时断开 WebSocket 并停止 Service。
- 状态采集在 IO 线程执行（`collectScope`），只有通知更新回主线程；
  `onDestroy` 取消 scope。此前采集在主线程、前台档每 10 秒一次，是 ANR 风险。
- 通知被移除后，仅在常驻卡开关开启且共享策略仍允许时恢复。

## 资源兼容

RemoteViews 仅使用系统支持的 `LinearLayout`、`TextView` 和 `ImageView`，图标为本地 vector drawable。颜色分别由 `values/colors.xml` 和 `values-night/colors.xml` 提供。

## 权限和降级

Android 13+ 需要 `POST_NOTIFICATIONS`。权限被拒或后台启动受限时，异常写入 `WARN/ERROR` 日志，不让应用 UI 闪退。

## 验收

- 收起态高度紧凑，不裁切头像和摘要。
- 展开态包含头像、前台 App、同步、手机、电量、网络和一个响铃 Action。
- Android 13～16 能构建并使用系统装饰模板展示。
- 状态未变化时不重复更新、响铃或震动。
- 未完成知情同意或共享已关闭时，不启动真实通知、状态采集或同步。
