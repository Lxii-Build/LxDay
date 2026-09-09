# 本次方案交付检查

日期：2026-09-09。设计资料与业务实现并存；未提交或发布。代码完成度与未验证项以 IMPLEMENTATION.md 为准。

## 业务实现追加检查（当前工作树）

- Android 已落地拟态表面封装、语义按钮/弹窗复用、统一播放器 mini/full 外壳、SAF ZIP 本机导入与已发布资源下载、主页陪伴角入口及设置页管理入口；页面卡片已按装饰面／可点击面区分。首页与管理页现在共用真实 Cubism `GLSurfaceView` 宿主和单活跃租约；默认构建仍不携带许可 Core/Framework，真实模型首帧与真机绘制因此仍是明确未验收项，不能把降级占位当成功证据。
- Live2D 管理页另已落地本机“当前使用”选择与安全回退：首次导入自动选中、切换不触网、删除当前模型后清理失效偏好；主页展示本机当前模型，并在运行时未授权/不可用时明确显示等待 Core，不伪称已经完成 Cubism 绘制。
- 对用户提供的 `C:\Users\Administrator\Downloads\live2d.zip` 已做只读结构检查：缺少 `yumi.moc3`，且包内 VTube Studio 配置写有 `WorkshopSharingForbidden: true`；导入器现在会识别嵌套根目录、大小写冲突、VTS sidecar、预览图、可选动作/表情/物理资源，并以兼容性报告明确提示缺失 moc3 和 8192×8192 贴图预算问题，未把不完整/可能受限的资源写进 APK 或后台资源库。
- 服务端资源库校验与 APP 使用同一条安全边界：model3 引用按 manifest 所在目录解析，可接受包内安全的 `../` 兄弟路径；ZIP 条目拒绝大小写冲突，贴图会在后台上传阶段校验 PNG 头与 4096px/16M 像素预算，避免“后台发布后 APP 下载才失败”。
- 后台已落地石墨／浅色拟态 token、桌面侧栏与手机抽屉、响应式表格／卡片、焦点与触达尺寸、统计异常降级、Live2D 超管资源库入口；GitHub Release 暂时不可达时版本页现在返回 200 降级提示而不是 502，页面继续可用。
- Android App shell 现统一承接权限拒绝、诊断导出和返回退出等短提示，使用 `AppNoticeBus` / `LxNoticeHost` 拟态通知，不再调用原生 Toast；系统文件选择器和 Sharesheet 仍明确属于系统边界。
- Android 高频操作已继续收口：返回、相册/照片/待办图标动作使用 `LxIconButton`；播放器胶囊使用 `LxClickableSurface` 和共享 `NeteaseTrackCover`；歌词页提供随机/循环控制。根据用户验收边界，本机不执行 Android Gradle；Android 编译、单测、Lint 与 APK 打包只以 GitHub Actions 质量门禁为准。
- 交互表面残留已继续收口：可点击/长按卡片使用共享 Inset 按压反馈并移除平台 ripple；主页待办 FAB、相册详情照片网格、本机选图网格均走语义拟态组件，选图保留 Checkbox 状态语义。GitHub Actions 质量门禁 #29（`e0faf68`）与本轮 #31（`5b4608a`）的服务端、后台、安卓编译/单测/Lint 均成功；#31 另产出 Debug APK 与 Lint 报告 artifact。
- 一起听的 WS、房间令牌、重连、心跳和远端同步现在由 `ListenSessionController` 持有；页面切换不会销毁会话，断线恢复会重新申请服务端内存令牌，避免复用被另一台设备撤销的旧令牌。播放命令仍只走服务端权威响应，远端快照不会反向发命令。
- 发现页“ 一起看 ”已接入同一情侣房间的 `kind=watch` 权威状态：HTTPS 链接校验、成员控制、时间轴心跳、WS 推送、直接媒体本机播放与网页外部打开均有明确边界；后台列表只显示标题/时间轴并脱敏观看链接。
- 真实隔离服务审计：`node admin/scripts/mobile-audit.mjs http://127.0.0.1:7793` 覆盖 412×915、360×640、390×844、768×1024；登录、首登改密、全部菜单及 Live2D 权限页通过，无横向溢出、白屏、控制台错误或失败请求。服务端临时数据库与上传目录已停止并与工作树隔离。
- 实际通过：`server` 的 `gofmt`、`go vet ./...`、`go test -timeout 400s ./...`；`admin` 的 `npm run build`、`npm run lint`；设计 token JSON 与移动审计脚本语法检查。未运行 Android APK 安装／启动，也不在本机执行 Android Gradle；Android 编译、单测、Lint 与 APK artifact 以 GitHub Actions 质量门禁记录为准，Cubism 真机行为仍未验收。

## Draft1.2 追加检查

