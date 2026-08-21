# ClaudeScheme_0811 · 林曦日记「第二轮」改造方案与提问

> 生成日期：2026-08-11（第二轮）
> 依据：`TodoList_0811.md` + 对 `android/`、`server/`、`.github/`、参考项目 `C:\Lx\KernelSU-main`、以及 hl6 编排理念的源码审阅
> 定位：第一轮方案已实现并合入 `origin/main`（`e34b3fc`）。**本轮 = 真机体验后的缺陷修复 + KernelSU 高保真对齐 + CI/部署工作流重构**。
> 流程：请在「第五部分 · 提问」每个 `选择:` 后填写，其余原样回填到 `ClaudeScheme_0811_Answer.md`；`我的意见` 是我的默认推荐，认可即填该项。
> 分支：本轮直接在 `main` 上工作（你已确认）。⚠️ 推送 `main` 会触发部署逻辑，我在**每次 push 前都会先向你确认**。
> 备注：第一轮方案已另存为 `ClaudeScheme_0811_Round1.md`（未跟踪，供留档）。

---

## 第一部分 · 需求逐条理解（请核对，若有偏差请在答复里纠正）

### A. 客户端（android，Kotlin+Compose，miuix 0.9.3，包名 com.linxi.diary）
1. **登录/注册页层级**：注册应是登录的**子页**；现在在注册页按系统返回键会**直接回桌面**，应改为**回到登录页**。
2. **绑定页「调试环境跳过」**：点击后**不再弹「知情同意」**了（以前会弹）。需修复；弹窗是**页内 Dialog**（非独立页），确认后进入主页面。
3. **待办页**：
   - (1) 搜索逻辑疑似有问题，审查后修复。
   - (2) 待办卡片**高保真仿 KernelSU 模块卡**：模块名→**待办事件**、模块介绍→**详情**、作者→**提出待办事项者**，并**新增「待办事项被提醒者」**；**保留开关控件（作为开关）**，并**新增删除键**（与 KernelSU 一致）。
   - (3) **搜索框固定**，仅待办卡片列表可滑动；下拉刷新的**「下拉刷新/松开刷新/正在刷新/刷新成功」四态动画直接照抄 KernelSU**。
   - (4) **添加按钮（FAB）直接照抄 KernelSU**。
4. **「我的」页**：
   - (1) 日志相关**只保留「发送日志」**，其余（崩溃日志/导出诊断日志/清空崩溃日志）全删。
   - (2) 「发送日志」触发的二级弹窗与内容**直接照抄 KernelSU**。
   - (3) 「关于」和「发送日志」两个卡片**连在一起**（同 KernelSU）。
   - (4) 关于页的软件图标**直接使用软件自身图标**（不要用别的）。

### B. 服务端 / CI（Go 后端 + art-design-pro 后台前端）
1. **用三个工作流替换旧工作流**（旧的不再需要）：
   - ① **构建服务端**：Docker 容器 → 推送 `ghcr.io` 供拉取；「前后端分离」部署方式让使用者自行打包。
   - ② **构建安卓客户端**：可配置 **服务端地址、通讯密钥、构建类型(Debug/Release)、版本号**。
   - ③ **发行版**：包含以上全部信息并发布版本；**一般由 AI 收到命令才触发**，因为要写**应用介绍（首次）/更新日志（后续每版）**并写入规范文档。
2. **学 hl6**（github.com/HanLuLL/hl6）：容器编排**仅 app + 数据库，去掉 nginx**。
3. **README 高度完善**；**服务端 UI / 后台 / APP 三端内容互相关联**，面向生产环境。

---

## 第二部分 · 现状调查与问题诱因（关键事实，均带代码定位）

### 客户端

