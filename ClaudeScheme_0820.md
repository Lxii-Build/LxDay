# ClaudeScheme_0820 · 林曦日记「第四轮」改造方案与提问

> 生成日期：2026-08-20（第四轮）
> 依据：`TodoList_0820.md` 逐字需求 + 对 `android/`(81 个 kt)、`server/`(24 个 go)、`admin/`、`.github/workflows/` 三端源码审阅 + 参考 `C:\Lx\KernelSU-main`（miuix 皮肤/PullToRefresh 正解）、`C:\Lx\hl6`（单容器部署）、`C:\Lx\art-design-pro-main`（后台模板原始面貌）、`C:\Lx\icon.jpg`。
> 方法：superpowers（系统化调查→根因定位→最小改动）+ grilling（逐字追问、暴露歧义与返工点）。调查由 4 个并行子代理完成，**所有 file:line 已本人复核**，未复核的一律标「待确认」。
> 流程：请在「第八部分 · 提问」每个 `选择:` 后填写，其余原样回填到 `ClaudeScheme_0820_Answer.md`；`我的意见` 是我的默认推荐，认可即填该项。答复后我一次性做完，中途不停；网络波动我直接续上。
> 环境约束（决定验证策略）：本机**无 java / keytool / go / Android SDK / docker / gh**，仅有 python3.11(+cryptography)、node/npm、OpenSSL 3.5.4。→ 后台前端可本地 `npm run build` 验证；Go 与安卓靠 CI；APK 只能靠你真机（一加15/安卓16）验。
> 分支/CI：直接在 `main` 工作。push `server/**|admin/**|Dockerfile` 触发 build-server（构建+推 ghcr+导出镜像 tar），**不自动上生产**；安卓/发行版为手动触发。

---

## 第一部分 · 需求逐条理解（请核对，偏差请在答复里纠正）

### A. 客户端（android，Kotlin+Compose，miuix 0.9.3，minSdk **33**、targetSdk 37）

- **C1 发现页加载动画用 miuix，统一成待办那样**。
- **C2 伴侣历史状态页优化**；且 **miuix 下拉刷新动画太靠上，下滑时看不到**。
- **C3 状态同步还要更快**（实时性）；**对方息屏/亮屏要给一个静默通知**——不弹出、不响铃，但要推送出消息。
- **C4 信息同步时间间隔再减小**。
- **C5 求陪伴/求冷静/响铃找人/紧急找人 默认 7 秒**，且**对方取消可以随时关闭**——此前测试必须划掉后台才能停。
- **C6 相册初版**，前提是**先确认上传照片彻底可行**；**换掉安卓原生图片选择器**（太烂）。

### B. 服务端 / 后台

- **S1 后台前端与系统无关的多余内容全删；所有设置写标准；全部修好。**
- **S2 后台很不安全 → 仔细审查、提出问题、完善功能。**

### C. 额外要求

- **X1 全项目扫描**：安全 / 体验 / 实用 三方面并行。
- **X2 给流程 + 结构图 + 修复方案。**
- **X3 三个工作流**（尤其安卓）：**生成 release 签名密钥给你配置，让每次 APK 签名固定统一。**

> **我对 C5 的理解有歧义，是本轮最大返工风险点**：「默认 7 秒」可能是①响铃/振动只响 7 秒自动停；②按钮冷却 7 秒（**这个已经做了**，见 Q9）。我按①理解并在 Q9 请你确认。

---

## 第二部分 · 现状调查与根因（关键事实，均带 file:line）

### 【致命前提】相册的前置条件现在是坏的：头像/照片上传在生产环境必然 500

`avatar_handler.go:63` 调 `newVipsWorker`，`avatar_worker.go:68,116` 用 **外部 CLI `vipsheader`/`vipsthumbnail`**（libvips）。而运行镜像 `Dockerfile:26-28` 是 `alpine:3.20`，只装了 `ca-certificates tzdata wget`，**没有 libvips** → 每次上传头像都走 `mapAvatarError`（`avatar_handler.go:184`）默认分支返 500「头像处理失败」。
`avatar_worker.go:16` 的注释「依赖 CI 固定的 Ubuntu 24.04」是**过时假设**——CI 只是跑 `go test`（用 stubWorker），真实运行时在 alpine 容器里。

叠加的第二个必错点：`avatar_format.go:25-42` 的魔数白名单里**没有 JPEG**（只有 PNG/BMP/GIF/WebP/HEIF/AVIF），而客户端 `ProfileEditScreen.kt:137-139` 允许选 `image/jpeg`。**选 JPG 必报「不支持的图片格式」**。手机相册九成是 JPG → 这条比 vips 更常触发。

第三个不一致：`storage.go:58` `LocalStorage.urlPrefix` 还是旧 `/uploads`，而实际落盘与静态挂载是 `/upload/YYYY/MM/DD/`（`handlers.go:37-40`、`static.go:43-45`）。

> **结论：不先修这三点，相册做出来就是"选了图→转圈→失败"。所以本轮施工顺序必须是「先修上传链路 → 真机验证通 → 再做相册」，与你「确定上传彻底可行后再开始」的要求一致。**

### C1 发现页加载动画
`DiscoverScreen.kt:35,57` 已经是 miuix `CircularProgressIndicator(Modifier.padding(24.dp))`，与 `TodoScreen.kt:46,167` **完全一致**。真正的差异是：
- 发现页的 loading 是**假的**——`DiscoverScreen.kt:50-54` 一个 `delay(500)` 硬编码，没有任何真实请求；
- 发现页**没有下拉刷新**（`:55` 只传了 title，未传 `isRefreshing/onRefresh`）；
- 全 App 只有 3 处指示器（`TodoScreen.kt:167`/`DiscoverScreen.kt:57`/`HistoryScreen.kt:69`），其余都是**纯文字态**："上传中…"(`ProfileEditScreen.kt:200`)、"保存中…"(`:266`)、`LoginScreen.kt:162`、`RegisterScreen.kt:240`、`BindScreen.kt:143,182`、"处理中…"(`WallpaperScreen.kt:167`)；**主页 `NowScreen` 连 loading 态和下拉刷新都没有**。
→ 所以这条的实质是「**把剩下的文字态也换成 miuix 指示器 + 给发现页/主页补真实加载与下拉刷新**」。

### C2 下拉刷新指示器被顶栏吃掉 —— 根因是一个参数写死为 0
`KernelScreen.kt:112` 给 miuix `PullToRefresh` 传了 `contentPadding = PaddingValues(0.dp)`。miuix 0.9.3 的 RefreshHeader 靠 `contentPadding.calculateTopPadding()` 把自己下移；传 0 就贴在容器 y=0。
而 `KernelScreen.kt:99-117` 的 `Column` 从 Scaffold 内容区顶部起算（毛玻璃 TopAppBar 是**悬浮覆盖**的），顶栏高度只补给了 LazyColumn 的 `contentPadding.top`（`:91`），**没补给 PullToRefresh** → 指示器被顶栏盖住。
待办页看起来正常，只是因为它传了 `header`（`TodoScreen.kt:151`），`KernelScreen.kt:100-104` 先用 `padding(top=innerPadding)` 放搜索框，把整个 PullToRefresh 挤到了顶栏之下。**历史页 `header==null` → 裸露在顶栏底下。**

KernelSU 的正解（`ModuleMiuix.kt:530-545`）就是把顶栏高度传进去：
```kotlin
val contentPadding = PaddingValues(
    top = innerPadding.calculateTopPadding() + 6.dp,   // ← 我们写了 0.dp
    start = …, end = …, bottom = bottomInnerPadding,
)
PullToRefresh(…, contentPadding = contentPadding) { … }
```

历史页其他短板：`HistoryScreen.kt:37-40` `runCatching` 吞异常 → 失败与空态共用一句「当日暂无记录」(`:97`) 且无重试；`:38` 写死 `limit=100, offset=0` 无分页；`:102` `key={index,_->index}` 下标当 key，换日期后状态错位；只能 ±1 天点着走(`:61-63`) 且**能翻到未来空日**；`Models.kt:108-109` `timeLabel` 每次访问 new 一个 `SimpleDateFormat`（滚动时每帧多次）；曲线图无坐标轴/时间刻度。

### C3/C4 同步"更快" —— 瓶颈**不在间隔**，在三个断链

现有全部时间常量（已核对）：

| 项 | 值 | 位置 |
|---|---|---|
| WS 心跳 ping | 20s | `StatusSyncManager.kt:67` |
| WS 重连退避 | 1s 起翻倍，封顶 **16s**，**无 jitter** | `WsReconnectPolicy.kt:5,11-12` |
| 服务端判死 | 90s | `hub.go:94` |
| 在线态 TTL | 60s | `store.go:504` |
| 前台资料轮询 | **30s** | `LinxiApp.kt:243` |
| 绑定等待轮询 | 3s | `BindScreen.kt:86` |
| 历史落库粒度 | 5min | `hub.go:147` |

**断链①（最致命，也是你"感觉不实时"的真凶）**：`NowScreen.kt:52` 直接读 `DeviceStatusHolder.partner`，而它是 `DeviceStatus.kt:36` 的普通 `@Volatile` 字段，**不是 Compose State**。→ 服务端 WS 推送到了、`StatusSyncManager.kt:180-198` 也解析了，**主页 UI 却不会重组**。现在主页能变，纯靠 30s 轮询顺带触发的其他重组"蹭刷"。**把间隔从 30s 压到 5s 也只是让"蹭刷"更频繁，治不了本。**

**断链②**：`ScreenStateReceiver.kt:24` 亮/息屏立即 `pushNow()`，但它只改了 `Holder.screenOn`（`:22-23`）**没有重新采集**，而 `pushNow` 发的是 `Holder.current` 的**上一次快照** → **推出去的 `screen_on` 还是旧值**（`StatusCollector.kt:140` 才读 Holder.screenOn）。所以"息屏了对方不知道"是数据错，不是慢。

**断链③**：`ScreenStateReceiver` 只能动态注册，注册点在 `StatusForegroundService.kt:114-121`。**前台服务没起/被系统杀 → 息屏事件彻底丢失**。而 Android 15+ 对 `dataSync` 型前台服务有 6h/24h 上限，超时 `stopSelf` 后**没有任何重启路径**；`BootReceiver` 因未声明 `directBootAware` 且 `LOCKED_BOOT_COMPLETED` 不投递，**基本拉不起来**；`NetworkReceiver` 是**100% 死代码**（`AndroidManifest.xml:76` 静态注册 `CONNECTIVITY_CHANGE`，API24+ 不再投递给清单接收器，代码里也无 `NetworkCallback`）。
另外 `StatusForegroundService.kt:48,156` 的 `ACTION_SYNC`（注释写"5 分钟定时采集"）**全仓无任何 AlarmManager/WorkManager 调度它** → 周期采集根本不存在，`hub.go:146` 的"客户端 5min 上报天然对齐"假设不成立。

**"静默通知"现状**：只有 3 个 channel（全在 `StatusForegroundService.kt:202-217`）——`status_card`(LOW，常驻卡)、`status_event`(HIGH)、`status_ring`(HIGH)。**全仓 0 处 `setSound(null)`** → 连 LOW 都用系统默认音。**没有任何"息屏/亮屏"通知**。且 channel 在另 3 处被重复创建（`StatusSyncManager.kt:238-240,261-263`、`TodoAlarmReceiver.kt:47-49`）且属性不一致——`createNotificationChannel` 对已存在 channel 改不了 importance，谁先创建谁决定行为。
**无任何第三方推送**（fcm/jpush 全 0 命中）；`push.go:45-57` 是纯占位（只 `log.Printf`，SDK 调用全是注释），`config.example.yaml:14` `provider: none`。→ 离线送达只靠 `hub.go:221` 的**进程内存**队列 `PushEventQ`，**服务端一重启全丢**，且队列无上限无 TTL（可被打到 OOM）。

