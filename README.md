# 林曦日记 · 完整开发工程

双人情侣专属互动 APP（安卓）+ 运营后台（Web）+ Go 服务端：账号登录/注册、伴侣绑定、实时状态共享、循环待办提醒、**共同相册**、**共同日记**、发现页、共同互动。项目将小范围免费使用。部署见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)；更新日志见 [CHANGELOG.md](CHANGELOG.md)；应用介绍见 [docs/APP_INTRO.md](docs/APP_INTRO.md)。

## 功能一览

| 模块 | 能力 |
|---|---|
| 账号与绑定 | 邮箱注册/登录、8 位混合字符邀请码绑定（1 小时有效）、主动解绑双向生效 |
| 实时状态 | 电量/充电/亮灭屏/前台应用/WiFi 实时共享；常驻通知卡；状态历史与电量曲线 |
| 互动 | 求陪伴 / 求冷静 / 强制响铃（7 秒自动停止 + 双向撤回） |
| 待办 | 双向待办、仅一次/每天/每周提醒、强提醒、本地闹钟兜底 |
| **相册** | 相册列表与详情网格、大图查看（Pager + 双指缩放）、「这一天」、评论、点赞、软删除（回收站接口已就绪，客户端入口待补）；接口见 [docs/ALBUM.md](docs/ALBUM.md) |
| **日记** | 共同日记、图片上传 |
| 运营后台 | 数据看板、用户/绑定管理、内容审核（待办 / 日记 / **相册照片**）、版本发布、通知、审计与网络日志、管理员管理 |

## 技术栈

- **安卓端**：Kotlin + Compose（minSdk 29）、miuix 设计体系、**Coil 3**（图片加载，内存 + 磁盘缓存）、OkHttp、WebSocket、AlarmManager。
- **服务端**：Go + Gin、内嵌 SQLite、WebSocket Hub、进程内存态；图片解码为**纯 Go**（`image/jpeg|png|gif` + `golang.org/x/image/webp`），不依赖 libvips 等系统库。
- **运营后台**：Vue 3 + TypeScript + Element Plus + Vite + Pinia。

## 架构与部署形态

**一体化镜像（单容器）**：Go 服务端通过 `go:embed` 内嵌运营后台前端产物，自托管 后台静态 / API / WebSocket / `/uploads` 上传文件，**不再依赖 Nginx**；数据库用**内嵌 SQLite**、缓存/在线态/离线队列改**进程内存**，因此**容器编排只有一个 `app` 容器**（无 MySQL、无 Redis）。HTTPS/WSS 的 TLS 由外部反向代理（宝塔面板 / Nginx / Caddy）终止；**容器内外端口统一 `7740`**。**数据库零手动导入**：服务端启动内嵌 `schema.sql` 自动建表；SQLite 文件与上传目录挂载到数据卷持久化。密钥（`JWT_SECRET` / `APP_KEY`）经 `.env` 注入，不写入提交的配置文件。

```
lx/
├── Dockerfile                # 一体化多阶段镜像：node 构建 admin dist → go 内嵌编译 → 精简运行时
├── docker-compose.yml        # 一键部署：单容器(默认从 GHCR 拉取镜像)，内嵌 SQLite（无 mysql/redis）
├── deploy/config.docker.yaml # 容器内服务端配置（端口 7740 / db.path SQLite；密钥走 .env 的 JWT_SECRET/APP_KEY）
├── .env.example              # 密钥模板：复制为 .env 填 JWT_SECRET / APP_KEY（勿提交）
├── docs/                     # DEPLOYMENT.md 部署 · SIGNING.md 安卓签名 · ALBUM.md 相册接口 · APP_INTRO.md 等
├── CHANGELOG.md              # 版本更新日志
├── server/                   # Go 服务端（Gin + WebSocket + 内嵌 SQLite + 进程内存态）
│   ├── main.go               # 入口 + 路由 + 配置；AppKeyGuard 通讯密钥中间件
│   ├── static.go             # 去 Nginx：内嵌 SPA 后台 + /uploads 静态 + /healthz
│   ├── admin.go              # 后台 /api/admin/*（JWT+RBAC，{code,msg,data} 信封）
│   ├── album_handlers.go     # 相册接口；album_media.go 上传 + /media 鉴权代理；album_store.go 数据访问
│   ├── avatar_*.go / exif.go # 纯 Go 图片处理链（解码/缩放/EXIF），不依赖 libvips
│   ├── handlers.go / store.go / models.go / hub.go / migrations.go
│   ├── sql/schema.sql        # 建库建表
│   └── Dockerfile            # 仅后端镜像（前后端分离部署用）
├── admin/                    # 运营后台前端（Vue3 + TS + ElementPlus + Vite + Pinia）
│   └── Dockerfile            # 仅前端 Nginx 镜像（前后端分离部署用）
├── android/                  # 安卓端（Kotlin + Compose / minSdk 29 / miuix）
│   └── app/src/main/java/com/linxi/diary/  # ui(theme/components/navigation/screens) / core / data / sync
└── .github/workflows/        # build-server.yml / build-android.yml / release.yml
```

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 三种部署（Compose 为主 / 单容器 / 前后端分离）+ 反代 TLS + 通讯密钥 + 初始账号 |
| [docs/SIGNING.md](docs/SIGNING.md) | **安卓签名**：固定签名密钥的生成、CI Secret 配置、指纹校验与轮换 |
| [docs/ALBUM.md](docs/ALBUM.md) | 相册接口文档：数据模型、全部接口、`/media` 鉴权代理、上传配额 |
| [docs/APP_INTRO.md](docs/APP_INTRO.md) | 应用介绍（首发文案） |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新日志 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统架构图、模块划分、时序、ER、状态机 |
| [DESIGN.md](DESIGN.md) | 设计决策（含决策速查表） |
| [server/README.md](server/README.md) | 服务端接口速览 + 启动说明 |
| [android/README.md](android/README.md) | 权限清单、保活、Gradle |

