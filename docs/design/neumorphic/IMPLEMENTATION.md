# 工程实施与验证计划

本文件与 DESIGN.md 配套。下面标“新增”的类／组件／文件是建议，不是仓库现有API，不可直接假定能import。路径以仓库根为基准；Android简称包根为 `android/app/src/main/java/com/linxi/diary/`。

Draft1.2最新范围：[REFINEMENTS-1.2.md](REFINEMENTS-1.2.md)。石墨暗色、双端角色管理/文件选择器、资源库发布下载、上滑播放器和图标为必做。Live2D L0～L3沿用[LIVE2D-AND-CONTROLS.md](LIVE2D-AND-CONTROLS.md)，新增L4后台资源/L5客户端资源库；只允许为此新增必要服务端能力，其他业务边界保持。

## 当前实现进度（代码已落地；Android 编译/真机仍由环境与管理员验收）

### 2026-09-09 当前主分支增量

- `04609bc` / `9e5a225`：一起看清除内容使用统一危险确认（冷静期、请求中不可关闭、失败留在弹窗内）；歌词跳转和“这一天”照片网格使用 `LxClickableSurface`，移除平台 ripple 并补齐无障碍语义；可选 Cubism GLSurfaceView 不再把透明角色提升到整个窗口最上层。
- GitHub Actions 质量门禁 #34（`04609bc`）与 #35（`9e5a225`）均通过服务端、后台、Android 编译/单测/Lint；#35 产出非过期 Debug APK 与 Lint 报告 artifact。Android 仅以 GitHub Actions 为构建证据，本机不执行 Gradle/Java；授权 Core AAR、匹配 Framework、完整授权模型与一加 15 首帧仍是外部验收门槛。

### 2026-09-08 第二批覆盖（本轮新增）

### 2026-09-09 会话与门禁收口（本轮新增）

### 2026-09-09 播放器与高频操作材质收口（本轮新增）

- 新增 `ui/components/LxIconButton.kt`，把返回、相册管理、照片查看、待办操作等原先直接使用的 `miuix IconButton` 收口到统一语义组件：组件内部提供 48dp 触达下限、Raised/Inset 按压状态、禁用态和按钮无障碍角色。
- 全局播放器的迷你胶囊改用 `LxClickableSurface`，移除原生 ripple；搜索结果、一起听结果、歌词页、迷你胶囊和全屏播放器共用 `NeteaseTrackCover`，远程封面失败时保留可见拟态底色，不渲染透明空白。
- 歌词详情页补齐封面、随机播放和循环模式控制，仍只派发给唯一的 `NeteasePlaybackManager`，没有创建第二个播放器或本地假状态。
- 资料页性别、日期步进、头像入口和待办重复选项迁移到统一拟态按钮/可点击表面；解绑、删除相册等危险操作不再使用无语义的红色可点击文字。
- 验证：Android 不在本机执行 Gradle；真实设备绘制和 Cubism 首帧仍需授权运行时、模型与真机验收，不能以静态检查或文档替代。Android 构建证据统一记录在对应 GitHub Actions 质量门禁。

### 2026-09-09 默认点击反馈清零（本轮新增）

- 待办详情展开、知情同意整行和本机相册分桶切换改用 `LxClickableSurface`；按下时统一进入 Inset、移除平台 ripple，并声明按钮/复选框/状态文案语义。嵌套的提醒开关、完成/删除动作和 Checkbox 仍保留各自事件，不改变业务操作。
- 本轮不在本机执行 Android Gradle 或 Java；Android 编译、单测、Lint 与 Debug APK 打包只以 GitHub Actions 质量门禁为证据。质量门禁 #31（`5b4608a`）已成功完成三端 job，并产出 `android-debug-apk-5b4608a981e3421594a45b3f865937ab37e6a7e4` 与 Lint 报告 artifact。

### 2026-09-09 交互表面残留收口（本轮新增）

- `LxClickableSurface` 现在共享 `MutableInteractionSource`：点击与长按均移除平台 ripple，按下时切换为 Inset 边界，所有复用它的相册、发现、音乐与一起听卡片保持同一拟态按压反馈；描边半径可随 8dp 缩略图或圆形按钮调整，不再用固定 16dp 圆角。
- 主页面待办 FAB 从 Miuix `FloatingActionButton` 迁移到 `LxIconButton`，保留 48dp 触达下限、品牌蓝语义、圆形造型和中文无障碍描述；不再与其他拟态图标动作出现原生外观差异。
- 相册详情照片网格、本机选图网格改用共享可点击表面；照片网格继续保留长按进入选择、选中状态和预览独立入口，选图控件声明 Checkbox 角色与状态文案。
- 回归验证以 GitHub Actions 质量门禁为准：`e0faf68` 的质量门禁 #29 已成功完成服务端、后台和“安卓编译、单测与 Lint”三项 job；仅保留既有 Kotlin/Android API 警告。

