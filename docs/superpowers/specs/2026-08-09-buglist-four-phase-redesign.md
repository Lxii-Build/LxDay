# BugList 四阶段生产改造设计

- 日期：2026-08-09
- 状态：待用户书面审阅
- 来源：`C:\Users\Administrator\Downloads\BugList.md`
- 交付方式：功能分支 + Draft PR；每阶段推送并等待工作流全绿；用户只测试最终合并至 `main` 后的成品 APK
- 产物约束：实施开始第一步删除本地 `dist/` 并加入忽略；后续不下载 APK 到本地，APK 仅由 GitHub Actions artifact 提供

## 1. 总体目标与边界

本改造覆盖四个垂直阶段：

1. 基础 UI、横向通知和现有 Bug；
2. 双方资料、动态头像与纪念日；
3. KernelSU 主题中心、全局壁纸与动态取色；
4. ColorOS、OriginOS 与 Android 16 持续状态能力。

内部按阶段开发、测试和推送；每阶段 CI 失败必须在当前阶段修复，不得进入下一阶段。中间 APK 不交付用户测试。四阶段完成后进行全量代码评审、安全审查和回归，再合并 `main`，最终 `main` 工作流产物是唯一用户测试版本。

明确不实现：

- GPS、城市或手动位置状态；
- 未公开厂商接口的反射调用、伪造系统身份、签名/白名单绕过；
- 把普通通知样式冒充为真正进入系统流体云/原子岛；
- KernelSU 中对林曦日记没有业务意义的导航角标；
- 本地保存或下载 APK。

## 2. 已确认的产品决策

| 决策 | 结论 |
|---|---|
| 实施路线 | 分阶段生产改造 |
| 测试方式 | 用户只测试最终版；中间阶段仅自动测试和 CI |
| Git 流程 | 独立功能分支 + Draft PR；每阶段推送和修复 CI |
| 通知位置 | 不实现 |
| 资料存储 | 昵称、头像、纪念日全部由服务端保存并双向同步 |
| 示例数据 | 仅跳过绑定的调试模式本地显示，不上传、不提醒、不同步 |
| 主题范围 | KernelSU 有业务意义的完整主题能力 + 全局壁纸；不实现导航角标 |
| 壁纸裁剪 | 按当前屏幕比例，应用内双指缩放和自由平移 |
| 通知头像 | 圆形真实头像；失败时默认情侣头像/姓名首字 |
| 动态头像 | 保留 GIF/动态 WebP 等动画；通知使用静态缩略图 |
| 动态头像上限 | 原文件 15MB、最长 10 秒、最多 120 帧、输出最大 512×512 |
| 历史入口 | “我的”中的“伴侣状态历史”直接入口 |
| 相恋天数 | 纪念日当天算第 1 天 |
| 持续状态优先级 | ColorOS、OriginOS 专项公开能力；Android 16 可选增强；标准通知兜底 |

## 3. 阶段 1：基础 UI 与现有 Bug

### 3.1 横向 RemoteViews 状态卡

#### 问题诱因

当前通知为 `BigTextStyle`，状态以纵向多行文本展示；旧 `notification_expanded.xml` 也是纵向布局，无法形成紧凑的设备状态组件。

#### 目标布局

```text
┌────────────────────────────────────┐
│ 林曦日记                       现在 │
│ [圆形头像]  微信 · 已同步           │
│             📱 亮屏  🔋 86%  📶 WiFi │
└────────────────────────────────────┘
```

正式实现不使用 emoji 图标，而使用 RemoteViews 支持的 `ImageView + TextView`。

#### 结构

- Header：应用名、更新时间；
- 主体左侧：圆形伴侣头像；
- 主体中部：前台 App 与同步状态；
- Status Row：手机状态、电量、网络三个横向组件；
- 不显示位置；
- 展开态可保留标准“响铃提醒”Action；
- 收起和展开可使用独立 RemoteViews，但均保持横向紧凑；
- Android 12+ 保留系统通知装饰区域，不覆盖系统图标、系统时间或系统圆角。

#### 数据与线程