### C5 响铃停不下来 —— 五层原因叠加，"划后台才能停"是结构性必然

先厘清：**现在的"7 秒"是按钮 UI 冷却，跟响铃时长无关。** 客户端 `NowScreen.kt:40` `INTERACTION_COOLDOWN_MS = 7000L`（三个 flag 各自 delay 后复位，`:65-67`），服务端 `store.go:533-534` 7s/1 次强制。**响铃走的是另一套：600s/3 次**（`store.go:515-516`）——第 4 次响铃在 10 分钟内被 `hub.go:194-197` **静默丢弃且不回错误码**，发送方 UI 仍显示"已发送"。

响铃停不下来的五层：
1. **无任何自动停止定时器**。`RingHelper.kt:57,135` 注释写着"循环 10 秒""10 秒后自动停止避免无限响铃"，但**全仓不存在针对 `forceRing` 的定时器**（`postDelayed` 只出现在 `:125` 的 `todoStrongRemind` 8s，那条路径 `isLooping=false`）。→ `MediaPlayer.isLooping=true`(`:64`) + `PARTIAL_WAKE_LOCK`(`:65`) **无限响**。
2. **振动永不停**。`:95-96` `createWaveform(…, 0)` repeat=0 = 无限循环；而 `RingController.stop()`(`:139-143`) 只 `stop/release` MediaPlayer，**全仓 0 处 `Vibrator.cancel()`**。
3. **唯一停止入口是 RingActivity，而它可能根本不出现**。`RingActivity.kt:19-22`「我知道了」/`:25-28` onDestroy 是唯一停止点，靠全屏 Intent 拉起（`RingHelper.kt:75-83`）；但 `:72-74` 整个通知块被 `POST_NOTIFICATIONS` 未授权就跳过 → **铃在响、UI 不出现**。且 `launchMode=singleInstance` + `excludeFromRecents=true`（Manifest:102-105），任务列表里也看不到。
4. **没有远端取消**。`ring_cancel`/`stopRing` 全仓 0 命中，`models.go:136-152` 消息常量表无 cancel 类型，`hub.go` 无分支。→ **发送方无法撤回，接收方无法通知对端"我关了"**。
5. **系统副作用不恢复**：`:45-46` 把闹钟流音量拉满、`:53-55` 把勿扰切成 `INTERRUPTION_FILTER_ALARMS`，**二者都不还原**。

外加一个必崩：`RingHelper.kt:125` 用了废弃的无参 `Handler()`，而 `todoStrongRemind` 由 `StatusSyncManager.kt:227` 在 **OkHttp WS 读线程**调用——该线程无 Looper → **抛 RuntimeException 崩溃**。

还有：`RingHelper.kt:89` 与 `StatusSyncManager.kt:264` 用了**同一个通知 id 10002**，后者会覆盖前者的全屏常驻通知。以及三个互动按钮走 WS 上行（`StatusSyncManager.kt:158-168`），`ws==null` 时**静默什么都不做**，但 UI 立刻显示"已发送"（`NowScreen.kt:104-106`）→ **离线假成功**。服务端那三个 HTTP 端点（`main.go:148-150`→`handlers.go:820-836`）**客户端从未调用**，是死路径。

### C6 图片选择器 & 相册
- 头像用的是 `ActivityResultContracts.OpenDocument()`（`ProfileEditScreen.kt:106`）——**就是你说的"丑陋原生"那个 SAF 文件浏览器**。
- 有趣的是壁纸页**已经用了现代 Photo Picker** `PickVisualMedia()`（`WallpaperScreen.kt:63-64,129-130`），现成参照。
- 客户端**无压缩、无 EXIF 旋转**（`:111-115` 只是 `copyTo` 原样上传），扩展名靠 MIME 猜，猜不到写 `"img"`。
- **全项目没有 Coil/Glide 任何图片库**（`libs.versions.toml:21-42` 核对）。头像显示是自己撸的 `NetworkAvatar`（`ProfileEditScreen.kt:277-298`）：`produceState` **默认主线程** + `BitmapFactory.decodeByteArray`(`:283`) **主线程解码**、无采样、无缓存、每次 url 变化重新下载。相册要铺缩略图网格，这条必须先解决。
- 发现页三卡全是占位：`DiscoverScreen.kt:61-65` → `LinxiApp.kt:195-197` → 三个都渲染同一个 `DiscoverPlaceholderScreen`（`DiscoverScreen.kt:104-123`）。导航是 `enum + var screen` 手写状态机（`LinxiApp.kt:342`），**无法传参** → 相册要传 albumId/photoIndex 得先扩展。
- 服务端 `server/sql/schema.sql` 14 张表（user/pair/todo/status_history/diary/diary_image/push_token/app_setting/admin_user/app_version/admin_audit_log/notify_template/notify_record/request_log），**无任何相册表**。`diary_image`(`:87-93`) 只有 `id/diary_id/url/sort_no`，挂在日记下、无 pair_id、无尺寸/拍摄时间/上传者，不能直接当相册用。

---

## 第三部分 · 结构图与流程图

### 图 1 · 上传链路现状 vs 修复后（相册前提）

```
【现状 · 两条不一致的链路，且都有坑】
头像:  选图(OpenDocument-丑) ──原样copyTo──> POST /profile/avatar
        │                                      ├ 15MB 上限
        │                                      ├ 魔数白名单【无JPEG】❌ 选JPG必失败
        │                                      └ newVipsWorker → vipsheader/vipsthumbnail
        │                                                         └ alpine 无 libvips ❌ 必 500
日记图: 无调用方(死)      ──> POST /diaries/images ─ 10MB ─ 仅按扩展名(无魔数)⚠
显示:  自撸 NetworkAvatar → 主线程 decode、无缓存、无采样 ⚠

【修复后 · 单一链路，纯 Go 解码，无外部依赖】
选图(新选择器) ─客户端预处理─> POST /api/v1/media (统一入口)
   │  ├ 读 EXIF 方向并旋正                 │  ├ 大小/张数限流
   │  ├ 长边压到 2048、JPEG q85            │  ├ 魔数白名单【含JPEG】✅
   │  └ 生成本地预览                       │  ├ 纯 Go 解码(image/jpeg,png + x/image/webp)
   │                                       │  ├ 生成 512 缩略图(nfnt/golang.org/x/image draw)
   └─────────────────────────────────────> └ 落盘 /upload/YYYY/MM/DD/<rand24>.<ext>
显示: Coil 3 (内存+磁盘缓存、自动采样、缩略图优先) ✅
```

### 图 2 · 状态同步：现状断链 vs 修复后

```
【现状】
息屏事件 ─ScreenStateReceiver(仅前台服务在跑时注册)❌─> pushNow()
                                                        └ 发的是【旧快照】❌ screen_on 没更新
WS ──partner_status──> StatusSyncManager 解析 ✅ ──> DeviceStatusHolder.partner (@Volatile 普通字段)
                                                        └ NowScreen 直接读 ❌ 不是 State → UI 不重组
兜底: 30s 轮询 /pair/status ──> 顺带触发重组"蹭刷"（治不了本）

【修复后】
息屏/亮屏 ─ScreenStateReceiver─> refreshNow()重采集 ✅ ─> pushNow() 立即上报
                                    ↑ 前台服务 + 自愈(定时重启检查) + directBootAware
WS ──partner_status──> PartnerStateStore(MutableStateFlow) ──collectAsState──> NowScreen 实时重组 ✅
                              │
                              └─> 静默通知 channel(status_quiet, IMPORTANCE_LOW + setSound(null)
                                   + setVibrate(null) + 不 setFullScreenIntent) → 只进通知栏，不弹不响
兜底: 前台 10s / 后台 60s / 息屏 5min 分档轮询（省电）
```

### 图 3 · 响铃可取消（新增 ring_cancel 双向事件）

```
A 点「响铃找人」                                   B 收到
  │ WS: ring_request{id, ttl:7s}                    │ RingHelper.forceRing(id)
  ├──────────────────> hub 转发 ──────────────────> ├ 起 7s 自动停定时器 ✅
  │                                                 ├ 通知(status_ring) + 全屏 RingActivity
  │ UI 进入"响铃中 7s"倒计时，出现【撤回】按钮        │ 通知上挂【停止】Action ✅(即便 Activity 没起来)
  │                                                 │
  ├─【撤回】WS: ring_cancel{id} ──> hub ──────────> ├ 收到 → stopAll(id)：MediaPlayer.stop
  │                                                 │        + Vibrator.cancel() ✅
  │                                                 │        + 恢复音量/勿扰 ✅
  │                                                 │        + 取消通知 + finish RingActivity
  └<─ ring_stopped{id, by:B} <── hub <──────────────┤ B 点【停止】也回一条，A 侧 UI 显示"对方已知悉"
7s 到点：两端各自本地结束（不依赖网络）✅
```

### 图 4 · 相册初版结构（表 / 接口 / 页面）

```
表(新增 2 张，沿用现有幂等建表机制 schema.sql + __NEXT_SCHEMA__ 锚点)
┌─ album ────────────────────────────────┐   ┌─ photo ─────────────────────────────────┐
│ id INTEGER PK                          │   │ id INTEGER PK                            │
│ pair_id  ← 归属，所有查询必带           │◄──┤ album_id (0=未归类)                      │
│ name / cover_photo_id                  │   │ pair_id  ← 冗余一份，越权校验用           │
│ created_by / created_at / updated_at   │   │ uploader_id                              │
│ status (1正常 2删除)                    │   │ url / thumb_url                          │
└────────────────────────────────────────┘   │ width / height / size_bytes / mime        │
                                             │ taken_at (EXIF) / created_at              │
                                             │ caption / status(1正常 2回收站)           │
                                             └─────────────────────────────────────────┘
接口(/api/v1，全部 JWTAuth + pair 归属校验)
  GET    /albums                 列表(含封面缩略图、张数)
  POST   /albums                 建相册            PUT /albums/:id   改名/换封面
  DELETE /albums/:id             删相册(软删，照片移入未归类)
  GET    /albums/:id/photos      分页(page/size，按 taken_at desc)
  POST   /media                  统一上传(见图1)，返回 {url,thumb_url,w,h,taken_at}
  POST   /albums/:id/photos      把已上传的 media 挂入相册(支持批量)
  DELETE /photos/:id             软删入回收站      POST /photos/:id/restore
  GET    /photos/:id             单张详情
页面(android)
  发现 → 相册 AlbumListScreen(网格2列，封面+名称+张数)
           └→ AlbumDetailScreen(网格3列缩略图 + 顶栏"选择/上传")
                └→ PhotoViewerScreen(HorizontalPager + 双指缩放 + 删除/详情)
           └→ PhotoPickerScreen(自研 miuix 选择器，见 Q12)
```

### 图 5 · 后台安全加固后的鉴权链

```
浏览器 ──Authorization: <token>──> Gin
  │                                 ├ ① 安全响应头中间件(新增) CSP/nosniff/XFO/Referrer-Policy/HSTS
  │                                 ├ ② SetTrustedProxies(新增) 让 ClientIP 可信 → 限流/审计才有意义
  │                                 ├ ③ AdminAuth(改): 不再信 token 里的 role/mc
  │                                 │     └ 实时读库: 用户存在? status=1? token_ver 匹配? must_change?
  │                                 ├ ④ RBAC(补): 敏感路由全部 requireSuper
  │                                 │     settings/admins/notify/upload/app-versions/删除类
  │                                 └ ⑤ 审计(补): 设置变更记录 diff，版本/模板操作补 AddAudit
私密图片 ──> /upload/* 现在【完全公开】❌ ──> 改为 /media/:id 鉴权代理(校验 JWT + pair 归属) ✅
                                             且 request_log 跳过 /upload 与 /media（现在会把
                                             私密照片 URL 写进日志给任何 admin 看见）❌→✅
```

