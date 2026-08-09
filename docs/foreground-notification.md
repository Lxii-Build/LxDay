# 常驻通知实现

## 实现位置

- Service：`android/app/src/main/java/com/linxi/diary/service/StatusForegroundService.kt`
- 文本格式化：`service/NotificationStatusFormatter.kt`
- 单元测试：`android/app/src/test/java/com/linxi/diary/service/NotificationStatusFormatterTest.kt`

## 通知频道

`status_card` 使用 `IMPORTANCE_LOW`，关闭角标，描述为“常驻显示伴侣实时状态，静默更新”。固定通知 ID 为 `10001`。

## 官方模板

常驻通知使用 `NotificationCompat.Builder`：

- 收起态：标题“伴侣 · 名称”；摘要“电量 · 屏幕 · 前台 App”。
- 展开态：`BigTextStyle` 显示电量/充电、屏幕/锁定、前台 App、网络类型、音乐和更新时间。
- Action：仅一个标准 `addAction()`“响铃提醒”，发送 `ACTION_RING` 到前台 Service。
- 行为：`CATEGORY_SERVICE`、`setOngoing(true)`、`setOnlyAlertOnce(true)`、`PRIORITY_LOW`。

不使用自定义 `RemoteViews` 作为常驻状态卡主体；深浅色、圆角、收起和展开布局均由 Android/ROM 官方通知模板负责。

## 权限和降级

Android 13+ 需要 `POST_NOTIFICATIONS`。权限被拒后前台服务启动或通知显示受系统限制，异常写入 `WARN/ERROR` 日志，不让应用 UI 闪退。

## 验收

- 收起态不拥挤、不裁切。
- 展开态包含完整状态和一个响铃按钮。
- vivo/OPPO 深浅主题由系统通知模板正确适配。
- 状态刷新不重复响铃或震动。
