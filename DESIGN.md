# 林曦日记 · 整合设计文档（历史文档）

> ## ⚠️ 这是 2026-08-08 的设计快照，不再是权威依据
>
> 本文件记录首轮设计访谈定下的决策，保留下来是为了**能查到某个设计当初为什么那样定**。
> 它**不反映当前实现**，以下几处已经变了：
>
> | 本文档写的 | 现在的实际情况 |
> |---|---|
> | MySQL + Redis | **内嵌 SQLite**（`modernc.org/sqlite`，纯 Go）+ **进程内存态**，单容器无 MySQL/Redis |
> | Nginx 反代静态资源 | Go 服务端 `go:embed` 内嵌后台前端，**不依赖 Nginx**（TLS 仍由外部反代终止） |
> | 日记功能（`/diaries`、`diary` 表、`diary_new` 事件） | **已整体下线**，表已可删。App 名称保留「日记」二字但没有这个功能 |
> | 相册 | 本文档成文时还没有；现在是主功能，见 [docs/ALBUM.md](docs/ALBUM.md) |
> | minSdk 29 | minSdk 33 / targetSdk 37 |
>
> **当前的权威文档**：架构看 [ARCHITECTURE.md](ARCHITECTURE.md)、
> 接口看 [server/README.md](server/README.md) 与 [docs/ALBUM.md](docs/ALBUM.md)、
> 代码约定看 [AGENTS.md](AGENTS.md)、变更历史看 [CHANGELOG.md](CHANGELOG.md)。

> 版本：v1.0（2026-08-08）
> 来源：五轮设计访谈（grilling）的全部决策固化。

---

## 0. 决策来源速查

| # | 决策点 | 结论 |
|---|---|---|
| Q1 | 受众与渠道 | 纯自用 2 人，APK 直装，不上架 |
| Q2 | 知情授权 | 首次绑定后强制双方知情授权页，各自确认后才采集 |
| Q3 | 端内 UI | Jetpack Compose + 通知卡 XML（RemoteViews） |
| Q4 | 不可滑删 | 自动重发兜底（Android 14 现实边界） |
| Q5 | 强制响铃 | 仅响铃与震动 |
| Q6 | 保活 | 前台服务 + 推送反哺（纯 WS 形式的反哺） |
| Q7 | 服务端 | Go（Gin + gorilla/websocket） |
| Q8 | iOS | 不开发，仅出限制文档 |
| Q9 | 部署 | 香港轻量云 2C2G + Let's Encrypt 证书，免备案 |
| Q10 | 深浅色 | 端内做深色模式（语义反转扩展规范）；通知卡 values-night 跟随系统 |
| Q11 | 响铃呈现 | 全屏通知锁屏亮屏显示「谁在找你」+ 响铃震动 |
| Q13 | 商业推送 | 不接入；纯 WS + 离线重连补拉 + 本地 AlarmManager 兜底 |
| Q14 | 共享控制 | 总开关 + 临时隐身（后经 Q31 裁决：砍掉隐身，仅总开关） |
| Q15 | 待办提醒 | 服务端定时扫描 + 客户端本地 AlarmManager 双保险 |
| Q16 | 深色实现 | 端内语义反转扩展规范；通知卡用系统 values-night |
| Q17 | 数据加密 | TLS 传输；静置不额外加密，靠云盘加密 |
| Q18 | 状态历史 | 存历史（Q23 定粒度、Q24 定呈现） |
| Q19 | 离线推送 | 纯 WS 为主，离线重连补拉，不接商业推送 |
| Q20 | 图片存储 | 服务器本地磁盘 + Nginx 静态服务，不接 OSS |
| Q21 | 邀请码 | 6 位数字，1 小时有效 |
| Q22 | 设备型号 | vivo + OPPO（保活引导重点两家） |
| Q23 | 历史粒度 | 5 分钟聚合，永久保留 |
| Q24 | 历史呈现 | 时间线 + 电量曲线图 |
| Q25 | 首页结构 | 底部 4 Tab：此刻 / 待办 / 发现 / 我的 |
| Q26 | 卡片按钮 | 通知展开态只留「响铃提醒」 |
| Q27 | 待办提醒强度 | 创建待办可选 普通 / 强提醒 |
| Q28 | 冲突裁决 | 覆盖需求 #3：通知展开态仅「响铃提醒」按钮；求陪伴/求冷静仅 App 首页入口 |
| Q29 | 账号 | 昵称 + 密码，无手机验证码 |
| Q30 | 解绑 | 不提供解绑，绑定关系终身制 |
| Q31 | 临时隐身 | 砍掉，隐私控制仅「总开关」 |
| Q32 | 强提醒细节 | 闹钟流 80% 音量 + 震动 + 普通通知（非全屏） |
| Q33 | 交付 | 先文档后代码 |

