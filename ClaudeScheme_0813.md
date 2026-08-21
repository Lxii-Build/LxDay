# ClaudeScheme_0813 · 林曦日记「第三轮」改造方案与提问

> 生成日期：2026-08-13（第三轮）
> 依据：`TodoList_0813.md` 逐条需求 + 对 `android/`、`server/`、`admin/` 三端源码审阅 + 参考 `C:\Lx\hl6`（部署/DB 编排）、`C:\Lx\KernelSU-main`（仅 UI 皮肤参考）、`https://killaislop.com`（反 AI-slop 设计原则）。
> 方法：本轮采用 superpowers（系统化调查/根因定位）+ grilling（对每条需求逐字追问、暴露歧义与返工点）两套方法；调查由 6 个并行子代理完成，关键结论已本人复核源码（file:line 均已核对）。
> 流程：请在「第六部分 · 提问」每个 `选择:` 后填写，其余原样回填到 `ClaudeScheme_0813_Answer.md`；`我的意见` 是我的默认推荐，认可即填该项。
> 分支/CI：直接在 `main` 上工作；push `server/**|admin/**|Dockerfile` 会触发 build-server 构建并推 ghcr 镜像（不再自动上生产）。**每次 push 前仍先与你确认，绝不擅自 force-push。** 本机无 go/java/Android SDK/docker，仅 admin 前端可本地 `npm build` 验证，其余靠 CI。

---

## 第一部分 · 需求逐条理解（请核对，若有偏差请在答复里纠正）

### A. 客户端（android，Kotlin+Compose，miuix 0.9.3，主色蓝 #277AF7）

- **C1 绑定回调**：邀请方 A 生成邀请码、被邀请方 B 输入码绑定后，B 能进主界面，但 **A 没被"发包"推进主界面**（A 卡在展示邀请码/等待页）。需让 A 在对方绑定后自动进入主界面。
- **C2 定期同步**：客户端应能"隔一会就给服务端发一个请求"来同步信息（伴侣资料/绑定态/状态等）。现在缺少固定周期的主动同步。
- **C3 日志规范 + 删调试模式**：① 可以删除"调试模式"了；② 日志要按"国际日志标准"来（你觉得现有日志"一点也不标准"）。
- **C4 个人资料精简**：保留"个人资料"，其余"三个（名称/头像/性别 之类）"可删掉——即消除资料相关的重复入口/字段（具体删哪三个见提问，易返工）。
- **C5 待办结构图**：待办相关问题较大，需要一份**结构图**（我已在第二部分给出端到端结构图）；并据此定位/修复。
- **C6 错误码友好化**：客户端不要再直接显示 403/400 等**状态码**，要显示"为什么错了"的中文原因。
- **C7 登录/注册美化**：注册与登录页"太难看"，用 killaislop（反 AI-slop 设计）原则美化。

### B. 服务端（Go 后端 + art-design-pro 后台前端 + Docker 编排）

- **S1 后台清理 + 设置规范**：删除后台前端里"多余的、与系统无关"的内容；所有设置项都要"写标准"（填了就生效，不留占位坑）；全部修好。
- **S2 DB 免手动导入 + 端口一致**：① 为什么还要人工手动导入 SQL？学 hl6 做到免导入；② 容器内外端口保持一致（现在内 8080 / 外 7740 不一致）。
- **S3 管理员头像换 LOGO**：后台左上/用户菜单的默认管理员头像（内置 base64 webp）换成系统默认 LOGO。
- **S4 网络日志**：系统日志之外，还要有"网络日志"。

### C. 额外要求

- **优化扫描**：扫描整个项目，从 **安全 / 体验 / 实用** 三个维度并行找出可优化点（见第四部分）。
- **给流程 + 结构图 + 修复方案**：部分需求需附流程与结构图（C1、C5、S4 已附）。

---

## 第二部分 · 现状调查与问题诱因（关键事实，均带 file:line）

### 客户端

**C1「邀请方绑定后进不去主界面」根因（四层叠加，最致命）**
根因不是单点，而是四层同时缺失，其中最致命的是**服务端绑定成功后完全不通知邀请方 A，而 A 此刻既无实时通道也无任何轮询/监听**：
1. **导航只在冷启动读一次 pairId**：`LinxiApp.kt:89-97` `var screen by remember { mutableStateOf(when{... pairId<=0 -> Bind ...}) }`——只在首次组合求值一次，之后 pairId 变化不重算。唯一响应式导航 `LinxiApp.kt:101-108` 只处理 `navigateToBind`（Main→Bind），**没有 Bind→Main**。Bind→Main 只有 `BindScreen(onBound={screen=Main})` 一条回调（`LinxiApp.kt:141-147`）。
2. **BindScreen 里 A 根本没有等待/推进逻辑**：A 的 `createInvite()`（`BindScreen.kt:49-58`）只把邀请码展示出来，**不调 onBound、无轮询、无监听**；B 的 `bind()`（`BindScreen.kt:60-77`）拿到接口返回后亲手写 `UserPrefs.pairId`（:67）并 `onBound()`（:72）→ 所以 B 进得去、A 进不去。
3. **绑定前 A 的 WebSocket 没连上**：`ProfileSyncPolicy.canConnect` 要求 `pairId>0`（`ProfileSyncPolicy.kt:6-7`），`StatusSyncManager.connect()` 首行即门控（`:76-77`）。A 等待时 pairId=0 → **WS 未连接**，即便服务端想推也推不到。
4. **服务端绑定接口只回给 B、绝不通知 A**：`handleBind`（已复核 `handlers.go:177-218`）绑定成功后仅 `ok(c,{pair_id,partner})`（:217）返回给绑定者 B，**全函数无任何 `hub.route/Notify`**；对比其它写操作都会通知伴侣（资料 `:408`、待办 `:483/551/567`、日记 `:610`）。且服务端无 `paired` 消息类型、客户端 `WsEventRouter.kt:11-35` 也无对应分支。
> 附带死锁：A 侧 `pairId` 永不落盘（写入点只有 B 的 bind、调试跳过、登出、以及被 `canConnectNow` 门控的 refresh），所以**连"重启自愈"都不成立**——重启仍读到 pairId=0 回 Bind 页。现实里 A 能进去多半是它自己去"我输入"填了个码或点了调试跳过。
> 已复核 `handlePairStatus`（`handlers.go:220-229`）：未绑定返回 `{bound:false}`，绑定后返回资料——**这正是可用于轮询/救回 A 的现成接口**。