- 首页陪伴角现在读取本机当前模型并通过 `ui/components/Live2DPreviewHost.kt` 嵌入真实 `GLSurfaceView`；管理页预览也复用同一宿主。宿主用进程级单活跃租约处理 `AnimatedContent` 转场，避免两个页面同时初始化 Cubism Framework。缺少授权 Core AAR/Framework 或模型宿主创建失败时，仍显示可读的降级状态，不用 PNG/线框冒充渲染。
- Live2D 导入安全边界继续收口：APP 与服务端现在同时限制压缩 ZIP 50 MiB、单文件 64 MiB、moc3 20 MiB，并按 `model3.json` 实际引用汇总全部贴图像素，避免多张“单张合规”纹理合计耗尽显存。新增服务端与 JVM 回归测试覆盖聚合预算。
- 新增 `Live2DRenderPolicy`（纯策略）与 30fps 上限：静态模型不启动自由运行 ticker，卡片离屏、被遮挡、未选中或尺寸为零时停止绘制。新增 `Live2DNativeRuntime`/`Live2DRendererBridge`，可探测官方 Core/Framework，并保持缺少许可 AAR 时的真实降级态。
- Android `src/cubism` 提供可选的官方 Java Framework `GLSurfaceView` 适配器：只有显式提供匹配的 `Live2DCubismCore.aar` 与 Framework 根目录才编译；适配器加载私有 model3/moc3/PNG、校验路径、在 GL 线程绑定纹理和释放资源。默认仓库构建不携带专有 Core，也不把静态图或占位框伪称 Live2D 成功。

- 发现页“ 一起看 ”已从开发中占位接入真实情侣房间：服务端新增 `kind=watch` 状态、HTTPS URL 校验、时间轴/播放/清除控制和 WS 广播，客户端复用 `ListenSessionController` 的权限、重连与会话令牌；直接 MP4/WebM 交给 Media3，YouTube/Bilibili 等网页链接明确交给系统浏览器，不把网页地址假装成可下载媒体。后台活动房间只显示观看标题与时间轴，不返回可能携带短期签名的 URL。

- 一起听的会话生命周期已从页面彻底收口到 `data/ListenSessionController.kt`：WebSocket、内存会话令牌、断线重连、房主心跳、成员状态拉取和登出清理均由单一 owner 管理。重连会重新 join 取得新令牌，不把可能已被另一台设备撤销的旧令牌无限重试；页面只展示状态并发出用户意图。
- 一起听控制请求期间保留页面内容与搜索控件，只在首次进入房间且尚无快照时显示整页 loading；系统文件写入失败也不再显示“保存成功”。
- 本轮复跑 `admin` 的 `npm run build`、`npm run lint` 与 `server` 的 `gofmt`、`go vet ./...`、`go test -timeout 400s ./...`，均通过。安卓仍遵守“本机不安装／启动 APK”，源码编译受 Gradle daemon loopback 环境阻塞，不能把静态检查写成编译通过。

### 2026-09-09 后台残余材质收口（本轮新增）

- 侧栏主题变体、工作区标签、登录页主题/语言按钮、拖拽验证器和 Markdown/代码预览不再回退到旧的纯色灰框或黑色投影；它们统一读取 `--lx-surface`、`--lx-elevated`、`--lx-*shadow` 与焦点 token，保留品牌蓝、成功绿和危险红的语义用途。
- 全局层补齐折叠面板、上传拖拽区、复选框/单选框、Teleport 浮层和 `border-mode`/`shadow-mode` 的 raised/inset 状态，并提供 `prefers-contrast: more` 的无阴影高对比降级；工作区标签增加明确的 `art-work-tab` 语义类，避免依赖 Tailwind 生成类覆盖主题。
- 本轮 `admin` 的 `npm run build`、`npm run lint` 和目标 SCSS 的 stylelint 检查通过；构建只更新本地 `dist`，没有把后台产物写入提交范围。

### 2026-09-08 手机网页端补齐（本轮新增）