**后续澄清（2026-08-08）**
- 无注销账号接口；App 端保留「退出登录」仅清除本地 token，服务端数据不动。
- 临时隐身不开发，隐私控制仅「状态共享总开关」。
- 状态历史永久保留已接受。

---

## 1. 产品定位与范围

- **形态**：双人情侣私用 App，Android 10+，Kotlin 原生，APK 直装，不上架。
- **核心能力**：实时状态掌控 + 轻量远程互动 + 双人共同相册。
- **不含**：iOS 客户端、商业推送、解绑、注销、临时隐身、OSS、手机验证码、深色模式通知卡配色自定义（跟随系统）。

---

## 2. 架构与模块

```
安卓端 (Compose + Kotlin, minSdk 29 —— 现已提到 33)
├─ 采集层: 电量/充电/屏幕/解锁/前台APP/用量/WiFi/音乐(NotificationListener)
├─ 同步层: WebSocket (状态上报/对方状态/事件), 离线重连补拉, 心跳 30s
├─ 常驻层: 前台服务(dataSync) + 常驻通知卡(RemoteViews) + 卡片被删重发兜底
├─ 交互层: 强制响铃 / 待办(普通+强提醒) / 求陪伴 / 求冷静
├─ 数据层: 状态历史 5min聚合 + 电量曲线 自绘Canvas + 时间线列表
└─ UI层:  4 Tab (此刻/待办/发现/我的) + 通知卡 XML + 深色模式
   服务端 (Go + Gin + MySQL + Redis, 香港轻量云)
├─ REST API: 认证/绑定/待办/相册/状态历史/图片上传(本地磁盘)
├─ WebSocket Hub: 双人房间, 在线直转, 离线补偿队列(Redis), 低电量/指定WiFi事件
├─ 定时任务: 待办到点提醒扫描, 历史状态聚合落库兜底
└─ 存储: SQLite(用户/绑定/待办/相册/状态历史/用量) + Redis(在线/最新状态/事件队列) + 本地磁盘(图片)
```

**数据隔离**：所有业务数据（待办/相册/状态历史）以 `pair_id` 为隔离键，仅对 pair 内双方可见。

---

## 3. 实时状态（核心）

**采集项与来源**

| 状态 | 来源 | 前置授权 | 说明 |
|---|---|---|---|
| 电量/是否充电 | BatteryManager | — | 前台即时 / 后台 5min |
| 屏幕亮/锁屏/解锁 | 动态注册 Receiver | — | 关键变更即时上报 |
| 前台 APP | UsageStatsManager | 使用情况访问 | 无授权则显示"无前台" |
| 当日各 App 时长 | UsageStatsManager 聚合 | 使用情况访问 | 30min 批量上报 |
| WiFi 名称 | WifiManager | 定位权限 + 定位服务开 | Android 10+ 必需 |
| 移动网络标注 | ConnectivityManager | — | 无 WiFi 时显示「移动网络」 |
| 音乐(歌名/歌手/播放中) | NotificationListener | 通知使用权 | 媒体通知解析 |

**刷新频率**
- 前台：事件驱动即时（亮屏/解锁/音乐/充电）+ 15s 轮询。
- 后台：AlarmManager 每 5 分钟采集上报一次。
- 关键变更即时推送对方：亮屏解锁、开始播放音乐、电量 <15%、连接指定 WiFi。

**状态变更推送（服务端判断）**
- `low_battery`：上报状态中电量 0<level<15 时，转发 + 离线入队 + 本地通知。
- `wifi_joined`：客户端检测到连接「关注 WiFi」时发送，服务端转发。

**最新状态存储**：Redis `status:user:{uid}`（JSON），24h TTL；离线重连后服务端推送对方最新状态（补偿）。

---

## 4. 状态历史

- **粒度**：每 5 分钟一条（服务端兜底聚合 + 客户端随 5min 上报落库）。
- **保留**：永久。
- **字段**：user_id, pair_id, battery, charging, screen_on, locked, foreground_pkg, foreground_name, ssid, network, ts。
- **呈现**：
  - 时间线：按天分组，每条「HH:mm 屏幕亮 · 使用 微信 · WiFi」；点击进入当日明细。
  - 电量曲线：24h 折线（自绘 Canvas），叠加充电区间高亮。