---

## 第四部分 · 逐条修复方案

### C1 加载动画统一（改动小）
1. `KernelScreen` 新增可选 `loading: Boolean`，统一在列表首位渲染 miuix `CircularProgressIndicator`，各页不再各写一遍。
2. 剩余 7 处文字态（`ProfileEditScreen.kt:200,266`、`LoginScreen.kt:162`、`RegisterScreen.kt:240`、`BindScreen.kt:143,182`、`WallpaperScreen.kt:167`）改为 **按钮内嵌小号 miuix 指示器 + 文案**（新增 `LxButton` 的 `loading` 参数，统一按压/禁用态）。
3. 发现页删掉 `delay(500)` 假 loading，接真实数据（相册张数/最近更新），补 `isRefreshing/onRefresh`。
4. 主页 `NowScreen` 补 loading 骨架 + 下拉刷新（拉 `/pair/status` + 伴侣状态）。

### C2 历史页 + 刷新指示器
1. **根治指示器**：`KernelScreen.kt:105-113` 的 `contentPadding` 改为 `PaddingValues(top = innerPadding.calculateTopPadding() + 6.dp, bottom = …)`，并对 `header != null` 分支不再重复补 padding（避免双倍）。照 KernelSU `ModuleMiuix.kt:530-545` 写法。**Todo/History/新增的发现页与主页一次性全好。**
2. 历史页优化：① 加**日期选择器**（miuix DatePicker/BottomSheet）+ 禁用未来日期；② 时间线**分页加载更多**（limit 50 + offset，滚到底自动拉）；③ 失败态 + **重试按钮**（不再与空态混用文案）；④ `key` 改用 `h.id`（或 `ts+field` 稳定组合）；⑤ `timeLabel` 的 `SimpleDateFormat` 提到 `remember` 外/伴生对象；⑥ 卡片重排为"时间 + 图标行 + 电量条"，配 miuix 分组标题按小时分段；⑦ 电量曲线补时间刻度与当前值标注；⑧ 空态给引导文案而非冷冰冰一句。

### C3/C4 同步更快 + 静默通知
**先治断链，再谈间隔**：
1. **伴侣状态改响应式**（本轮最关键的体验改动）：新增 `PartnerStateStore`（`MutableStateFlow<DeviceStatus>`），WS/轮询统一写入，`NowScreen` 用 `collectAsStateWithLifecycle()`。→ 服务端推到即刷新，**这才是"实时"**。
2. **修息屏上报错值**：`ScreenStateReceiver` 改为先 `refreshNow()` 重采集再 `pushNow()`（或 `pushNow` 内部先合并 Holder 最新字段）。
3. **可靠性**：`NetworkReceiver` 改 `ConnectivityManager.NetworkCallback` 动态注册（现在 100% 无效），网络恢复立即重连 + 重推；`BootReceiver` 补 `directBootAware` 或改用 `BOOT_COMPLETED`；前台服务加自愈（`ACTION_SYNC` 接上 AlarmManager/WorkManager 周期心跳，同时兼作服务存活检查）；`WsReconnectPolicy` 加 ±20% jitter（避免双端惊群）。
4. **间隔分档**（引入 `lifecycle-process` 观察前后台，现在完全没有）：

| 档位 | 轮询间隔 | 说明 |
|---|---|---|
| 前台可见 | **10s**（原 30s） | 配合 WS，主要作兜底 |
| 后台 | 60s | 省电 |
| 息屏 | 5min | 只保状态不掉线 |
| WS 心跳 | **15s**（原 20s） | 服务端判死 90s→**45s**，更快发现死连接 |

5. **静默通知**：新增 channel `status_quiet`（`IMPORTANCE_LOW` + `setSound(null)` + `setVibrate(null)` + `enableVibration(false)`，**不设 fullScreenIntent、不 setDefaults**）→ 只落通知栏，不弹 heads-up、不响铃。伴侣息屏/亮屏、上线/下线走这条。同时把 3 处重复 channel 创建收敛到 `NotificationChannels` 单一入口（消除属性竞态），并给已有 channel 补 `setSound(null)`（`status_card` 常驻卡不该响）。
6. **防刷屏**：同一事件类型 60s 内合并（`NotificationUpdatePolicy` 已有类似策略可复用），固定通知 id 覆盖更新而非堆叠；息屏/亮屏通知**默认开关放在设置页**（见 Q7）。
7. **离线可靠性**：`hub.go:221` 事件队列加长度上限（每人 100 条）+ TTL（24h）；`ring/comfort/calm` 超频改为**回错误给发送方**（现在 `hub.go:194-197` 静默丢弃，UI 假成功）；`sendEvent` 在 `ws==null` 时**回失败**，UI 不再假"已发送"。

### C5 互动 7 秒 + 随时可关
1. **响铃 7s 自动停**：`RingHelper` 引入 `Handler(Looper.getMainLooper())`（顺手修 `:125` 无参 `Handler()` 在 WS 读线程必崩）+ 7s `postDelayed(stopAll)`；`stopAll` 里补 `Vibrator.cancel()`、恢复音量与勿扰、取消通知、finish RingActivity。振动 waveform 改为**有限次**（repeat=-1）双保险。
2. **通知上挂【停止】Action**：即便 `RingActivity` 因权限未起来，接收方也能在通知栏一键停（并解决 `10002` 通知 id 冲突）。
3. **新增双向取消事件**：`ring_cancel`（发送方撤回）/ `ring_stopped`（接收方已关，回执给发送方）。`models.go` 加常量、`hub.go` 加转发分支、客户端 `WsEventRouter` 加分支。求陪伴/求冷静同理支持撤回（它们不响铃，但要能撤下通知）。
4. **发送方 UI**：7s 倒计时 + 【撤回】按钮（替代现在只有禁用态）；服务端超频**回明确错误**（"对方 10 分钟内已被响铃 3 次"），不再静默。
5. **"紧急找人"**：你需求里列了 4 个，代码只有 3 个（求陪伴/求冷静/响铃找人）。见 Q10 确认是否新增"紧急找人"（更强：绕过勿扰 + 全屏 + 需对方确认，且 7s 后转为常驻通知不自动消失）。
6. 三个互动统一走 WS，把 `handlers.go:820-836` 三个死 HTTP 端点**改为 WS 不可用时的降级通道**（而不是删掉），提升离线可达性。

### C6 上传修复 + 图片选择器 + 相册初版
**Step 1 · 先让上传彻底可行（相册前提，独立验证）**
1. **去掉 libvips 外部依赖**，改**纯 Go 解码**：`image/jpeg`+`image/png`+`golang.org/x/image/webp`(解码)+`golang.org/x/image/draw`(缩放)。理由：与"单容器纯 Go SQLite"的既定架构一致，镜像不用装 libvips（省 ~40MB 且不再有 CLI fork 风险）。**代价**：放弃 HEIF/AVIF 与动图头像（Q11 确认）。
2. **魔数白名单补 JPEG**（`avatar_format.go`），并让日记图/相册也走魔数校验（现在只看扩展名）。
3. 统一 `storage.go:58` 的 `urlPrefix` 为 `/upload`，消除 `/uploads` 歧义（保留旧路径只读兼容）。
4. 客户端补 **EXIF 旋正 + 长边压到 2048 + JPEG q85**（`BitmapFactory` + `ExifInterface`，不引额外库），上传前显示预览与进度。
5. **引入 Coil 3**（`coil-compose` + `coil-network-okhttp`，复用现有 OkHttp 与鉴权头）：内存+磁盘缓存、自动采样、缩略图优先。替换自撸 `NetworkAvatar`（顺带修主线程解码）。
6. 加**服务端上传配额**（每人每天张数/总字节）与频率限流；`uploads/tmp` 加启动清理与定时清理。
7. **验证方式**：先只做 Step 1 + 头像换成新选择器 → 你真机验一次"选 JPG 头像能成"→ 再进 Step 2。

**Step 2 · 图片选择器**（推荐自研 miuix 网格，见 Q12）
自研 `PhotoPickerScreen`：`MediaStore` 查询（`READ_MEDIA_IMAGES`）→ miuix 风格 3 列网格 + 多选计数气泡 + 底部"已选 N 张/完成" + 按月份分组 + 相机入口。与全 App miuix 皮肤一致，且能做多选/预览/裁剪。**风险**：Android 14+ 的"仅选择部分照片"权限（`READ_MEDIA_VISUAL_USER_SELECTED`）需要额外处理，我会加"权限不足时一键切换系统 Photo Picker"的兜底。

**Step 3 · 相册初版**（表/接口/页面见图 4）
1. 导航状态机扩展为支持参数（`Screen` 从 enum 改 sealed class，或加 `screenArg`），否则相册进不了详情。
2. 页面：相册列表（2 列封面网格）→ 相册详情（3 列缩略图网格 + 多选删除）→ 大图查看（Pager + 缩放 + 左右滑）。
3. 上传流：选图 → 本地预览与压缩 → 逐张上传带进度 → 失败可重试单张 → 完成后 WS 通知伴侣（走静默通知）。
4. **隐私**：私密照片不能靠"随机文件名 + 完全公开目录"（见安全 B-3/B-5）。方案：`/media/:id` 鉴权代理 + `request_log` 跳过图片路径。见 Q13。

### S1 后台前端清理 + 设置写标准
**该删的（已核实全部未被引用）**
- `views/outside/Iframe.vue` + `router/core/IframeRouteManager.ts`（无路由引用，能力已废）
- **26 个未引用组件**：art-basic-banner / card-banner / back-to-top / bar·donut·line-chart-card / data-list-card / image-card / progress-card / stats-card / timeline-list-card / bar·dual-bar·h-bar·k-line·radar·ring·scatter-chart / button-more / button-table / excel-export / excel-import / form / search-bar / wang-editor / cutter-img / video-player / screen-lock / global-search / fireworks-effect（`echarts` 与 `ArtLineChart` **保留**，仪表盘真在用真数据）
- **假数据**：`art-notification/index.vue:180-240`（"冷月呆呆给你发了一条消息"等）+ 它引用的 6 张 avatar 图；`config/modules/festival.ts:32-51` 圣诞示例
- **模板痕迹**：`index.html:4,9` 标题仍是 `Art Design Pro`；`utils/sys/console.ts:1-13` **生产控制台仍打原作者 GitHub + QQ 群**（`main.ts:8` 无条件引入）；`asyncRoutes.ts:1` 的 `artd.pro` 链接；`art-excel-export/index.vue:260` 作者写死；`package.json:2` name 仍 `art-design-pro`；`@author Art Design Pro Team` 出现在 **77 个文件**（统一改成本项目署名）
- `utils/socket/index.ts`（365 行 WS 封装，零引用）
- **51 个未引用图片**（`assets/images/` 共 71 个用 20 个）：3d/ avatar/ cover/ draw/ safeguard/ user/ login/ 整目录
- **死依赖**：`xlsx`、`@wangeditor/editor(+-for-vue)`、`xgplayer`、`vue-img-cutter`、`file-saver`、`qrcode.vue`、`highlight.js`；`vite.config.ts:120-134` 的 `optimizeDeps.include` 同步清理
- `vite.config.ts:112` `vueDevTools()` 无条件注入 → 改为仅 dev
- `utils/constants/links.ts` 6 个键全指向同一个 `love.lxii.cc`（假多样性）+ BILIBILI 个人空间外链；`ArtUserMenu.vue:40-47` 的"文档/GitHub"外链 → 精简
- `login/index.vue:115` 硬编码预填 `username:'admin'`、`change-credentials/index.vue:18` placeholder 写着默认密码 `123456` → 删（等于告诉攻击者账号）

