# 更新日志 · Changelog

本项目所有值得记录的版本变更都记录于此。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)（`vMAJOR.MINOR.PATCH`）。

> 约定：发行版由「发行版工作流」手动触发，`versionCode` 由 tag 推导。
> 面向用户的应用介绍见 [docs/APP_INTRO.md](docs/APP_INTRO.md)，此后每个版本在此追加一条更新日志。

## [Unreleased]

（暂无）

## [1.0.0] - 2026-08-22

首个正式发行版。0811 起共五轮开发，本节按模块汇总，不再按开发轮次分段。

### 你能用到的功能

- **实时状态共享**：绑定伴侣后互相可见电量与充电、亮屏/息屏（含息屏显示 AOD）、
  正在使用的应用、正在播放的音乐、网络与 WiFi。需双方在 App 内完成知情同意才开启，
  可随时关闭，关闭即停止采集。
- **伴侣状态历史**：按天查看对方的状态时间线与电量曲线。
- **共同相册**：上传照片（单次最多 100 张）、建分组、拖动管理、评论点赞、
  「这一天」回顾、回收站（可恢复、可彻底删除、显示还剩几天自动清理）。
- **待办与提醒**：给自己或对方添加待办，支持「仅一次 / 每天 / 每周指定几天」循环提醒，
  可开强提醒（走闹钟音量），也可随时暂停某条提醒。
- **远程互动**：一键发「求陪伴 / 求冷静 / 响铃提醒」，响铃 7 秒自动停且可双向撤回。
- **恋爱天数与资料**：纪念日、头像（圆形裁剪）、昵称、性别、简介。
- **同步自检页**：逐项检查保活相关权限，并写明「不开会怎样」。
- **应用内检查更新**。

### 客户端（Android）

- **全 App 统一 miuix 风格**：31 处 Material 图标换成 `MiuixIcons`，UI 层 `material3` 清零
  （仅配色链保留），响铃全屏页改 Compose + miuix（外层 try/catch 回退 XML——
  锁屏唤醒时序敏感，宁可丑不能哑），冷启动帧换 `DeviceDefault` 并与 miuix 背景对齐。
- **弹窗规范焊进组件**：`LxConfirmDialog` / `LxFormDialog` 把「取消灰底在左、确认蓝/红在右、
  危险操作 1 秒冷静期」做成组件行为，不再依赖每个调用点自觉。
- **按钮最小触达尺寸焊进组件**：`LxButton` 用 `defaultMinSize` 兜住 48dp。
- 自研 miuix 风格选图器：按相册分桶（相机 / 截屏 / 微信 / 下载）、分页加载、角标预览大图，
  顶栏常驻「系统相册」入口走系统 Photo Picker 兜底（无需任何读取权限）。
- 头像改用同一个选图器 + 圆形裁剪页（此前直接居中裁，竖构图人像的头会被切掉）。
- 相册三档图：thumb 384（网格）/ preview 1080（大图页先出）/ origin（放大后才拉），
  大图页预加载相邻两张。
- 自己上传的照片不走网络：`LocalPhotoIndex` 记住「照片 id → 本机原图 uri」，
  查看时直读本机、本机删了才回云端。磁盘缓存 128MB，设置页可清。
- 上传失败逐张给出具体原因并可重试失败项（此前一句"格式不支持或超过 20MB"
  把 OOM、解码失败、配额用尽全糊在一起）。
- 动态 WebP 不再被压成静图（`Bitmap.compress` 只写单帧，动画会整个丢失）。
- 状态采集重写：`Display.getState()` 作权威来源并单独识别 AOD；前台应用查询窗口
  24 小时→60 秒并逐级回退，认 PAUSED/STOPPED（回桌面不再显示上一个应用），息屏不查；
  采集移到 IO 线程（此前在主线程、前台档 10 秒一次，是 ANR 风险）；
  WS 断线改走 `POST /status` 兜底（此前直接丢弃，地铁里状态完全停更）；
  主页显示「更新于 N 分钟前」，过期置灰、失联不再把旧值当现状。
- 伴侣状态改 `StateFlow`。此前读普通 `@Volatile` 字段，Compose 不会重组——
  这才是「状态同步不实时」的真因，轮询再密也没用。
- 同步节奏分档：前台 10s / 后台 60s / 息屏 5min；WS 心跳 15s、服务端判死 45s，
  重连退避加 ±20% jitter 避免双端同时重连。
- 静默通知渠道 `status_quiet`（低优先级、无声、无振动、不亮灯）：伴侣息屏/亮屏、
  上线/下线只落通知栏。全部渠道收敛到 `NotificationChannels` 统一创建——
  `createNotificationChannel` 改不了已存在渠道的重要性与声音，谁先创建谁决定行为，
  此前 4 处各自创建等于把行为交给竞态。