- **入口**：首页「此刻」卡片右上角「历史」图标。
- **隐私**：历史仅对绑定双方可见；总开关关闭时停止写入并本地清空（服务端历史按用户确认后保留，仅停止新增）。

---

## 5. 远程互动

### 5.1 强制响铃（紧急找人）
- 触发：App 首页「响铃」按钮 / 通知展开态「响铃提醒」按钮。
- 接收端：闹钟流 STREAM_ALARM 最大音量循环 10s + 强震动 + **全屏通知锁屏亮屏**显示「对方 正在找你」，点击「我知道了」停止。
- 边界：勿扰「完全静音 Total Silence」时系统屏蔽一切声音，无法绕过（如实说明）。
- 冷却：Redis 10 分钟 / 3 次，超频丢弃并提示。

### 5.2 待办（双向）
- 给对方添加 / 编辑 / 标记完成 / 删除 / 设置提醒时间。
- **提醒强度**（创建时可选）：
  - 普通：到点弹普通通知 + 短震动。
  - 强提醒：闹钟流 80% 音量 + 震动 + **普通通知（不做全屏）**。
- 提醒触发：服务端定时扫描 + 客户端本地 AlarmManager 双保险。
- 完成/新增：双方 WS 实时同步，离线补拉。

### 5.3 求陪伴 / 求冷静
- 仅 App 首页入口（通知展开态只留响铃提醒）。
- 对方收到 WS 事件 → 本地高优通知：「对方 需要你的陪伴」「对方 现在需要冷静」。
- 离线：入补偿队列，重连后补拉。

---

## 7. 通知栏常驻状态卡

- **形态**：收起态单行摘要（电量 + 屏幕 + 前台APP）；展开态全量 + **仅「响铃提醒」按钮**。
- **静默更新**：setOnlyAlertOnce + IMPORTANCE_LOW，不响铃不震动。
- **配色**：充电绿 / 低电红 / 亮屏蓝 / 音乐紫；通知卡资源走 `values-night` 深浅两套。
- **常驻**：setOngoing(true)；Android 14 侧滑删除 → NotificationListener 检测后自动重发；设置内开关关闭才停止。
- **按钮**：「响铃提醒」点击 → 服务 PendingIntent → 触发强制响铃链路（无需打开 App）。

---

## 8. 双人绑定体系

- 邀请码：6 位数字，创建者 A 生成，1 小时有效（创建记录时间，过期需重生成）。
- 绑定流程：B 输码 → 服务端校验 → 关系建立 → **双方各自确认知情授权** → 开始采集。
- 数据完全隔离（pair_id）；绑定关系终身制，无解绑。

---

## 9. 账号与认证

- 昵称 + 密码注册 / 登录，无手机验证码。
- JWT（30 天 TTL）鉴权。
- 无注销 / 删除账号接口；App「退出登录」仅清本地 token。

---

## 10. 服务端接口

（与骨架一致，增补历史相关端点与图片上传，见下）

```
POST /api/v1/auth/register|login
POST /api/v1/pair/create-invite | bind
GET  /api/v1/pair/status | partner/status
POST /api/v1/todos | GET /api/v1/todos?status= | PUT /:id | POST /:id/complete | DELETE /:id
POST /api/v1/diaries | GET /api/v1/diaries?date= | PUT/DELETE /:id
POST /api/v1/diaries/:id/images        # 图片文件上传（multipart）→ 返回 URL
POST /api/v1/interactions/comfort|calm|ring
GET  /api/v1/status/history?date=YYYY-MM-DD&page=   # 历史时间线分页
GET  /api/v1/status/history/battery?date=YYYY-MM-DD # 24h 电量曲线序列
GET  /api/v1/usage?date=              # 当日各 App 时长
POST /api/v1/push/register-token      # 预留（暂不接推送，接口占位）
WS   /ws?token=JWT
```

统一响应 `{"code":0,"message":"ok","data":{...}}`；错误码沿用骨架（1001-1011）。

**WebSocket 消息**（沿用骨架）：`status_update / partner_status / comfort_request / calm_request / ring_request / todo_new / todo_completed / diary_new / low_battery / wifi_joined`。

---

## 11. 权限清单（AndroidManifest）