- 不修改前台 Service 的生命周期、采集策略、WebSocket 和状态同步协议；允许替换 Service 内的通知构建器与 RemoteViews 数据绑定，因为它属于通知 UI 层；
- 固定通知 ID 更新；
- 头像只读取内存/磁盘缓存，不在通知刷新主线程下载；
- 头像缓存未完成时使用默认资源；
- 状态未变化时避免重复 `notify()`；
- 深浅色分别使用 `values` 和 `values-night` 资源。

### 3.2 待办和日记 FAB

#### 问题诱因

FAB 位于页面内层 `KernelScreen.Scaffold`，悬浮玻璃 Tab 栏位于外层主 Scaffold；外层底栏层覆盖 FAB 触摸区域。

#### 方案

- 移除 `TodoScreen` 和 `DiaryScreen` 的内层 FAB；
- 主导航层新增统一 `MainFabHost`；
- 当前 Tab 注册对应 FAB 行为；
- 待办 FAB 打开添加待办弹窗；日记 FAB 打开发布日记弹窗；
- 使用系统导航栏 Insets、实际底栏高度和视觉间距计算位置；
- 使用 Miuix 图标和按压反馈，不使用文本“+”；
- Tab 切换时清除旧页面 FAB 行为，避免误打开错误弹窗。

### 3.3 调试示例数据

新增 `DemoMode` 与本地 `DemoRepository`，仅跳过绑定的“调试伴侣”模式启用。

示例待办：

- 今晚一起看电影；
- 周末采购；
- 给对方准备惊喜。

示例日记：

- 第一次约会；
- 周末散步；
- 一篇带图片占位的纪念日记。

约束：

- 使用独立模型或负数 ID；
- 不调用 API、WebSocket、AlarmManager、上传、完成或删除接口；
- 页面显示“示例数据”标识；
- 真实绑定后自动关闭 DemoMode，仅展示服务端数据。

### 3.4 Miuix 知情同意弹窗

#### 问题诱因

当前 AndroidX `Dialog + Card` 只是手工拼接；此前直接使用 `OverlayDialog` 时没有完整的 Miuix overlay/popup host 装配。

#### 方案

- 参考 KernelSU `DialogMiuix` 的 Host 和内容结构；
- 应用根级提供 Miuix overlay/popup host；
- 使用 Miuix 标题、摘要、滚动区、选择状态和按钮；
- 不可外部点击关闭；
- 不可返回键跳过；
- 未确认时主按钮禁用；
- 同意后才写授权状态、启动共享和同步；
- 组合日志放入 Effect，不在组合函数体直接写盘。

### 3.5 应用主题状态统一

当前 `NowScreen` 与 `WarningCard` 直接调用 `isSystemInDarkTheme()`，会忽略应用内强制主题。

规则：

- 仅主题根节点允许读取系统暗色；
- 解析出实际 App DarkMode 后通过统一状态提供；
- 页面、卡片、警告、壁纸遮罩不直接读取系统暗色；
- 全局搜索同类调用并修复；
- 系统暗色 + App 浅色、系统浅色 + App 深色均必须完全同步。

### 3.6 主页与历史

- Tab 和页面标题“此刻”统一改为“主页”；
- 移除主页右上角“历史”文字；
- “我的”新增 `ArrowPreference`：
  - 标题：伴侣状态历史；
  - 摘要：查看状态时间线与电量曲线；
- 历史页改用 Miuix 顶栏、按钮/选择控件、Card、空状态和加载状态；
- 保留既有历史 API、时间线和 Canvas 曲线数据逻辑。

### 3.7 阶段 1 测试

- DemoMode 不调用 API/WS/Alarm；
- 强制主题解析与页面颜色；
- 通知文本与状态组件绑定；
- FAB 当前 Tab 注册和清理；
- RemoteViews ID 完整性和 Android 13～16 构建兼容；
- 阶段文档更新；
- Draft PR CI 全绿后进入阶段 2。

## 4. 阶段 2：资料、动态头像与纪念日

### 4.1 服务端模型

现有 `user.nickname` 和 `user.avatar_url` 保留，扩展：

```sql
ALTER TABLE pair ADD COLUMN anniversary_date DATE NULL;
ALTER TABLE user ADD COLUMN avatar_thumbnail_url VARCHAR(255) NULL;
```