现状绑定流程（❌ 为断点）：
```
A: 创建邀请码 ──HTTP──> 服务端 (仅返回邀请码)
   └─ 展示邀请码，此后【无轮询/无WS/无监听】❌  ← A 永远停在这里
B: 输入邀请码 ──POST /bind──> handleBind 绑定成功
   ├─ 返回 {pair_id, partner} 给 B → B 写 pairId → onBound() → 进主界面 ✅
   └─ 【不向 A 推送任何事件】❌   (且 A 的 WS 因 pairId=0 本就没连 ❌)
```

**C2「定期同步」现状**：唯一固定周期是 **WS 心跳 ping 30s**（`StatusSyncManager.kt:67`，服务端 90s 读超时 `hub.go:60-63`），且仅在 WS 已连（=已绑定）时才有。`pushNow()` 是事件驱动（建连/亮息屏/网络切换/音乐变化），`/pair/status` 刷新也只在启动/登录/网络恢复等一次性时机触发且被 `canConnectNow` 门控。**没有任何固定间隔的主动业务同步**；`StatusForegroundService.kt:48` 定义了 `ACTION_SYNC` 但全仓无 `AlarmManager.setRepeating/WorkManager` 调度它——"每5分钟采集"是**未接线的死代码**。

**C3「日志不标准 + 调试模式」现状**
- 客户端 `Logs.kt` 其实比想象的规范：文件日志已是结构化英文行 `ISO时间 LEVEL Tag [线程]: message`（`Logs.kt:77-79`，时间戳 `yyyy-MM-dd'T'HH:mm:ss.SSSXXX` 带时区）、4MB×3 轮转、7 天清理、落盘前 `LogSanitizer` 脱敏。**真正的差距**：① 缺"源码位置(类.方法:行)"；② 级别只有 DEBUG/INFO/WARN/ERROR，缺 VERBOSE/FATAL，且 **release 也照写 DEBUG（无按 BuildType 分级门控）**；③ **正文中英混杂**（脚手架英文，但调用点大量中文，如"上传头像失败""刷新情侣资料失败"）——这多半就是你觉得"不标准"的直观来源；④ logcat tag=`Linxi/xxx` 含斜杠、API<26 可能超 23 字符。
- `CrashHandler.kt`：`Thread: not captured`（`:69` 明明有 `thread.name` 却没写）、**崩溃内容未脱敏**（与 Logs 不一致）、写调试标记文件 `attach_pid_*/onCreate_pid_*`（`:33,42`）、用裸 `Log.i/e` 未走门面。
- 服务端 Go：**只用标准库 `log`，无任何结构化日志库**（slog/zap/logrus 零命中）；无级别（全 `Printf`≈INFO、致命 `Fatalf`）、无请求ID、无 JSON、时间戳无毫秒无时区、中英混杂（`main.go:48/160` vs `handlers.go:840`）。
- **调试模式（应删）**：⚠️`BindScreen.kt:150-171`"跳过(开发调试)"**无 `BuildConfig.DEBUG` 守卫=生产后门**（直接置 `demoMode=true,pairId=1,partnerName="调试伴侣"`），这与 0811 既定"跳过仅 debug 保留"相悖，属回归且有安全风险，**优先删/加守卫**；`LoginScreen.kt:128-141` 的跳过已有 DEBUG 守卫（较干净）；另有 `debug/DemoContent.kt` 整文件、`UserPrefs.demoMode` 全链分支、CrashHandler 标记文件、`MainActivity.kt:63` 极简安全模式。

**C4「个人资料删三个」现状（存在重复入口 → 易返工）**
- "我的"设置页 `SettingsScreen.kt`（标题"我的" `:106`）分组1 里有：`编辑资料`（`:110-115`→ 打开 ProfileEditScreen，摘要写"头像、名称、性别、简介与生日"）、`伴侣`，以及**仅在 bound&&!demo 时出现的 3 个内联入口**：`我的头像`（`:122-141` uploadAvatar）、`我的昵称`（`:142-147` 走 `/profile`）、`纪念日`（`:148-157` 走 `/pair/anniversary`）。
- 资料编辑页 `ProfileEditScreen.kt`（标题"编辑资料" `:145`）5 段：①头像 ②名称 ③性别 ④简介 ⑤生日/纪念日；保存走 `/profile/me`。
- **关键冗余**：头像、名称在**两处都能改**（设置页内联 + 编辑资料页），昵称甚至走**两个不同接口**（`/profile` vs `/profile/me`）。这份重复很可能就是你要"删三个"的诱因。
- ⚠️命名坑：编辑页第 5 段标题"生日/纪念日"实际存 `birthday`(个人生日)，而设置页"纪念日"存情侣 `anniversary_date`——两者不是一回事，删改时勿混。

**C6「显示状态码」根因**（已复核 `ApiClient.kt`）
- `ApiClient.check()`（`:46-53`）会解析 `{code,message,data}`，`code!=0` 时抛 `ApiException(code, message)`——**业务错误其实带着服务端中文 message**。
- 但 `get/postJson/putJson/delete`（`:58/74/82/89`）在 **HTTP 非 2xx 时先一步抛 `ApiException(-1,"HTTP ${resp.code}")`**，**跳过了 body 里的中文 message**。而服务端大量业务错误恰恰用带状态码的形式返回，如 `handleBind` 的 `fail(c,400,1002,"邀请码为6位数字")`（`handlers.go:192`）→ 客户端只显示"HTTP 400"，把"邀请码为6位数字"丢了。网络异常则直接把 OkHttp 原文（"Failed to connect to…"）经 `LoginScreen.kt:69 error=it.message` 显示给用户。**这就是你看到 403/400 的直接原因。**