- 后台新增共享 `LxMobileRecordList`：管理员、APP 版本、通知模板、容量统计四个原先直接渲染 `ElTable` 的页面，在 768px 以下改成同一请求数据的字段卡片；状态标签、危险操作、下载链接与权限文案都保留，桌面端继续使用原表格与分页。手机不再被迫横向拖整张管理表。
- 移动记录卡统一提供加载、空态、字段标签、操作区、safe-area 底部留白、44px 触达和石墨/浅色 raised 表面；桌面表格与手机卡片共用同一接口和状态，不复制 mock 数据。
- 后台弹窗 footer 在手机端改为可换行／单列的大触达按钮，避免确认／取消挤在 360px 视口边缘。

### 2026-09-08 APP 播放器与页面占位补齐（本轮新增）

- 全屏播放器继续消费唯一 `NeteasePlaybackManager`：加入自适应封面（含失败占位）、小屏可滚动布局、真实进度语义与点击 seek；不创建第二个 ExoPlayer。全局 mini 播放条的高度通过新的 `LocalPlaybackBottomPadding` 传给 `KernelScreen`，二级页末尾内容不会再被浮层盖住。
- “我的 → 音乐设置”已补齐播放胶囊配置：可关闭本机媒体通知，或允许通知副文案显示已缓存的当前歌词；不申请悬浮窗权限、不伪造系统灵动岛，最终呈现仍由 Android/厂商媒体通知决定。

- Android `KernelScreen` 现在默认使用 16dp 页面边距，并把石墨/浅色 `canvas` 作为所有列表页的底层材质；模糊顶栏的采样底色也从 Miuix 动态表面收敛到同一套 `LxSurfaceTokens`。
- Live2D 本机模型现在有单一“当前使用”选择：选择结果只写入本机 `SharedPreferences`，导入首个模型时自动选中；主页陪伴角展示当前模型并在可选运行时就地渲染，缺少运行时则明确显示等待 Core 的状态。删除当前模型会清理并自动回退到剩余模型。资源库下载与 SAF 导入共用同一同步路径，不会把选择状态上传服务端。
- 主页的纪念日、伴侣状态、手机信息和 Live2D 入口，音乐馆/一起听的主卡与结果行，Live2D 管理页的导入/资源库/预览/模型行，以及“我的/主题与界面”分组，已使用 `LxSurface`；深色不再使用大面积粉色/绿色/蓝灰底，语义色仅保留为小面积强调。
- 剩余的照片选择器、歌词候选、相册入口、待办、绑定模式和发现入口也已收口到 `LxSurface`／`LxClickableSurface`；后者只给真正的操作卡挂点击语义，装饰面不会意外变成大按钮，长按相册与嵌套管理按钮继续保留。
- 发现页的相册摘要请求增加了“有数据/失败可重试”的可见错误态；错误不再伪装成空数据。
- 后台应用壳增加拟物 header、侧栏 active/hover/pressed 状态、页面壳阴影、safe-area、320px 最小宽度和 640/800px 移动断点；表格、对话框、分段控件、空态、加载遮罩以及用户菜单／语言菜单／右键菜单共用 token。桌面与手机仍共用同一份路由和请求层，不复制一套移动业务。
- 后台移动审计清单已把超管专属的 `/live2d-manage` 纳入真实路由覆盖；权限不足时应保留服务端／路由的无权限态，不能用“菜单隐藏”替代验证。
- 播放器覆盖层已移到 `AnimatedContent` 外的 App shell；页面转场同时组合旧/新目标时不会生成两份 mini 播放器或两个返回处理器。
- `LxConfirmDialog`／`LxFormDialog` 的内部内容也统一包进 Floating 表面，继续保留取消灰、确认蓝/危险红、危险延迟和 busy 禁止外部关闭规则。
- 后台版本页对 GitHub Release/CHANGELOG 的外部依赖采用可见降级：服务端返回 200 的 `degraded/warning` 数据，前端显示可重试警告，不把临时网络故障渲染成白屏或 502。
- Android 的权限结果、诊断导出、返回退出提示已从系统 Toast 收口到 App shell 的 `AppNoticeBus` + `LxNoticeHost`；通知使用 Floating 拟态表面、统一触达尺寸和进出场动效。系统文件选择器与 Sharesheet 仍保留为用户明确触发的系统界面，不在 App 内伪造。
- Android `LxSurface` 的 Raised/Floating 阴影现在使用明暗主题 token 作为 ambient/spot 颜色，并叠加低透明高光边；Inset 仍使用独立的按压边界，避免页面各自散写单侧黑投影。
- 真实隔离服务的 `mobile-audit.mjs` 已覆盖 412×915、360×640、390×844、768×1024 四档视口，登录、首登改密、全部菜单和 `/live2d-manage` 均通过；检查项包括无横向溢出、无白屏、无控制台错误、无失败请求。

