# 林曦日记 · 开发规范

本文件是这个仓库的**长期约束**。写代码前先读它，改动后按它自查。
操作细节（工具链路径、验证命令）见 `docs/DEVELOPMENT.md`。

---

## 一、UI 规范（客户端）

### 1.1 弹窗按钮：确认与取消的背景色必须不同

这是管理员反复提过的要求。**不要每个弹窗各写一遍两个按钮**——那样只要有人少传一个
`variant` 就破功（0821 之前全仓 11 处弹窗里有 6 处不合规，「删除相册」与「保存名称」
完全同色，危险操作没有任何视觉区分）。

**一律用 `ui/components/LxDialog.kt` 里的两个组件**，按钮语义已焊死在组件里：

| 组件 | 用途 | 取消侧 | 确认侧 |
|---|---|---|---|
| `LxConfirmDialog` | 确认/危险操作 | 左·`Neutral`（灰底） | 右·`Positive` 蓝 / `destructive=true` 时 `Negative` 红 |
| `LxFormDialog` | 表单（新建、改名） | 左·`Neutral` | 右·`Positive` 蓝 |

- 危险/不可逆操作（删除、清空、解绑、彻底删除）传 `destructive = true`
- 不可逆操作的确认按钮**默认延迟 1 秒可点**（`confirmDelayMs`）：弹窗刚出现时
  用户手指往往还停在上一个按钮的位置，没有这段延迟"删除→确认"两下连点就把数据删了
- 确认文案要**说清后果**（"删除后 X 张照片会退回未归类"），不是干巴巴的"确定吗"
- 处理中传 `busy = true`：按钮禁用 + 文案变"删除中…"，且不允许点外部关闭
  （请求已经发出去了，关掉弹窗只会让用户以为取消了）

### 1.2 按钮语义配色

一律用 `ui/components/LxButton.kt`，**禁止裸用 `miuix.Button`**（它没有语义配色）：

- `Positive` = 品牌蓝 `#277AF7`，白字 —— 保存/创建/同意/确定/恢复
- `Negative` = 品牌红，白字 —— 删除/清空/解绑/彻底删除
- `Neutral` = `onBackground` α0.08 灰底，深字 —— 取消/稍后/关闭/次要动作

### 1.3 组件与图标：全部 miuix，不留 Material

- 组件一律 `top.yukonga.miuix.kmp.basic.*`，**禁止 `androidx.compose.material3.*`**
  - 唯一例外：`ui/theme/`（`MaterialTheme`/`Typography` 在那里是 materialkolor 的
    配色计算桥，用户看不见；拆掉要重调几十个色值，投入产出比极差）
- 图标一律 `MiuixIcons`，**禁止 `androidx.compose.material.icons.*`**
  - `icon.extended` 下的图标接收者是 `MiuixIcons`
  - **`icon.basic` 下的（Search/Check/Close/ArrowRight 等）接收者是 `MiuixIcons.Basic`**
    —— 这一点没有文档，是 `javap` 反编译 AAR 查出来的，写错会报
    "receiver type mismatch"
- miuix 0.9.3 缺的图标用近似顶替（已定：Notifications→Messages、Explore→Community、
  Movie→RecordingTape、Construction→Tune、Code→File）
- 通知栏 RemoteViews 只能是 XML（系统进程渲染），这是硬约束，不算违规

### 1.4 页面骨架与状态

- 列表页一律用 `ui/components/KernelScreen.kt`
- 二级页必须有 `BackHandler` + `BackAction`（否则按返回直接退到桌面）
- 加载态一律 `KernelScreen(loading=)` 或 `LoadingRow()`，**禁止各页自写**
  - `KernelScreen` 已保证 `loading` 与 `isRefreshing` 互斥（两个圈一起转是 0821 修的 bug）
- 错误态必须**可重试**（给"重试"按钮），且**空态与错误态文案必须区分**
  ——用户要能分辨"没数据"和"网络挂了"
- 圆角：卡片 16dp、按钮 16dp、缩略图 8dp
- 面向用户的文案用中文；日志用英文

---

## 二、代码约定

### 2.1 写代码前先 grep 项目内既有先例