**C7「登录/注册难看」现状**（`LoginScreen.kt` / `RegisterScreen.kt`）
- 结构：整页就是 `fillMaxSize + verticalScroll + verticalArrangement=Center` 的**纯居中单列**（`LoginScreen.kt:75-82`）——标题纯文字("欢迎回来"28sp)+ 一张 miuix `Card`（内两个 TextField + 一行红字 error + LxButton）+ 文字链接。**无 Scaffold/TopAppBar、无状态栏/键盘 insets 处理、无品牌头图**（项目已有爱心 Logo 且 `AboutScreen.kt:106-110` 已在用，登录/注册却完全没放）。
- 难看点：零层次（全靠字号/alpha 拉差异，无留白梯度/分割/插画）、控件平铺（TextField 无 leading 图标、无 helper）、校验反馈弱（规则只写在副标题、只门控按钮置灰不解释原因；邮箱校验仅 `contains("@")&&contains(".")`）、链接 `indication=null` 无水波纹、LxButton 是 Box 手搓无按压态、**品牌色割裂**（主色蓝 `#277AF7` vs 图标粉紫爱心 `#B9A9DC`，`ic_launcher_background`）。
- 可复用素材：`Color.kt` `BrandBlue#277AF7`/`BrandRed#E5484D`；`LxButton`(Positive/Negative/Neutral)；`R.mipmap.ic_launcher` 与 `AboutScreen` 现成 Logo 展示范式。

**C5 待办 · 端到端结构图（应你要求）**
组件与字段：
```
客户端(android)                          服务端(Go)                        存储
─────────────────────────────           ──────────────────────           ──────
TodoScreen.kt                            main.go:115-119 路由             MySQL:
 ├ SearchRow(:246) 150ms防抖(:88)         POST/GET /todos                  todo 表
 ├ TodoCard(:286) 标题/提出者/被提醒       PUT /todos/:id                   ├ id,pair_id
 │   /日程行/提醒开关/完成/删除            POST /todos/:id/complete         ├ creator_id 提出者
 ├ AddTodoDialog(:380) 构造body(:505)      DELETE /todos/:id                ├ assignee_id 被提醒者
 └ FAB(:105 滚动渐隐)                     handlers.go:                     ├ title,note
ApiClient: todos/createTodo/              ├ handleCreateTodo(:443)         ├ remind_at,remind_type(0普/1强)
 updateTodo/completeTodo/deleteTodo       │  →normalizeRepeat(:490)        ├ status(0待/1完/2删)
TodoAlarmScheduler(:98) 本地闹钟兜底       ├ handleListTodos(:508)         ├ repeat_type(0一次/1每天/2每周)
TodoAlarmReceiver(:31) 到点本地通知        ├ handleUpdateTodo(:522)        ├ weekdays(位掩码 bit0=周一)
StatusSyncManager(:178) 收WS:             ├ handleCompleteTodo(:555)       ├ remind_enabled 提醒开关
 todo_new/completed/remind                ├ handleDeleteTodo(:571)         └ completed_at
WsEventRouter(:16) 事件白名单             └ scanDueTodos(:833) 每分钟扫      Redis: PushEventQ 离线事件队列
                                            →nextRemind(:877) 滚动下次
                                          hub.route(:156) 在线WS/离线入队
                                          push.Send(:22) 厂商推送【仅日志占位】
```
数据流（关键）：
```
① 添加: AddTodoDialog → POST /todos → CreateTodo → hub.Notify(todo_new)
        ├在线→对方WS"todo_new"通知; └离线→PushEventQ+push.Send(占位)
        客户端本地: 若开提醒且=本人 → TodoAlarmScheduler.schedule(本机闹钟)
② 到点提醒(主): scanDueTodos每分钟 → DueTodos(status=0&remind_enabled&remind_at<=now)
        → MsgTodoRemind → hub.route(assignee)+hub.route(creator)
        ├在线→WS todo_remind(强提醒→RingHelper 响铃震动); └离线→队列+push(占位)
        → AdvanceTodoRemind 滚动下次(仅一次则置nil)
③ 完成/删除: complete→status=1+todo_completed; delete→status=2; 客户端 cancel 本地闹钟
```
> 已发现的两个隐患：(i) 本地闹钟调度在"添加者"设备，**指派给对方时兜底提醒响在自己手机**而非被提醒者（`TodoScreen.kt:236` vs `handlers.go:854`）；(ii) `push.Send` 全是日志占位（`push.go:45-57`），**离线被提醒者只能靠重连补拉**，无真实系统推送。

### 服务端

**S1「后台多余内容 + 设置不规范」现状**（`admin/`）
- 路由本身已较干净（10 个业务模块，无 examples/widgets 演示菜单）。**残留可删**：`auth/register/`（提交是空壳，`register()` 里真实 API 被注释 `:198-210`，后端无自助注册）、`auth/forget-password/`（空实现 `:53`）、`outside/Iframe.vue`（iframe 演示）——连同 `staticRoutes.ts:33-44,63-77` 与 i18n 词条；`mock/` 目录多为死数据（`formData/articleList/commentList` 无 import、`chinaMap/commentDetail/changeLog` 仅被未挂载的组件引用）；模板组件 `art-chat-window`(聊天)/`art-map-chart`(地图)/`comment-widget`(评论)/`utils/sys/upgrade.ts`(升级弹窗) 均未用。
- 顶栏 11 项功能全 `enabled:true`（`headerBar.ts:16-61`），对运营后台多余的：`chat`(聊天演示)/`language`(多语言)/`notification`(占位无后端)/`settings`(外观抽屉)；外观抽屉"复制配置"输出开发者文案"粘贴到 src/config/setting.ts"；用户菜单"文档/GitHub"指向**模板作者站** `github.com/Daymychen/art-design-pro`（`links.ts:10-19`）。
- **设置项不规范（填了不生效）**：存储 OSS/COS/Kodo 字段 UI 可填，但 `newStorage()` 只实现 local（`storage.go:53-58` 占位）；`push.provider` 运行时从 YAML 读（`main.go:86`）**不读** `app_setting.push.provider`，面板改无效；`site.logo` 被 `site.ts:28` 读了，但 `art-logo/index.vue:4` 硬编码 `logo.webp` 忽略它 → **站点 LOGO 设置不生效**（与 S3 同源）。规范的：`smtp.*` 真实生效、`site.name/description` 生效。