本批只做可逆的视觉/状态覆盖，没有改版本号、发布配置或照片权限；Android 编译/真机渲染仍需按下方验证边界执行，后台全菜单审计已实际完成。

- 已落地 P0 材质层：Android `LxSurfaceTokens`、`LxSurface`、按钮按压态与 `KernelScreen.horizontalPadding`；Vue 后台 `linxi-neumorphic.scss` 已接入全局 token、卡片/输入/按钮/弹窗/表格和 360～桌面响应式规则。
- 已落地播放器第一批：`MusicPlayerOverlay` 挂在 App shell（登录/注册/绑定页外）而不是某个音乐列表；音乐页移除重复局部 mini；同一 `NeteasePlaybackManager` 驱动 mini/full，底部上滑展开与返回收起，控制使用已核验的 Miuix 图标。
- 已落地 APP Live2D 文件管理第一批：系统 `OpenDocument` 选择 ZIP、本机 no-backup 隔离目录、路径穿越/条目数/解压体积/必需文件校验、取消/失败不覆盖旧模型、删除入口；原生 Cubism 宿主已接入为可选 source set，默认构建仍因许可边界不携带 Core/Framework，预览会明确显示等待运行时。
- APP 导入校验已追加 `Live2DImportPolicy`：嵌套根目录和 manifest 相对路径（含包内安全 `../`）按运行时语义解析，大小写冲突拒绝，VTube Studio sidecar/预览图/可选动作、表情、物理资源保留；失败时返回分项兼容性报告，因此用户提供的包会明确显示缺少 moc3 与 8192×8192 贴图预算，而不是静默丢包。
- 已落地后台 B18 资源库与服务端 L4 基础：超管菜单、拖放文件暂存、发布/撤回/删除状态、私有目录、SQLite 迁移 5、流式 ZIP/manifest 校验与审计；同时接入 APP 已发布目录查询和 JWT 下载后本机再校验导入。原生 Cubism 渲染验收仍未完成，不把结构校验冒充渲染成功。
- 已实际验证：`server` 的 `go test -timeout 400s ./...`、`go vet ./...`，以及 `admin` 的 `npm run build`、`npm run lint` 通过；后台真实隔离服务四档视口审计通过。Android 未在本机安装／启动 APK；此前 Gradle daemon loopback 环境问题仍记录为未验证项。Cubism 宿主已接入，但 Core AAR、完整授权模型与真机首帧仍未验收，禁止据此声称 Android 三端全通过。

## 1. 必须遵守的改造边界

既有模块只重构UI与为全局播放器所必需的状态生命周期，保留接口、音源解析、鉴权、图片处理与业务语义。角色资源库的API/存储/数据库迁移是Draft1.2单独授权的新增设计范围，必须独立批次与测试；不是重写其他服务端或放宽权限的许可。不改compose/.env，不无关升级依赖或改版本号。

第一步先`git status --short`，确认用户改动；读AGENTS和DEVELOPMENT。搜索既有同类调用，禁止凭印象创造miuix API。读每个要改的文件和测试，不能只凭这份设计稿盲改。

## 2. 文件落点与职责

