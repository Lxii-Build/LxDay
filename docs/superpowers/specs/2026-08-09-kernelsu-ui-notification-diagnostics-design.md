# KernelSU UI、常驻通知与诊断体系改造设计

- 日期：2026-08-09
- 状态：待用户审阅
- 范围：Android UI、主题、常驻通知、运行日志、诊断导出、功能文档
- 目标：以 KernelSU 当前 Miuix 实现为结构基准，只替换林曦日记的业务内容，不再自造同类 UI 机制

## 1. 背景与问题诱因

### 1.1 Tab 栏与液态玻璃

当前实现只复制了 `FloatingBottomBar` 及其相关效果文件，没有复制 KernelSU 的完整装配链路。`LinxiApp.kt` 仍自行决定三档样式、固定底部边距、使用 Material3 `Icon` 并手动指定颜色；它缺少 KernelSU `BottomBarMiuix` 中的导航栏 Insets、`defaultMinSize(76.dp)`、miuix `Icon`/`Text` 和 `LocalContentColor` 传播，也没有完整复用 KernelSU 的页面 Backdrop、Pager Backdrop 与底栏 Backdrop 关系。因此组件源码相同但渲染结果不同。

此外，当前 `liquidGlassMode` 是项目自行设计的三档模型，并非 KernelSU 的两个独立能力开关。这造成设置项、默认值、页面编排和实际开启态均不一致。

### 1.2 主题

当前主题额外暴露了 AMOLED 与动态取色控制，且 `UserPrefs` 中仍存在对应偏好。用户要求仅保留 KernelSU 风格的跟随系统、浅色、深色三种模式；多余控制项会导致设置页和主题行为偏离目标。

### 1.3 常驻通知

当前 `StatusForegroundService` 使用自定义 `RemoteViews` 同时作为收起态和展开态。Android 12+ 及 vivo/OPPO 系统会对自定义通知的尺寸、装饰和折叠策略施加限制，导致卡片视觉不稳定。该实现也偏离 Android 官方通知模板。

### 1.4 日志

当前日志虽双写内部目录和 `Android/data` 外部目录，但文件级别是单字符 `D/I/W/E`，滚动时使用 `readLines()`，没有完整的保留策略与诊断导出入口。用户要求日志留在 Android app 私有 data 目录，并具有 INFO、WARN、ERROR 等明确级别，同时可用于真机问题反馈。

## 2. 已确认的验收标准

### 2.1 UI 与玻璃

- 默认启用 KernelSU 的完整悬浮底栏 + 悬浮栏模糊效果。
- 不保留项目自造的普通/胶囊/完整三档模型。
- 不做自动降级；设备支持判断和效果调用方式按 KernelSU 原实现处理。
- 底栏的尺寸、Insets、Tab 最小宽度、拖动、选中胶囊、复制层、高光、透射、颜色传播和动画均由 KernelSU 同款组件决定。
- 仅替换业务标签、图标和页面：此刻、待办、日记、我的。
- 主题设置仅有：跟随系统、浅色、深色。
- 删除 AMOLED 与动态取色用户控制。

### 2.2 常驻通知

- 使用 Android 官方 `NotificationCompat.Builder` 模板，不再依赖自定义 `RemoteViews` 作为状态卡主体。
- 收起态显示：伴侣名、电量、屏幕状态、前台 App。
- 展开态使用 `BigTextStyle` 显示：电量/充电、屏幕/锁定、前台 App、网络、音乐、更新时间。
- 仅保留一个标准 `addAction()`“响铃提醒”动作。
- 保持前台服务要求：固定通知 ID、`setOngoing(true)`、`setOnlyAlertOnce(true)`、低重要性常驻频道。
- 通知深浅色、布局、圆角由系统和 ROM 官方模板负责。

### 2.3 日志与导出

- 运行日志主存储位置为 `context.filesDir/logs/`，即 Android 应用私有目录。
- 日志级别为 `DEBUG`、`INFO`、`WARN`、`ERROR`，文件中使用完整级别名称。
- 每日一个运行日志文件；单文件超限时采用不依赖 `readLines()` 的安全滚动/截断策略。
- 默认保留最近 7 天，清理只作用于日志目录。
- 日志不写 token、邀请码、SSID、日记正文或未脱敏的私密状态原文。
- 设置页提供“导出诊断日志”，由用户主动触发，通过 Android Sharesheet 分享 ZIP；不自动上传。
- 崩溃堆栈继续单独保存到私有 `crash/` 目录，并纳入导出包。

### 2.4 文档

实现同步维护以下文档：

- `docs/android-ui.md`：Tab 装配、主题、玻璃链路、组件索引、验收清单。
- `docs/foreground-notification.md`：频道、模板、收起/展开字段、Action、系统限制。
- `docs/diagnostics.md`：日志级别、路径、保留策略、脱敏规则、导出步骤。
- `docs/feature-index.md`：功能入口、实现文件、权限、同步协议和诊断日志标签。

## 3. 方案与架构

### 3.1 Tab 与 Backdrop 装配

将当前 `MainTabs` 重构为 KernelSU `MainScreen` 的等价装配：