**S2「DB 要手动导入 + 内外端口不一致」根因**
- **免导入根因**：LxDay 把"建基础表"和"加增量列"拆到两处——基础表只在 `server/sql/schema.sql:17-168`，而 Go 启动迁移 `migrations.go:22-137`(version1-5) **全是 `ALTER TABLE` 增量、预设表已存在**。compose 虽挂了 `schema.sql:/docker-entrypoint-initdb.d`（`docker-compose.yml:17`），但该目录**只在 MySQL 数据卷为空(首次)时执行**；一旦复用旧卷、或宝塔用**面板自带 MySQL**（非 compose 的 mysql 服务），schema.sql 不跑 → 基础表缺失 → Go 迁移第一步 `ALTER TABLE user` 直接报错 → 只能人工 `mysql < schema.sql`。
- **hl6 做法（可借鉴）**：DB 侧只建空库（`docker-compose.prod.yml:63-72` 仅给 `POSTGRES_DB/USER/PASSWORD`，无 initdb 挂载）；建表全在 Go 启动时 `AutoMigrate`（`cmd/server/main.go:224-278`，40 张表从结构体自举）+ advisory lock 并发安全 + 建完 `verifyRequiredTables` 自检。**任何环境零手动导入。**
- **端口**：LxDay 容器内监听 8080（`config.docker.yaml:3`、`main.go:161`），对外映射 7740（`docker-compose.yml:43-44` `"7740:8080"`）→ 宝塔显示 `7740→8080` 内外不一致。hl6 用 `"${APP_PORT:-8080}:8080"` + `SERVER_PORT=8080`（`docker-compose.prod.yml:9-12`）→ 显示 `8080→8080` 一致。

**S3「管理员头像换 LOGO」现状**：后台头像**不是**后端下发，而是硬编码静态资源 `@imgs/user/avatar.webp`（2130B<4KB 默认阈值，被 Vite 构建期内联为 `data:image/webp;base64,…`，这就是你看到的那串 base64），引用点 `ArtUserMenu.vue:17,26` 与锁屏 `art-screen-lock/index.vue:24,62`；后端 `handleAdminInfo` 返回 `avatar:""`（`admin.go:170`）。系统 LOGO 是 `@imgs/common/logo.webp`（`art-logo/index.vue:4`）。

**S4「网络日志」现状**：后台"审计日志"页（`audit-log/index.vue`→`admin.go:557 handleAdminListAudit`）记的是**管理员操作审计**（`admin_audit_log`: admin_id/action/detail/ip，`migrations.go:97-107`），**不是网络日志**。HTTP 访问日志现仅靠 `gin.Logger()`（`main.go:89-90`）打到**控制台，不落库、不可查、无页面**；无 `request_log` 表、无查询接口、无前端页面。

---

## 第三部分 · 总体方案（逐条落地）

### 客户端

**C1 绑定回调（修复后流程）**：双保险——服务端在 `handleBind` 成功后向**邀请方 A** `hub.route(paired 事件)`；同时放开 A 在绑定前也能连 WS（凭 token 即可，不再强制 pairId>0），并新增 `paired` 事件类型：客户端收到后写 `pairId`、拉 `/pair/status`、导航 Bind→Main。**再叠加轮询兜底**：A 停在"等待对方绑定"页时每 3~5s 轮询 `GET /pair/status`，`bound=true` 即进主界面（弱网/推送丢失也能进，且与 C2 共用）。导航改为对 `pairId` 响应式（`derivedStateOf`/收到事件即切）。
```
修复后:
A 创建邀请码 → 进入"等待绑定"态: ①连WS(凭token) ②每3~5s轮询/pair/status
B 绑定 → handleBind 成功 → ①ok给B(B进主界面) ②hub.route(A, paired)
A: 收到 paired 事件(即时) 或 轮询到 bound=true(兜底) → 写pairId → 进主界面 ✅
```
**C2 定期同步**：前台每 30s 轮询 `/pair/status`（拉伴侣资料/绑定态/纪念日），WS 在线时以推送为主、轮询为辅可降频；息屏/切后台自动停或降频省电；与 C1 的等待轮询复用同一套调度。

**C3 日志规范 + 删调试**：
- 客户端：日志格式补"源码位置"、release 关闭 DEBUG 落盘（按 `BuildConfig.DEBUG` 分级）、**正文全英文化**（INFO/WARN/ERROR，脱敏保留）、`CrashHandler` 补线程名并接 `LogSanitizer`、去掉 `attach_pid_*/onCreate_pid_*` 标记文件、logcat tag 收敛（去斜杠/限长）。
- 服务端：引入 Go 官方 `log/slog`（零依赖），统一 `time level msg key=val` + 每请求注入 request-id；替换裸 `log.Printf`，`Fatalf` 保留为启动致命。
- 删调试模式：**移除 `BindScreen` 生产后门**（首要，安全）+ 视答复决定是否整链移除 demo（`DemoContent`/`demoMode` 全链/`LoginScreen` 跳过/极简安全模式）。

**C4 个人资料精简**：按提问 Q-C4 定夺"删哪三个"。默认**去重版**：保留"编辑资料"整页作为唯一"个人资料"入口，删掉设置页分组1里重复的 `我的头像/我的昵称/纪念日` 三个内联项；同时把昵称统一到 `/profile/me` 一个接口，消除 `/profile` 双写。

**C5 待办**：先修两个已知隐患——本地闹钟仅当"被提醒者=本人"时才在本机调度（避免响错设备）、明确以服务端扫描推送为主并让重连补拉可靠；其余按你补充的真机现象（Q-C5）精准修。

**C6 错误码友好化**：统一改造 `ApiClient`——HTTP 非 2xx 时**先解析 body 的 `{code,message}` 用其中文文案**，无法解析再按状态码兜底映射（400=请求有误/401=登录已失效/403=无权限/404=资源不存在/408=网络超时/5xx=服务器开小差，请稍后重试）；网络异常（`IOException`）映射"网络连接失败，请检查网络后重试"。UI 只显示这些友好文案；并把 401 接入"自动跳登录"。

