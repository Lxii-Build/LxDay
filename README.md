# 林曦日记 · 完整开发工程

双人情侣专属互动 APP：实时状态掌控 + 轻量远程互动 + 双人共同日记。

## 工程结构

```
lx/
├── server/                  # Go 服务端（Gin + WebSocket + MySQL + Redis）
│   ├── main.go              # 入口 + 路由 + 配置
│   ├── models.go            # 数据模型 + WS 消息协议
│   ├── store.go             # MySQL/Redis 存储层
│   ├── hub.go               # WebSocket 实时通道
│   ├── push.go              # 推送网关适配层（个推/极光）
│   ├── handlers.go          # HTTP handler + JWT
│   ├── config.example.yaml  # 配置示例
│   ├── sql/schema.sql       # 建库建表
│   └── README.md            # 服务端启动说明 + 接口速览
│
├── android/                 # 安卓端（Kotlin / minSdk 29）
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── res/layout/notification_expanded.xml
│       └── java/com/linxi/diary/
│           ├── App.kt / MainActivity.kt / RingActivity.kt
│           ├── service/StatusForegroundService.kt   # 常驻卡片
│           ├── sync/StatusSyncManager.kt            # WebSocket
│           ├── core/                                # 采集/响铃/监听
│           └── util/Utils.kt
│   └── README.md            # 权限/保活/推送/Gradle/iOS 限制
│
└── MilkGlassDesignScheme.md # UI 视觉规范（原始文件）
```

## 开发里程碑

1. **M1** 服务端骨架：Auth/绑定/WS/REST + 数据库 + 安卓绑定流程
2. **M2** 安卓状态采集 + 常驻卡片 + WS 实时同步（核心验收点）
3. **M3** 远程互动（响铃/求陪伴/求冷静/待办）+ 推送接入 + 保活适配
4. **M4** 日记模块 + OSS 直传 + 隐私合规页 + 全厂商真机适配
5. **M5** iOS 轻量伴侣端（状态仅展示 Android 侧数据）

## 关键设计决策

- **数据隔离**：`pair_id` 作为所有业务数据（待办/日记）的隔离键，仅双人可见。
- **实时通道**：WebSocket 在线直转；离线高优事件入 Redis 补偿队列 + 厂商推送兜底。
- **保活**：前台服务（dataSync|location）为主 + 电池白名单 + 开机自启 + 厂商白名单引导。
- **响铃**：闹钟音量流绕过静音/振动；勿扰授权后切「仅闹钟」；Total Silence 无法绕过（系统级）。
- **隐私**：首次绑定双方知情授权页 + 数据加密传输 + 注销即级联删除。

## 上架合规必做

- 隐私政策：列明采集项（电量/前台APP/用量/WiFi/音乐）、用途、存储周期
- 状态共享开关：任一关闭则停止采集并本地清空
- 响铃冷却（服务端已实现 Redis 限频，10 分钟 3 次）
- 账号注销入口