| 优先级 | 文件／目录 | 动作 |
| --- | --- | --- |
| P0 | `ui/theme/Color.kt`、`Theme.kt`、`AppearanceSettings.kt`、`AppearanceStore.kt` | 保留蓝色种子与现有主题模式；新增可组合材质tokens，不绕过现有主题解析 |
| P0 | 新增`ui/theme/LxSurfaceTokens.kt` | 提供明暗颜色、阴影档位、尺寸。无IO、无网络、无逐帧取壁纸色 |
| P0 | 新增`ui/components/LxSurface.kt` | 纯绘制容器：Flat/Raised/Inset/Floating；参数shape、modifier、content；不自动clickable、不重复语义 |
| P0 | `ui/components/LxButton.kt` | 内部接入材质和按压，保留全部现有重载／默认参数／48下限／Positive Negative Neutral；内容式重载也生效 |
| P0 | `ui/components/LxDialog.kt` | 只改表面；保留危险1秒延迟、busy不可关闭、取消灰与确认蓝／红 |
| P0 | `ui/components/KernelScreen.kt` | 加横向padding兼容参数默认12；新迁移页面传16。列表间距先核对调用点，不默认重复spacedBy |
| P1 | `ui/navigation/LinxiApp.kt`、`MainFabState.kt`、`MainFabDestination.kt` | 抽取BottomChrome挂载、播放器覆盖层、返回优先级；主tab仍4个 |
| P1 | 新增`ui/navigation/PlayerChromePolicy.kt` | 纯策略：哪些Screen显示mini、IME/同意页门控、返回和padding决策，供JVM测试 |
| P1 | 新增`ui/components/MiniPlayer.kt`、`ui/screens/FullPlayerScreen.kt` | 只消费状态与派发命令，不自己建播放器／WS／心跳 |
| P1 | `data/NeteasePlaybackManager.kt`、`MusicNotificationController.kt` | 单一播放事实源；路由所有用户控制到同一命令层，房间模式不被通知栏绕过 |
| P1 | 新增`data/ListenSessionController.kt`（名称可调整） | 将房间state/role/token、socket、恢复任务从Screen提取；明确start/leave/close/cleanup |
| P1 | 新增`data/PlaybackCommandRouter.kt`（名称可调整） | 单独听命令走本机；一起听命令按权限走现有房间接口；同步回放与用户命令分开防递归 |
| P1 | `ui/screens/ListenTogetherScreen.kt` | 迁出会话生命周期，保留入口兼容，展示房间状态／设置；不要删除旧能力后只剩一个漂亮播放器 |
| P1 | `ui/screens/MusicHomeScreen.kt`、`LyricsScreen.kt`、`MusicSettingsScreen.kt` | 音乐馆／全屏／歌词职责拆分；账号登录Activity与收藏保留 |
| P2 | 其余`ui/screens/` | 按DESIGN A01～A12迁移；既有组件比复制新组件优先 |
| P0 | 新增`admin/src/assets/styles/core/linxi-neumorphic.scss` | CSS变量＋状态样式；在`index.scss`的现有主题样式之后加载，核对Sass与Tailwind层叠顺序 |
| P0 | `admin/src/assets/styles/core/el-ui.scss`、`el-light.scss`、`el-dark.scss`、`dark.scss` | 用Element变量适配；去除被接管的冲突声明，不全站!important |
| P0 | 新增`admin/src/components/linxi/` | PageHeader、FilterBar、StatePanel、ConfirmDialog、DetailDrawer，名称可调整；尽量复用已有公共组件 |
| P2 | `admin/src/components/core/tables/art-table/`、`hooks/core/useTable` | 统一数据区状态／尺寸，保留分页与请求机制；先看现有能力再扩展 |
| P2 | `admin/src/views/`全部B01～B17 | 页面迁移；不删模块、不发明业务 |
| P2 | `admin/src/router/modules/`、i18n资源 | 核对菜单与页内权限，标签保留i18n；日志旧路由兼容 |
| P3 | `docs/android-ui.md` | 同步陈旧粉色／间距描述，记录新壳与真实代码，不能文档先声称已完成 |

## 3. Android 材质实现契约

### 3.1 依赖判断

当前versions.toml声明miuix0.9.3、Compose BOM2026.06.01、Media3 1.10.1。声明不等于已解析依赖，接手运行dependencyInsight确认compose-ui版本与可用API；不顺手更新整个BOM。

Android官方支持`dropShadow`和`innerShadow`用于可控外／内阴影。先写一个最小组件编译并截图。若当前工具链不支持，优先在已有`ui/liquid/miuix/`寻找可复用绘制逻辑，再做封装的轻描边／单阴影降级；新增库必须另行说明必要性。

### 3.2 绘制顺序（概念，不是可直接粘贴源码）

```text
外部布局尺寸／阴影留白
  → 两层dropShadow（同shape，左上亮、右下暗）
  → shape背景
  → Inset档才绘制两层innerShadow
  → 内容裁切／边界与交互反馈（不得裁掉外部阴影）
  → 可访问内容
```

外阴影和内容clip分层：不要给父容器过早clip使投影全消失。内阴影要在背景之后绘制；实际modifier顺序须用截图验证。已有miuix Card自带背景／裁切／阴影时，不再套一个同样Raised容器。正文、数据行不承担阴影绘制。

`LxSurface`不拥有按钮语义、不拦截点击；`LxButton`继续拥有Role.Button、disabled、48下限，使用一个interactionSource连接点击和pressed。滚动取消手势后必须恢复抬起状态；鼠标hover不得造成布局变宽。图标使用MiuixIcons；basic包用MiuixIcons.Basic。仅theme保留Material桥。

不要用每帧重新生成Bitmap的方式实现阴影；不要给LazyColumn每个row加大范围blur。进度变化仅重组进度/时间，封面和页面列表不跟着每秒整页重绘。纯数据策略与绘制层分离。

## 4. 播放器工程：先处理生命周期，再处理外观

### 4.1 当前真实风险