以上仅表达目标 Schema，不直接作为重复执行脚本。新增版本化迁移表与有序 migration 文件；迁移器在事务中记录版本，已应用版本不重复执行，失败回滚并阻止服务启动到未知 Schema。纪念日属于 pair 共同字段，不属于单个用户。

### 4.2 服务端接口

```text
GET  /api/v1/profile
PUT  /api/v1/profile
POST /api/v1/profile/avatar
PUT  /api/v1/pair/anniversary
```

`GET /pair/status` 扩展返回双方资料和 `anniversary_date`。

昵称校验：去首尾空格、2～32 字符、保持唯一、不允许修改身份或绑定关系。

纪念日校验：合法 ISO 日期、不得晚于当天、调用者必须属于 pair。

### 4.3 动态头像格式和资源

客户端选择入口使用 `image/*`，接受 Android 可安全解码的常见栅格格式：

- JPEG/JPG；
- PNG；
- 静态/动态 WebP；
- HEIF/HEIC；
- AVIF（系统解码支持时）；
- GIF；
- BMP（设备解码支持时）。

不支持 SVG、RAW/DNG、PSD、TIFF。

动态头像限制：

- 原始文件最大 15MB；
- 最长 10 秒；
- 最多 120 帧；
- 裁剪输出最大 512×512；
- 通知静态缩略图为 256×256。

服务端字段：

```text
avatar_url            # 原始/处理后的动画头像
avatar_thumbnail_url  # 通知和低成本页面使用的静态缩略图
```

主页和资料页可播放支持的动画头像；RemoteViews 通知只使用静态缩略图。动画处理失败不替换旧头像。

动态裁剪采用两段式处理：客户端上传原文件与规范化裁剪参数（归一化中心点、缩放和 1:1 输出框）；服务端在受限 worker 中验证并逐帧应用同一裁剪，输出受资源上限约束的动画 WebP/GIF，同时生成首帧静态缩略图。客户端可以先显示静态裁剪预览，不在手机端同步重编码全部动画帧。worker 必须设置 CPU、内存、帧数、时长和执行超时，失败时删除临时文件并保留旧头像。

### 4.4 安全图片处理

- Photo Picker/OpenDocument，不申请传统存储权限；
- 通过 `content://` 读取；
- 先读取尺寸并下采样；
- 修正 EXIF；
- 应用内 1:1 裁剪；
- 服务端验证 MIME、文件头、大小、像素、帧数和时长；
- 文件名使用 UUID；
- 禁止内容嗅探；
- 新头像成功后清理旧文件；
- 失败时保留旧头像。

### 4.5 客户端资料状态

统一模型：

```text
CoupleProfile
├── me(id, nickname, avatarUrl, avatarThumbnailUrl)
├── partner(id, nickname, avatarUrl, avatarThumbnailUrl)
└── anniversaryDate
```

- 登录/绑定后读取 `/pair/status`；
- 本地缓存最后成功资料；
- 离线显示缓存；
- 修改后先以服务端返回更新权威状态；
- WebSocket `profile_updated` 事件只携带 user ID，客户端重新读取 pair status；
- 不在事件队列复制敏感资料正文。

### 4.6 资料二级页面

“我的”顶部资料卡展示本人头像、昵称和伴侣摘要，点击进入“个人与情侣资料”。

分组：

- 个人资料：本人头像、本人昵称、伴侣头像和昵称只读；
- 我们的信息：纪念日、当前相恋天数、绑定状态。

昵称修改使用 Miuix 输入弹窗；失败保留输入并显示错误。

### 4.7 纪念日选择和相恋天数

不使用系统 DatePicker。实现 Miuix 年/月/日联动选择器：

- 处理闰年和每月天数；
- 不允许未来日期；
- 默认当前设置值，无值时定位今天；
- 重组/旋转不丢状态。

主页关系卡：

```text
[双方头像]
我们已经在一起
第 N 天
YYYY.MM.DD
```

计算使用本地日期：`当前日期 - 纪念日 + 1`，当天为第 1 天；不使用毫秒除法。跨日自动更新。未来异常值不显示负数并记录 WARN。