**这是我反复犯错的地方**：凭印象写出过 `SuperDialog`（实际是 `OverlayDialog`）、
`getObject()`（实际是 `get()`）、`deleteJson()`（实际是 `delete()`）、
`currentSMTPConfig()`（实际是 `loadSMTP()`）、`StatusForegroundService.appContext`（不存在）。
写任何 API 调用前，先在仓库里搜同类用法。

### 2.2 Go：`rows.Scan` 必须检查返回值

忽略它会让可空列（NULL）静默变成零值。0821 的崩溃就是这么来的：
`status_history.foreground_pkg` 为 NULL → `Scan` 报错被忽略 → 整行零值 →
`Ts.UnixMilli()` = `-62135596800000`（每行同值）→ 客户端 LazyColumn 撞重复 key → 崩溃。

坏行的正确处理是 **跳过 + `slog.Error` 留痕**，不是静默 continue。

### 2.3 Go：提前返回错误前要排空请求体

`fail()` / `afail()` 里已统一调 `drainRequestBody`。**不要绕过它们直接 `c.JSON`**。

原因：Go 的 http server 只自动排空 ≤256KB 的未读 body，超过就关连接，
Nginx 侧表现为 **502 Bad Gateway**——把我们精心写的中文错误整个吞掉。
实测生产上 body ≥2MB 且被中间件拒绝时必 502。

### 2.4 SQLite：不要写 MySQL 语法

`datetime('now', '-7 days')` 而不是 `NOW() - INTERVAL 7 DAY`。
后者在 SQLite 上语法报错，而如果调用方忽略了返回值，清理任务会**永久静默失败**、
磁盘只涨不跌（0820 的 netlog 就踩过这个）。
清理类 SQL 一律返回 `RowsAffected` 并记日志，写错了才看得出来。

### 2.5 SQLite 迁移：顺序必须是「建表 → 补列 → 建索引」

`schema.sql` 不能一趟执行完。**老库里表已存在，`CREATE TABLE IF NOT EXISTS` 是空操作，
新列不会凭空出现**；若紧随其后的 `CREATE INDEX` 引用了那个新列，就会报
`no such column: xxx`，**容器直接起不来**。

0821 就是这么炸的：`idx_photo_status_deleted` 引用 `photo.deleted_at`，
而补列（`addColumns`）在整个 schema 跑完之后才执行。

新库不会暴露这个问题（`CREATE TABLE` 自带新列），所以**只用全新临时库做测试永远测不到
这条升级路径**。任何涉及加列的改动，必须补一个"先建旧表结构、再跑 runMigrations"的测试
（见 `migrations_upgrade_test.go`）。

### 2.6 新增依赖要检查它声明的最低 Go 版本

```bash
go list -m -f '{{.Path}} {{.GoVersion}}' <module>
```

0821 引入的 HEIC/AVIF 解码器声明 `go >= 1.25`，`go mod tidy` 把 `go.mod` 提到 1.25，
而 `Dockerfile` 还是 `golang:1.22-alpine`、CI 配 `go-version: '1.22'` → 镜像构建失败。
本机恰好是 1.25 所以完全没复现出来。**改完 go.mod 要同步确认 Dockerfile 与 CI 的版本。**

### 2.7 客户端：解码大图必须先缩

`ImagePrepPolicy.sampleSize` 只能做 2 的幂，4000×3000 会算出 `sample=1` 从而
**全尺寸解码 45.8MB**。必须配合 `decodeDensityScale`（`inDensity`/`inTargetDensity`
+ `inScaled`）在解码期直接出目标尺寸。连续上传时前者必 OOM，而失败只会静默
`uploadFailed++`——表现就是用户说的"照片会消失"。

### 2.8 Kotlin：`?.use { }` 返回的是 lambda 的值，不是"流开没开"

0822 查出的「上传显示无法读取图片 / 服务器没成功上传过一张」就是这一行：

```kotlin
// 错的：decodeStream 在 inJustDecodeBounds=true 时按设计永远返回 null
//       于是 ?: 恒成立，每一张图都报「无法读取所选图片」
resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    ?: error("无法读取所选图片")

// 对的：先明确拿到"流开没开"，再单独判尺寸
val opened = resolver.openInputStream(uri)?.use {
    BitmapFactory.decodeStream(it, null, bounds); true
} ?: false
ImagePrepPolicy.boundsFailure(opened, bounds.outWidth, bounds.outHeight)?.let { error(it) }
```

