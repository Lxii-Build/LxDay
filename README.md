# 林曦日记 · 完整开发工程

双人情侣专属互动 APP（安卓）+ 运营后台（Web）+ Go 服务端：账号登录/注册、伴侣绑定、实时状态掌控、循环待办提醒、发现页、共同互动。项目将小范围免费使用，部署见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

## 工程结构

```
lx/
├── docker-compose.yml       # 一键部署（Go + MySQL + Redis + Nginx[后台静态+反代]）
├── deploy/                  # 部署配置：nginx.conf / config.docker.yaml
├── docs/DEPLOYMENT.md       # 三种部署方式说明（Compose/单容器/手动）
├── server/                  # Go 服务端（Gin + WebSocket + MySQL + Redis）
│   ├── main.go              # 入口 + 路由 + 配置
│   ├── account.go           # 账号：注册/登录/邮箱验证码(可配置SMTP)/扩展资料
│   ├── admin.go             # 后台 /api/admin/*（JWT+RBAC，独立 {code,msg,data} 信封）
│   ├── appversion.go        # APP 版本发布 + 客户端检查更新
│   ├── models.go / store.go / hub.go / handlers.go / migrations.go
│   ├── sql/schema.sql       # 建库建表
│   └── Dockerfile
│
├── admin/                   # 运营后台前端（Vue3 + TS + ElementPlus + Vite + Pinia）
│   └── Dockerfile           # 数据看板/用户/绑定/内容审核/版本/通知/设置/审计/管理员
│
├── android/                 # 安卓端（Kotlin + Compose / minSdk 29 / miuix）
│   └── app/src/main/java/com/linxi/diary/  # ui(theme/components/navigation/screens) / core / data / sync
│
└── .github/workflows/       # ci.yml(Go测试+安卓debug/release构建) / deploy.yml(服务端部署)
```

## 文档导航

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 系统架构图、模块划分、时序、部署、ER、状态机 |
| [DESIGN.md](DESIGN.md) | 全部设计决策（含决策速查表、与骨架差异清单） |
| [server/README.md](server/README.md) | 服务端接口速览 + 启动说明 |
| [android/README.md](android/README.md) | 权限清单、保活、Gradle、待补资源 |

## 核心设计决策（摘要）

- **数据隔离**：`pair_id` 作为所有业务数据（待办/日记/状态历史）的隔离键，仅双人可见。
- **实时通道**：WebSocket 在线直转；离线高优事件入 Redis 补偿队列，重连后补拉。
- **不接商业推送**：私人直装零门槛路线（小米/OPPO/vivo/荣耀均强制上架才给推送权限），纯 WS + 本地 AlarmManager 兜底。
- **图片存储**：服务器本地磁盘 + Nginx 静态服务（`/uploads/`），不接 OSS。
- **保活**：前台服务（dataSync|location）为主 + 电池白名单 + 开机自启 + vivo/OPPO 白名单引导。
- **响铃**：闹钟音量流绕过静音/振动；勿扰授权后切「仅闹钟」；Total Silence 无法绕过（系统级，已如实说明）。
- **隐私**：首次绑定强制双方知情授权页 + 状态共享总开关（关闭即停采+本地清空）+ 传输全链路 TLS。
- **历史**：5 分钟粒度状态历史永久保留，时间线 + 24h 电量曲线双呈现。
- **绑定**：6 位数字邀请码、1 小时有效；不提供解绑（终身制）；仅「退出登录」清本地 token。

## CI

`.github/workflows/ci.yml`：push/PR 到 main 时运行
- Go 服务端：`go mod tidy` → `go vet` → `go build` → `go test`
- 安卓源码：XML 格式校验 + 关键文件存在性检查