### 4.8 头像缓存与通知

- 资料刷新时在 IO 线程下载并缓存；
- 缓存键由 URL/版本生成；
- 生成圆形静态 Bitmap；
- 通知构建只读取缓存；
- 缓存未完成时使用默认情侣头像/姓名首字；
- 下载完成后用相同通知 ID 刷新；
- 失败请求退避，不在每次状态刷新重复下载。

### 4.9 阶段 2 测试

- 服务端昵称唯一性与长度；
- pair 权限、未来日期和闰年；
- 图片 MIME/文件头、超大图片、动画限制；
- 头像替换与旧文件清理；
- CoupleProfile JSON 和缓存；
- 相恋天数跨月/年/闰年；
- 通知头像缓存；
- Draft PR CI 全绿后进入阶段 3。

## 5. 阶段 3：KernelSU 主题中心、壁纸与动态取色

### 5.1 二级主题中心

“我的”只保留“主题与界面”入口。二级页面迁移 KernelSU 有业务意义的能力：

- 跟随系统/浅色/深色；
- Monet/动态配色；
- 预设种子色；
- PaletteStyle；
- ColorSpec 2021/2025；
- 页面模糊；
- 悬浮底栏；
- 悬浮栏玻璃；
- 预测性返回；
- 页面缩放；
- 全局壁纸。

不实现无业务意义的导航角标，不恢复 AMOLED。

默认：页面模糊、悬浮栏、玻璃、预测性返回均开启，页面缩放 100%。

依赖：悬浮栏关闭时使用普通 NavigationBar 并隐藏玻璃开关；不支持 Spec 2025 的 PaletteStyle 回落到 Spec 2021并显示说明。

### 5.2 全局壁纸

壁纸为本地设备偏好，不同步服务端。数据模型：源信息、处理文件、裁剪缩放、平移偏移、输出尺寸、模糊、浅/深遮罩和颜色缓存。

处理后文件存入私有 `files/wallpaper/`；不长期依赖外部 URI。新文件保存成功后删除旧文件。

### 5.3 应用内自由裁剪

- Photo Picker 选图；
- 全屏 Miuix 裁剪页；
- 裁剪框比例匹配当前屏幕可用区域；
- 双指缩放、单指平移、双击适应/放大、重置和实时预览；
- 不允许图片边缘露出裁剪框空白；
- 输出宽度按物理屏幕，最大 1440px；高度按比例，最大 3200px；
- 高质量 WebP，失败降级 JPEG；
- 动态图片作为壁纸使用静态预览帧，不持续播放。

### 5.4 壁纸渲染与可读性

根层 `WallpaperHost` 只解码一次并位于所有页面后：壁纸、主题遮罩、Backdrop、页面内容。

使用壁纸时 Scaffold 背景透明/半透明，Card 保留可读不透明度；顶栏和玻璃采样壁纸。无壁纸时恢复标准 Miuix surface。

可调：壁纸模糊、浅色遮罩、深色遮罩。默认浅色 20%、深色 35%。遮罩读取解析后的 App 主题，不直接读取系统暗色。

### 5.5 壁纸动态取色

以最终裁剪壁纸为输入：后台缩小、提取候选色、过滤过暗/过亮/异常饱和和低区分灰色，生成种子色，再结合 PaletteStyle 和 ColorSpec 生成主题。壁纸或裁剪变化时才重新分析并缓存。

颜色来源：壁纸自动、系统动态色、手动种子色。手动选择后不被壁纸覆盖。

### 5.6 单一外观状态

```text
AppearanceSettings
├── colorMode
├── monetEnabled
├── colorSource
├── keyColor
├── paletteStyle
├── colorSpec
├── blurEnabled
├── floatingBottomBarEnabled
├── floatingGlassEnabled
├── predictiveBackEnabled
├── pageScale
└── wallpaperSettings
```

UI 只观察单一状态；旧偏好自动迁移；未知值安全回落；主题切换不重建 Activity。

### 5.7 阶段 3 测试