**C7 登录/注册美化（依 killaislop 反 AI-slop 原则）**：顶部品牌区（应用 Logo + "林曦日记" + 一句**具体**副文案，不用"✨极速"类空话）；单张表单卡，**站点统一一个圆角**、**克制阴影**（tight blur/小偏移/低透明，不做发光/glass）；字段配 leading 图标 + **内联校验提示**（说明为什么不可提交）；处理状态栏/键盘 insets；`LxButton` 补按压态动画（仅过渡 background/opacity，120–200ms）；**配色统一走蓝**（依"单一强调色"原则），若 Logo 粉紫与主色割裂则同步改品牌蓝前景（见 Q-C7）；层次靠**尺度与留白**（间距梯度 4/8/16/32）而非堆颜色/装饰。

### 服务端

**S1 后台清理 + 设置规范**：删除 `auth/register`、`auth/forget-password`、`outside/Iframe` 三页及其静态路由/i18n；清理 `mock/` 死数据与未用模板组件（chat/map/comment/upgrade）；顶栏只留必要项（去 chat/占位 notification/外观抽屉；多语言若仅面向中文用户则一并去除英文包与语言开关，见 Q-S1）；"文档/GitHub"链接改指向本项目仓库。**设置项"写标准"**：站点 LOGO 接通 `ArtLogo`（顺带修 S3）、SMTP 保留、存储本轮按 Q-S1b 决定（默认只留 local、隐藏未实现的 OSS/COS/Kodo）、push 面板与后端打通或隐藏——原则是**绝不留"填了不生效"的项**。

**S2 DB 免导入 + 端口一致**：
- 免导入（对齐 hl6）：把建基础表逻辑收进 Go 启动迁移——新增 version0 内置全部 `CREATE TABLE IF NOT EXISTS`（由 `schema.sql` 转写），排在现有增量之前，空库启动即自举；`schema.sql` 保留供参考。这样 compose/宝塔/裸机/复用旧卷**任何环境零手动导入**。（并发多副本可加 `GET_LOCK` 兜底。）
- 端口一致（默认方案 A，反代零改动）：容器内也改 7740（`config.docker.yaml` `port:7740`、`docker-compose.yml` `"7740:7740"`、`Dockerfile` `EXPOSE 7740`），对外仍 7740、宝塔显示 `7740→7740`；更新文档里"内 8080"的描述。

**S3 头像换 LOGO**：`ArtUserMenu.vue:17,26`（及锁屏 `:24,62`）头像 src 改为系统 LOGO（`logo.webp`/`<ArtLogo>`）；并把 `site.logo` 设置接通 `ArtLogo`（一并修好"LOGO 设置不生效"）。

**S4 网络日志**：服务端新增请求日志中间件（采集 时间/方法/路径/状态码/耗时ms/客户端IP/UA，可选 用户或管理员ID、request-id），排除 `/ws`、`/uploads`、`/healthz` 与静态，**异步/缓冲写入**、**不记 body/密钥**；新增 `request_log` 表（迁移 version6 + `created_at/path/status` 索引）+ `ListRequestLogs`（分页+过滤）+ `GET /api/admin/network-logs`（挂 `AdminAuth`）；后台新增 `network-log/index.vue`（仿 audit-log 的 ArtTable）+ 路由/菜单/i18n；默认保留 7 天滚动清理（见 Q-S4）。
```
网络日志链路: 请求 → [ReqLog中间件: 计时/采集] → 业务 → 异步写 request_log 表
后台"网络日志"页 → GET /api/admin/network-logs(分页/过滤 方法·状态·时间·IP) → 展示
清理: 定时任务按保留期滚动删除 (控量)
```

---

## 第四部分 · 全项目优化扫描（安全 / 体验 / 实用 三维并行）

> 已通读三端源码。以下按维度、按优先级列出**可落地**问题（带 file:line）。**最高优先：SEC-1 越权、SEC-2 默认口令、ROB-1 WS 并发写崩溃**——影响面最大且修复成本低。

### 一、安全 Security
- 🔴 **SEC-1 越权/IDOR**：待办/日记的改/删只校验"是否绑定"、不校验对象归属，任意已绑定用户遍历自增 id 即可改删他人数据。`handlers.go:522/571/555/628/649`→`store.go` 各 SQL 缺 `pair_id`。**修复**：所有按 id 的写操作 SQL 追加 `AND pair_id=?`，校验 `RowsAffected==1`。
- 🔴 **SEC-2 默认弱口令**：超管固定 `admin/123456`（`admin.go:95`），且 `handleAdminLogin`（`:138`）即便 `must_change=1` 也签发可用 token（未在服务端拦截）。**修复**：服务端在 `must_change` 时限制只能访问改密接口；或首次部署随机初始密码打印一次。
- 🟠 **SEC-3** WS `CheckOrigin` 恒 true（`hub.go:28`，CSWSH）→ 收紧 Origin 白名单。
- 🟠 **SEC-4** WS token 走 query + `gin.Logger` 全量记 URL（`main.go:90,142`）→ token 落盘；改首帧鉴权或过滤 query（与 C3/S4 一并做）。
- 🟠 **SEC-5** 后台通用上传无扩展名白名单（`admin.go:883`）+ `/uploads` 原样托管（`static.go:27`）→ 传 `.html/.svg` 同源 XSS；加白名单+魔数+`nosniff`/`Content-Disposition`。
- 🟠 **SEC-6** 后台 token 存 localStorage（`user.ts:230-232`）→ 配 CSP，或内存+httpOnly。
- 🟠 **SEC-7** 登录无限流（`account.go:219`、`admin.go:138`）→ 按账号/IP 失败计数锁定（Redis 已在手）。
- 🟠 **SEC-8** 服务端无 RBAC（`admin.go:899-936` 仅 `AdminAuth`，仅建管理员校验 super）→ 敏感操作按 role 服务端拦截。
- 🟡 **SEC-9~12** 客户端 token 明文 SharedPreferences(`Utils.kt:36-38`，建议 EncryptedSharedPreferences)、无证书固定、Release 用 debug 签名(`build.gradle.kts:32`)、JWT 720h 无 jti/黑名单、`refreshToken=access`(`admin.go:159`)、默认 JWT secret 占位。
- ✅ 正向：SQL 全参数化无注入、bcrypt、crypto/rand、AppKey `subtle.ConstantTimeCompare`、JWT 强制 HMAC、头像上传魔数+子进程限额、客户端日志脱敏完善、Manifest 组件 `exported=false`。