**Bug①「注册页返回键回桌面」根因**
- 导航是**手写状态机**（单个 `screen` 变量 + `AnimatedContent`，**无回退栈**），`Screen` 枚举 `Login/Register/Bind/Main/...`（`LinxiApp.kt:302`）；`Register` 与 `Login`、`Bind` 是**平级顶层状态**。
- 登录→注册：`LinxiApp.kt:134` `onNavigateRegister={ screen=Register }`；注册页 `onBack={ screen=Login }`（`LinxiApp.kt:139`）逻辑本身正确，但**只被页面内「已有账号?返回登录」文字调用**（`RegisterScreen.kt:174-184`），**从未绑定系统返回键**。
- `RegisterScreen` **无 `BackHandler`**；`MainActivity` 未重写返回分发。→ 系统返回键落到 `ComponentActivity` 默认 dispatcher → `finish()` → 回桌面。
- **同样问题存在于 `Bind` 页**（返回键也会退出 App）。修复只需在这两页补 `BackHandler{ onBack() }`（仓内既有写法 `AppearanceScreen.kt:24`）。

**Bug②「调试跳过不弹知情同意」根因（回归）**
- 知情同意已是页内 Dialog：`PrivacyConsentDialog`（`PrivacyConsentScreen.kt:43-49`，miuix `OverlayDialog`）。**唯一实例化点在 `MainTabs` 内**（`LinxiApp.kt:294-299`），`show = forcedConsent || reviewConsent`，**仅 `Screen.Main` 渲染**。
- `forcedConsent = remember{ mutableStateOf(pairId>0 && !demoMode && !privacyConsented) }`（`LinxiApp.kt:199-201`）——**含 `!demoMode` 且 `remember` 只求值一次（非响应式）**。
- 「跳过(开发调试)」按钮（`BindScreen.kt:147-168`）把 `demoMode=true, pairId=1, privacyConsented=false` 后 `onBound()` → 直接进 `Main`（`LinxiApp.kt:141-144`）。因 `demoMode=true` 使 `forcedConsent` 恒 `false` → **Dialog 永不弹**。
- 回归点：git `602f1a3` 重构成 Dialog 时新增的 `!demoMode` 门槛**误伤了 demo/调试路径**（此前 `onBound→Screen.Consent` 与 demo 无关）。真实绑定路径（`demoMode=false`）不受影响，故你观察到「只有调试跳过坏了」。
- 附带事实：`SharingRuntimePolicy`（`isSharingActive` 含 `!demoMode`）→ **demo 下即使同意也不会真共享**，所以让 demo 也弹同意在数据侧安全。

**待办页现状**
- 搜索（`TodoScreen.kt:114-117`）：只匹配 `title`+`note`、忽略大小写、**无防抖**、**query 未 trim**；数据源只 `todos(status=0)`（**只有未完成**，已完成/删除搜不到）。搜索框 `SearchRow`（`:185-223`，miuix `TextField`），聚焦/有输入时渐显「取消」。
- 卡片 `TodoCard`（`:225-270`）：仅显示 标题 / 日程行 / 右侧「完成」IconButton / 可展开 note；**从不显示提出者、被提醒者**。
- **数据模型两个关键字段端到端都已存在**：`TodoItem`（`Models.kt:39-67`）↔ `Todo`（`models.go:79-92`）均含 `creatorId/creator_id`（**提出者**）与 `assigneeId/assignee_id`（**被提醒者**），另有 `repeatType`(0仅一次/1每天/2每周)、`weekdays`(位掩码)、`remindType`(0普通/1强)、`status`(0待办/1完成/2删除)。**UI 完全没用这两个字段**。
- 服务端指派逻辑（`handlers.go:442-446`）：被指派者默认= `pair.UserBID`，仅当 `assignee_id==uid` 时才改为本人 → 情侣仅 2 人，**被提醒者只能「本人 / 伴侣」二选一**。删除接口 `DELETE /todos/{id}` 已存在（`deleteTodo` 已定义、**当前 UI 未调用**）。
- FAB：**全局** miuix `FloatingActionButton`+`Icons.Rounded.Add`（`LinxiApp.kt:279-289`），经 `MainFabState` 解耦，**已有滚动渐隐**（现用 `fadeIn+scaleIn/fadeOut+scaleOut`）。
- **下拉刷新：不存在**（全仓无 `PullToRefresh/SwipeRefresh`）；刷新只由程序内 `refresh()` 触发。