凡是 `?.use { ... } ?: error(...)`，都要先问一句**lambda 最后一个表达式可不可能是 null**。
这类 bug 在编译期与代码审查里都很隐蔽，`AvatarCropper`/`AvatarCropScreen` 里的同类写法
判的是 `bounds.outWidth` 所以侥幸没踩。

**判定逻辑要抽成纯策略再单测**（`ImagePrepPolicy.boundsFailure`）：
`BitmapFactory` 在 JVM 单测里不可用，把判定留在 Android 调用处等于永远测不到。
补测试后**必须把实现临时改回旧语义、确认测试真的会红**，否则测试只是摆设。

### 2.9 可点区域下限焊在组件里，不靠调用点传参

`LxButton` 原本只有 `padding(vertical = 13.dp)`，横向零留白 → 顶栏里不带
`fillMaxWidth` 的调用点被压成文字宽度（管理员报的「右上角上传按键太窄」）。
现在由 `defaultMinSize(MIN_TOUCH_DP)` 兜住 48dp 下限。

新增交互组件一律在组件内部保证最小触达尺寸，别指望每个调用点都记得传参。

### 2.10 服务端图片地址是「条件绝对」的，客户端必须补全

服务端 `mediaPathURL` / `publicUploadURL` 在后台 `site.url` **未配置时返回相对路径**
（`/media/<id>/thumb`），而 `app_setting` 表没有种子数据 —— **默认就是相对路径**。

Coil 拿到无 scheme 的字符串**不走网络 fetcher**，会当本机文件找 → 失败 →
`AsyncImage` 没配 error 占位就渲染空白。0822 管理员报的「缩略图貌似是透明的」正是如此。

补全统一走 `MediaUrlPolicy.absolutize`，且**收口在 `AppImageLoader` 的 Coil mapper 里**，
不要在每个调用点各写一遍（新增调用点必然有人忘）。

**两个坑**：
- `BuildConfig.BASE_URL` 是 `https://域名/api/v1`，**带 API 路径**；图片挂在根路径。
  必须先取 origin（`MediaUrlPolicy.originOf`）再拼，否则得到 `/api/v1/media/1/thumb` → 404。
- 带 scheme 的一律不能动：`content://` / `file://` 是本机原图
  （`LocalPhotoIndex` 的"读本机原图"优化），改写就等于把那条优化改坏。

### 2.11 图片位必须有占位底色

相册网格与相册封面此前没有背景色，**加载失败就是字面意义上的透明**——
这让 2.10 那个 bug 完全隐形。任何图片位都要有占位底色，
失败时是可见的灰格子而不是什么都没有。

### 2.12 惰性缓存不能放在 rows 遍历路径上

`MaxOpenConns(1)` 下，「遍历 rows 期间再发查询」会永久死锁（2.2 已提过）。
**惰性缓存是这个坑的隐形版本**：`siteBaseURL()` 有缓存、注释也写了防死锁，
但**第一次调用仍要查库** —— 而它被 `scanPhoto` 在 4 处 rows 循环里调用。

0824 实测：库里有过期回收站照片时，启动同步跑的 `runRetentionCleanup` 会
在冷缓存下挂死那条唯一连接，**容器起来后全站不可用**，只能重启。
新库与现有测试都撞不到（无回收站数据 / `st==nil` 时短路）。

规则：**会在 rows 遍历中被调用的函数，绝不允许查库**。加载责任交给显式的
预热函数（启动时 + 配置变更后各一次），冷态直接返回降级值。
让死锁在结构上不可能，而不是靠"第一次之后就有缓存了"。

### 2.13 OFFSET 分页 + LazyColumn key = 崩

服务端 `LIMIT ? OFFSET ?` + 客户端拿「已加载条数」当 offset，在**列表持续增长**时
必然错位：拉下一页前若有新数据写入，全体下移，offset 会把上一页最后那条再返回一次。
`items(key = ...)` 撞重复 key 直接抛 `IllegalArgumentException`。