- 引入 Coil 3 做图片加载，替代原先在主线程 `BitmapFactory` 解码的做法。

### 服务端

- **21 项运行参数可在后台调**（相册配额、数据保留天数、验证码与登录限流、token 有效期、
  邀请码、互动冷却），分 5 个分区、带范围收敛与一键恢复默认（默认值由服务端下发，
  永远与代码一致）。改完立即生效，无需重启容器。连同站点/SMTP/存储共 32 项可配。
- **真支持 HEIC / AVIF / BMP**：用 gen2brain 的纯 Go wasm 解码器（wazero，无需 CGO），
  不破坏「单容器 + 纯 Go」架构，也不必给 alpine 镜像装 libheif。
- 图片鉴权代理 `GET /media/:id`（及 `/thumb`、`/preview`）：真实磁盘路径不出服务端，
  只有该 pair 的成员能读；照片 URL 不进 `request_log`。
- 上传配额改原子占额（先记账后判断、超了回退）。此前"先查后写"两步，
  客户端改成并发上传后配额形同虚设。
- 三处「磁盘只涨不跌」全修：回收站按天清理并真删磁盘文件、状态历史加保留期、
  网络日志保留天数可配。每类清理都记录实际删除行数。
- 相册回收站补齐彻底删除与清空（真删文件），并给出「还剩 N 天自动删除」。
- 后台新增**相册管理**与**磁盘统计**页（各 pair 占用、回收站占用、真实磁盘占用），
  可清空指定情侣的回收站。后台查看缩略图只给 384、每次写审计、响应头带 `no-store`。
- 照片 `Cache-Control` 1 天→30 天 + `ETag`；GIF 缩略图改出 JPEG（PNG 大好几倍）。
- 原图按原字节保存不重编码（保画质与 EXIF）。
- 数据库启动自动建表（内嵌 `schema.sql`），任何环境零手动导入。
- 结构化日志（`log/slog`）+ 请求 ID；网络日志页保留天数可配。
- 头像/照片处理去 libvips 改纯 Go 解码。

### 运营后台

- 移动端适配：`art-table` 在 ≤768px 卡片化（复用同一份列配置与插槽，11 个页面零改动全受益）、
  搜索区窄屏纵向堆叠、`viewport-fit=cover` + 安全区。
  新增 `admin/scripts/mobile-audit.mjs`：Playwright 四档视口逐页断言无横向溢出、
  无控制台错误、无失败请求、非白屏。
- 「内容审核」相册照片 tab：元数据表格、关键词与 pair 筛选、真分页、软删二次确认。
- 通知定向投递可用（此前 `target` 字段是假的，选「指定用户」也永远全站广播）。
- 待办管理、用户管理、网络日志、审计日志、通知模板、管理员管理。

### 移除

- **「日记」功能整体下线**（客户端页面与模型、服务端 5 个 handler + 7 个 store 方法 +
  路由 + WS 消息 + 推送、`diary` / `diary_image` 两张表、后台页面与仪表盘统计卡、文档）。
  App 名称「林曦日记」保留，但没有写日记这个功能。
  下线前提供 `GET /api/admin/diaries/export` 导出 Markdown 留档；
  删表由 `POST /api/admin/diaries/purge?confirm=DROP` 显式触发，**刻意不在自动迁移里跑**
  ——否则新镜像一上线数据就没了，导出接口会变成废物。
- 死代码：`SAFE_MODE`、`DemoContent`、壁纸裁剪页（无任何入口可达）、
  「跳过（开发调试）」入口（含生产后门）。

### 修复 · 客户端

> 以下五条都追到了具体代码行，其中多条本机跑出复现。记下来是因为它们的表象与真因相距很远。

- **上传从来没成功过一张**：`ImagePrep` 读图片边界那步写成
  `openInputStream(uri)?.use { decodeStream(it, null, bounds) } ?: error("无法读取所选图片")`。
  `use{}` 返回的是 lambda 的值即 `decodeStream` 的返回值，而 `inJustDecodeBounds = true` 时
  **`decodeStream` 按设计永远返回 null**（只填 `outWidth`/`outHeight`，不产出 Bitmap）。
  于是 `?:` 恒成立，**除 GIF 与动态 WebP 外每一张图都在上传第一步抛异常**。
  判定已抽成可单测的 `ImagePrepPolicy.boundsFailure`，区分"流没打开"与"尺寸为 0"两种失败。
- **连续上传必 OOM**：`sampleSize` 只能做 2 的幂，4000×3000（最常见的手机直出）
  算出 `sample=1` → 全尺寸解码 **45.8MB**，而失败只静默计数，表现就是"照片会消失"。
  已加 `decodeDensityScale` 在解码期直接出目标尺寸，实测 **45.8MB → 12.0MB**。