**「我的」/设置页现状**（`SettingsScreen.kt`）
- 骨架 `KernelScreen("我的")` + 每分组一张 miuix `Card` + `ArrowPreference/SwitchPreference`。**日志四项都在**：崩溃日志 / 导出诊断日志 / 发送日志 / 清空崩溃日志（分组4 `:251-288`）；「关于」单独一张 Card（分组5 `:291-300`）。
- 「发送日志」（`:303-321`）：实为 miuix `OverlayDialog`（标题「发送日志」）+ 两行 `LogSheetRow`（保存日志=`CreateDocument` 存 .zip / 发送日志=系统分享），**无图标、无取消按钮**。
- 关于页 `AboutScreen.kt`：头部是**圆形 BrandBlue 底 + `Favorite` 矢量图标**（**非应用图标**）+「林曦日记」+ 版本；Card1=检查更新/开源仓库(`github.com/Lxii-Build/LxDay`)/官网(`love.lxii.cc`)；Card2=退出登录(红字)。

**KernelSU 参考要点（均为 miuix 皮肤，本项目同基座可高保真复刻）**
- 模块卡 `ModuleMiuix.kt:808-1110`：**独立卡**(`padding(bottom=12dp)`)；顶部 `Row`[左 Column(名 17sp/550、版本、作者) + 右 `Switch`]；描述 `animateContentSize` 折叠 4 行；`HorizontalDivider(0.5dp)`；动作行 `Row`[左动作胶囊 … `Spacer(weight)` … 更新按钮 … **删除 `IconButton`（`secondaryContainer` 底、`Delete` 图标，待删态切 `Undo`）**]。字符串：`module_author`=Author/作者、`uninstall`=Uninstall、`undo`=Undo。
- 下拉刷新：**直接用 miuix 库自带** `top.yukonga.miuix.kmp.basic.PullToRefresh`+`rememberPullToRefreshState()`（`ModuleMiuix.kt:523-545`），传入 `isRefreshing/onRefresh/refreshTexts`。四态字符串：`Pull down to refresh/下拉刷新`、`Release to refresh/松开刷新`、`Refreshing…/正在刷新…`、`Refreshed successfully/刷新成功`。**本项目 miuix 也是 0.9.3，可零成本启用**。
- 发送日志 `SendLogDialog.kt:42-155`：miuix `OverlayDialog`= 标题 + `ArrowPreference`(保存日志/`Save` 图标) + `ArrowPreference`(发送日志/`Share` 图标) + `TextButton`(取消)。保存=`CreateDocument("application/gzip")` 名 `KernelSU_bugreport_<time>.tar.gz`、发送=`FileProvider`+`ACTION_SEND`。
- 关于页 `AboutMiuix.kt`：`Image(R.drawable.ic_launcher_foreground)`+`ColorFilter.tint(onBackground)` 100dp + 名 35sp + 版本 14sp + 一张 Card 内 `forEach` 链接 `ArrowPreference`。
- **相连卡**：把多个 preference 行放进**同一张 miuix `Card`** 即视觉相连；组间用不同 `Card`+`padding(top=12dp)`。（模块列表是「独立卡」，设置/关于是「相连卡」。）
- FAB `ModuleMiuix.kt:402-468`：miuix `FloatingActionButton`+`Icons.Rounded.Add`(40dp、tint `onPrimary`)，靠 `nestedScroll` 用 `offset`+`animateDpAsState(350ms)` **下滑向下位移隐藏、上滑恢复**。