数据库唯一约束拦不住 —— 每条记录本身都合法，重复只存在于客户端拼出来的那份列表里。

追加时一律走 `PagingMerge.appendDistinct` 去重，并用 `allDuplicates` 判「到底了」
（否则整页全重时会无限拉同一页）。触发场景是真实的：状态历史每 5 分钟一条，
傍晚滚到底必崩，而滚到底是 `LaunchedEffect` 自动的；相册则是**对方**上传时触发，
自己上传走整页重载，所以自测发现不了。

### 2.14 值切片遍历改不了元素

`for _, p := range list`（`list` 是 `[]T` 而非 `[]*T`）拿到的是**副本**。
往副本上赋值等于什么都没做，而如果该字段带 `omitempty`，结果是**它从 JSON 里彻底消失**
—— 不是 0、不是 null，是没有这个字段。客户端 `optInt` 拿到 0，
「还剩 0 天自动删除」比不显示更糟，是错误信息。

改元素一律用下标：`for i := range list { list[i].X = ... }`。

### 2.15 后台的状态类接口必须校验 err 与取值白名单

`c.ShouldBindJSON(&req)` 不检查返回值 + status 不做白名单，后果是：
body 解析失败时 `req.Status` 是零值 0，而 `authUserByToken` 认为 `status != 1` 即禁用
→ **一次格式错误的请求就把用户静默封禁**；传 `status: 7` 也能落库，
而后台前端只提供 1/2 两个选项，写进去之后没有任何界面能改回来。

`handleAdminSetAdminStatus` 是做对了的范本：检查 err 且限定取值。

### 2.16 后台配置优先于常量

新增可调参数一律加到 `server/settings.go` 的 `runtimeSettingSpecs`，
**不要新增环境变量、不要改 docker-compose**（管理员明确不想动服务器上的
compose 与 .env）。加一行 spec 即自动获得：读写、范围收敛、默认值下发、
后台渲染、一键恢复默认。

**加完 spec 必须回去把读取端也改掉。** 0827 查出四项配置是死的
（响铃冷却、互动冷却、两个 token TTL）：spec 加了、后台能改能存，
但读取处仍是 `cfg.App.*` 或代码常量。比"没有这个开关"更糟的是
**冷却值还会通过 `/client-config` 下发给客户端**：客户端按新值解禁按钮、
服务端按旧值拒绝，用户点了就报"过于频繁"。
一个配置项被两边用不同的值解释，比它压根不生效更难排查。

新增 spec 后自检两件事：① `grep` 一遍该字段在非 settings.go 里的读取点；
② 若它会下发给客户端，确认服务端判定读的是同一个来源。

### 2.17 惰性过期不等于会被回收

`memStore` 那五张表原先"全部带惰性过期"，读注释像是没问题的。
但惰性过期的前提是**键过期后还会被再读一次**，而 0827 打死生产的两类键
恰恰永远不会被读第二次：

- `login:fail:<账号名>` —— 键含**请求体里的账号名**，登录接口公开、
  账号名不校验存在性也不限长度。枚举不存在的账号 = 每次都留一条永久条目；
- `media:cnt:<日期>:<uid>` —— 按**日期**分桶，过了今天没人会再读那个桶。

雪上加霜的是 `count()` 过期分支只 `return 0` 不 `delete`，
连"被读到时顺手清掉"都是断的（`del()` 只在**登录成功**时调用，
而垃圾全部来自失败的那些）。

规则：**任何 key 含外部输入或含时间分桶的缓存，必须有主动清扫**，
不能只靠惰性过期。且三件事一起做才算完：

1. 定时 sweeper（周期远小于最短 TTL）；
2. key 长度收敛 —— **用哈希不用截断**。截断会让两个不同的超长 key
   撞成同一个，攻击者可借此替他人清零限流计数；
3. 容量上限 + 到顶淘汰。让"内存无界"在结构上不可能，
   而不是指望清扫一定跑得过增长。

淘汰策略选"最早过期"而非"拒绝写入"：拒绝写入意味着攻击者把表填满
就能让限流计数器写不进去 —— 那是 fail-open。

### 2.18 零值即"永不超时"的字段必须显式设置