- 偏好迁移；
- ColorSpec 回落；
- 裁剪矩阵和输出尺寸；
- 图片不能露空；
- 壁纸取色缓存；
- 强制浅/深遮罩；
- 移除壁纸恢复；
- 设置项依赖；
- 壁纸只解码一次；
- 裁剪/取色不阻塞主线程；
- Draft PR CI 全绿后进入阶段 4。

## 6. 阶段 4：ColorOS、OriginOS 与 Android 16 持续状态

### 6.1 真实能力边界

不能承诺所有 vivo/OPPO 设备进入系统岛位。厂商能力受 ROM、机型、地区、白名单、签名和审核控制。Android 16 Live Update 主要服务用户发起、时间敏感、进度型过程，不适合作为长期伴侣状态的通用兜底。

最终三级架构：

1. ColorOS/OriginOS 公开且普通应用可用的专项 Adapter；
2. Android 16 Live Update 仅用于符合资格的短时事件；
3. 标准横向 RemoteViews 常驻卡始终兜底。

### 6.2 统一接口

```text
OngoingStatusController
├── StandardNotificationAdapter
├── ColorOsStatusAdapter
├── OriginOsStatusAdapter
└── AndroidLiveUpdateAdapter
```

统一数据不含位置：伴侣名、静态头像、前台 App、屏幕、电量、网络、同步状态、更新时间、主动作和锁屏隐私级别。

支持状态：Supported、Unsupported、RequiresUserSetting、RequiresVendorApproval、TemporarilyUnavailable。

### 6.3 ColorOS 与 OriginOS

分别调查公开开发文档、SDK、Intent、模板、版本、地区、上架/白名单要求。

- 公开且普通应用可用：按官方 API 接入；
- 需要资格：返回 `RequiresVendorApproval` 并在设置页明确显示；
- 无公开能力：返回 Unsupported；
- 不使用反射、私有 extras 猜测、伪造包名、签名绕过或内部 token；
- 专项失败不停止前台 Service，自动使用标准通知。

### 6.4 Android 16 Live Update

仅在系统 API、频道、资格和事件语义都满足时使用。适合尝试：10 秒强制响铃、短时待办强提醒、用户主动等待回应。长期电量、前台 App 和相恋天数不请求 promoted。

### 6.5 更新频率与隐私

真实状态变化才更新；仅时间文字变化不触发高频刷新。使用内容哈希去重。

锁屏隐私：完整、简要、隐藏敏感内容；默认简要。不显示 SSID、日记/待办正文；锁屏默认不显示具体前台 App，可替换默认头像。

设置页显示每个 Adapter 的真实支持状态和原因。日志记录 Adapter 选择、资格和降级，不记录认证 token 或状态正文。

### 6.6 阶段 4 测试

- Adapter 顺序和厂商识别；
- 不支持/失败时标准通知兜底；
- 重复状态不刷新；
- 隐私过滤；
- Android 版本和资格；
- 不把普通状态标记为 Live Update；
- 最终真机矩阵：ColorOS、OriginOS、Android 16、普通 Android 13+。

## 7. Git、CI 与最终交付

### 7.1 实施启动

1. 删除根目录 `dist/`；
2. 确认 `.gitignore` 忽略 `dist/`；
3. 创建独立功能分支；
4. 创建 Draft PR；
5. 不下载任何 APK 到本地。

### 7.2 阶段门禁

每阶段：测试先行 → 实现 → 文档 → 代码评审 → 推送分支 → Draft PR 工作流。工作流至少执行 Go test/vet/build、Android 单测、Debug/Release assemble。失败必须在本阶段修复至全绿。

### 7.3 最终交付

四阶段全部全绿后：全量代码复用/质量/效率评审、安全审查、服务端迁移审查和回归；合并 main；等待 main 最终工作流；只向用户提供 GitHub Actions 成品 artifact 的运行链接/状态，不在本地保存 APK。

## 8. 文档要求

同步维护：

- `docs/android-ui.md`；
- `docs/foreground-notification.md`；
- `docs/diagnostics.md`；
- `docs/feature-index.md`；
- 新增资料/头像/纪念日文档；
- 新增主题壁纸文档；
- 新增持续状态厂商能力矩阵；
- 服务端 README、schema 和 API 文档。

每个阶段文档和代码同批提交，不允许最终补写。
