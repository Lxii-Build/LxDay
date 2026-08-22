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

### 2.12 后台配置优先于常量

新增可调参数一律加到 `server/settings.go` 的 `runtimeSettingSpecs`，
**不要新增环境变量、不要改 docker-compose**（管理员明确不想动服务器上的
compose 与 .env）。加一行 spec 即自动获得：读写、范围收敛、默认值下发、
后台渲染、一键恢复默认。

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