### 服务端 / CI
- **现有工作流只有 2 个**：`ci.yml`（push/PR 到 main、**无路径过滤**；三 job：go vet/test、admin `npm build`、android `assembleDebug+assembleRelease` 并上传 APK）；`deploy.yml`（push 到 main 且 `server/**` → **裸二进制 go build + scp + `systemctl restart`**，**非 Docker**）。**无 ghcr 推送、无可配置安卓构建、无发行版工作流**。
- **compose 现含 nginx**（`docker-compose.yml`，4 服务）：`mysql` / `redis` / `server`(仅 `expose:8080` 不发布端口) / `web`=**nginx**(`ports:80:80`，唯一对外入口，`admin/Dockerfile` 是 `FROM nginx:alpine` 托管前端+反代)。
- **Go 当前不自托管静态**（无 `Static/FileServer/embed`）：admin 由 nginx `location /` 提供；`/uploads` 由 nginx `alias` 提供；**WS 升级是 Go 自己做**（`hub.go`），但 **TLS 由 nginx 终结**（Go 用 `r.Run(":8080")` 明文）。→ **要去 nginx，需给 Go 新增 admin 静态托管 + `/uploads` 静态 + TLS 方案**。`Redis` 是**强依赖**（`main.go` `initStore` Ping 失败即 Fatal），最小编排 = `server+mysql+redis`。
- **安卓可配置构建当前不成立**：`ApiClient.kt:25 BASE` 与 `StatusSyncManager.kt:41 WS_URL` **硬编码**为 `api.linxi.app`；`versionCode/versionName` 硬编码；release **用 debug 签名**。→ 需先把地址/版本改为 `buildConfigField`/gradle 属性注入。
- **「通讯密钥」当前不存在**：全仓无 client-server 共享密钥/HMAC/api-key，仅 JWT。→ 属需在服务端+客户端新增的概念。
- README/文档过时（README 仍称安卓 CI 只做「XML 校验」，与真编译不符）。

---

## 第三部分 · 总体方案（逐条落地）

### 客户端
- **需求1（登录/注册返回）**：给 `RegisterScreen` 补 `BackHandler{ onBack() }`（回登录）；转场按父子关系（登录→注册用滑入/滑出）。**一并给 `BindScreen` 补 `BackHandler`**（见 Q1 决定回到哪）。
- **需求2（调试跳过弹同意）**：把 `forcedConsent` 判定**去掉 `!demoMode`** 并改为**响应式**（`derivedStateOf` 或直接读 `UserPrefs` 状态），使「已进主页且 `pairId>0 && !privacyConsented`」一律补弹（含 demo 冷启动，见 Q2）。同意/拒绝后留在主页；demo 拒绝=不开共享。
- **需求3(1)（搜索）**：`trim` + 150ms 防抖 + 匹配范围按 Q5 决定（默认扩到 事件/详情/提出者/被提醒者名字）；空态/无结果态文案保留。
- **需求3(2)（待办卡仿模块卡）**：重构 `TodoCard` 为 KernelSU 模块卡布局——顶部 `Row`[左 Column(事件[粗]、`提出者:X`、`提醒:Y`) + 右 **开关**(语义见 Q3)]；`animateContentSize` 展开「详情」；`HorizontalDivider`；动作行含 **删除 `IconButton`**（`Delete` 图标、复用 `DELETE /todos/{id}`）。提出者/被提醒者名字取「本人 / 伴侣」。
- **需求3(3)（固定搜索+下拉刷新）**：搜索框固定在顶部（不进 `LazyColumn`），下方卡片列表包进 **miuix `PullToRefresh`**，`refreshTexts` 用四态中文（下拉刷新/松开刷新/正在刷新…/刷新成功），`onRefresh` 走 `refresh()`。
- **需求3(4)（FAB 照抄）**：改为 KernelSU 式 `offset`+`animateDpAsState(350ms)` 下滑位移隐藏/上滑恢复（见 Q7）。
- **需求4(1)（日志精简）**：设置页删除 崩溃日志/导出诊断日志/清空崩溃日志三项，**仅留「发送日志」**；底层 `CrashHandler/DiagnosticExporter` 保留（发送日志诊断包仍需崩溃记录）。
- **需求4(2)（发送日志照抄）**：把 `OverlayDialog` 内容对齐 KernelSU——标题 +「保存日志」(`Save` 图标)/「发送日志」(`Share` 图标) 两个 `ArrowPreference` + 取消按钮；保存格式见 Q8。
- **需求4(3)（关于+发送日志相连）**：把「发送日志」与「关于」两行放进**同一张 miuix `Card`**（相连卡），置于设置页底部。
- **需求4(4)（关于页图标）**：关于页头像 `Image` 改用**应用图标**（`R.mipmap.ic_launcher`，本项目已是 icon.jpg 位图，见 Q9），去掉 `Favorite` 矢量。