`http.Server` 的 `ReadTimeout` / `WriteTimeout` / `IdleTimeout` /
`ReadHeaderTimeout` 零值全是**永不超时**；`net.Dial` / `tls.Dial` /
`smtp.SendMail` 同理；gorilla 的 `WriteMessage` 不设 deadline 也会永久阻塞。

这类缺失**没有任何错误日志**，现象只是内存与 goroutine 数缓慢上涨，
所以极难从日志定位。0827 三处都踩了：HTTP 服务器四个超时全缺、
WS 写无 deadline（打死全站待办提醒）、SMTP 无超时（黑洞主机挂住请求协程）。

两个取值注意点：

- **读写超时要覆盖最大的那次正常传输**。单张照片上限 20MB，
  弱网传完可能几分钟，所以给 5 分钟而不是常见的 30 秒 ——
  设小了弱网用户会在上传大图时被服务端掐断，现象和"上传总是失败"一样。
- **WS 不受 http.Server 超时影响**：gorilla 在 Hijack 后会
  `SetDeadline(零值)` 清掉（`server.go:251`），此后必须由自己的读写超时接管。

### 2.19 位图回收要写在 finally 里

`recycle()` 写在正常流程末尾时，磁盘满、编码失败、或**首次 OOM**
都会跳过它。而上传失败会被标成"可重试"，用户点重试就在内存已紧张的情况下
再走一遍、再漏一份 —— **每次重试都让下一次更容易失败**，
表现就是管理员反馈过的"照片会消失"。

另外 `Bitmap.createBitmap(src, ..., matrix, true)` 在返回前
**源图与目标图同时在堆上**，这是 API 语义决定的。所以旋正与缩放
要合并成一次矩阵变换，不能拆成两次调用（否则双份峰值经历两遍）。
竖拍的 EXIF 方向是常态不是边缘情况，一次选图上限 100 张。

### 2.20 功能开关写了要挂上，且服务端必须校验

`requireAlbumEnabled` 定义了却**零挂载**，于是「相册功能总开关」关掉后
只有上传被 handler 内部的检查挡住，建相册/改名/挂照片/评论点赞照旧可写。
`album.social_enabled` 与 `on_this_day_enabled` 更彻底 ——
**服务端零校验，纯靠客户端隐藏入口**，对旧版 App 与直接调接口的人完全无效。

开关的意义是出故障时能立刻止损，"只关掉入口的一半"比没有开关更危险
（管理员以为已经止损了）。新增开关后必须 grep 它的挂载点，
并确认写操作真的会被拒。

### 2.21 权限分组要按"破坏力"而不是"含不含密钥"

运行参数整体的收敛理由是"不含密钥所以放给普通 admin"，
但这批参数里混着破坏力完全不同的东西：

- `retention.*` 是**不可逆销毁**开关 —— 回收站保留期改小，
  下次定时清理就真删库行 + 真删磁盘文件，用户以为能恢复的照片几小时内消失；
- `security.*` 是**防护强度**开关 —— 往上调等于削弱爆破防护。

这两组已标 `Super: true`，与 SMTP 同级。判断标准是
「这个值被恶意改动会造成什么」，不是「它是不是一个密钥」。

同理，**审计日志的脱敏要用白名单**。原先黑名单只匹配
`password`/`secret`/`access_key`，于是 `smtp.host`/`smtp.username`
被明文写进普通 admin 能读的审计表 —— 而 `GET /settings` 收敛到超管的
理由正是"主机与账号本身就是攻击面"，等于收敛被绕过。
白名单的默认行为是脱敏，新增的敏感键自动落在安全那侧；
黑名单的默认行为是明文，漏一个就泄露一个。

### 2.22 凭据类字段一律不下发给后台

后台的绑定关系列表原先把 `invite_code` 原样下发。
**挂起的邀请码就是"成为某人伴侣"的凭据本身**：任何管理员抄走它、
注册个账号调 `/pair/bind` 就能绑定成功，从此对方相册、`/media/<id>`、
待办、状态历史（含 SSID 与前台应用）全部合法可读 ——
而那些接口早就特意收敛到超管了，这条口子把收敛整个绕过。