### 二、体验 UX
- 🟠 **UX-1** 错误提示不友好（即 C6，`ApiClient.kt:58/74/89`、`LoginScreen.kt:69`）——本轮修。
- 🟠 **UX-2** 无障碍缺口：可点图标普遍缺 `contentDescription`（全 main 仅 20 处、7 处为 null）→ 补语义。
- 🟡 **UX-3** "发现"三入口进"开发中"占位（`DiscoverScreen.kt:85`）→ 隐藏入口或标"即将上线"。
- 🟡 **UX-4** 后台危险操作已有二次确认（正向）；但 `user-manage/index.vue:141` `.then()` 无 `.catch` → 取消产生未处理 rejection，补 catch。
- 🟡 **UX-5** 错误码 HTTP 语义混乱：大量业务错误返回 HTTP 200 带 code（`handlers.go:237/264/309/323/439`）→ 与 C6 客户端改造一起理顺（绑定态用 200+data，真错误用 4xx/5xx）。

### 三、实用 / 健壮 Practicality
- 🔴 **ROB-1 WS 并发写崩溃**：gorilla 禁止并发写同一 conn，但 `hub.route(:163)/pushLatestPartner(:190)/上线补偿(:55)` 无写锁；`scanDueTodos`（`handlers.go:833`）是**无 recover 的独立 goroutine**，一次 `concurrent write` panic 会**静默杀死整个到点提醒扫描**。**修复**：每连接封装带 `sync.Mutex` 的 writer，所有写经它；给扫描 goroutine 加 recover。
- 🟠 **ROB-2** 无优雅关闭、`db/rdb` 从不 Close（`main.go:161,193`）→ `signal.NotifyContext`+`Shutdown`+`defer Close`。
- 🟠 **ROB-3** 响铃冷却硬编码 `3次/600s`（`store.go:522-527`）无视 yaml（`main.go:21-22`）→ 读配置。
- 🟠 **ROB-4** 群发通知忽略 `target` 且请求内串行遍历全量用户（`admin.go:843-867`）→ 按 target 过滤 + 异步队列。
- 🟠 **ROB-5** 心跳方向不匹配疑似空闲断连：服务端注册 PongHandler 但客户端发的是 PING（`hub.go:60-63` vs `StatusSyncManager.kt:67`），控制帧不刷新 ReadDeadline → 可能每 90s 被动断连重连抖动（需真机确认）。
- 🟡 **ROB-6~8** 死表 `device_status/app_usage_daily` 只落 Redis 不落库且注释误导(`store.go:404`)、未检查类型断言(`handlers.go:95`)、`http` 重试 `MAX_RETRIES=0` 使重试逻辑成摆设、魔数散落。

---

## 第五部分 · 风险与开发中可能遇到的问题（提前说好）

1. **无本地 Android/Go/docker 工具链**：客户端与服务端只能靠 CI 验证；改动严格对照现有 miuix/Compose、Gin 写法逐文件自检，红了再迭代。仅 admin 前端可本地 `npm build`。
2. **C1 放开"绑定前连 WS"**：需同步放宽 `ProfileSyncPolicy`（凭 token 即可连），但要保证**未绑定时服务端 hub 也能路由到该连接**（现连接注册可能以 pairId 为 key，需核对 `hub.go` 上线登记逻辑），否则 paired 事件仍推不到 A。轮询兜底可消除此风险，故建议二者都做。
3. **C2 轮询省电**：前台 30s 轮询要在息屏/切后台时停/降频，避免耗电与无谓流量；WS 在线时应优先推送、轮询降级，防止双通道重复刷新抖动。
4. **C3 服务端引入 slog**：属跨文件替换，需保证所有 `log.Printf` 迁移一致、不遗漏；日志格式变更可能影响你现有的宝塔"日志查看"习惯（会更规范但样式变）。
5. **C4 删资料入口**：删设置页内联项要确认没有别处依赖其副作用（如上传头像后刷新缓存）；`/profile` 与 `/profile/me` 双写统一后，注意老数据/接口兼容。**这是最易返工项，务必先答 Q-C4。**
6. **C6 错误映射**：需区分"业务错误(HTTP200+code)"与"HTTP层错误(4xx/5xx)"与"网络异常"三类；401 自动跳登录要防止在登录页自身触发死循环。
7. **C7 美化 vs miuix**：全站是 miuix 基座，美化要用 miuix 组件语汇实现 killaislop 原则，避免引入与 miuix 冲突的 Material 控件；`LxButton` 是手搓 Box，补按压态注意与 miuix 交互一致。
8. **S2 Go 建基础表**：把 `schema.sql` 转写进 Go 迁移要与现有表结构**逐列一致**（字符集/索引/外键/默认值），否则老库与新建库结构漂移；建议 `CREATE TABLE IF NOT EXISTS` + 保留 `schema.sql` 作为对照真源。**改端口**会影响单容器 `-p` 示例与文档，需同步更新 DEPLOYMENT.md。
9. **S4 网络日志量**：高频接口会让 `request_log` 快速膨胀，必须异步写 + 保留期清理 + 索引；IP/UA 属个人信息，仅管理员可见、并在文档注明。
10. **优化项修复的连带影响**：SEC-1 加 `pair_id` 校验要覆盖所有相关 SQL 并回归测试；ROB-1 写锁改造要覆盖所有写 WS 路径；证书固定/正式签名/EncryptedSharedPreferences 改动面大或需你提供物料，建议列清单分步。
11. **验证策略**：服务端 `go vet/build/test`、后台 `npm build` 力争本地/CI 跑绿；安卓靠 CI（build-android 手动触发）；compose 改动本地 `docker compose config` 校验；DB 免导入要在"空库/复用旧卷/宝塔自带库"三情形各验证一次（靠 CI 或你在宝塔实测）。

---