### 服务端 / CI
- **去 nginx（学 hl6）**：让 **Go 后端 `embed` admin 构建产物**并自托管：新增 admin 静态路由 + SPA `NoRoute` 回退 + `/uploads` 静态路由；compose 精简为 `server`(发布 8080) + `mysql` + `redis`（Redis 强依赖保留）。TLS 见 Q11。
- **工作流①（构建服务端镜像）**：`docker build ./server` → 推 `ghcr.io/<owner>/lxday-server:<tag>`（`server/Dockerfile` 已就绪）；admin 静态随 Go embed 一并进镜像 → **单一 app 镜像**（对应「app+db」）。「前后端分离」部署方式在文档中说明使用者自行 `npm build`。
- **工作流②（构建安卓）**：先改 `build.gradle.kts` 用 gradle 属性/env → `buildConfigField`(BASE_URL、WS_URL、COMM_KEY、versionName、versionCode)；工作流 `workflow_dispatch` 输入 服务端地址/通讯密钥/构建类型/版本号，产出对应 APK。通讯密钥机制见 Q13。
- **工作流③（发行版）**：`workflow_dispatch`（AI 命令触发），组合镜像 tag + 安卓 APK，创建 GitHub Release；首发写「应用介绍」，此后每版写「更新日志」，落库到规范文档（`CHANGELOG.md` + 首发 `docs/APP_INTRO.md`）。
- **旧工作流处置**：见 Q14（默认：删 `deploy.yml`，测试并入新构建流）。**部署/更新方式**见 Q15（默认：出镜像，服务器 `docker compose pull` 手动更新，不再自动部署生产）。
- **README/文档**：重写根 README + `docs/DEPLOYMENT.md`，覆盖 三种部署（Compose 为主/单容器/前后端分离）+ 三个工作流用法 + 通讯密钥 + 初始账号；补 `CHANGELOG.md`。
- **三端关联**：待办的 提出者/被提醒者、资料字段等本就共用同一 DB，天然关联；后台是否补展示见 Q18。

---

## 第四部分 · 风险与开发中可能遇到的问题（提前说好）

1. **无本地 Android SDK，安卓只能靠 CI 验证**：本轮改动多为 UI（返回键、待办卡、下拉刷新、FAB、设置页、关于页），需严格对照现有 miuix 写法逐文件自检；miuix 0.9.3 的 `PullToRefresh` 需确认 API 签名（`refreshTexts` 顺序/参数名），若与 KernelSU 所用版本有出入，红了按报错微调。
2. **`forcedConsent` 改响应式**：要避免「同意后又被重复弹出」——需以持久化的 `privacyConsented` 为准，并保证 `reviewConsent`（我的页只读态）与强制态互不打架。
3. **待办卡「开关」语义未定（Q3）**：不同语义会改动数据流（提醒开关需新增字段/复用 remindType；完成开关复用 status）。**这是本轮最需要你拍板的一项**，定错会返工。
4. **被提醒者可选（Q4）**：若允许添加时切换被提醒者，需调整服务端 `assignee` 逻辑（现固定「本人或 UserB」）。
5. **去 nginx + Go embed 静态**：`embed` 要求构建时 admin `dist` 已存在于镜像构建上下文；多阶段 Dockerfile 需先 `npm build` 再拷进 Go 构建阶段。SPA 回退不能吞掉 `/api`、`/ws`、`/uploads` 前缀。TLS 若靠外部反代，容器本身只跑明文，需在文档写清（否则用户直接暴露 8080 明文有风险）。
6. **通讯密钥（Q13）**：APK 内置密钥可被逆向提取，只能提高门槛、不能视作强安全；服务端中间件要放行健康检查/静态资源，避免把 admin/uploads 也挡住。
7. **删除自动部署的影响（Q15）**：删 `deploy.yml` 后 push main 不再自动上生产，避免误触；但也意味着更新需手动 `docker compose pull`，需在文档强调。
8. **版本注入改造**：`versionName/Code` 从硬编码改注入后，本地 `assembleDebug` 缺参时要有默认值兜底，避免本地/CI 构建失败。
9. **安全提醒**：服务端对外暴露 8080（去 nginx 后）应默认要求置于 TLS 反代之后；`CheckOrigin` 现恒 true（`hub.go`），生产建议按域名校验（可另评估）。

