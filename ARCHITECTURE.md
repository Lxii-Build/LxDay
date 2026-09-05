# 系统架构

本文描述当前实现。历史讨论稿不再作为设计依据；变更历史请看 [CHANGELOG.md](CHANGELOG.md)，开发约束请看 [AGENTS.md](AGENTS.md)。

## 1. 部署拓扑

```text
Android A/B ── HTTPS/WSS ──┐
                           ├─ 外部 TLS 反向代理 ── localhost:7740
后台浏览器 ───── HTTPS ────┘                         │
                                                    v
                                      Go + Gin 单实例容器
                                      ├─ REST /api/v1
                                      ├─ WebSocket /ws
                                      ├─ 后台 SPA /
                                      ├─ 公开资源 /upload、/uploads
                                      └─ 私密媒体 /media/<id>
                                                    │
                                      SQLite + 持久化媒体目录
```

仓库根 `Dockerfile` 先构建 `admin`，再把产物复制到 `server/webdist` 并编译进 Go 二进制。容器内没有 MySQL、Redis 或 Nginx；TLS 由容器外的反向代理终止。`server/Dockerfile` 和 `admin/Dockerfile` 只用于需要前后端分离的场景。

当前单实例是有意限制：WebSocket 在线表、离线事件、限流、验证码和相册日配额位于进程内存，重启会丢失这些临时状态；SQLite 连接池固定为单连接以避免并发写入和迁移竞态。横向扩展前必须把 Hub、队列和限流迁移到共享设施。

## 2. 请求边界

```text
请求
  └─ SecurityHeaders → RequestLogger → Recovery → JSON body limit
       ├─ 公开：注册、登录、发送验证码、检查更新
       ├─ JWT：账户、绑定、待办、互动、状态、相册、/media
       ├─ Admin JWT：后台读写；破坏性和敏感操作再过 Super 管理员检查
       └─ /ws：只从 Authorization 头取 JWT，不接受查询串令牌
```

服务端固定只接受 HS256。用户令牌和后台令牌都要求有效签名、正整数主体和合法令牌版本；每次请求还会实时读取账号状态和 `token_ver`，封禁或撤销后旧令牌立即失效。登录失败不区分“账号不存在、密码错误、账号被禁用”。

后台设置按破坏力分组：SMTP、存储、保留策略和安全强度只允许超级管理员；普通管理员只读写明确授权的运行参数。审计字段采用默认脱敏白名单策略，邀请码等凭据不返回后台列表。

## 3. Android 客户端

```text
ui/navigation
  └─ screens ── data/ApiClient ── HTTPS REST
       │             └──────────── WSS
       └─ service/StatusForegroundService
            ├─ StatusCollector：电量、屏幕、网络、前台应用
            ├─ NotificationCard：常驻状态卡
            ├─ RingHelper / TodoAlarmReceiver：本地提醒
            └─ StatusSyncManager：状态、互动和离线补偿
```

- `UserPrefs` 的访问令牌使用 Android Keystore AES/GCM 保存；不会把通讯密钥编入 APK。
- WebSocket 是实时主通道，REST 状态上报和本地 AlarmManager 是降级路径。
- 图片加载集中由 `AppImageLoader` 处理鉴权头、相对 URL 补全、占位底色和 Coil 缓存。
- 上传先在解码期限制像素、帧数和内存，再做缩放、EXIF 旋正和重试；服务端仍重复校验，客户端不能成为安全边界。
- 二级页统一处理 `BackHandler`，列表页使用 `KernelScreen`；miuix 组件和图标是 UI 默认实现，`ui/theme` 是 Material 配色计算的唯一兼容层。

## 4. 服务端模块

| 模块 | 责任 |
| --- | --- |
| `main.go` | 配置、路由、中间件、后台静态资源和后台任务启动 |
| `handlers.go` / `account.go` | 用户认证、绑定、资料、待办、状态和互动 |
| `album_handlers.go` / `album_media.go` | 相册、上传、媒体鉴权代理和社交操作 |
| `avatar_*.go` / `image_budget.go` | 头像与相册图片的格式、像素、帧和派生图预算 |
| `hub.go` / `push.go` | WebSocket 房间与兼容推送适配入口；当前不接商业推送 |
| `listen_together.go` | 每对情侣一个活动房间、播放状态持久化、房间 WSS 和服务端控制校验 |
| `admin.go` / `admin_album.go` | 后台管理、RBAC、缩略图审核和审计 |
| `settings.go` | 数据库存储的运行参数、范围校验、缓存和权限分组 |
| `migrations.go` / `sql/schema.sql` | SQLite 建表、补列和索引升级 |
| `memstore.go` / `netlog.go` | 有界临时状态、主动过期清理和请求日志 |

配置来源按优先级为环境变量、可选 `config.yaml`、默认值。可运行参数必须注册在 `runtimeSettingSpecs`，读取端也必须使用同一来源，避免后台显示的值和实际判定值不一致。

## 5. 数据与隐私

- 所有待办、状态和相册记录通过 `pair_id` 隔离；服务端每次按当前用户重新检查归属。
- SQLite 文件、公开上传目录和私密相册目录分开持久化。私密照片不挂静态路由，真实磁盘路径不出服务端。
- 对外媒体地址统一为 `/media/<id>` 鉴权代理；后台审核只提供 384px 缩略图，并带 `no-store` 和 `Referrer-Policy: no-referrer`。
- `/upload` 和 `/uploads` 仅承载历史公开资源，不能用于私密相册；这些路径不进入网络日志。
- 网络日志不记录认证头、请求体或私密媒体地址；敏感配置和凭据不下发给后台普通管理员。
- 一起听房间按 `pair_id` 隔离；自动加入要求用户 JWT、有效绑定关系和活动房间状态，
  房间口令仅是兼容入口的防误入校验，不替代身份鉴权。

## 6. 更新链路

1. 客户端请求 `/api/v1/app/latest` 获取 GitHub Release 元数据。
2. 正式渠道只接受正式 Release；测试渠道同时允许正式版和 prerelease。
3. 版本说明以仓库根 `CHANGELOG.md` 为准，不把 Release 标题当作更新日志。
4. 客户端在更新弹窗中展示版本号、发布时间和历史日志；文本区域可滚动，取消/更新按钮固定在底部。
5. 更新下载链接来自对应 Release 资产，服务端不保存 APK 副本。

## 7. 关键不变量

- 任何 `rows.Scan` 错误都必须记录并跳过坏行。
- SQLite 迁移顺序必须是“建表 → 补列 → 建索引”，并覆盖旧库升级测试。
- rows 遍历期间不得触发新的数据库查询。
- HTTP、SMTP、网络拨号和 WebSocket 写操作必须有明确超时。
- 所有危险后台操作必须有服务端鉴权、范围校验和审计；客户端隐藏入口不算权限控制。
- 图片处理必须在解码和派生阶段都受尺寸、帧数、并发和内存预算约束。