**设置项"写标准"（逐项核过 app_setting 与服务端读取方）**

| 设置项 | 现状 | 处置 |
|---|---|---|
| 站点名称/LOGO | 生效 | 补校验（LOGO 必须是合法 URL/上传） |
| 站点描述 | 存了**没人读** | 接到后台页脚 + `/api/v1` 站点信息 |
| **site.url** | **服务端真在用**（`handlers.go:51-56` 决定图片是否返回绝对 URL），**页面上却没有这一项** | **补上**（最实质的缺口） |
| 存储驱动 | disabled 死值 local | 保留只读展示，去掉"可选"错觉 |
| **本地目录 storage.local_dir** | **假设置**（服务端从不读，uploadDir 来自 YAML） | 要么接通、要么删（我选删，见 Q17） |
| SMTP 六项 | 生效，但**零校验、无测试发信** | 补必填/端口数字/发件人邮箱格式校验 + **"测试发信"按钮**（新增 `POST /api/admin/settings/smtp-test`） |
| **推送服务商 push.provider** | **假设置**（服务端读 YAML `main.go:105`，不读 app_setting） | 删（`push.go` 本身是占位） |
| OSS/COS/Kodo 5 个键 | 前端已隐藏，**服务端白名单仍接受写入**，且 `GET /settings` **明文回吐 oss_access_key/oss_secret** | 服务端一并删键 + 脱敏 |

另：整个表单**没有 `:rules`/`validate()`**，`handleSave` **无 catch** → 补 ElForm 校验 + 失败提示。

**各页面缺陷修复**（简明）
- 分页全是真分页（已核实，无假分页、无假导出）✅ 不用动
- `pair-manage`/`content-audit` **没有搜索框**，而服务端支持 keyword → 补
- `content-audit` 与 `todo-manage` **功能重复且 status 映射互相矛盾**（`todo-manage:47-51` 待办/完成/删除 vs `todo-table.vue:26-30` 未开始/进行中/已完成）→ 合并为一页，统一映射
- 日记删除是**物理删除**（`store.go:391-397`）、待办是软删 → 统一软删 + 明确警示
- `notify` 的 **target 字段是假的**：服务端 `AllUserIDs()` 全量广播，`req.Target` 只写记录不影响投递，返回的 `sent` 是全站人数不是真实到达 → 要么实现按用户/按 pair 定向，要么删字段并写明"全站广播"（我选实现定向，见 Q18）；模板**只能增改不能删** → 补删除
- `audit-log` **无任何筛选**且 `detail` 基本为空（如 `admin.go:782` `AddAudit(aid,"","update_settings","",ip)`）→ 补时间/操作人/动作筛选 + **记录变更 diff**
- `admin-manage` 只有列表+新增：**不能改角色/禁用/删除/重置密码、不分页** → 补齐（离职无法回收账号是真实风险）
- `app-version` **version_code 重复不校验** → 补
- 所有时间列**直出服务端字符串无格式化**、表格无自定义空态 → 统一 formatter + 空态
- **i18n 半残**：框架文案 158 键中英齐全，但 11 个业务页的 label/表头/提示**全中文硬编码** → 见 Q19（全量补 key 工作量不小）

---

## 第五部分 · 安全审查（S2）· 按危险等级

> 上一轮（0813）已修的不再复述（SEC-1 越权 pair_id / SEC-2 首登改密 / SEC-3 WS Origin / SEC-5 上传白名单 / SEC-7 登录限流 / SEC-8 RBAC 雏形 / 超管随机口令）。以下是**本轮新查出的**。

### 高危（建议全修）