**验证**：服务端 `go vet/build/test`、后台 `npm build` 本地跑绿；安卓靠 CI（新「构建安卓」工作流）验证；compose 改动本地 `docker compose config` 校验编排。

---

## 第五部分 · 提问（请在每个 `选择:` 后填写，其余原样回填到 ClaudeScheme_0811_Answer.md）

### 客户端

问题:注册页返回键修复范围，以及 Bind 页/根页返回策略？
详情:注册按返回应回登录（已定位，补 BackHandler 即可）。Bind 页当前返回键也会回桌面；Login 作为根状态返回=退出 App。
选项:A 只需求要求的：注册→回登录；同时把 Bind→返回回到登录页；Login 根页直接退出 App（不加确认）
B 在 A 基础上，给根页加「再按一次返回退出」防误触
C 仅修注册页，Bind 页不动
选择:
我的意见:A（达标且最小改动；若你常误触退出可选 B）。

问题:知情同意的补弹范围？
详情:根因是 `forcedConsent` 含 `!demoMode` 且非响应式。除修调试跳过外，是否让所有「已进主页但未同意」（含 demo 冷启动）都补弹。
选项:A 改响应式并覆盖 demo：凡 `pairId>0 && !privacyConsented` 进主页即强制弹，同意/拒绝后留在主页（demo 拒绝=不开共享）
B 仅修「调试跳过」当次弹一次，不改 demo 冷启动行为
选择:
我的意见:A（彻底、符合「未同意就该弹」，且 demo 下同意也不真共享，安全）。

问题:待办卡「保留的开关」控什么？（★本轮最关键，定错会返工）
详情:KernelSU 模块卡的 Switch=启用/禁用模块。待办卡现无开关、只有「完成」按钮。你要「保留开关作为开关」+新增删除键。
选项:A 开关=该待办「提醒开/关」（关=保留待办但不提醒）；「完成」保留为卡上独立动作；删除键=删除
B 开关=「完成/未完成」状态（勾起=已完成）；删除键=删除；不再单独放完成按钮
C 开关=「启用/禁用整条待办」（禁用=灰显不提醒不计入）；完成另做；删除键=删除
选择:
我的意见:A（与 KernelSU「开关管启用、删除键管删除」最神似，且提醒开关对情侣待办最实用）。

问题:被提醒者是否可在「添加待办」时选择？
详情:卡片要显示「提出者/被提醒者」。情侣仅 2 人，服务端指派限「本人或伴侣」。添加时现固定「给对方」。
选项:A 添加时可切换被提醒者（我自己 / 对方），默认对方；卡片显示两者名字
B 维持固定「给对方」，卡片只做展示不提供选择
选择:
我的意见:A（既然卡片要展示被提醒者，添加时给选择更自洽；改动仅限指派逻辑放开二选一）。