## 第六部分 · 提问（请在每个 `选择:` 后填写，其余原样回填到 `ClaudeScheme_0813_Answer.md`）

### 客户端

问题:邀请方 A 绑定后进主界面，用哪种机制？
详情:根因=服务端 handleBind 不通知 A + A 的 WS 因 pairId=0 未连 + 无 Bind→Main 响应式导航 + A 侧 pairId 永不落盘。推送式/轮询式各有取舍。
选项:A 仅客户端轮询：A 等待时每 3~5s 轮询 /pair/status，bound=true 即进主界面（最稳、弱网可用，且顺带满足需求2）
B 仅服务端推送：放开 A 绑定前连 WS + handleBind 推 paired 事件 + 客户端路由该事件进主界面（即时，但依赖 WS 连上）
C A+B 都做：推送保即时、轮询保兜底（最稳健）
选择:
我的意见:C（纯 B 有"WS 没连上就收不到"的风险，纯 A 有 3~5s 延迟；两者叠加最稳）。

问题:客户端"定期同步"的间隔与范围？
详情:现无固定周期业务同步（仅 30s WS ping）。你要"隔一会发请求同步"。
选项:A 前台每 30s 轮询 /pair/status（伴侣资料/绑定态/纪念日等），息屏/后台自动停或降频；WS 在线时以推送为主、轮询降频
B 前台每 60s + 后台 WorkManager 每 15min（系统最小周期）兜底
C 你指定：前台__秒 / 后台__分钟
选择:
我的意见:A（省电够用，与需求1等待轮询复用一套）。

问题:日志"国际标准化"做到哪一层？
详情:客户端文件日志已是 `ISO时间 LEVEL Tag [线程]: msg`，缺源码位置、级别缺 VERBOSE/FATAL、release 未按级别门控、正文中英混杂；服务端 Go 用裸 log 无级别无结构化。
选项:A 客户端补源码位置+release 关 DEBUG 落盘+正文全英文化+CrashHandler 补线程与脱敏；服务端引入 Go 官方 log/slog（`time level msg key=val`+请求ID）
B 仅客户端英文化+格式补全，服务端不动
C 全量结构化 JSON 日志（客户端+服务端，便于 ELK/Loki 采集）
选择:
我的意见:A（对齐业界又不过度；slog 零依赖）。要接日志平台则 C。

问题:"删除调试模式"删到什么程度？
详情:BindScreen"跳过(开发调试)"(:150-171)无 DEBUG 守卫=生产后门；LoginScreen 跳过已有守卫；另有 DemoContent、demoMode 全链、CrashHandler 标记文件、极简安全模式。
选项:A 彻底移除 demo/skip 整条链（生产无任何调试后门）
B 仅移除无守卫的 BindScreen 后门，其余调试项加 BuildConfig.DEBUG 守卫、debug 包保留
选择:
我的意见:A（你说"可以删除调试模式了"，且后门有安全风险；若仍想 debug 包留跳过选 B）。

问题:个人资料"删掉三个"具体删哪三个？（★最易返工，务必确认）
详情:现状有重复入口——①编辑资料页(ProfileEditScreen)含 头像/名称/性别/简介/生日 5 段；②"我的"设置页分组1 另有内联的 我的头像/我的昵称/纪念日 3 个入口（与编辑资料重复，昵称还走两个接口）。
选项:A 字面版：在编辑资料页里删 头像/名称/性别 3 段，仅保留 简介+生日
B 去重版：保留"编辑资料"整页为唯一"个人资料"入口，删掉设置页里重复的 我的头像/我的昵称/纪念日 3 个内联入口
C 你另行说明确切要删的三项：____
选择:
我的意见:B（消除重复入口最干净，"编辑资料"即"个人资料"；A 会让资料页只剩简介+生日显得残缺）。

问题:待办你遇到的"很大的问题"具体是什么？（结构图见第二部分 C5）
详情:我已发现两处隐患：①本地闹钟调度在"添加者"设备，指派给对方时兜底提醒响在自己手机；②离线推送 push.Send 仅日志占位，离线被提醒者只能靠重连补拉。"问题很大"较模糊。
选项:A 先修我发现的两点（本地闹钟仅本人时调度+明确以服务端扫描为主+重连补拉可靠）
B 你补充具体现象：____（如"提醒不响""重复提醒""对方收不到""时间/时区不对""搜索/完成/删除异常"等）
C 看完结构图我再答
选择:
我的意见:B（请描述真机现象我好精准修）；无论如何我都会先把 A 两点缺陷修掉。

问题:错误提示如何友好化？
详情:根因=ApiClient 在 HTTP 4xx/5xx 时直接抛 "HTTP <code>"，忽略了 body 里服务端返回的中文 message；网络异常抛 OkHttp 原文。
选项:A 改造 ApiClient：HTTP 失败优先用 body 的中文 message，无法解析再按状态码兜底映射中文；网络异常映射"网络连接失败，请检查网络"
B 在 A 基础上，401 自动跳登录、强更等特殊码全局统一处理
选择:
我的意见:B（A 是基础，B 顺带把登录失效自动跳转做掉，体验更完整）。

问题:登录/注册美化方向 + 是否扩展到全站？
详情:依 killaislop 反 AI-slop 原则（单一强调色/尺度留白建层次/减法优先/统一圆角/克制阴影/勿堆装饰）。现图标粉紫#B9A9DC 与主色蓝#277AF7 割裂。
选项:A 只美化登录/注册：加品牌 Logo 头区+统一圆角克制阴影的表单卡+字段 leading 图标与内联校验+insets+按压态；配色统一走蓝，Logo 同步改品牌蓝前景
B 同 A，但保留现有粉紫爱心 Logo（不改图标配色）
C 同 A，并把 kill-ai-slop 原则扩展应用到 App 全站/后台 UI（工作量更大）
选择:
我的意见:A（先把登录/注册做到位并统一到品牌蓝；全站扩展可下一轮，见 C）。

### 服务端

