# 林曦日记 · 完整开发工程

双人情侣专属互动 APP（安卓）+ 运营后台（Web）+ Go 服务端：账号登录/注册、伴侣绑定、实时状态共享、循环待办提醒、发现页、共同互动。项目将小范围免费使用。部署见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)；更新日志见 [CHANGELOG.md](CHANGELOG.md)；应用介绍见 [docs/APP_INTRO.md](docs/APP_INTRO.md)。

## 架构与部署形态

**一体化镜像**：Go 服务端通过 `go:embed` 内嵌运营后台前端产物，自托管 后台静态 / API / WebSocket / `/uploads` 上传文件，**不再依赖 Nginx**。容器编排为 应用 + 数据库（MySQL + Redis）。HTTPS/WSS 的 TLS 由外部反向代理（宝塔面板 / Nginx / Caddy）终止；**容器内外端口统一 `7740`**（宝塔容器列表显示 7740 → 7740）。**数据库免手动导入**：服务端启动内嵌 `schema.sql` 自动建表，任何环境零手工 source。密钥（`JWT_SECRET` / `APP_KEY`）经 `.env` 环境变量注入，不写入提交的配置文件。

```
lx/
├── Dockerfile                # 一体化多阶段镜像：node 构建 admin dist → go 内嵌编译 → 精简运行时
├── docker-compose.yml        # 一键部署：server(7740) + mysql + redis（无 nginx）
├── deploy/config.docker.yaml # 容器内服务端配置（端口 7740 / dsn / redis…；密钥走 .env 的 JWT_SECRET/APP_KEY）
├── .env.example              # 密钥模板：复制为 .env 填 JWT_SECRET / APP_KEY（勿提交）
├── docs/                     # DEPLOYMENT.md 部署 · APP_INTRO.md 应用介绍 · android-ui.md 等
├── CHANGELOG.md              # 版本更新日志
├── server/                   # Go 服务端（Gin + WebSocket + MySQL + Redis）
│   ├── main.go               # 入口 + 路由 + 配置；AppKeyGuard 通讯密钥中间件
│   ├── static.go             # 去 Nginx：内嵌 SPA 后台 + /uploads 静态 + /healthz
│   ├── admin.go              # 后台 /api/admin/*（JWT+RBAC，{code,msg,data} 信封）
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
| [docs/APP_INTRO.md](docs/APP_INTRO.md) | 应用介绍（首发文案） |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新日志 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统架构图、模块划分、时序、ER、状态机 |
| [DESIGN.md](DESIGN.md) | 设计决策（含决策速查表） |
| [server/README.md](server/README.md) | 服务端接口速览 + 启动说明 |
| [android/README.md](android/README.md) | 权限清单、保活、Gradle |

## 核心设计决策（摘要）

- **数据隔离**：`pair_id` 作为所有业务数据（待办/日记/状态历史）的隔离键，仅双人可见。
- **实时通道**：WebSocket 在线直转；离线高优事件入 Redis 补偿队列，重连补拉。
- **通讯密钥（可选）**：构建期把 `APP_KEY` 注入 APK，请求带 `X-App-Key` 头；服务端 `app_key`（或环境变量 `APP_KEY`）非空时校验 `/api/v1/*`，用于挡非官方客户端。留空即禁用。
- **不接商业推送**：纯 WS + 本地 AlarmManager 兜底。
- **图片存储**：服务器本地磁盘，Go 自托管 `/uploads/`（去 Nginx）；预留对象存储抽象。
- **待办提醒**：仅一次 / 每天 / 每周指定几天（全选=每天）+ 强提醒 + 提醒开关（`remind_enabled`，关闭保留待办但不提醒）；被提醒者可为情侣任一方。
- **隐私**：绑定后页内知情同意 Dialog + 状态共享总开关（关闭即停采+本地清空）；TLS 全链路。
- **绑定**：6 位数字邀请码、1 小时有效；账号体系登录后用邀请码绑定伴侣；邀请方生成码后轮询绑定状态，对方一绑定自动进入主界面（服务端并推 `paired` 事件）。
- **后台安全**：超级管理员初始口令随机生成（仅启动日志打印一次）+ 首登强制改密；登录失败限流；敏感操作按角色校验；「网络日志」记录 API 请求（方法/路径/状态/耗时/IP/UA，留 7 天）。

## 构建 / 发布（GitHub Actions）

- **build-server.yml**：`push` 到 main（`server/**`、`admin/**`、`Dockerfile`）或手动触发 → 先 `go vet`/`go test` → Buildx 构建一体化镜像并推送 `ghcr.io/lxii-build/lxday`（`latest` + 短 SHA）；仓库私有，**额外导出镜像 `.tar.gz` 作为工作流产物**，国内可下载后 `docker load` 离线导入。
- **build-android.yml**：手动触发，输入 **服务端地址 / 通讯密钥 / 构建类型(Debug/Release) / 版本号** → 跑单测 → 产出对应 APK 工件。
- **release.yml**：手动触发的**发行版**（通常由 AI 收到命令后触发）→ 构建 Release APK + 推带版本 tag 的镜像 + 创建 GitHub Release（附 APK、正文关联镜像 tag 与 `CHANGELOG.md`）。首发同时提供《应用介绍》，此后每版补充更新日志。