后台需要的是**状态**（"有没有挂起邀请"），不是凭据。
**也不要做部分脱敏**：邀请码只有 8 位，泄露任何一段都在成倍缩小爆破空间。

### 2.23 认证失败的响应必须无法区分原因

后台登录原先"先验密码、通过了再看封禁状态"，于是密码错回 400、
密码对但已禁用回 403 —— 拿已禁用账号爆破时，403 就等于"这个口令是对的"。
而账号被禁用往往正是因为它已经不可信（离职/疑似泄露）。

规则：账号不存在、密码错误、账号被禁用，三者必须返回**完全一致**的
状态码与文案，且都要计入失败限流。

### 2.24 "内存与尺寸相关"的判断必须实测，不能靠直觉

0828 的真凶不是解码，是**缩放**。`draw.CatmullRom.Scale` 会分配一块
临时缓冲，而它的大小是 **目标宽 × 源高 × 32 字节** —— 与源图高度成正比，
不是"只与目标尺寸有关"。实测把公式钉死了：

| 源 | 目标 | 实测 | 公式 |
|---|---|---|---|
| 8000×8000 | 384×384 | 94.77 MB | 93.75 MB |
| 3464×3464 | 1080×1080 | 114.69 MB | 114.17 MB |
| 同上，`ApproxBiLinear` | | 0.00 MB | 不分配该缓冲 |

所以缩略图虽然只有 384，却因为源图高而极贵；相册每张跑两次
（缩略图 + 预览图），单段走完约 157 MB，3 路并发闸门最坏约 470 MB。
**这与图片格式无关**：一张合法的大 PNG 就能触发，文件不大、帧数为 1、
所有既有校验都放行 —— 比 GIF 帧炸弹更隐蔽，且 0827 那轮针对帧数与
并发的修复对它完全不管用。

修法是两段式（`scaleInto`）：先用 `ApproxBiLinear` 廉价缩到目标的 1.25 倍，
再用高质量插值器收尾。公式里的"源高"从原图高度降到目标高度的 1.25 倍，
**与原图多大彻底脱钩**，缩略图 94.77 MB → 6.60 MB。

剩下的是 API 下限而非"没优化到"：1080 预览图第二段固定约 44.8 MB，
即便 `prescaleFactor` 取 1（第二段退化成等尺寸重采样、画质白给）
也仍需约 35.8 MB。压不到更低，除非换插值器 —— 那是画质取舍。

规则：凡是"这段代码要占多少内存"的结论，**先写个临时测试打印
`runtime.MemStats.TotalAlloc`**，确认实测与公式吻合，再据此定阈值。
本轮就是这样把我原先写的"400MB"改成了实测的 157MB。

### 2.25 测试通过不等于测试有效

新增测试后**必须临时改坏实现、确认它真的会红**（2.8 已提过），
但 0828-0829 又踩出三种"绿着的哑弹"：

**① 只断言错误码，等于没测。** GIF 帧炸弹在"先扫描后解码"与
"先 `DecodeAll` 再判帧数"两种实现下返回**完全相同**的
`ErrAvatarTooManyFrames`，而内存差 1000 倍（0.0 MB vs 981.9 MB）。
只断言错误的测试在两种实现下都绿。修内存/性能问题时，
**必须度量那个量本身**。

**② 阈值贴着实测值 = 定时哑弹。** 派生图内存测试原先阈值 64 MB、
实测 63.3 MB，只差 0.7 MB。这不代表"卡得严"，只代表它迟早以假警报
的形式浪费一次排查，然后被当成抖动调高、彻底失效。
阈值要落在「正确实现」与「错误实现」**之间且两侧都留足余量**：
改成 96 MB（正确 63 MB、单段 157 MB），两侧各约 1.5 倍。

**③ 概率性测试要跑到确定性为止。** 单账号在飞上限的并发测试，
单轮只有约 1/4 概率抓到"先查后写"的击穿 —— 一个 3/4 概率放过 bug
的测试等于没有，它会以"CI 偶尔红一次"的形式存在然后被当成抖动忽略。
改成 64 并发 × 60 轮 + 累计判定后，破坏实现跑 15 次全红。

