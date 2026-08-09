# 功能实现索引

| 功能 | UI/入口 | 核心实现 | 权限或协议 |
|---|---|---|---|
| 绑定 | `screens/BindScreen.kt` | `data/ApiClient.kt` | REST `/pair/create-invite`、`/pair/bind` |
| 知情授权 | `screens/PrivacyConsentScreen.kt` | `UserPrefs.privacyConsented` | 双方确认后开启共享 |
| 此刻 | `screens/NowScreen.kt` | `core/DeviceStatusHolder.kt` | 状态同步 WS |
| 待办 | `screens/TodoScreen.kt` | `core/TodoAlarmReceiver.kt` | REST + WS + AlarmManager |
| 日记 | `screens/DiaryScreen.kt` | `data/ApiClient.kt` | REST `/diaries` |
| 历史 | `screens/HistoryScreen.kt` | `data/ApiClient.kt` | REST history endpoints |
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