## 核心设计决策（摘要）

- **数据隔离**：`pair_id` 作为所有业务数据（待办/日记/相册/状态历史）的隔离键，仅双人可见。
- **实时通道**：WebSocket 在线直转；离线高优事件入**进程内存**补偿队列，重连补拉（单容器单实例）。
- **数据与存储**：内嵌 **SQLite**（单文件，启动自动建表、零手动导入）；在线态/伴侣状态缓存/离线队列/限频均为**进程内存**；图片走服务器本地磁盘、Go 自托管 `/uploads/`。
- **通讯密钥（可选）**：构建期把 `APP_KEY` 注入 APK，请求带 `X-App-Key` 头；服务端 `app_key`（或环境变量 `APP_KEY`）非空时校验 `/api/v1/*`，用于挡非官方客户端。留空即禁用。
- **不接商业推送**：纯 WS + 本地 AlarmManager 兜底。
- **图片存储**：服务器本地磁盘，Go 自托管 `/uploads/`（去 Nginx）；预留对象存储抽象。
- **相册隐私**：照片对外 URL 一律是鉴权代理 `/media/<id>`，**真实磁盘路径不出服务端**；只有该 pair 的成员能读，照片 URL 也不进网络日志。运营后台的照片审核页只给元数据、不给缩略图。详见 [docs/ALBUM.md](docs/ALBUM.md)。
- **图片处理纯 Go**：解码链只用标准库 + `x/image`，因为运行镜像是 alpine、没有 libvips；代价是不支持 HEIC/AVIF，故由客户端在上传前转成 JPEG。
- **待办提醒**：仅一次 / 每天 / 每周指定几天（全选=每天）+ 强提醒 + 提醒开关（`remind_enabled`，关闭保留待办但不提醒）；被提醒者可为情侣任一方。
- **隐私**：绑定后页内知情同意 Dialog + 状态共享总开关（关闭即停采+本地清空）；TLS 全链路。
- **绑定**：8 位混合字符邀请码（原 6 位纯数字可枚举）、1 小时有效、每账号 10 分钟 5 次尝试上限；账号体系登录后用邀请码绑定伴侣；邀请方生成码后轮询绑定状态，对方一绑定自动进入主界面（服务端并推 `paired` 事件）。
- **后台安全**：超级管理员初始口令随机生成并写入 `0600` 权限文件（不再打进日志）+ 首登强制改密；后台 token 有效期 2 小时 + `token_ver` 即时撤销；鉴权实时读库不信 token 里的角色与状态；敏感路由全部收敛到超管；登录失败限流；「网络日志」记录 API 请求（方法/路径/状态/耗时/IP/UA，留 7 天）。

## 构建 / 发布（GitHub Actions）

- **build-server.yml**：`push` 到 main（`server/**`、`admin/**`、`Dockerfile`）或手动触发 → 先 `go vet`/`go test` → Buildx 构建一体化镜像并推送 `ghcr.io/lxii-build/lxday`（`latest` + 短 SHA）；仓库私有，**额外导出镜像 `.tar.gz` 作为工作流产物**，国内可下载后 `docker load` 离线导入。
- **build-android.yml**：手动触发，输入 **服务端地址 / 通讯密钥 / 构建类型(Debug/Release) / 版本号** → 跑单测 → 产出对应 APK 工件。
- **release.yml**：手动触发的**发行版**（通常由 AI 收到命令后触发）→ 构建 Release APK + 推带版本 tag 的镜像 + 创建 GitHub Release（附 APK、正文关联镜像 tag 与 `CHANGELOG.md`）。首发同时提供《应用介绍》，此后每版补充更新日志。版本号由 tag 推导：`v1.2.3` → `versionName=1.2.3`、`versionCode=10203`。

### 安卓签名

APK 使用**固定签名密钥**（PKCS#12，指纹恒定），因此用户可以直接覆盖安装升级，不必卸载重装。
密钥生成、CI Secret 配置、指纹校验与轮换流程见 **[docs/SIGNING.md](docs/SIGNING.md)**。
发行版流水线会打印 APK 签名指纹到 Job Summary 并做指纹断言，指纹一旦变化流水线即失败。