另外**测试自身不得依赖运行时刻**：`TestBatteryCurveWithNullColumns`
拿 `time.Now().Truncate(5*time.Minute)` 做基准，在本地 00:00~00:10
之间必红（基准减 10 分钟跨到前一天，被"当日"查询排除 → 只剩 2 点）。
0829 00:09 实测撞上。凡涉及"当日"的测试，把基准锚到当日正午这类
与运行时刻无关的点。

---

## 三、提交前自检

三端都要过，缺一不可：

```bash
# Go
cd server && gofmt -l .        # 空 = 通过
go vet ./... && go test -timeout 400s ./...

# Android
cd android && gradle :app:compileDebugKotlin --no-daemon
gradle :app:testDebugUnitTest --no-daemon

# 后台
cd admin && npm run build
```

**不得提交**：
- `server/webdist/`（后台构建产物，`go:embed` 用；`.gitignore` 已配
  `server/webdist/*` + `!index.html`。曾误提交过 139 个文件）
- `server/*.exe`（编译二进制，14MB+）
- `android/local.properties`（本机 SDK 路径）
- 任何密钥：`JWT_SECRET` / `APP_KEY` 走 env 与 GitHub Secret，绝不进仓库

---

## 四、CI 与部署

- `build-server.yml`：push main 且改了 `server/**`|`admin/**`|`Dockerfile` 时触发，
  构建镜像推 ghcr + 导出 tar 作 Artifact。**不自动部署生产**
- `build-android.yml`：仅手动触发，需传 BASE_URL/WS_URL/APP_KEY/版本/构建类型
- `release.yml`：手动触发发行版，`versionCode` 由 tag 推导（`v1.2.3`→`10203`）
- **线上后台看不到新改动 ≠ 代码有问题**：后台是 `go:embed` 进二进制的，
  线上跑的是镜像里那一份。要 `docker compose pull && docker compose up -d`

---

## 五、验证的边界

**必须实际跑，不能只看"文件存在 / 编译通过"。**

0820 的教训：管理员报「后台白屏」，我回答"前端是好的、你拉的是旧镜像"——
镜像旧这半对，但我把它当成了全部原因。真凶是我自己加的 CSP
（`script-src 'self'` 拦掉了 Vite 产物的 inline 引导脚本），
HTML 与接口全是 200，**只有真开浏览器才看得到**。

- 后台改动：用 `admin/scripts/mobile-audit.mjs`（Playwright）逐页跑，
  走完 登录 → 首登改密 → 每一个菜单页。只测登录页会漏掉主界面的问题
- 服务端改动：写 Go 测试，尤其是"NULL 列"、"并发"、"清理 SQL 真删到行"这三类
- 客户端纯逻辑：抽成无 Android 依赖的 policy 对象再单测
  （`Uri.parse` 等在 JVM 单测里不可用，把规则和 Android 类型耦在一起等于永远测不了）
- 真机行为（相册、响铃、状态同步、观感）只能由管理员在一加 15 上验

### CSP 的既定结论（别再收紧回去）

- `script-src` 必须带 `'unsafe-inline' 'unsafe-eval'` —— Vite 产物依赖 inline 引导脚本
- `style-src` 必须带 `'unsafe-inline'` —— Vue 运行时注入 inline style
- `connect-src` 必须放行 `api.iconify.design` / `api.simplesvg.com` / `api.unisvg.com`
  —— 后台用 `@iconify/vue` **在线**按需拉图标，拦掉后菜单与按钮全无图标

---

## 六、隐私底线

相册照片是情侣私密内容，这条线不能松：

- 对外 URL 一律 `/media/<id>` 鉴权代理形态，**真实磁盘路径不出服务端**
- `/upload` 与 `/uploads` 静态目录无鉴权，只靠随机文件名保密 →
  这类路径**不得进 request_log**（`netlog.go` 的 skip 列表），
  否则任何管理员都能从网络日志页直接点开
- 后台查看缩略图（管理员 Q28=D 要求）**只给 384 缩略图、永不给原图**，
  且每次查看写审计 + 响应头带 `no-store` 与 `Referrer-Policy: no-referrer`
- 敏感设置（SMTP/存储密钥）限超管；不含密钥的运行参数才对普通 admin 开放