1. 建立页面内容 Backdrop，使用 `rememberLayerBackdrop { drawRect(surface); drawContent() }`。
2. 完整玻璃开启时，Pager 内容使用 `.layerBackdrop(backdrop)`，让底栏可以采样页面内容。
3. 底栏调用链使用 KernelSU `BottomBarMiuix` 的结构：
   - 悬浮底栏和悬浮栏模糊固定为开启态；本次不暴露底栏/模糊设置开关，也不做三档或自动降级逻辑。
   - `FloatingBottomBar` 使用系统 `WindowInsets.navigationBars` 底部间距。
   - 每项设置 `defaultMinSize(minWidth = 76.dp)`。
   - 内容使用 miuix `Icon` 与 `Text`，不手动覆盖选中态 `LocalContentColor`。
4. `HorizontalPager` 与 `MainPagerState` 保留林曦日记页面业务，但动画和同步遵循 KernelSU 的 `MainPagerState`。
5. 完整玻璃相关文件保留 KernelSU 原始实现及包名适配，不在本次设计中增加自定义 shader 或硬件分支。

### 3.2 主题状态

将主题状态收敛为 `ColorMode.SYSTEM/LIGHT/DARK`，删除 AMOLED 和动态色开关的用户入口及持久化读取。Miuix 使用 KernelSU 同款 `ThemeController`/`ColorSchemeMode` 映射；林曦日记使用固定情侣种子色，主题模式负责明暗，不额外暴露调色器。

现有历史偏好迁移规则：旧模式值 `3`（AMOLED）按深色 `2` 读取；旧 `keyColor` 不再影响用户界面。迁移不清理未知键，避免覆盖安装丢失无关偏好。

### 3.3 官方常驻通知

`buildCard()` 改为标准通知构建器：

- `setSmallIcon`、`setCategory(CATEGORY_SERVICE)`、`setVisibility(PUBLIC)`。
- `setContentTitle("伴侣 · 名称")`。
- `setContentText` 生成一行状态摘要。
- `setStyle(BigTextStyle().bigText(fullStatus))` 生成展开内容。
- `addAction(icon, "响铃提醒", serviceAction(ACTION_RING))`。
- 删除自定义 `RemoteViews` 字段写入和自定义通知布局依赖。
- 频道保留 `IMPORTANCE_LOW`、不显示角标、静默更新。

状态字符串由通知专用纯函数生成，统一处理空 partner、无前台、无音乐和移动网络，避免把 UI 组合逻辑带进 Service。

### 3.4 日志与诊断导出

`Logs` 继续提供 `d/i/w/e` 调用接口，但内部统一为 `LogLevel` 枚举和结构化行格式。文件写入保持同步锁保护，写盘失败只记录到 logcat，不影响业务线程。

新增诊断导出组件：

1. 在私有目录创建临时 ZIP。
2. 收集最近 7 天运行日志和 `CrashHandler` 崩溃文件。
3. 使用 `FileProvider` 生成临时 `content://` URI。
4. 通过 `Intent.ACTION_SEND` 和 `ClipData` 打开 Sharesheet。
5. ZIP 写入私有 `cacheDir/diagnostics/`；应用启动或下次导出时清理过期文件。系统不会可靠回调“分享已完成”，因此不在分享界面关闭时错误删除文件；不向网络发送。

导出前进行敏感信息脱敏：token、邀请码、SSID、日记正文及认证 URL 参数不进入日志；已有日志中的敏感字段在打包时以 `[REDACTED]` 替换。

## 4. 错误处理与安全边界

- 原生渲染错误不通过自定义吞异常伪装成成功；完整玻璃按 KernelSU 调用链执行，必要的支持能力判断沿用 miuix/KSU 现有 API。
- 通知权限拒绝、前台服务启动失败、文件写入失败均记录 `WARN`/`ERROR`，不让 UI 直接闪退。
- 诊断分享仅使用 FileProvider 的临时只读授权，不暴露 `file://` URI，不申请存储权限。
- 日志严格避免敏感数据；导出只由用户点击触发。
- 不修改服务端协议和业务数据模型。

## 5. 测试与验收

### 5.1 编译与静态验证

- GitHub Actions：Go test、Android `assembleDebug`、`assembleRelease` 全部成功。
- 搜索确认不存在 `liquidGlassMode`、AMOLED 主题 UI、动态取色 UI、自定义常驻 RemoteViews 路径和外部日志目录写入。
- 文档中的实现文件路径和入口与代码一致。

### 5.2 真机验收

至少在用户已验证可运行 KernelSU 玻璃的设备上检查：

- 默认启动即为悬浮玻璃底栏。
- 四个 Tab 的间距、底部避让、图标、文字、选中胶囊和拖动动画与 KernelSU 一致。
- 跟随系统/浅色/深色切换后，底栏、页面、系统栏图标和 preference 颜色一致。
- 常驻通知收起/展开使用系统标准样式，字段完整，仅有响铃 Action。
- 关闭通知权限时不闪退并有 `WARN` 日志。
- 设置页可导出诊断 ZIP，ZIP 内含最近 7 天日志与崩溃记录且不含明文敏感字段。

## 6. 实施顺序与不可跳过项

1. 替换 Tab/玻璃装配链路并收敛主题模式。
2. 替换官方常驻通知。
3. 重构私有日志与诊断导出。
4. 编写/同步四份功能文档。
5. 全量构建、静态检查和真机验收清单核对。

每一步完成并通过验证后才能进入下一步；任何 CI 失败或验收失败都必须先修复，不得跳过。