| 编号 | 问题 | file:line | 攻击场景 |
|---|---|---|---|
| **H1** | **私密照片链路三重泄露**：`/upload` 与 `/uploads` 完全公开无鉴权（后者还映射 uploadDir 根，把 APK 与全部历史头像一并公开）；`netlog.go:68-70` skip 列表**漏了 `/upload`** → 每张照片完整 URL 写进 request_log，**任何 admin 在"网络日志"页直接点开情侣私密相册**；全站无 `Referrer-Policy` → URL 随外链外泄 | `static.go:43-45`、`handlers.go:952-986`、`netlog.go:68-70,83` | 相册上线后这是产品最致命隐私事故面 |
| **H2** | **后台 RBAC 形同虚设**：只有 `POST /admins` 与 `PUT /settings` 有 `requireSuper`。**无保护**的包括 `GET /settings`（**明文回吐 `oss_access_key`/`oss_secret`**，只脱敏了 smtp.password）、`GET /admins`、`POST /notify`（全站群发）、`POST /upload`（300MB 落盘）、`app-versions` 增删改、`DELETE /todos|/diaries`、`PUT /users/:id/status`、`POST /pairs/:id/unbind` | `admin.go:964-1002`、`751-760` | 普通 admin = 事实超管 |
| **H3** | **AdminAuth 不校验 status**（只查存在性），**且 role/must_change 取自 token 不读库** | `admin.go:83-92` | 管理员被禁用后旧 token 仍可全量操作整月 |
| **H4** | **首登强制改密可绕过**：`Password` 可为空，只带 `old_password` 调一次即 `must_change=0`，初始随机口令继续有效 | `admin.go:227-278`、`167` | 一行 curl 绕开强制改密 |
| **H5** | **绑定码可爆破**：6 位纯数字、TTL 1h、**无尝试次数限制、无限流** | `handlers.go:262-296`、`store.go:146-153` | 脚本爆破绑上陌生人挂起的 pair → 读其日记/待办/**状态历史（含位置/WiFi/使用应用）** |
| **H6** | **邮箱验证码可爆破**：6 位数字、10min、校验**无尝试上限** | `account.go:158,204-207` | 占用他人邮箱注册 |
| **H7** | **日志清理 SQL 用的是 MySQL 语法**：`NOW() - INTERVAL ? DAY` 在 SQLite 上**永久报错静默失败** → request_log 无限增长打满磁盘 | `netlog.go:105` | 某天全站突然挂掉 |
| **H8** | **全站零安全响应头**（仅 `/upload*` 有 nosniff）：无 CSP / X-Frame-Options / Referrer-Policy / HSTS | 全仓，`static.go:29-40` 是唯一响应头处 | 点击劫持、XSS 无缓解、放大 H1 |
| **H9** | **初始超管随机口令明文写进日志**（`slog.Warn` 带 password），`docker logs`/宝塔面板长期可见 | `admin.go:123-141` | 看日志即接管后台 |

### 中危（建议全修）

| 编号 | 问题 | file:line |
|---|---|---|
| M1 | **JWT 30 天不可撤销**（admin 与 user 都是 720h）：claims 无 `jti`/`token_ver`，改密/禁用/删号后旧 token 照用 | `admin.go:32-41`、`handlers.go:150-157` |
| M2 | **用户 JWTAuth 不校验 status**，登录也不查 → **后台"封禁用户"形同虚设** | `handlers.go:176-196`、`store.go:103-108` |
| M3 | 后台登录限流**只在进程内存 + 只按账号不按 IP** → 重启清零、换账号名绕过 | `admin.go:191-197`、`memstore.go:129-140` |
| M4 | 登录滑块验证**只是前端摆设**，服务端不校验 | `login/index.vue:45,133-137` |
| M5 | 密码强度仅 `len>=6`，`123456` 可过；`role` 字段无白名单（可写任意字符串） | `admin.go:257-262,672-675` |
| M6 | **WS 无 `SetReadLimit`**（单连接超大帧吃满内存）、无全局连接上限、上行 `status_update` 无限频（每条写 SQLite）；token 走 URL query（落 Nginx 日志/浏览器历史）；`/ws` 不在 AppKeyGuard 范围 | `main.go:163-175`、`hub.go:68-123` |
| M7 | 离线事件队列**内存无上限无 TTL** → 持续触发即 OOM；且服务端重启全丢 | `hub.go:221`、`memstore.go:76-80` |
| M8 | **无限流端点**：注册（可刷满 SQLite）、上传（单账号循环上传打满磁盘）、`/pair/bind`、`/app/latest`；`send-code` 仅 60s/邮箱冷却，**换邮箱即可无限发**（SMTP 额度耗尽/被当垃圾邮件源） | `handlers.go:115-118`、`account.go:140-171` |
| M9 | 容器以 **root** 运行 | `Dockerfile:26-35` |
| M10 | **无 `SetTrustedProxies`** → `X-Forwarded-For` 可伪造，污染审计 IP 并绕过按 IP 限流 | `main.go` |
| M11 | 无 `gin.SetMode(release)`（全仓无 `GIN_MODE`）；`fail(c,400,1009,err.Error())` 把内部错误原样吐给客户端 | `handlers.go:292-296` |
| M12 | `AddDiaryImages` 直接存客户端传入的任意 URL 字符串（可注入外部图/`javascript:`） | `store.go:363` |
| M13 | SQLite 文件权限默认 0644、**无任何备份策略**（情侣日记全在单文件，误删即永久丢失） | `main.go:209`、`storage.go:33` |
| M14 | `admin/.env` **已提交进仓库**且含 `VITE_LOCK_ENCRYPT_KEY=s3cur3k3y4adpro`（锁屏加密密钥公开=锁屏可解） | `admin/.env` |

### 低危 / 卫生

- L1 `go.mod` 仍挂弃用的 mysql/redis/miniredis/sqlmock（Dockerfile `go mod tidy` 会剔除但源码树仍在）；Go 1.22 / gin 1.10 / x-net 0.25 均为 2024 上半年版本，**期间有安全修复（具体 CVE 未联网核对，标待确认）**
- L2 `admin/package.json` 的 `xlsx ^0.18.5`（npm 线已停更）随死依赖一并删除即解
- L3 `SetVersionStatus`/`DeleteVersion`/`UpsertTemplate`/`GetSettings` **无审计记录**；无单点登出/踢人能力
- L4 LIKE 参数未转义 `%`/`_`（只影响匹配范围，非注入）
- L5 **未发现任何 SQL 注入**：动态查询全部占位符 + 常量列名，`ORDER BY` 硬编码 ✅
- L6 `server/server.exe` **未被提交**（`*.exe` 已 ignore、`.dockerignore` 也排除），config.yaml/.env/*.jks 均已 ignore ✅（已用 `git ls-files` 实测）
- L7 request_log 与 slog **不记录 body/Authorization/密码** ✅（唯一泄露是 H1 的 path 与 H9 的口令）

---

## 第六部分 · 体验与实用扫描（X1）

> 安全维度见第五部分。以下是客户端体验/健壮性，全部已核对 file:line。

### 🔴 必崩 / 必坏（不是"优化"，是 bug）

1. **添加带提醒的待办必崩**：manifest **既无 `SCHEDULE_EXACT_ALARM` 也无 `USE_EXACT_ALARM`**，而 minSdk=**33**（API31+ 未授予时 `setExactAndAllowWhileIdle` 直接抛 `SecurityException`），调用点 `TodoScreen.kt:200/239` **无 try-catch** 且 `:239` 在 `onAdded` 回调里。
2. **`RingHelper.kt:125` 无参 `Handler()` 在 OkHttp WS 读线程（无 Looper）→ 抛 RuntimeException 崩溃**（`StatusSyncManager.kt:227` 调用 `todoStrongRemind`）。
3. **重复提醒只响第一次**：`repeat_type=1/2`（每天/每周）只调度了一次性闹钟（`TodoScreen.kt:239`），触发后永不重排；**重启后所有闹钟不重建**（`BootReceiver` 无调度调用）。
4. **解绑失败仍清本地状态**：`AboutScreen.kt:231` 吞异常后无条件清 `pairId/partnerName` 并跳绑定页 → **服务端仍绑定、客户端已解绑，双端分裂**。
5. **`NetworkReceiver` 100% 无效**（静态注册 `CONNECTIVITY_CHANGE`，API24+ 不投递）→ 断网恢复既不重连也不重推。
6. **`BootReceiver` 基本拉不起来**：监听 `LOCKED_BOOT_COMPLETED` 但未声明 `directBootAware`；即使投递，`MODE_PRIVATE` 的 SharedPreferences 在直接启动阶段不可读会崩。
7. **主页伴侣状态不实时**（见 C3 断链①）——双人 App 的核心卖点是坏的。
8. **`ACCESS_BACKGROUND_LOCATION`** 声明了从未运行时请求 → 后台采 WiFi 名恒为 null（`AndroidManifest.xml:27`）。

### 体验（按痛感排序）

| # | 现象 | file:line |
|---|---|---|
| 1 | **全 App 零个"重试"按钮**；11 处 `runCatching` 吞异常无反馈（`TodoScreen.kt:96,194,208,217,517`、`HistoryScreen.kt:37`、`ProfileEditScreen.kt:119`、`WallpaperScreen.kt:69,155`、`LinxiApp.kt:131`、`BindScreen.kt:166`） | 见左 |
| 2 | 创建待办**失败时弹窗不关、无提示、输入还在** → 用户只会重复点 | `TodoScreen.kt:517-518` |
| 3 | **"添加"按钮无 busy 门控，连点两次创建两条待办**；完成/删除 IconButton 无禁用，连点发两次请求 | `TodoScreen.kt:491-524,352,355` |
| 4 | **离线假成功**：点互动立刻显示"已发送"，但 `ws==null` 时静默什么都不做 | `NowScreen.kt:104-106`、`StatusSyncManager.kt:161` |
| 5 | **MainTabs 缺 BackHandler**：任意 tab 按返回直接退桌面，不回主 tab，无"再按一次退出"（二级页倒是齐全） | `LinxiApp.kt:210` |
| 6 | **电池优化白名单入口没接**：`PermissionHelper.hasIgnoreBattery/toBatteryOptimization` 写好了**零引用** → 国产 ROM 上前台服务必被杀，这是保活最大缺口 | `PermissionHelper.kt:44,63`、`SettingsScreen.kt:147-173` |
| 7 | **冷启动一次性要 通知+精确定位+粗略定位**，无前置说明、无 rationale、被拒后只有一句 Toast 无引导 | `MainActivity.kt:96-116,39-44` |
| 8 | **表单零个 `maxLength`/`imeAction`/焦点前进**；待办标题详情可无限输入；邀请码框无数字键盘、无 6 位截断 | `TodoScreen.kt:431-432`、`BindScreen.kt:173` |
| 9 | 提示不统一：Toast 仅 2 处，其余全内联红字，无 Snackbar；空态文案缺失（NowScreen/SettingsScreen/HistoryScreen 曲线错误态） | 全局 |
| 10 | 横屏/大字体会坏：固定高度 `560/520/220/110dp` + `offset(27,31)` | `TodoScreen.kt:428`、`PrivacyConsentScreen.kt:67`、`HistoryScreen.kt:90`、`NowScreen.kt:148,207` |
| 11 | 硬编码颜色（不随主题/深色模式）：#3A2233/#FCE4F1/#F06AA8/#1A3825/#DFFAE4/#36D167/#D9412F | `NowScreen.kt:138-139,154,195-197`、`WallpaperScreen.kt:180`、`DeviceStatus.kt:45-49` |
| 12 | 无障碍：8 处可点区域 <48dp（32/34dp 按钮、13sp 纯 Text）；`DiscoverScreen.kt:86,114` 承载信息的图标 `contentDescription=null`；`BindScreen.kt:147-156` 裸 `Text.clickable` 无 `Role.Button` 读屏读不出 | 见左 |
| 13 | 时间显示不友好：无"刚刚/几分钟前"，待办 `MM-dd HH:mm` 不区分今天/明天；miuix Text 全程不可选，**日志/诊断信息无法复制** | `Models.kt:108`、`TodoScreen.kt:372` |
| 14 | 性能：`NowScreen.kt:70-129` 整页塞进单个 `item{}`（任一字段变化整页重组）；`SimpleDateFormat` 未 remember（每帧 new）；`WallpaperScreen.kt:70` 原图全尺寸 decode 无采样 → 大图 OOM；`StatusForegroundService.kt:175` 在服务主线程跑 `queryEvents(24h)`+UsageStats → **ANR 风险** | 见左 |
| 15 | `SettingsScreen.kt:68-70` 三个权限检查在 composition 体内做 `AppOpsManager` IPC + `Settings.Secure` 查询（每次重组主线程），且从系统设置返回后**状态不更新** | 见左 |
| 16 | 退出登录**未清 `myUserId`** → 换账号后可能用旧 id 判断"被提醒者是我" | `AboutScreen.kt:181-190`、`LinxiApp.kt:116-120` |

### 实用性缺口（值得决策的）

- **App 叫「林曦日记」，但日记功能整体缺失**：tab 只有主页/待办/发现/我的，`ApiClient.diaries/createDiary`(`:261-265`) 与服务端 diary 表**全无调用方**。见 Q20。
- **壁纸页不可达**：`Screen.Wallpaper` 无任何入口（`AppearanceScreen` 只剩主题模式一项），整个裁剪页 + `WallpaperProcessor` 是死功能。见 Q21。
- 无下拉刷新：NowScreen / SettingsScreen / DiscoverScreen；无分页：HistoryScreen 写死 100 条。
- 待办：无已完成列表、无撤销删除（删了就没，无确认无 undo）、无排序、无编辑、无到期高亮。
- 历史：无日期选择器（只能 ±1 天点）、可翻到未来空日。

---

## 第七部分 · 三工作流 + 安卓签名密钥（X3）

### 7.1 签名每次都变的根因（一行）

`android/app/build.gradle.kts:32` `signingConfig = signingConfigs.getByName("debug")` —— **release 用的是 debug 签名**，而 CI runner 每次都是全新机器、`~/.android/debug.keystore` **每次自动新生成** → 每次构建签名指纹都不同 → 装不上/覆盖不了。

### 7.2 密钥物料已生成（本机无 java/keytool，用 OpenSSL 3.5.4 走 PKCS#12，AGP 与 JDK21 原生支持）

**存放位置（仓库外，绝不进 git）**：`C:\Users\Administrator\Downloads\lxday-signing\`

| 文件 | 用途 |
|---|---|
| `lxday-release.p12` | **正式签名密钥库**（RSA 4096 / SHA-256 / 有效至 **2056-08-12**，别名 **`Lx-Day`**） |
| `lxday-release.p12.base64` | 上面这个文件的 base64（**一整行**，直接粘进 GitHub Secret） |
| `release-store-password.txt` | 密钥库口令（32 位随机；PKCS#12 下 key 口令 = store 口令） |
| `release.cert.pem` | 公钥证书（留档/校验指纹用） |
| `lxday-debug.p12` + `.base64` | **固定的 debug 密钥库**（别名 `androiddebugkey`，口令 `android`，安卓标准约定）→ 让 debug APK 签名也固定，能互相覆盖安装 |
| `debug.cert.pem` | debug 证书留档 |

**Release 证书指纹（以后每次构建都应该是这个，可用来验证签名固定）**
- SHA-256：`59:4A:B3:8F:AA:AE:A3:B6:28:E9:0A:A2:55:95:62:66:A1:0C:40:08:76:14:C2:43:E4:AA:DE:35:32:05:76:4F`
- SHA-1：`6B:E9:51:7A:F4:57:D3:D7:11:28:5D:B0:57:2F:00:75:BA:A1:92:00`
- Subject：`C=CN, O=Lxii, OU=LxDay, CN=LxDay Release`

**Debug 证书 SHA-256**：`FD:FD:F9:98:CE:CF:86:BD:DB:99:49:34:14:BA:EB:96:64:EC:6C:B0:52:47:F9:65:70:0D:C3:75:5B:36:97:12`

### 7.3 你需要做的配置（4 个 GitHub Secret，5 分钟）

仓库 → Settings → Secrets and variables → Actions → New repository secret：

| Secret 名 | 值从哪来 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 打开 `lxday-release.p12.base64`，**全选复制**（一整行，无换行） |
| `ANDROID_KEYSTORE_PASSWORD` | 打开 `release-store-password.txt`，复制里面那 32 个字符（开头 `UHOp`、结尾 `oFfr`，用于核对） |
| `ANDROID_KEY_ALIAS` | 填 `Lx-Day` |
| `ANDROID_KEY_PASSWORD` | 与 `ANDROID_KEYSTORE_PASSWORD` **相同**（PKCS#12 惯例） |

> ⚠️ **`lxday-release.p12` 请你自己另存一份离线备份**（U盘/网盘）。这个文件一旦丢失，**以后发的 APK 永远无法覆盖安装已装版本**，只能卸载重装（用户数据全丢）。我不会把它提交进仓库。
> ⚠️ 本文档写的口令**开头/结尾 4 位**只是给你核对，完整口令只在那个 txt 文件里。**这份 md 与 Answer 文件都不要提交进 git。**

### 7.4 三个工作流的改动

**`build-android.yml`（手动构建）**
1. 新增"解码密钥库"步骤：`ANDROID_KEYSTORE_BASE64` → `android/app/lxday-release.p12`（Secret 缺失时**跳过并回退 debug 签名**，保证 fork/无 Secret 也能构建）。
2. 把 4 个签名参数以 `-P` 注入 gradle。
3. 构建后**打印 APK 的签名指纹**（`apksigner verify --print-certs`）写进 Job Summary → 每次都能肉眼确认与 7.2 的指纹一致。
4. 产物名带上 `versionName+buildType+短SHA`，避免多次构建的产物混淆。
5. `--no-daemon` 保留；加 gradle 缓存（`setup-gradle` 自带）以缩短构建。
6. 【新】用 `if-no-files-found: error` 已有 ✅；补上传 `mapping.txt`（R8 已开 `isMinifyEnabled=true`，没有 mapping 以后崩溃日志无法还原）。

**`release.yml`（发行版）**
1. 同样接入固定签名（发行版必须固定，这是本需求核心）。
2. **修 `VERSION_CODE` 缺陷**：现在 release.yml **完全没传 `VERSION_CODE`**，`build.gradle.kts:23` 回退默认 **1** → **每个发行版 versionCode 都是 1，装不上更新**。改为必填输入，或由 tag 自动推导（见 Q23）。
3. Release 正文自动带上**签名指纹**与镜像 tag，附件加 `mapping.txt`。
4. 加"tag 已存在则失败"的前置检查（避免覆盖已发布版本）。

**`build-server.yml`（服务端镜像）**
1. Go 版本 1.22 → 与安全项 L1 一并升级（见 Q24）。
2. 加 `go build` 前的 `gofmt -l` 检查（现在只有 vet/test）。
3. Dockerfile 变更后**顺带验证 `/healthz`**：起容器 curl 一次，避免推出去的镜像根本起不来（现在完全没有 smoke test）。
4. 镜像加 OCI label（版本/commit/构建时间），便于生产核对当前跑的是哪个版本。

### 7.5 `build.gradle.kts` 的改法（条件签名，无 Secret 也能编）

```kotlin
val ksFile = (project.findProperty("KEYSTORE_FILE") as String?)?.takeIf { it.isNotBlank() }
signingConfigs {
    if (ksFile != null) create("release") {
        storeFile = file(ksFile); storeType = "PKCS12"
        storePassword = project.findProperty("KEYSTORE_PASSWORD") as String?
        keyAlias = project.findProperty("KEY_ALIAS") as String?
        keyPassword = project.findProperty("KEY_PASSWORD") as String?
        enableV1Signing = false; enableV2Signing = true
        enableV3Signing = true;  enableV4Signing = true   // v4 便于 adb install --fastdeploy
    }
}
buildTypes { release {
    signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
} }
```
debug 侧同理接 `lxday-debug.p12`（该文件**可以**提交进仓库——debug 密钥按安卓惯例是公开的，口令固定 `android`；这样本地与 CI 的 debug 签名也一致）。见 Q22。

---

## 第八部分 · 提问（请在每个 `选择:` 后填写）

> `我的意见` 是我的默认推荐。认可就填该项字母；多选就写 `ABC`；有补充直接在后面写中文。
> 带 ★ 的是**最易返工/影响最大**，请务必回答。

### 一、客户端 · 加载动画与历史页

**Q1**
问题:加载动画"统一"的范围到哪？
详情:发现页与待办页现在用的**已经是同一个** miuix `CircularProgressIndicator`（`DiscoverScreen.kt:57` vs `TodoScreen.kt:167`）。真正不一致的是另外 7 处纯文字态（"上传中…""保存中…""处理中…"、登录/注册/绑定的按钮文案），以及主页完全没有加载态。
选项:A 只把发现页做成"真加载+下拉刷新"，其余不动
B A + 把 7 处文字态换成按钮内嵌 miuix 小指示器（推荐）
C B + 主页也补加载骨架与下拉刷新
D 全 App 引入统一"加载遮罩"组件，所有异步操作都用它
选择:
我的意见:C。你要的是"统一"，那就一次做干净：发现页真加载、7 处文字态换指示器、主页补下拉刷新。D 的全局遮罩会让轻量操作也变卡顿感，不推荐。

**Q2** ★
问题:下拉刷新指示器的位置，按 KernelSU 那样"顶栏之下 6dp"处理对吗？
详情:根因是 `KernelScreen.kt:112` 写死 `contentPadding = PaddingValues(0.dp)`，指示器被毛玻璃顶栏盖住。KernelSU 的正解是传 `top = innerPadding.calculateTopPadding() + 6.dp`。改这一处，Todo/History/发现/主页**一次性全好**。
选项:A 照 KernelSU：顶栏高度 + 6dp（推荐）
B 再往下一点（顶栏 + 16dp），更醒目
C 指示器改成悬浮在顶栏之上（盖住顶栏）
选择:
我的意见:A。与 KernelSU 观感一致，也是 miuix 的设计意图。若你真机看还是觉得靠上，我再按 B 调（这是一行常数，随时可改）。

**Q3**
问题:伴侣历史状态页要优化到什么程度？
详情:现有短板：失败与空态共用一句文案且无重试；写死 100 条无分页；下标当 key；只能 ±1 天点着走且能翻到未来；电量曲线无坐标轴；卡片信息拼成一长串（"电量 x% · 充电 · 亮屏·锁定 · 前台应用 · SSID"）。
选项:A 只修"看不到刷新动画"+失败态重试（最小）
B A + 日期选择器 + 禁未来 + 分页加载更多 + 稳定 key
C B + 卡片重设计（按小时分组、图标化字段、电量条）+ 曲线补坐标轴时间刻度
D C + 新增"统计"视图（今日亮屏时长/充电次数/最常用应用）
选择:
我的意见:C。你说的"优化一下"我理解为观感+可用性一起，C 是完整但不外扩的边界。D 属于新功能，建议留到下一轮（要新增服务端聚合接口）。

### 二、客户端 · 同步速度与静默通知

**Q4** ★
问题:同步"更快"的实现路线，先治断链还是先压间隔？
详情:我查到"感觉不实时"的真凶**不是间隔**：`NowScreen.kt:52` 直接读 `DeviceStatusHolder.partner`，而它是普通 `@Volatile` 字段**不是 Compose State** → 服务端 WS 推到了、客户端也解析了，**主页 UI 就是不重组**。现在能变纯靠 30s 轮询"蹭刷"。间隔压到 5s 也治不了本，只会更耗电。
选项:A 只压间隔（30s→10s），不改架构
B 先把伴侣状态改成响应式 StateFlow（治本），间隔适度压到 10s（推荐）
C B + 再压到 5s，追求极致实时
D B + 间隔干脆保持 30s，完全靠 WS 实时（最省电）
选择:
我的意见:B。改成响应式后 WS 一到即刷新，实时性由 WS 决定（毫秒级），轮询只是兜底。10s 是兜底与耗电的平衡点。C 的 5s 收益极小、耗电与服务端压力翻倍；D 在 WS 断线时会有最长 30s 空窗。

**Q5**
问题:轮询间隔分档具体取值？
详情:现在**完全没有前后台感知**（`ProcessLifecycleOwner` 全仓 0 命中，`LinxiApp.kt:241` 的 `while(true)` 循环在后台也照跑）。我打算引入 `lifecycle-process` 做分档。
选项:A 前台 10s / 后台 60s / 息屏 5min（推荐）
B 前台 5s / 后台 30s / 息屏 2min（更激进，更耗电）
C 前台 15s / 后台 2min / 息屏 停止轮询（更省电）
D 不分档，统一 10s
选择:
我的意见:A。一加15 + 安卓16 对后台轮询管得严，不分档（D）会被系统限制甚至判定为耗电应用。息屏 5min 是保证"对方看你状态"不至于太旧的下限。

**Q6**
问题:WS 心跳与判死时间怎么调？
详情:现在心跳 20s（`StatusSyncManager.kt:67`）、服务端判死 90s（`hub.go:94`）、重连退避封顶 16s **无 jitter**（双端同时断网会同步重连，惊群）。判死 90s 意味着对方断网后最长 90 秒你还以为他在线。
选项:A 心跳 15s / 判死 45s / 退避加 ±20% jitter（推荐）
B 心跳 10s / 判死 30s（更快发现掉线，更耗流量）
C 保持 20s/90s，只加 jitter
选择:
我的意见:A。心跳包极小（几十字节），15s 一次流量可忽略；判死 45s = 3 个心跳周期，是业界常规取值。jitter 必加。

**Q7** ★
问题:"对方息屏/亮屏的静默通知"具体推什么、能不能关？
详情:实现方式是新建 channel `status_quiet`（`IMPORTANCE_LOW` + `setSound(null)` + 不振动 + 不设全屏 Intent）→ 只落通知栏，不弹横幅不响铃，完全符合你说的"不弹出不响铃但会推送消息"。问题是推哪些事件、以及会不会刷屏（对方频繁亮息屏时）。
选项:A 只推 息屏/亮屏
B A + 上线/下线（WS 连接变化）
C B + 充电开始/结束、电量低于 20%
D C + 对方打开了某个应用（前台应用变化）
选择:
我的意见:B + 60s 内同类事件合并 + 固定通知 id 覆盖更新（不堆叠）+ **设置页给独立开关（默认开）**。C 的充电/低电量容易变噪音，建议做成可选项默认关。D 太侵入（且会暴露对方在用什么 App，隐私上不合适）。

**Q8**
问题:静默通知在"免打扰时段"要不要静音？
详情:即使是静默通知，深夜通知栏亮起也可能扰人（尤其常亮屏手机）。
选项:A 不做时段控制，反正不响
B 设置页可配"免打扰时段"（如 23:00-07:00），该时段内不推静默通知（推荐）
C 该时段内仍推，但攒到早上汇总成一条
选择:
我的意见:B。实现简单（一个时间段设置 + 判断），且符合"不打扰"的本意。C 的汇总逻辑复杂且价值不大。

### 三、客户端 · 互动与响铃

**Q9** ★★
问题:"默认 7 秒"指的是什么？（**本轮最大歧义，务必确认**）
详情:代码里现在的 7 秒是**按钮 UI 冷却**（`NowScreen.kt:40` `INTERACTION_COOLDOWN_MS=7000`，服务端 `store.go:533-534` 也是 7s/1 次），**跟响铃时长无关**。而响铃是**无限响**的——`RingHelper.kt` 里注释写着"10 秒后自动停止"但**全仓不存在任何定时器**，`MediaPlayer.isLooping=true` + 振动 repeat=0（无限循环）+ `stop()` 从不调 `Vibrator.cancel()`。这就是你"必须划后台才能停"的原因。
选项:A 7 秒 = **响铃/振动只响 7 秒自动停**（按钮冷却另议）
B 7 秒 = 按钮冷却（已实现），响铃时长另给一个值
C 两者都是 7 秒
选择:
我的意见:A（并保留按钮冷却 7s 不变，即等于 C）。我按"响铃 7 秒自动停 + 按钮冷却仍 7 秒"实现。若你想响铃更久（比如 15 秒才够叫醒对方），请在这里写明秒数。

**Q10** ★
问题:"紧急找人"要不要新增？它与"响铃找人"的区别是什么？
详情:你需求里列了 4 个（求陪伴/求冷静/响铃找人/紧急找人），代码里**只有 3 个**（`NowScreen.kt:101-122`），没有"紧急找人"。
选项:A 不新增，"响铃找人"就是紧急（现状）
B 新增"紧急找人"：绕过勿扰 + 全屏弹窗 + **7 秒后不自动停，转为常驻通知直到对方确认** + 需二次确认才能发（防误触）
C 新增，但只是文案不同，行为与响铃一致
选择:
我的意见:B。两个层级才有意义：响铃找人=7 秒提醒（会自动停），紧急找人=必须对方确认（不自动消失）。发送前二次确认防误触，且服务端给更严限流（如 1 小时 1 次）。

**Q11**
问题:响铃的取消权限给到什么程度？
详情:要新增 `ring_cancel`（发送方撤回）/`ring_stopped`（接收方已关的回执）两个 WS 事件——现在**完全没有远端取消能力**。
选项:A 只做"接收方能关"（通知栏【停止】+ 全屏页按钮 + 7s 自动停）
B A + 发送方能【撤回】（对方立刻停响）（推荐）
C B + 发送方能看到"对方已知悉/已静音"回执
选择:
我的意见:C。三条一起做才闭环：对方能随时关（你的核心要求）、你发错了能撤回、你还能知道对方关了没（否则你会反复发）。

### 四、客户端 · 上传与相册

**Q12** ★★
问题:上传照片的图片处理，是否接受"放弃 HEIF/AVIF 与动图头像"以换取彻底可靠？
详情:头像上传现在**在生产必然 500**——代码调外部 CLI `vipsheader`/`vipsthumbnail`（libvips），而运行镜像 `alpine:3.20` **根本没装 libvips**。两条路：①镜像里装 libvips（约 +40MB，且要 libheif 插件才支持 HEIF，alpine 上依赖链较脆）；②改**纯 Go 解码**（`image/jpeg`+`png`+`x/image/webp`），与"单容器纯 Go SQLite"的既定架构一致、零外部依赖，但**不支持 HEIF/AVIF，动图头像也要放弃**。注意 iPhone 默认拍 HEIC，但**安卓相机默认 JPEG**，且你的用户是安卓双人。
选项:A 纯 Go 解码，放弃 HEIF/AVIF 与动图（推荐）
B 镜像装 libvips，保留全格式与动图
C 纯 Go 解码 + 客户端把 HEIC 转成 JPEG 再传（客户端 Android 原生支持解 HEIF）
选择:
我的意见:C（先按 A 落地，客户端顺手加 HEIC→JPEG 转换）。这样既不给服务端引外部依赖，又不会让"从别人那收到的 HEIC 图"上传失败。动图头像我认为可以放弃（当前也没用上）。

**Q13** ★
问题:换掉"丑陋的原生图片选择器"，用哪种？
详情:头像现在用的是 `ActivityResultContracts.OpenDocument()`（`ProfileEditScreen.kt:106`）——**SAF 文件浏览器**，就是你说的那个丑东西。你的壁纸页其实**已经用了现代 Photo Picker**（`WallpaperScreen.kt:63-64`），观感好很多但仍是系统 UI（无法 miuix 化）。
选项:A 换成系统 Photo Picker `PickVisualMedia`（改动最小，1 行，系统 UI）
B **自研 miuix 风格选择器**（MediaStore 查询 + 3 列网格 + 多选 + 按月分组 + 相机入口），与全 App 皮肤一致（推荐）
C B，但保留"从系统相册选"作为兜底入口
选择:
我的意见:C。你明确嫌系统选择器烂，那就自研 miuix 网格（相册功能也正好复用这套网格代码）。但 Android 14+ 有"仅授权部分照片"的权限模型（`READ_MEDIA_VISUAL_USER_SELECTED`），极端情况下自研选择器只能看到用户勾选的那几张——所以保留系统 Photo Picker 兜底，避免"一张都选不到"的死局。

**Q14** ★★
问题:相册照片的隐私保护级别？（决定架构，后面很难改）
详情:现在 `/upload` 与 `/uploads` 两个静态目录**完全公开无鉴权**，只靠随机文件名保护（`handlers.go:950` 注释写着"纯自用免鉴权"）。更糟的是 `netlog.go:68-70` 的日志跳过列表**漏了 `/upload`** → 每张照片的完整 URL 被写进 request_log，**任何后台管理员在"网络日志"页就能直接点开你们的私密照片**。相册上线后这是最致命的隐私面。
选项:A 保持公开 + 随机名（最快，但等于没保护）
B 改 `/media/:id` **鉴权代理**：校验 JWT + 校验 pair 归属，只有你们俩能看（推荐）
C B + 图片在服务端**加密存储**（落盘即加密，读时解密）
D B + 短时效签名 URL（便于 CDN，但你没上 CDN）
选择:
我的意见:B（并立刻修 request_log 漏 `/upload` 的问题 + 补 Referrer-Policy）。C 的落盘加密会让 CPU 与内存开销上升、且单容器 SQLite 架构下备份更麻烦，收益在"服务器只有你自己"的前提下不大。D 对你无 CDN 的场景是多余复杂度。

**Q15**
问题:相册初版做多深？
详情:服务端**目前没有任何相册表**（14 张表里最接近的 `diary_image` 只有 `id/diary_id/url/sort_no`，挂在日记下、无 pair_id，不能直接用）。所以表、接口、页面都要新建（见图 4）。
选项:A 极简：只有一个"我们的相册"（不分册），网格 + 上传 + 大图查看 + 删除
B A + 多个相册（建/改名/删/封面）+ 回收站
C B + 照片描述、按拍摄时间(EXIF)分组、"这一天"回忆
D C + 评论/点赞、伴侣上传时推送通知
选择:
我的意见:B（+ 上传完成给伴侣发一条静默通知，即 D 的通知部分）。A 太单薄，很快就要返工加表；C 的 EXIF 分组我会把字段先存下来（`taken_at`），UI 留到下一轮；评论/点赞对双人场景价值低。

**Q16**
问题:相册要不要引入 Coil 图片库？
详情:项目**目前没有任何图片库**，头像显示是自己撸的（`ProfileEditScreen.kt:277-298`）：**主线程解码**、无缓存、无采样、每次重新下载。相册要铺几十张缩略图网格，不引库必然卡顿+OOM。Coil 3 能复用现有 OkHttp 与鉴权头。
选项:A 引入 Coil 3（推荐）
B 不引库，自己写内存 LruCache + 磁盘缓存 + 采样解码
C 引入 Glide
选择:
我的意见:A。Coil 3 是 Compose 原生首选、Kotlin 协程实现、体积小（~500KB），且能直接复用我们的 OkHttp 实例带鉴权头（Q14 选 B 的鉴权代理正需要这个）。B 等于自己重写一遍 Coil，容易出 bug；C 是 View 时代的库，Compose 支持不如 Coil。

### 五、服务端 / 后台

**Q17** ★
问题:后台"多余内容"删除的力度？
详情:我已核实**完全未被引用**的有：26 个模板组件、51 张无用图片、7 个死依赖（xlsx/wangEditor/xgplayer/vue-img-cutter/file-saver/qrcode.vue/highlight.js）、365 行零引用 WS 封装、iframe 整套、假通知数据、圣诞节日示例。另外 `@author Art Design Pro Team` 出现在 **77 个文件**里，`index.html` 标题还是 `Art Design Pro`，生产控制台还在打**原作者的 GitHub 和 QQ 群**。
选项:A 只删死组件与死依赖（构建变小、风险最低）
B A + 删无用图片 + 清模板署名/标题/控制台输出 + 精简外链（推荐）
C B + 顺手重构目录结构（把 art-* 前缀改成本项目前缀）
选择:
我的意见:B。C 的批量重命名会碰到 77 个文件 + 全局注册表，收益纯观感、返工风险高（一个 import 写错就白屏），不值得。

**Q18** ★
问题:那些"假设置项"怎么处理？
详情:核过服务端读取方后，三项是假的：① **`storage.local_dir`**——服务端从不读它（uploadDir 来自 YAML）；② **`push.provider`**——服务端读 YAML 不读 app_setting，而 `push.go` 本身是纯占位（只打日志）；③ **OSS/COS/Kodo 5 个键**——前端已隐藏但服务端仍接受写入，且 `GET /settings` **明文回吐 oss_access_key/oss_secret**。另外真正在用的 **`site.url`**（决定图片是否返回绝对 URL）**页面上反而没有**。
选项:A 全部接通（让每一项真生效）
B 删掉不可能生效的（local_dir/push/OSS 五键），补上缺失的 site.url，其余加校验（推荐）
C 保留但灰掉并标注"暂未启用"
选择:
我的意见:B。"写标准"的正解是**页面上的每一项都真生效**，不是留一堆灰按钮。push 要真做需要接 FCM/厂商推送（国内还要各家资质），本轮不做就该删掉这个入口，别留假开关。

**Q19**
问题:SMTP 要不要加"测试发信"按钮？
详情:现在配完 SMTP **没有任何验证手段**，只能去 App 端走一遍注册流程试错。而且六个字段**零校验**（端口能填字母、发件人能填非邮箱），表单连 `validate()` 都没有。
选项:A 加"测试发信"按钮（填收件人→立刻发一封测试邮件→返回成功/失败原因）（推荐）
B 只加字段校验，不加测试发信
C A + 保存时自动测试，失败则拒绝保存
选择:
我的意见:A。C 的"失败拒绝保存"会导致临时网络抖动都存不下配置，很烦人。A 是标准做法（新增 `POST /api/admin/settings/smtp-test`，仅超管可调）。

**Q20**
问题:后台的中英文 i18n 要补到什么程度？
详情:0813 你说过"要英文也可以"所以保留了 i18n。现状：框架文案 158 键中英**完全对齐**，但 **11 个业务页的 label/表头/提示全是中文硬编码**（如用户管理、系统设置整页）→ 切到 English 后只有菜单和框架变英文，内容区全中文，属于半残状态。
选项:A 全量补齐 11 个页面的 i18n key（工作量较大，约 300+ 个 key）
B 保持现状（框架双语，业务中文）
C **移除语言切换**，后台只做中文（最干净）
选择:
我的意见:A。你说过"要英文也可以"，那半残状态就是 bug。300 个 key 是机械劳动但我能一次做完；做完后语言切换才是真能用的功能。若你其实不需要英文，选 C 我就把切换入口删掉（那样也算"写标准"）。

**Q21** ★
问题:安全问题修到什么范围？
详情:第五部分共 **9 高危 + 14 中危 + 7 低危**。其中几条不是"理论风险"而是**已经在生产上成立**的：H1（后台管理员能直接点开你们私密照片 URL）、H2（普通 admin 能读 OSS 明文密钥并全站群发通知）、H4（一行 curl 绕过首登强制改密）、H5（6 位绑定码可爆破绑上陌生人读其状态历史）、H7（日志清理 SQL 是 MySQL 语法在 SQLite 上永久失败→磁盘必然打满）、H9（初始超管口令明文进日志）。
选项:A 只修 9 条高危
B 高危 + 中危（推荐）
C 全修（高+中+低）
D 只修与相册/隐私直接相关的
选择:
我的意见:C。低危那 7 条基本是顺手的（删弃用依赖、补审计记录、转义 LIKE），跟中危一起改成本极低。**特别提醒 H7 与 M13**：日志表会无限增长打满磁盘、SQLite 单文件**目前零备份**——你们的日记和相册全在那一个文件里，误删就永久没了，我强烈建议本轮把定时备份做上（Q25）。

**Q22**
问题:JWT 有效期与撤销机制怎么改？
详情:现在 admin 与 user token **都是 720 小时（30 天）且完全不可撤销**（claims 里没有版本号）：改密、禁用、删号后旧 token 仍然全功能可用整整一个月。另外**用户端根本没有改密/找回密码接口**。
选项:A 后台 token 降到 2h + refresh token 续期；用户端保持 30 天但加 `token_ver`（改密即失效）（推荐）
B 两端都降到 2h + refresh
C 只加 `token_ver` 不改有效期
D 保持现状
选择:
我的意见:A。后台是高价值目标（能看全站数据），必须短时效；App 端用户不接受频繁重新登录，加 `token_ver` 即可解决"改密后旧 token 还能用"。顺带补上用户改密接口（现在完全没有）。

**Q23**
问题:绑定码与验证码要不要加强？
详情:绑定码 6 位纯数字、TTL 1h、**无尝试次数限制、无限流** → 可爆破绑上陌生人挂起的 pair，从而读到对方的日记、待办、**状态历史（含 WiFi 名、前台应用、电量轨迹）**。邮箱验证码同样 6 位无尝试上限。
选项:A 绑定码提到 8 位混合字符 + 尝试上限 5 次 + IP 限流；验证码尝试 5 次即失效（推荐）
B 绑定码保持 6 位数字（好念好输），只加尝试上限与限流
C 绑定码改二维码扫码绑定（最安全但要新做扫码 UI）
选择:
我的意见:A。8 位混合字符（去掉易混的 0/O/1/l/I）复制粘贴即可，你们已经有"点击复制"功能（`BindScreen.kt:147-156`），不影响体验。C 留作以后增强。

### 六、工作流与签名

**Q24** ★
问题:debug 签名密钥库要不要提交进仓库？
详情:我生成了固定的 `lxday-debug.p12`（别名 `androiddebugkey`，口令 `android`，就是安卓官方约定的公开值）。提交进仓库后，**本地构建与 CI 构建的 debug APK 签名一致**，可以互相覆盖安装，调试时省掉"卸载重装丢数据"。debug 密钥按惯例不算敏感（AOSP 自带的那个也是公开的）。release 密钥**绝不提交**，只走 GitHub Secret。
选项:A debug 密钥提交进仓库（推荐）
B debug 也走 Secret，不进仓库
C debug 不固定，保持现状（每台机器各自生成）
选择:
我的意见:A。这是安卓社区通行做法。风险是"别人能用你的 debug key 签同包名的 APK"——但 debug 包本来就不该分发，且 release 用另一把钥匙，无实际影响。

**Q25** ★
问题:发行版的 `VERSION_CODE` 怎么定？（现在有个真 bug）
详情:`release.yml` **完全没传 `VERSION_CODE`** → `build.gradle.kts:23` 回退默认值 **1** → **每个发行版的 versionCode 都是 1，用户装不上更新**（安卓要求 versionCode 递增）。
选项:A 作为必填输入，每次手填（简单直白）
B 由 tag 自动推导：`v1.2.3` → `10203`（1*10000+2*100+3）（推荐）
C 用 GitHub run number 递增
D 用 commit 数量
选择:
我的意见:B。既保证递增，又与版本名一一对应，不依赖 CI 状态（C 的 run number 在重跑工作流时会跳变，D 依赖历史长度、换分支会乱）。

**Q26**
问题:CI 还要补哪些校验？
详情:现在 `build-server.yml` 只有 `go mod tidy` + `vet` + `test` 就直接推镜像——**镜像起不起来完全没验证**（如果推了个启动即崩的镜像，你在生产 `docker compose pull` 后才发现）。`build-android.yml` 也没有输出 mapping.txt（R8 已开混淆，没 mapping 以后崩溃日志无法还原）。
选项:A 加镜像 smoke test（起容器 curl `/healthz`）
B A + 安卓产物加 mapping.txt + 构建后打印 APK 签名指纹到 Job Summary
C B + 加 gofmt 检查 + Go 版本升级
D 不动 CI
选择:
我的意见:C。签名指纹打印这条对你特别有用——**每次构建你都能一眼确认指纹是不是 7.2 里那个固定值**，等于给"签名统一"上了个自动化验收。

**Q27**
问题:Go 版本与依赖要不要升？
详情:`go.mod` 是 Go 1.22（2024 上半年），gin 1.10、x/net 0.25，期间有安全修复（**具体 CVE 我未联网核对，标待确认**）。另外 go.mod 里仍挂着已弃用的 mysql/redis/miniredis/sqlmock（0813 改 SQLite 后没清）。
选项:A 升 Go 1.23 + 升 gin/x-net + 清理弃用依赖（推荐）
B 只清理弃用依赖，不升版本
C 全都不动（最保守）
选择:
我的意见:A。但我会**分两个提交**：先清理依赖（零风险），再升版本单独一个提交——万一 CI 红了能一眼定位是升级引起的。modernc.org/sqlite 对 Go 版本敏感，如果升级后 CI 红我会退回 1.22 并告诉你。

**Q28**
问题:SQLite 数据备份要不要本轮做？
详情:你们的日记、待办、相册元数据、状态历史**全部在单个 `.db` 文件**里，现在**零备份**（`main.go:209` 目录 0755、文件默认 0644，无任何 `VACUUM INTO`/快照）。误删、磁盘坏、`docker volume rm` 打错一次就永久丢失。
选项:A 服务端内置定时 `VACUUM INTO` 到 `/app/data/backup/YYYYMMDD.db`，保留最近 7 天（推荐）
B 只写文档教你用宝塔定时任务备份
C 后台加"立即备份/下载备份"按钮（超管）
D 不做
选择:
我的意见:A + C。A 保证无人值守也有备份（成本极低，SQLite 的 VACUUM INTO 是原子的、在线可用）；C 让你能随时拉一份到本地。顺带把 db 文件权限收到 0600。

### 七、范围与顺序

**Q29** ★
问题:"日记功能缺失"和"壁纸页不可达"这两个死功能怎么办？
详情:① App 叫「**林曦日记**」，但**日记功能整体没有入口**：tab 只有 主页/待办/发现/我的，`ApiClient.diaries/createDiary` 与服务端 diary 表、后台的"内容审核-日记"页**全都在，客户端却无任何调用方**（0811 那轮把日记页改成了"发现页"）。② `Screen.Wallpaper` 整个壁纸裁剪页没有任何入口（`AppearanceScreen` 只剩主题模式一项），`WallpaperProcessor` 是死代码。
选项:A 本轮不管，继续放着
B 本轮把日记入口加回来（发现页加一张"日记"卡 或 底部第 5 个 tab）
C 本轮删掉壁纸死代码；日记留到下一轮
D B + C 都做
选择:
我的意见:C（本轮删壁纸死代码，日记留下一轮）。理由：本轮已经有相册这个大功能 + 安全全修，再加日记会摊薄质量；但**日记确实该有**（App 名就叫日记，服务端和后台审核页都做好了在等它），我建议下一轮专门做日记 + 一起听/一起看。若你希望本轮就补，选 D 我一起做。

**Q30**
问题:那些"必崩/必坏"的 bug 优先级？
详情:第六部分列了 8 条不是"优化"而是真 bug，其中三条在你真机上应该已经能复现：① **添加带提醒的待办必崩**（manifest 缺 `SCHEDULE_EXACT_ALARM`，minSdk33 下抛 SecurityException 无 try-catch）；② **重复提醒只响第一次**（每天/每周只调度了一次性闹钟，且重启后所有闹钟不重建）；③ **解绑失败仍清本地状态**导致双端状态分裂。
选项:A 与本轮需求一起修（推荐）
B 先修这 8 条并让我验一次，再做需求
C 只修①②③，其余留后
选择:
我的意见:A。你说过"中间不要停下来让我验第几阶段"，所以我一起做完。但**唯一例外是上传链路**（Q12/Q13）——它是相册的前提，我会先把它修通、你真机验一次"选 JPG 头像能成功"，再动相册。这是你自己在需求里定的前提条件，我尊重它。

**Q31**
问题:施工与推送节奏？
详情:0813 那轮你授权了"本轮 push main 无需再确认，一路写到 CI 全绿"。本轮涉及的面更大（安全改动 + 相册新表 + 签名改造）。
选项:A 同 0813：一路 push，CI 红了自己迭代修，不打扰你（推荐）
B 每个大块（客户端/服务端/后台/CI）push 前问你一次
C 全部做完只 push 一次
选择:
我的意见:A。但有两个例外我会主动停下问你：① 需要你配完 4 个 GitHub Secret 我才能验签名（这一步只有你能做）；② 上传链路修通后需要你真机验一次再做相册。

**Q32**
问题:文档要不要同步更新？
详情:本轮会新增相册接口、改签名流程、加安全配置项。仓库现有 `README.md`/`DEPLOYMENT`/`CHANGELOG.md`/`docs/APP_INTRO.md`/`ARCHITECTURE.md`。
选项:A 全部同步更新 + 新增《签名与发布指南》（推荐）
B 只更 CHANGELOG
C 不更新
选择:
我的意见:A。签名这套东西半年后你自己也会忘（密钥在哪、Secret 叫什么、指纹该是多少），必须写成文档。同时把新增的相册接口补进接口文档。

---

## 第九部分 · 施工顺序与风险

### 顺序（按依赖关系，不是按优先级）

```
P0 先修"必崩"与上传前提（因为后面都依赖它）
   ├ manifest 补 SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM + canScheduleExactAlarms 引导 + try-catch
   ├ RingHelper 的 Handler() 崩溃 + 振动 cancel + 音量/勿扰恢复
   ├ 服务端上传改纯 Go 解码 + 魔数白名单补 JPEG + urlPrefix 统一
   └ 客户端换选择器 + EXIF 旋正 + 压缩 + 引 Coil
   ★ 停一次：你真机验「选 JPG 传头像成功」
P1 安全（服务端为主，改动集中，先做完好过后面反复回头改）
   H1~H9 + M1~M14 + L1~L7；含 /media 鉴权代理（相册要用）
P2 同步与通知（客户端架构性改动）
   PartnerStateStore 响应式 + 前后台分档 + 息屏上报修正 + NetworkCallback
   + 静默通知 channel + 响铃 7s 与双向取消
P3 相册（依赖 P0 的上传 + P1 的鉴权代理 + Coil）
   建表 → 接口 → 导航传参改造 → 三个页面 → 上传流
P4 后台清理与设置写标准（独立，可本地 npm build 验证）
P5 UI 收尾：加载动画统一 + 历史页优化 + 刷新指示器（改动小但触及所有页，放最后避免与 P2/P3 冲突）
P6 CI + 签名 + 文档
   ★ 停一次：你配 4 个 Secret，我跑一次 build-android 验指纹
```

### 风险与预案

| 风险 | 可能性 | 预案 |
|---|---|---|
| **纯 Go 解码后某些图仍失败**（渐进式 JPEG、CMYK、超大分辨率） | 中 | 加明确错误文案区分"格式不支持/尺寸过大/文件损坏"；服务端保留原图不删，便于我按日志定位 |
| **自研选择器在 Android 14+ "部分照片授权"下看不到图** | 中 | 已规划系统 Photo Picker 兜底入口 + 权限说明引导 |
| **Coil 3 与 Compose BOM 2026.06.01 / miuix 0.9.3 版本冲突** | 低 | 先只在相册用，冲突则退回自写 LruCache 方案（Q16 的 B）；CI 编译即可暴露 |
| **导航从 enum 改 sealed class 影响所有页面** | 中 | 改动集中在 `LinxiApp.kt`，用最小侵入方式（enum 保留 + 加 `screenArg` 变量），已有的 `MainFabDestination` 测试能兜住 |
| **JWT 加 token_ver 后你需要重新登录一次** | 高（必然） | 提前告知：本轮上线后 App 与后台各需重新登录一次 |
| **相册新表 + 迁移**：schema 走的是启动幂等 `CREATE TABLE IF NOT EXISTS` + `__NEXT_SCHEMA__` 锚点 | 低 | 沿用既有机制，不做破坏性变更；本机用 python sqlite3 逐条验证 DDL（0813 同样做法，验证有效） |
| **本机无 java/SDK/docker → 安卓与 Go 只能靠 CI + 你真机** | 高（既有） | 严格对照现有 miuix/Compose 写法逐文件自检；Go 侧尽量补单测（现有 27 个测试文件可参照）；CI 红了立即迭代 |
| **R8 混淆把 Coil/新增反射路径干掉** | 中 | 补 proguard 规则；release 构建后必须实机装一次（不能只看 CI 绿） |
| **签名切换后旧 debug 包装不上** | 高（必然） | 提前告知：换签名后你手机上的旧 APK 需**卸载重装一次**（此后永久固定，再不会有这问题） |

### 验证清单（我做完会逐项自查）

- [ ] 后台 `npm run build` 本地跑绿（唯一能本地验的）
- [ ] `go vet ./...` + `go test ./...` 在 CI 绿
- [ ] build-server 镜像 smoke test（`/healthz` 200）
- [ ] build-android Debug 构建绿 + **签名指纹 == 7.2 中的 debug 指纹**
- [ ] build-android Release 构建绿 + **签名指纹 == `59:4A:B3:8F:…:76:4F`**
- [ ] 连跑两次 Release 构建，两次指纹一致（这才叫"签名统一"）
- [ ] SQLite 新表 DDL 用 python sqlite3 逐条验证
- [ ] 需要你真机验的：① 选 JPG 传头像成功 ② 响铃 7 秒自停 + 通知栏能停 + 对方能撤回 ③ 息屏后对方收到静默通知（不响不弹）④ 下拉刷新指示器可见 ⑤ 相册上传/浏览/删除 ⑥ 添加带提醒的待办不崩

---

## 附：本文档回答方式

复制「第八部分」的 Q1~Q32，在每个 `选择:` 后填字母（如 `选择:C`），有补充直接写在后面，存为 `ClaudeScheme_0820_Answer.md`（放 `C:\Users\Administrator\Downloads\` 或仓库根都行，我会去找）。
**不想逐条答也行**——只回 Q2、Q9、Q12、Q13、Q14、Q21、Q25、Q29 这 8 条（带 ★★ 与影响架构的），其余我按「我的意见」执行。

> ⚠️ 提醒：本文档与 Answer 文档**都不要提交进 git**（含签名口令核对信息）。`ClaudeScheme_0811/0813` 当时也是保留为本地未跟踪文件。