- **进「伴侣状态历史」必崩**：`status_history` 三个可空列被 Go 侧用 `string` 接，
  而 `store.go` **忽略了 `rows.Scan` 的返回值** → 整行保持零值 →
  `Ts.UnixMilli()` = `-62135596800000`（每行同值）→ 客户端 `items(key = { it.ts })`
  撞重复 key → 崩溃。已改 `sql.NullString`，并给**全库 18 处 `rows.Scan` 补齐错误检查**。
- **有些图片扫不到**：`MediaStoreImages` 写死 `limit=2000` 且按 `DATE_TAKEN DESC` 排序。
  `DATE_TAKEN` 来自 EXIF、**只有相机直出才有**，截图与微信图普遍是 NULL 排在最后，
  图片总数一旦超 2000 就被整批截断。已改 `COALESCE(DATE_TAKEN, DATE_ADDED*1000)` 排序
  + 去上限 + 分页 200 + 覆盖全部存储卷 + 按目录分桶。
- **缩略图与相册封面是"透明"的**：服务端图片地址是条件绝对的——后台 `site.url` 未配置时
  返回相对路径 `/media/<id>/thumb`，而 `app_setting` 表无种子数据，默认就是相对路径。
  客户端此前零补全，Coil 拿到无 scheme 的字符串不走网络 fetcher、当本机文件找 → 失败；
  又因为图片位没有背景色，结果是**字面意义上的透明**。
  新增 `MediaUrlPolicy.absolutize` 并收口在 Coil mapper（网格/封面/大图/头像一处全受益），
  同时给图片位补占位底色。注意 `BASE_URL` 带 `/api/v1` 而图片挂根路径，必须先取 origin。
- **选头像即崩**：单选用了 `PickMultipleVisualMedia(1)`，而该 contract 的 init 是
  `require(maxItems > 1)`。contract 在 composition 期构造，所以一进页面就崩。改用 `PickVisualMedia`。
- **头像裁剪框中间是黑的**：`BlendMode.Clear` 会把目标像素的 alpha 一并清零，
  遮罩层没有离屏合成层时绘制直接落在父画布上，把图片连同蒙版一起擦掉、露出不透明窗口表面。
  已加 `CompositingStrategy.Offscreen`。
- 修「两个加载圈一起转」：`KernelScreen` 的 `loading` 与 `isRefreshing` 可同真
  （初次加载没结束就下拉），改为互斥，一处修好所有列表页。
- 修待办循环提醒只响一次、重启后闹钟不重建、缺精确闹钟权限导致必崩、
  `NetworkReceiver` 静态注册在 API 24+ 完全失效、下拉刷新指示器被顶栏遮挡。
- 修「邀请方绑定后进不去主界面」：邀请方进入等待态并轮询绑定状态，
  对方一绑定即自动进主界面（服务端同时推 `paired` 事件，双保险）。
- 错误提示不再直接显示「HTTP 400/403」，改显示服务端返回的中文原因。

### 修复 · 服务端

- **生产 502**：`fail()` / `afail()` 返回错误前不排空请求体，而 Go 只自动排空 ≤256KB，
  超过就关连接 → Nginx 侧变成 502，把精心写的中文错误整个吞掉。已统一 `drainRequestBody`。
- **老库升级起不来**（`no such column: deleted_at`）：`runMigrations` 一趟执行整个
  `schema.sql`，而补列在最后。老库里表已存在 → `CREATE TABLE IF NOT EXISTS` 是空操作 →
  新列不会出现 → 紧随其后引用该列的 `CREATE INDEX` 直接报错，**容器起不来**。
  已拆成「建表 → 补列 → 建索引」三段。
  **只用全新临时库做测试永远测不到这条升级路径**，故补了「先建旧表结构再跑迁移」的回归测试。
- 「伴侣状态历史」此前查的是**你自己**的记录，已改 `?who=me|partner`；
  日期查询的时区错位（写入用本地、查询按 UTC）已修。
- 删掉 6 个废弃 setting 键与死配置 `storage.upload_max_mb`（代码里从未引用，配了没用）。

### 安全修复（附「不修会怎样」）

> 半年后回看需要知道为什么改，故逐条记录后果。

- **头像上传在生产必然 500**：处理链依赖 libvips，而运行镜像是 alpine、根本没装。
- **手机相册主力格式必失败**：魔数白名单漏了 JPEG。手机照片九成是 JPG，
  等于上传功能对绝大多数图片直接不可用。
- **`request_log` 清理永久静默失败 → 磁盘被打满**：清理 SQL 用了 MySQL 语法
  （`NOW() - INTERVAL 7 DAY`），在 SQLite 上不报错也不删任何行。日志表只增不减，
  最终撑满磁盘导致服务不可写。