ListenTogetherScreen将`room`、`role`、`sessionToken`保存在页面state；`DisposableEffect(roomId, sessionToken)`创建socket，在`onDispose`关闭；`LaunchedEffect`发心跳／REST恢复。把视觉控制移到全局而保留这些Effect，会造成“离开一起听页→连接停止→迷你条仍显示一起听”的假状态。

NeteasePlaybackManager已是单例、有ExoPlayer、MediaSession、StateFlow和进度任务。不能为全屏和mini各新建player；也不能再叠三个ticker。当前通知相关控制路径也要搜全，避免通知按钮直调本机绕过房主权限。

### 4.2 推荐状态分层

```text
页面输入状态（搜索词、筛选、展开、滚动）
     ↓ 用户intent
PlaybackCommandRouter（本机／房间权限与命令）
     ├─ Local → NeteasePlaybackManager → 唯一音频实例
     └─ Room → ListenSessionController → 现有REST/WS协议
                                      ↓ 接收权威房间快照
                        内部applyRemote → NeteasePlaybackManager

只读状态 → MusicHome / MiniPlayer / FullPlayer / Lyrics / Notification
```

不要让`applyRemote`再走“用户命令”接口，否则远端事件会回发REST形成回路。会话token只存必要内存，不写日志、URL展示、SavedState或截图。旋转重建UI不产生第二条连接。

推荐会话作用域由明确owner持有：前台App级控制器或已有合适的音频生命周期组件。首轮保证App内跨页连续；切后台与长期播放按当前服务架构核对和真机验收，不声称将scope改为全局就能绕过Android后台限制。新增前台服务是单独架构决策，不能暗中加权限。

### 4.3 必须实现的状态迁移

| 事件 | 状态／任务处理 | UI结果 |
| --- | --- | --- |
| 首次打开音乐馆 | 只读取账号和列表，不自动建房 | 无播放内容无mini |
| 本机歌曲解析成功 | 替换track并播放；旧异步请求不得覆盖新歌 | mini与全屏一致 |
| 播放器展开／收起 | 仅UI状态转换；下起上展280ms/反向220ms，不创建销毁会话 | 回原页、原滚动；完整规则见Draft1.2 |
| 加入一起听 | 单飞请求；成功持有room/token/role，开启唯一连接及恢复任务 | 显示真实加入／连接状态 |
| host或允许成员控制 | 走现有controlListenRoom，处理返回快照 | 无响应不宣称成功 |
| 禁止成员控制 | 拒绝用户命令，包括mini与通知栏 | 给解释，不先改变音频再回滚 |
| 房间快照到达 | 顺序处理、丢弃已过期请求结果；原有版本字段若有则复用 | 不让旧曲目覆盖新曲目 |
| 切页／收起播放器 | 不结束会话 | 控制和状态持续 |
| WS断开 | 标恢复中，复用REST恢复；错误可见 | 不保持绿色“已同步” |
| 主动离开／服务端关闭 | 取消socket/心跳/恢复任务、清token、重置mode | 关闭后继续单独听需明确用户操作 |
| 退出账号／解绑音乐 | 按原业务停止清理，任务幂等取消 | 清除mini，旧token回调不得污染新用户 |
| 进程被杀后重进 | 只恢复允许持久化的数据；重新核对服务端和登录 | 不根据SavedState假装已连接 |

离开／关闭按钮必须说清区别：离开自己的会话与关闭整个房间不是同一动作。接口是否支持两者以现有ApiClient为准；不支持的动作不得仅凭设计图创建。

### 4.4 队列与同步范围

本机queue/repeat/shuffle沿用manager。房间快照当前含单曲及时间轴，不由此推断共享完整队列。房间模式首轮只同步协议已支持的控制；可显示“本机队列”，不能标“共同队列”。切歌必须经权限与现有选歌命令。成员收藏是自己的账号行为，不作为房间命令。

seek本机预览与提交分离，最终限制在0..duration；房间允许的action先搜索handler再接入。网络超时、房间被关、403、歌曲失效各有反馈。歌曲URL只在本机解析，不传入房间、日志或持久化存储。

### 4.5 底部占位算法

不要硬编码“原底栏高度＋64”。建议把BottomChrome作为一个可测量布局，包含可见mini、gap、可见nav和一次system inset；输出其实际占位高度。页面末尾padding=chrome遮挡高度+内容尾距，FAB底部位置以同一高度为基准。

Main现有Scaffold已通过LocalMainBottomPadding传bottom，迁移要么复用这个唯一来源，要么完整替换，不能双重消费。二级页需在root同样获得值，而不是仅在HorizontalPager内提供。IME显示则chrome隐藏，IME占位由输入布局消费一次。旋转／字体变化必须重新测量。