问题:搜索逻辑要修哪些？你实际遇到的问题是什么？
详情:现状=只匹配 事件+详情、无防抖、未去首尾空格、且只搜「未完成」待办。「可能存在问题」较模糊，希望你补充实际现象。
选项:A 修我发现的全部：trim 空格 + 加防抖 + 匹配范围扩到 事件/详情/提出者/被提醒者名字（仍只搜未完成）
B 只做基础修复（trim + 防抖），匹配仍限 事件+详情
C 我来补充具体现象：____（例如「搜不到某类待办」「输入卡顿」「大小写」等）
选择:
我的意见:A；若你有具体复现现象请在 C 补充，我据此精准修。

问题:下拉刷新采用 miuix 自带 PullToRefresh + 中文四态？
详情:miuix 0.9.3 自带与 KernelSU 同款 `PullToRefresh`，本项目可零成本启用；搜索框固定顶部、仅卡片列表可下拉。
选项:A 采用，四态用中文「下拉刷新/松开刷新/正在刷新…/刷新成功」
B 采用，四态用英文（同 KernelSU 英文串）
C 不要下拉刷新
选择:
我的意见:A（照抄 KernelSU 观感 + 本 App 用中文）。

问题:添加按钮(FAB)动画照抄 KernelSU 的位移式？
详情:KernelSU 是「下滑向下位移隐藏、上滑恢复」(offset+350ms)；本项目现为淡入缩放渐隐。
选项:A 改成 KernelSU 位移式（你要求「直接照抄」）
B 保留现有 fade+scale
选择:
我的意见:A。

问题:发送日志弹窗——保存文件格式？
详情:样式统一照抄 KernelSU（标题+保存/发送两项带图标+取消）。保存格式：KernelSU 用 `.tar.gz`，本项目现有实现产出 `.zip`。
选项:A 样式照抄 KernelSU，保存沿用本项目现有 `.zip`（改动小、更通用）
B 样式照抄，且保存也改成 `.tar.gz`（与 KernelSU 完全一致）
选择:
我的意见:A（观感一致即可，保存格式沿用现成 zip 省改动）。

问题:关于页图标用哪个？
详情:替换掉现在的 `Favorite` 矢量，改用软件自身图标。本项目图标已是 icon.jpg 位图（mipmap）。
选项:A 用启动图标 `R.mipmap.ic_launcher`（带背景，最接近桌面所见）
B 用前景层（无背景，可叠圆形底）
选择:
我的意见:A（直接用桌面同款图标，符合「直接使用软件图标」）。

### 服务端 / CI

问题:去 nginx 的落地方式？
详情:学 hl6「仅 app+db」。Go 现不自托管静态，admin 与 /uploads 靠 nginx。
选项:A 让 Go 后端 `embed` admin 产物并自托管静态 + SPA 回退 + `/uploads` 静态；compose = `server`(暴露8080)+`mysql`+`redis`，去掉 nginx 容器
B 保留 nginx（维持现状，不改）
C 不 embed，用卷把 admin dist 挂给 Go 托管
选择:
我的意见:A（最贴合 hl6 与「去 nginx」；Redis 是强依赖需保留，若要连 Redis 也去掉则需改服务端代码，不建议）。

问题:去 nginx 后 HTTPS/WSS 的 TLS 在哪终结？
详情:去掉 nginx 后容器内 Go 默认跑明文 8080，需明确证书方案（hl6 类做法通常靠前置反代）。
选项:A 文档指导用户在宿主/云上放一层反代（推荐 Caddy 自动证书，或 Nginx/云 LB）做 TLS，容器仍只 app+db
B Go 直接 `RunTLS` 读挂载证书，彻底不需外部反代
C compose 额外加一个 Caddy 容器做自动 HTTPS（多一个容器，但一键自动证书）
选择:
我的意见:A（生产最常见、容器最简；我会在文档附 Caddy 一键示例。若你想「compose 起来就有 HTTPS」则选 C）。