- 新增REFINEMENTS-1.2.md：石墨暗色参数；APP我的模型/资源库；Android SAF文件选择、取消、流读取与私有staging；后台超管资源库、文件选择/手动上传、静态校验、授权/真机验收后发布和下架；拟新增API/存储/迁移；播放器上下方向、时长、状态/返回/权限；图标与无障碍映射。
- 同步DESIGN/IMPLEMENTATION/LIVE2D补充/AI-HANDOFF/tokens，清除旧蓝灰色值和“不做后台管理/不新增服务端”的对应范围冲突。新增功能仍只是计划，不是既有接口。
- HTML更新石墨暗色、原创SVG图标、mini展开和收起的方向动画，保留底层DOM并阻止底层交互；资料面板加入1.2入口。系统文件选择器/真实资源库/Live2D不在HTML里伪造成功。
- JSON可解析，HTML内联JS语法检查通过，Markdown本地链接目标存在；检索旧暗色与冲突范围无匹配。
- 浏览器实际刷新到1.2，查看暗色截图；mini暂停只改变播放状态，点主体打开完整页，上一首点击反馈不破坏图标，收起回主页且暂停状态保留；检查资料面板含最新规范。动效方向在源码设置，交互起止态已验证，未量测网页或Android帧时序。
- 已有第一批业务代码：Android 材质/全局播放器/SAF 本机导入与已发布资源库下载，后台拟态壳/资源库页面，服务端资源表、发布下载与 ZIP/manifest 安全校验。此前的构建与阻塞记录保留在本文件；当前状态以上方“业务实现追加检查”为准。
- 第二批覆盖后再次执行 `admin` 的 `npm run build`（vue-tsc + Vite）和 `npm run lint`，均通过；真实浏览器重新打开后台登录页，验证浅色→石墨暗色切换及动画结束后的稳定状态。Android 本轮新增的是表面封装与卡片迁移，仍受同一 JDK/Gradle loopback 环境阻塞，未把源码静态检查冒充编译通过。
- 又把播放器覆盖层从 Android `AnimatedContent` 的目标页 lambda 外提到同一 App shell；这是为避免转场期间旧/新页各创建一个 mini 播放器的结构性修复，仍需 Android 编译和真机转场验证。

## Draft1.1 追加检查

- 新增LIVE2D-AND-CONTROLS.md，核对官方Java SDK/minimum样例、Core AAR来源、moc3/资源包、模型完整性校验与Expandable Applications发行规则；映射到NowScreen/AppearanceScreen/导航及拟新增导入/渲染组件。
- 更新DESIGN/IMPLEMENTATION/AI-HANDOFF与tokens，明确用户新增Live2D范围及许可门槛。
- 预览移除原生状态select，改成保留radio语义的拟态分段；文档入口改按钮和同材质dialog；主页新增明确标记的静态模型画布线框。
- JSON/JS语法/Markdown相对链接检查通过。浏览器实际验证Draft1.1刷新、失败radio选择、重试恢复、资料dialog打开/关闭与主页陪伴角位置，查看截图。
- 未下载Core、未取得模型、未运行Cubism，不以线框代替真实Live2D成功证据。未穷举新控件所有视口/键盘/暗色，生产仍须按补充方案验收。

## Draft1.0 原始检查

已完成：

- 对照实际代码基线bb6c16b检查主题、导航、按钮、列表、音乐与一起听生命周期、后台页面与服务端权限。
- 浏览器观察Neumorphism.io（含Pressed切换）、Themesberg Neumorphism UI、Creative Tim Soft UI Dashboard；查阅Android、MDN、Element Plus、miuix、W3C第一方资料。
- 设计主稿覆盖APP A01～A12与后台B01～B17；工程计划区分既有API与拟新增组件。
- JSON可解析、HTML内联脚本语法通过、Markdown相对文件链接目标存在、git diff --check通过。
- 浏览器成功加载样板，依次切换六页并核对标题；验证失败态→重试恢复、明暗切换；已查看浅色主页与暗色日志页截图；检查时未捕获页面error日志。

未完成／不属于本次交付：

- 这段是 Draft1.0 当时的历史记录；当前工作树已经有跨端业务实现，不能再按“未实施生产UI”解读。当前真实命令与范围见本文顶部和 IMPLEMENTATION.md。
- HTML是方向示意，非全部页面像素级设计，控件含明确标注的静态演示动作；未穷举所有状态、移动视口、字体缩放或无障碍检查。
- 未验证一加15真实绘制性能、后台播放、触感、系统权限流程。
- 未确认品牌蓝红白色小字的对比度例外解决方案；不得因此宣称全面AA。

接手AI必须执行IMPLEMENTATION.md中的后续检查，不能把这里的文档／示意验证冒充业务验收。
