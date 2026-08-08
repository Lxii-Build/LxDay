# 林曦日记 · 完整开发工程

双人情侣专属互动 APP：实时状态掌控 + 轻量远程互动 + 双人共同日记。

## 工程结构

```
lx/
├── ARCHITECTURE.md          # 系统架构产物（Mermaid 图集）
├── DESIGN.md                # 整合设计文档（五轮访谈决策固化）
├── MilkGlassDesignScheme.md # UI 视觉规范（原始文件）
├── server/                  # Go 服务端（Gin + WebSocket + MySQL + Redis）
│   ├── main.go              # 入口 + 路由 + 配置
│   ├── models.go            # 数据模型 + WS 消息协议
│   ├── store.go             # MySQL/Redis 存储层
│   ├── hub.go               # WebSocket 实时通道
│   ├── push.go              # 推送网关适配层（预留占位）
│   ├── handlers.go          # HTTP handler + JWT + 定时扫描
│   ├── config.example.yaml  # 配置示例
│   ├── sql/schema.sql       # 建库建表
│   └── README.md            # 服务端启动说明 + 接口速览
│
├── android/                 # 安卓端（Kotlin + Compose / minSdk 29）
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── res/             # values(values-night) / layout
│       └── java/com/linxi/diary/
│           ├── App.kt / MainActivity.kt / RingActivity.kt
│           ├── service/StatusForegroundService.kt   # 常驻卡片
│           ├── sync/StatusSyncManager.kt            # WebSocket
│           ├── core/                                # 采集/响铃/待办/权限
│           ├── data/                                # ApiClient / DTO
│           ├── ui/                                  # theme/components/navigation/screens
│           └── util/Utils.kt
│   └── README.md            # 权限/保活/Gradle/已知待补
│
└── .github/workflows/ci.yml # GitHub Actions：Go 构建测试 + 安卓源码校验
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