问题:安卓可配置构建——注入哪些、WS 地址怎么定？
详情:需把地址/版本改为构建期注入。WS 可单独传或由 HTTP 地址推导。
选项:A 注入 BASE_URL + 版本号；WS 由 BASE 自动推导（https→wss 同 host），如需再单独覆盖
B BASE_URL 与 WS_URL 都作为独立必填输入
选择:
我的意见:A（少一个易错项；仍保留覆盖能力）。

问题:「通讯密钥」用什么机制？
详情:现无 client-server 共享密钥。目的应是「只让官方客户端调接口」。
选项:A 静态 App Key：客户端所有请求带固定 Header（如 `X-App-Key`），服务端中间件校验（构建期注入 APK + 服务端配置）
B HMAC 请求签名（每请求用密钥对 时间戳+body 签名，防篡改/重放，复杂度高）
C 不做通讯密钥（仅 JWT）
选择:
我的意见:A（简单有效，挡掉非官方客户端；注意 APK 内密钥可被逆向，属提高门槛而非绝对安全）。

问题:旧工作流 `ci.yml` / `deploy.yml` 如何处置？
详情:你说「原来的工作流不需要了」。测试(go/android 单测)本身有价值。
选项:A 删 `deploy.yml`；`ci.yml` 精简保留为「纯测试验证」；另加三个新工作流
B 完全删除 `ci.yml`+`deploy.yml`，测试并入新构建流（构建镜像时跑 go test、构建安卓时跑单测）
选择:
我的意见:B（最贴合「原来的不需要了」，且顺带去掉 push 即自动部署生产的风险）。

问题:生产的更新/部署方式？
详情:改 ghcr 镜像后，服务器如何拿到新版本。
选项:A 不自动部署：镜像推 ghcr，服务器手动 `docker compose pull && up -d`（文档指导）
B 构建/发行后自动 SSH 到服务器 pull 并重启（需保留部署 Secrets）
选择:
我的意见:A（生产更可控、避免误部署；与「推送 main 前先确认」一致）。

问题:发行版工作流细节确认？
详情:③ 发行版由命令触发，产出 GitHub Release，首发写应用介绍、后续写更新日志。
选项:A SemVer(`vX.Y.Z`)；Release 附 Release APK + 标注 ghcr 镜像 tag；更新日志入 `CHANGELOG.md`、首发应用介绍入 `docs/APP_INTRO.md` 并写进 Release 正文
B 我另有版本号/产物/文档规范：____
选择:
我的意见:A。

问题:ghcr 镜像命名与可见性？
详情:小范围免费对外。
选项:A `ghcr.io/lxii-build/lxday-server`，**公开**（免登录直接 pull，部署最省事）
B 私有（pull 需 token）
选择:
我的意见:A（公开镜像便于部署；不含密钥，安全）。

问题:本轮是否铺开「后台」改动来体现三端关联？
详情:待办 提出者/被提醒者、资料字段等已共用同一 DB，天然关联。
选项:A 本轮聚焦「客户端修复 + 工作流重构」，后台不铺开（已有用户/绑定/版本管理够用）
B 本轮同时扩后台（展示待办被提醒者、资料新字段等）
选择:
我的意见:A（先把本轮真机缺陷与 CI 闭环做扎实；后台若缺具体展示，下一轮单列）。

---

### 附：待你直接补充的信息（非选择题）
- 通讯密钥（若 Q13 选 A）：由我随机生成并写入构建工作流 Secret / 文档回执？还是你指定固定值？（默认：我生成并在文档回执告知）
- ghcr owner 组织名确认（默认 `Lxii-Build` → 镜像 `ghcr.io/lxii-build/lxday-server`）。
- 首发「应用介绍」文案：你自拟 / 还是我按 App 功能起草初稿供你改？（默认：我起草初稿）
- 生产是否已有域名指向、是否用 Caddy（影响 Q11 文档示例）。

> 以上确认后，我据回复更新 TodoList 并分阶段施工（客户端修复 → 待办卡/下拉刷新/FAB → 我的页 → 去 nginx+Go 静态 → 三工作流 → README/文档），中途不停顿，最终以 CI/本地构建全绿为完成标准。