| 权限 | 类别 | 用途 |
|---|---|---|
| POST_NOTIFICATIONS | 运行时(13+) | 通知 |
| PACKAGE_USAGE_STATS | 特殊 | 前台APP/用量 |
| BIND_NOTIFICATION_LISTENER_SERVICE | 特殊 | 音乐/卡片重发兜底 |
| ACCESS_NOTIFICATION_POLICY | 特殊 | 强制响铃绕过勿扰 |
| 定位 精确+后台 | 运行时 | 后台读 WiFi 名 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 特殊 | 防 Doze |
| FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC | 运行时 | 前台服务 |
| 自启动 | 厂商(vivo/OPPO) | 保活 |
| INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / WAKE_LOCK / VIBRATE / MODIFY_AUDIO_SETTINGS / RECEIVE_BOOT_COMPLETED / USE_FULL_SCREEN_INTENT | 普通 | — |

**保活**：前台服务为主 + 电池白名单 + 开机自启 + **vivo / OPPO 自启动白名单引导页**（跳转对应设置页）。

---

## 12. 数据库变更（对比骨架）

- `device_status`：保留（最新状态，Redis 为主，落库兜底）。
- **新增 `status_history`**：
  ```sql
  CREATE TABLE status_history (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    pair_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    battery INT NOT NULL,
    charging TINYINT NOT NULL DEFAULT 0,
    screen_on TINYINT NOT NULL DEFAULT 0,
    locked TINYINT NOT NULL DEFAULT 1,
    foreground_pkg VARCHAR(128) DEFAULT NULL,
    foreground_name VARCHAR(64) DEFAULT NULL,
    ssid VARCHAR(64) DEFAULT NULL,
    network VARCHAR(16) NOT NULL DEFAULT 'wifi',
    ts DATETIME NOT NULL,
    KEY idx_pair_user_ts (pair_id,user_id,ts)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  ```
  5 分钟一条，永久保留；按月分区可后续加。
- `todo` 增字段 `remind_type TINYINT DEFAULT 0`（0普通/1强提醒）。
- `diary` / `diary_image` 不变；图片 URL 指向本地 `/uploads/`。
- `push_token` 保留为占位（暂不写入）。

---

## 13. 部署（香港轻量云 2C2G）

1. Ubuntu 22.04 + Docker（或裸装）：MySQL 8 + Redis 7 + Go 服务（systemd）+ Nginx。
2. 域名 A 记录 → 服务器 IP；Let's Encrypt 签证书（certbot），Nginx 挂 HTTPS + WSS（/ws 反代，透传 Upgrade/Connection）。
3. `/uploads/` 由 Nginx 静态服务；服务端负责写入。
4. 私密性：Nginx 仅放行绑定用户的鉴权（服务端控制 URL 可访问性，或用带随机 token 的 URL）。
5. 备份：crontab 每日 mysqldump + rsync uploads 目录。

---

## 14. 合规与信任（纯自用但仍是底线）

- 双方知情授权页 + 状态共享总开关（关闭即停采+本地清空）。
- 无解绑/无注销：绑定与数据永久保留（已明确）。
- TLS 全链路加密；静置靠云盘加密。
- 隐私面显式说明：状态历史（含前台APP/电量）**永久保留**，双方都可见。

---

## 15. 与骨架的差异清单（实现时按此改）

1. 通知卡布局：删除「求陪伴」按钮，仅留「响铃提醒」。
2. 新增 `status_history` 表 + 历史时间线/曲线 2 个接口 + 2 个页面。
3. 深色模式：端内语义反转扩展规范；通知卡 `values-night`。
4. 隐私授权页 + 状态共享总开关（本地 + 服务端联动停采）。
5. 待办 `remind_type` 字段 + 服务端定时扫描 + 本地 AlarmManager 双保险 + 强提醒 80% 音量普通通知。
6. 图片上传改本地磁盘（骨架原为 OSS 占位）。
7. 邀请码加 1 小时有效期。
8. 移除 OSS 配置 / 上传 token 接口（换本地上传）。
9. 「退出登录」仅清本地 token。
10. 深色通知卡配色资源（values-night）新建。

---

## 16. 待实现工作包（按里程碑）

- **M1** 服务端：历史表+接口、待办 remind_type、本地图片上传、邀请码有效期、定时扫描。
- **M2** 安卓：深色主题、4 Tab 重构、历史时间线+曲线、隐私授权页+总开关、待办强提醒、图片上传。
- **M3** 通知卡改版 + 全屏响铃页 + vivo/OPPO 保活引导 + 真机联调。
- **M4** 部署上线（云、证书、Nginx、备份）+ iOS 限制文档。