## 5. 后台主题与数据实现

### 5.1 CSS契约

在现有主题根选择器（先确认项目怎样切暗色）定义`--lx-canvas`、`--lx-surface`、`--lx-text`、`--lx-raised`、`--lx-inset`；映射Element背景、文字、主色、边界和圆角变量。不要同时保留两套互相覆盖的主题源。

Raised只用于明确标注的容器；给每个`.el-card`统一高阴影会让日志中心里的内层Card套娃。ElInput视觉边界在wrapper层，disabled/focus/error选择器分别处理，不能只改外面的div。ElDialog/Drawer/Popover常teleport到body，token必须在它们能继承的位置，不能只挂在页面局部scoped节点。

用原生button/ElButton及输入控件，保留键盘事件和焦点；不要为了inset改成div@click。focus-visible轮廓不得被overflow裁掉；表格横滚不等于页面横滚。

减少动画通过`prefers-reduced-motion`；高对比模式去阴影、增加明确边框并尊重系统颜色。过渡只限transform/opacity或小区域已测量阴影，不写`transition: all`。

### 5.2 状态与请求

统一列表区分initialLoading / refreshing / success / empty / errorWithData / errorWithoutData；没有数据又失败不能显示ElEmpty。刷新有旧数据时保留表格并显示错误条；第一次无统计数用“—”。筛选变化回第1页，防止慢请求覆盖新结果，可复用已有Abort/请求序号机制。

详情抽屉开关与选中ID独立；切行时取消旧请求或防过期回填。保存先校验，再禁用重复提交；成功关闭并刷新正确页，失败保持草稿。清空筛选清所有已应用参数，不能只把输入框视觉清空。

敏感缩略图按用户动作请求，关闭时撤销ObjectURL和引用；不得prefetch全部图。错误日志路径不自动链接到/upload；不增加日志请求体采集。

### 5.3 现有接口表（不是新增接口）

| 展示 | 已有入口 | 注意 |
| --- | --- | --- |
| 看板 | fetchDashboardStats → GET /api/admin/stats | 只使用类型内字段 |
| 用户／关系 | fetchUserList / fetchPairList | 写操作超管 |
| 相册 | fetchAlbumList → GET /api/admin/albums | 全部超管，照片不在这里预览 |
| 缩略图 | fetchPhotoThumbnail → 现有thumb代理 | 每次审计、no-store/no-referrer |
| 容量 | fetchStorageStats | 无分母不造百分比 |
| 日志 | fetchAuditLogs / fetchNetworkLogs | 两个tab，共用视觉，不混数据 |
| 房间 | fetchListenRooms / closeListenRoom | 读admin、关super |
| 配置 | fetchRuntimeSettings / fetchSettings / updateSettings | 按键Super和后端白名单校验，别整体放权 |
| 版本 | fetchAppReleases | 不发明发布API |

服务端如果没有某字段就删除对应设计模块，或登记为单独后续需求；禁止为了图完整把mock data混入正式请求失败分支。

## 6. 分阶段实施：降低接手成本

不按“全部一次重写”执行，每批只读相关文件、提交可验证成果。时间取决于模型与环境，不承诺固定天数。

| 批次 | 范围 | 出口条件 |
| --- | --- | --- |
| 0 基线 | 当前三端构建；现有关键页截图、状态、路由与权限清单 | 已知失败单列；不将原失败归因于新设计 |
| 1 材质样板 | tokens、LxSurface、LxButton/Dialog、后台主题和输入/弹窗样板 | 明暗状态齐；无API猜测；品牌对比冲突记录；用户确认方向 |
| 2 音乐骨架 | 命令层、会话owner、root底部占位、mini/full UI | 跨页播放/一起听不断；无重复player/WS/ticker；返回正确 |
| 3 APP覆盖 | A01～A12，按主页→音乐→相册→待办→设置→认证 | 每页正常/空/错/大字/暗色；无功能消失 |
| 4 后台覆盖 | B01～B17，先公共外壳后逐页；新增B18另走L4 | 角色请求矩阵、手机页面审计、无泄漏 |
| 5 集成验收 | 三端全验证、真机与对照截图、文档 | 所有P0问题关闭；未验证项明确，不写“完美”代替证据 |

每批提交说明要列：改动文件、用户可见效果、保留功能、测试命令/结果、截图、未完成项。提交本身需遵守用户授权；不自动push、发版或部署。

## 7. 必测用例（接手AI逐条勾选）

### 7.1 纯逻辑与回归测试