问题:后台"删多余内容"的范围与多语言处理？
详情:可删：注册/忘记密码页(空壳)、iframe 外链页、mock 死数据、聊天/地图/评论/升级弹窗模板组件、顶栏多余功能、指向模板作者的"文档/GitHub"链接。
选项:A 全删+全修：删上述残留、顶栏只留必要项、链接改指向本项目；多语言若仅面向中文则一并移除英文包与语言开关
B 只删死代码与模板残留，保留多语言开关
C 你指定保留/删除清单：____
选择:
我的意见:A（对齐"多余的删掉、都修好"；后台仅中文用户建议去多语言）。

问题:对象存储(OSS/COS/Kodo)本轮是否真正接通？
详情:后台有存储切换 UI，但 newStorage 仅实现 local，其余占位；"设置写标准"要求填了就生效。
选项:A 本轮只保留"本地存储"，隐藏/禁用 OSS/COS/Kodo（消除"填了不生效"）
B 本轮接通七牛 Kodo（有免费额度）——需你给 AK/SK/Bucket/域名
C 接通阿里云 OSS 或腾讯云 COS——需对应密钥
选择:
我的意见:A（先消除误导；相册功能真正落地时再接对象存储并给密钥）。

问题:数据库免手动导入用哪种实现？
详情:根因=基础表只在 schema.sql、Go 迁移只加增量列；换环境(复用旧卷/宝塔自带 MySQL)时 initdb 不跑→基础表缺→只能手动 source。hl6 用 Go 启动 AutoMigrate 全量建表零导入。
选项:A 对齐 hl6：把建基础表收进 Go 启动迁移(version0 内置全部 CREATE TABLE IF NOT EXISTS)，空库启动即自举，任何环境零手动导入
B 仅完善 compose+文档强调"必须用 compose 的 mysql、勿复用旧卷"（治标）
选择:
我的意见:A（真正免导入、与 hl6 一致；改动集中在 migrations.go）。

问题:容器内外端口一致用哪个方案？
详情:现内 8080 / 外 7740 不一致；hl6 内外都 8080。
选项:A 保持对外 7740、容器内也改 7740（宝塔显示 7740→7740，反代目标 127.0.0.1:7740 零改动）
B 内外都用 8080（需把所有反代/文档里的 7740 改成 8080，并确认宿主 8080 未被占用）
选择:
我的意见:A（不动你现有 7740 习惯、反代零改动）。

问题:后台管理员头像换 LOGO 的做法？
详情:现头像是内联的 avatar.webp(构建期转 base64)写死在 ArtUserMenu；系统 LOGO 是 logo.webp；且"站点 LOGO 设置"当前不生效。
选项:A 前端改引用：用户菜单(含锁屏)头像改为系统 LOGO；并把 site.logo 设置接通 ArtLogo（一并修好"LOGO 设置不生效"）
B 仅把默认头像文件替换为 LOGO（最小改动，但站点 LOGO 设置仍不生效）
选择:
我的意见:A（顺带修好设置项，符合"所有设置都写标准"）。

问题:网络日志记什么字段、保留多久？
详情:现"审计日志"是管理员操作流水非网络日志；gin.Logger 仅打控制台不落库。网络日志=API 请求日志。
选项:A 记 时间/方法/路径/状态码/耗时/IP/UA(+可选 用户或管理员ID/请求ID)，排除 /ws /uploads /healthz，异步写入，默认保留 7 天滚动清理
B 同 A，但保留 30 天，并加按状态码/路径/时间过滤与导出
C 你指定字段与保留期：____
选择:
我的意见:A（够用且控量；量大或需追溯再上 B。IP/UA 属个人信息仅管理员可见）。

### 额外（优化与物料）

问题:全项目优化本轮修复范围？
详情:最高危：①越权(遍历 id 改删他人待办/日记)②默认 admin/123456 且未强制改密③WS 无写锁并发写 panic 会静默杀死到点提醒扫描。另有多项中低危(见第四部分)。
选项:A 修全部高危(越权加 pair_id 校验+服务端强制首登改密+WS 每连接写锁+扫描加 recover)+性价比高的中危(上传白名单/CheckOrigin 白名单/登录限流/错误码语义)
B 只修三项最高危，中低危列入下一轮
C 高中低危全修（工作量最大）
选择:
我的意见:A（高危必修，挑成本低的中危一起；证书固定/正式签名/加密存储等改动大或需物料的列清单逐步来）。

问题:需要你提供的发布物料（签名/密钥）？
详情:Release 现用 debug 签名(无法上架/易被重签)；JWT secret、APP_KEY 上线应为非默认值。
选项:A 你提供正式 keystore（或授权我生成自签并回执）；JWT secret/APP_KEY 由我随机生成写入部署回执与 CI Secret
B 本轮不动签名/密钥，仅先修其它，物料以后再给
选择:
我的意见:A（生产尽快用正式签名；keystore 你保管，CI 只引用 Secret）。

---

### 附：待你直接补充的信息（非选择题）
- **C5 真机现象**：待办到底"哪里坏了"（提醒不响 / 重复 / 对方收不到 / 时间不对 / 搜索完成删除异常…），越具体越好。
- **C7 品牌 Logo**：是否同意把图标从粉紫爱心 `#B9A9DC` 统一到品牌蓝 `#277AF7`？还是保留现有粉紫。
- **S1b 对象存储**：若本轮要接（选 B/C），请给云厂商 Bucket / AccessKey / 域名（部署时给亦可）。
- **X2 发布物料**：正式 keystore（别名/密码）是否由你提供，或授权我生成自签并在回执告知。
- **多语言**：后台是否确定只面向中文用户（决定是否移除英文包与语言开关）。
- **本项目仓库/官网链接**：后台"文档/GitHub"改指向的地址（默认 `github.com/Lxii-Build/LxDay` + `love.lxii.cc`）。

> 以上确认后（回填 `ClaudeScheme_0813_Answer.md`），我据答复更新 TodoList 并分阶段施工：**C1/C2 绑定与同步 → C6 错误友好化 → C3 日志与删调试 → C4 资料精简 → C5 待办修复 → C7 登录注册美化 → S2 DB免导入与端口 → S1/S3 后台清理与LOGO → S4 网络日志 → 高危优化(SEC-1/2·ROB-1)**。中途不停顿续做，哪怕网络波动也直接续上，最终以 CI/本地构建全绿为完成标准。