- **私密照片 URL 被写进网络日志**：netlog 的 skip 前缀只写了旧的 `/uploads`，
  而实际落盘前缀是 `/upload`。任何能看后台「网络日志」页的管理员都能直接点开情侣私密相册。
- **普通 admin 事实上等于超管**：此前只有两个路由挂了 `requireSuper`，其余全裸奔——
  普通 admin 能读设置里的存储密钥、向全站群发通知、删任意数据、封禁用户、
  解绑他人情侣关系、上传 300MB 文件落盘。敏感路由已全部收敛到超管。
- **token 泄露后无法止损**：新增 `token_ver` 撤销机制（改密/重置/禁用/角色变更即令旧 token
  全失效），后台 token 有效期 720h → 2h；`AdminAuth` 改实时读库，不再信 token 里的
  `role`/`status`——否则把管理员降权或封禁后，他手上的旧 token 在过期前依然是超管。
- **邀请码可被暴力枚举**：6 位纯数字（100 万空间）升到 8 位混合字符
  （约 32^8 ≈ 1.1 万亿）+ 每账号 10 分钟 5 次上限。
- **初始超管口令写进日志**：改为写入 `0600` 权限文件。日志会被采集、轮转、
  随诊断包外发，口令跟着一起走。
- **WS 可被单帧打爆内存**：新增单帧 64KB 上限 + 上行限频；离线队列加上限与 TTL。
- **来源 IP 可伪造**：未设 `SetTrustedProxies` 时 Gin 信任所有代理，
  任何人都能用 `X-Forwarded-For` 污染审计日志并绕过一切按 IP 的限流。
- **debug 模式信息泄露**：生产默认切 gin release 模式。
- 其余：邮箱验证码尝试上限、全局安全响应头、跨域收紧、管理员首登强制改密、
  上传扩展名白名单、`/uploads` 禁嗅探、`JWT_SECRET` 缺省即拒绝启动、优雅关闭。
- 密钥改由环境变量 `JWT_SECRET` / `APP_KEY` 注入，不再写入提交的配置文件。

### CI 与发布

- **安卓固定签名**（PKCS#12，指纹恒定），详见 [docs/SIGNING.md](docs/SIGNING.md)。
  此前每次构建签名都不同，用户装新版会撞「签名不一致」而必须先卸载、连带丢本地数据。
- **修复 `release.yml` 完全没传 `VERSION_CODE`**：`build.gradle.kts` 因此回退默认值 1，
  每个发行版的 `versionCode` 都是 1。安卓要求它递增，用户**根本装不上更新**。
  现由 tag 推导：`v1.2.3` → `versionCode = 1*10000 + 2*100 + 3 = 10203`
  （单调递增且与版本名一一对应，minor/patch 超过 99 直接报错）。
- 镜像冒烟测试：构建后起容器 curl `/healthz`，60s 内不通过即失败——
  挡住「镜像推上去了但启动即崩」。
- APK 签名指纹打进 Job Summary + 发行版指纹断言（指纹变了就让流水线红，
  而不是等用户装不上才发现）。
- R8 `mapping.txt` 作为产物上传（否则线上崩溃栈全是混淆名，无法还原）。
- **Go 1.22 → 1.25**：HEIC/AVIF 解码器（底层 wazero）声明 `go >= 1.25`，
  `go mod tidy` 会把 `go.mod` 提上去，而 Dockerfile 与 CI 若还是 1.22 则镜像构建直接失败。
  本机恰好是新版所以完全没复现出来——引入新依赖后要检查它声明的最低 Go 版本。
- 服务端镜像除推 GHCR（私有）外，额外导出 `.tar.gz` 作为产物，国内可直接 `docker load`。
- 新增 `gofmt` 检查。

### 规范与文档

- 新增仓库根 [AGENTS.md](AGENTS.md)：弹窗按钮语义、miuix 组件与图标约束、
  Go 与 SQLite 易错点（`rows.Scan`、提前返回要排空 body、迁移顺序、清理 SQL 方言）、
  Kotlin 的 `?.use{}` 返回值陷阱、图片地址补全、可点区域下限、提交前三端自检、隐私底线。
  配一行 `CLAUDE.md` 引用。
- 新增 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)（工具链与本地验证流程，
  含「本机代理会造成假 502」的排查警告）、[docs/SELFTEST_0821.md](docs/SELFTEST_0821.md)
  （真机自测清单，含「我已验证 / 我无法验证」的分界）。

[Unreleased]: https://github.com/Lxii-Build/LxDay/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Lxii-Build/LxDay/releases/tag/v1.0.0