- [ ] PlayerChromePolicy：所有Screen×有无曲目×IME×强制隐私态；mini显隐正确。
- [ ] padding策略：nav-only、mini-only、both、none，不重复系统inset；大字体实测布局另验。
- [ ] 控制权限：host、member允许、member禁止、room关闭、功能关闭；通知命令同样受限。
- [ ] 会话生命周期：进入两次只一连接；切页不断；退出后任务取消；旧用户异步结果不污染新用户。
- [ ] 远端apply不会发送control；旧响应不覆盖新歌；超时/失败不错误宣布成功。
- [ ] seek无时长、负数、超过时长、拖动期间远端更新；不除零不跳指。
- [ ] 队列重复曲目不撞key；分页保留PagingMerge；上传失败任务不消失。
- [ ] 默认按钮／危险delay/busy禁用；不能因为重构surface取消48dp下限。
- [ ] 新测试按AGENTS临时破坏实现确认会红，随即恢复并重跑；保存红绿证据。不可将破坏实现提交。

### 7.2 UI与访问权限

- [ ] Android320/360/390/412宽，字体1.0/1.3/1.5/2.0，浅/深；键盘输入、横屏和返回链。
- [ ] 播放→主页→待办→相册→全屏→队列→返回；当前歌、暂停状态、进度保持一致。
- [ ] 房间host/member分别操作mini/full/通知；断网、重连、关闭房间、后台恢复。
- [ ] 账户失效、无歌词、长曲名、无封面、未知时长均可读可恢复。
- [ ] admin与super各走登录→强制改密→每个实际可见菜单；低权限无照片请求。
- [ ] 每个后台表单保存/失败/重复点/草稿离开；取消灰、危险红、确认蓝。
- [x] 后台360/390/768/1024四档真实审计；页面不横向溢出，数据容器可局部横滚；1440/1920与200%缩放仍需上线前人工复核。
- [ ] 键盘Tab/Esc/Enter、焦点恢复、TalkBack标签与状态，系统减少动画。

### 7.3 性能目标与测量方法

先基线后对照，同一设备、构建类型、数据量和亮度/刷新率；记录机型、系统、数据规模、场景、工具和原始结果。以下为验收目标，不是本稿实测成绩。

- 播放+滚动相册不因拟态增加逐帧bitmap分配；用Profiler/Perfetto观察卡顿、重组、内存。60Hz帧预算约16.7ms，120Hz约8.3ms；不要只看平均FPS。
- 100次播放器展开/收起后player实例仍1，连接/任务不线性增长；内存回稳，不给未测的MB保证。
- 长列表仅容器阴影，图片继续Coil采样；不增加原图解码、远程照片模糊取色。
- 后台Performance检查筛选与抽屉，避免每行大blur/transition all；用真实大量行和分页而非只5行样例。

### 7.4 仓库要求命令

按DEVELOPMENT实际安装的工具路径执行，不猜环境可用。提交前AGENTS要求三端都过：

```text
server: gofmt -l .
server: go vet ./...
server: go test -timeout 400s ./...
android: gradle :app:compileDebugKotlin --no-daemon
android: gradle :app:testDebugUnitTest --no-daemon
admin: npm run build
admin: npm run lint
admin: node scripts/mobile-audit.mjs <隔离的本地测试服务URL>
```

上方“当前实现进度”记录的是本工作树已经实际运行的命令；下方清单仍是接手 AI 的完整验收矩阵，未勾选项不能因静态代码或样板而视为通过。真机行为由管理员的一加15确认。不要用生产账号/照片做公开截图；测试服务使用隔离数据库与临时数据。

## 8. 回滚与防范围扩张

按上述批次保持小改动，UI材质与会话重构分开便于定位。出现权限泄漏、播放失效、上传丢任务、死循环连接或不可用小屏，立即停止继续铺页面，先修P0。

必要回滚采用针对明确提交的可恢复操作，经授权执行；不git reset --hard，不覆盖用户工作树。后台webdist是嵌入产物不可提交，Android local.properties/APK/server exe不提交。不得为了浏览器看见新样式去改生产compose。

## 9. 明确的待确认事项

1. 品牌蓝红白字与普通文本AA对比冲突：保持现有规则并记录，任何改色须用户确认。
2. 房间关闭后建议暂停并可继续单独听：实施时核对现状，若改变用户既有预期先确认。
3. 若要求一起听长期后台保持，需要额外评估服务生命周期；本设计不授权新增权限与服务。

其余布局、四tab、材质、页面拆分按本文默认执行，不反复向用户询问无关美术偏好。
