# 林曦日记 · 服务端

Go 1.22 + Gin + gorilla/websocket + **内嵌 SQLite**（`modernc.org/sqlite`，纯 Go 无 CGO）
+ **进程内存态**（替代 Redis）。无 MySQL、无 Redis、无 Nginx——运营后台前端产物由
`go:embed` 内嵌，同一进程同时提供 API / WebSocket / 后台静态页 / 上传文件服务。
部署形态见 [../docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)，架构图见 [../ARCHITECTURE.md](../ARCHITECTURE.md)。

## 快速启动

```bash
# 1. 配置（可选：不给 config.yaml 也能起，全走环境变量 + 默认值）
cp config.example.yaml config.yaml
# 编辑 config.yaml：jwt_secret（必填）/ db.path / app_key / storage.upload_dir

# 2. 运行（建表自动完成：启动执行内嵌 sql/schema.sql，幂等）
mkdir -p uploads data
go mod tidy
JWT_SECRET=$(openssl rand -hex 32) go run . config.yaml
```

`jwt_secret` 未设置或含 `change` 字样会**拒绝启动**（空密钥等于令牌可任意伪造）。
环境变量 `JWT_SECRET` / `APP_KEY` / `PORT` / `DB_PATH` 覆盖配置文件同名项。

## 目录

| 文件 | 说明 |
|---|---|
| main.go | 入口 + 路由注册 + 配置加载 + SQLite 初始化（WAL / busy_timeout / MaxOpenConns=1） |
| models.go | 数据模型 + WebSocket 消息协议 |
| store.go | SQLite 数据访问 + 内存态封装（在线/状态/离线队列/冷却） |
| memstore.go | 进程内存态实现（替代 Redis；重启即失，见下「说明」） |
| migrations.go | 启动执行内嵌 `sql/schema.sql` + 幂等补列（`PRAGMA table_info` 探测） |
| hub.go | WebSocket 实时通道（单机内存路由，多节点扩展点见注释） |
| handlers.go | 认证/绑定/待办/历史/状态上报 handler + JWTAuth + AppKeyGuard |
| account.go | 注册/登录/邮箱验证码/扩展资料 |
| invite.go | 邀请码生成与绑定限流 |
| album_handlers.go | 相册与照片接口（含保留字分派） |
| album_media.go | `POST /media` 上传 + `/media/:id` 鉴权代理 + 上传配额 |
| album_store.go | 相册数据访问（4 张表） |
| avatar_*.go / exif.go | 纯 Go 图片处理链（解码/缩放/EXIF），不依赖 libvips |
| admin.go | 后台 `/api/admin/*`（AdminAuth + requireSuper） |
| netlog.go | 结构化日志 + 请求日志异步落库（含 skip 前缀） |
| security.go | 安全响应头 / 口令强度 / 上传 URL 白名单 |
| static.go | 内嵌后台 SPA + `/upload(s)` 静态 + `/healthz` |
| push.go | 推送网关适配层（个推/极光，当前为占位实现） |
| sql/schema.sql | 建表脚本（18 张表，全部 `IF NOT EXISTS`，服务端启动自动执行） |

## 接口速览

以 `main.go` 的路由注册段为准。中间件链：全局 `SecurityHeaders → RequestLogger → Recovery`；
`/api/v1/*` 再过 `AppKeyGuard`（`app_key` 非空时校验请求头 `X-App-Key`），
其下 `auth` 分组再过 `JWTAuth`。

**公开（仅 AppKeyGuard，无需登录）**

```
POST /api/v1/auth/register           注册
POST /api/v1/auth/login              登录
POST /api/v1/auth/send-code          发送邮箱验证码（60s 冷却，码 10min 有效）
GET  /api/v1/app/latest?platform=&version_code=   检查更新
```

**绑定与资料**

```
GET  /api/v1/pair/status             绑定状态
POST /api/v1/pair/create-invite      生成 8 位邀请码（1h 有效）
POST /api/v1/pair/bind               绑定（失败限流 10min/5 次）
POST /api/v1/pair/unbind             主动解绑（双向生效）
POST /api/v1/pair/cancel-invite      取消自己发出的邀请
PUT  /api/v1/pair/anniversary        设置纪念日
GET  /api/v1/profile                 双人资料（自己 + 伴侣）
PUT  /api/v1/profile                 改昵称
GET  /api/v1/profile/me              自己的扩展资料
PUT  /api/v1/profile/me              改扩展资料（昵称/性别/签名/生日）
POST /api/v1/profile/avatar          头像上传（multipart, 落 /upload/YYYY/MM/DD/）
```

**状态 / 待办 / 互动**

```
GET  /api/v1/partner/status          对方实时状态
POST /api/v1/todos                   创建待办
GET  /api/v1/todos                   待办列表
PUT  /api/v1/todos/:id               编辑待办
POST /api/v1/todos/:id/complete      完成待办
DELETE /api/v1/todos/:id             删除待办
POST /api/v1/interactions/comfort    求陪伴（冷却 7s/1 次）
POST /api/v1/interactions/calm       求冷静（冷却 7s/1 次，与 comfort 分桶）
POST /api/v1/interactions/ring       强制响铃（默认 600s/3 次，可配）
GET  /api/v1/status/history?date=&limit=&offset=   状态历史时间线
GET  /api/v1/status/history/battery?date=          24h 电量曲线
POST /api/v1/push/register-token     注册推送 token（预留）
DELETE /api/v1/push/token            注销推送 token（预留）
WS   /ws?token=<JWT>                 实时通道
GET  /healthz                        健康检查（compose healthcheck 用）
```

**相册**（0820 新增，共 21 条：16 条 `/api/v1` 直接注册 + 3 条通配分派 + 2 条 `/media`）

完整入参与返回见 [../docs/ALBUM.md](../docs/ALBUM.md)。

```
GET  /api/v1/albums                  相册列表（含 photo_count / cover_thumb_url）
POST /api/v1/albums                  新建相册（name 1-32 字）
GET  /api/v1/albums/:id              相册详情（photo_count 现算）
PUT  /api/v1/albums/:id              改名 / 设封面（封面必须是本 pair 的正常照片）
DELETE /api/v1/albums/:id            删除相册（status=2）
GET  /api/v1/albums/:id/photos?page=&size=   相册内照片（id=0 为「未归类」虚拟相册）
POST /api/v1/albums/:id/photos       把已上传照片挂入相册（单次 ≤200 张，触发 album_new）
POST /api/v1/media                   上传单张（multipart 字段名 file，≤20MB，落 album_id=0）
GET  /api/v1/photos/:id              照片详情 + 评论 + 点赞态
PUT  /api/v1/photos/:id              改描述（≤500 字）
DELETE /api/v1/photos/:id            软删进回收站（不删盘上文件）
POST /api/v1/photos/:id/restore      从回收站恢复
POST /api/v1/photos/:id/like         点赞
DELETE /api/v1/photos/:id/like       取消点赞
POST /api/v1/photos/:id/comments     评论（1-500 字）
DELETE /api/v1/photos/:id/comments/:cid   删评论（只能删自己的）

# 通配分派（非独立注册的路由，见下「三处易踩的设计决策」①）
GET  /api/v1/albums/summary          相册概要 → handleAlbumByID 内识别
GET  /api/v1/photos/on-this-day?month=&day=   历年同月同日 → handlePhotoByID 内识别
GET  /api/v1/photos/recycled?page=&size=      回收站列表 → handlePhotoByID 内识别

# 图片读取：挂在根路径，只过 JWTAuth（不过 AppKeyGuard），见 ②
GET  /media/:id                      原图字节流
GET  /media/:id/thumb                缩略图字节流（长边 512）
```

### 三处易踩的设计决策

**① `/albums/summary`、`/photos/on-this-day`、`/photos/recycled` 不是独立注册的路由。**
gin 的路由树不允许同一层级同时存在静态段与通配段，同时注册 `/albums/summary` 与
`/albums/:id` 会在**启动时 panic**——不是 404，是进程起不来，容器会陷入反复重启。
故只注册通配路由，在 handler 内识别保留字：`handleAlbumByID` 认 `summary`，
`handlePhotoByID` 认 `on-this-day` 与 `recycled`。**对外路径与独立注册毫无差别**，客户端无感。
代价：以后新增保留字必须同步改 handler 的 switch，且相册不能有 id 为 `summary` 的字面路径。

**② `/media/:id` 挂在根路径而非 `/api/v1` 下，且不过 `AppKeyGuard`。**
根路径是因为 `netlog.go` 的日志 skip 前缀是 `/media`——挂到 `/api/v1/media` 就会漏出
skip 名单，每张照片的完整 URL 都被写进 `request_log`，而后台「网络日志」页对管理员可读，
等于任何管理员都能从日志里直接点开情侣的私密照片。那正是这套代理要防的事。
不过 `AppKeyGuard` 是因为图片由客户端图片库（Coil）发起，只能保证带上 `Authorization` 头，
补不了自定义的 `X-App-Key`。鉴权由 `JWTAuth` + `mustPair` + 照片 `pair_id` 比对承担，
**归属不符与 id 不存在返回同一个 403**（区别对待等于给出「该 id 存在」的探测信号）。

**③ 相册照片对外 URL 一律是 `/media/<photoId>` 与 `/media/<photoId>/thumb`。**
真实磁盘相对路径只存在库里：`Photo` 的 `diskPath` / `diskThumb` 是**非导出字段**，
`encoding/json` 看不见，不可能被序列化出去。原因是 `/upload` 与 `/uploads` 两个静态目录
**完全无鉴权**，只靠随机文件名保密；真实路径一旦经截图、日志、Referer 外泄，
拿到 URL 的任何人无需登录即可看到私密照片。读取的唯一闸门是 `handleGetMedia`，
其中 `safeUploadPath` 还会挡住库值被写坏时的路径穿越。响应头 `Cache-Control: private`
只允许终端自己缓存，禁止中间代理与 CDN 留副本。

**运营后台 `/api/admin/*`**（`admin.go`，不过 `AppKeyGuard`）

`AdminAuth` 的 role / must_change / status **全部实时读库，不信 token 里的副本**——
否则管理员被降级或禁用后，旧 token 仍按老权限畅通整个有效期。
首登改密前只放行 `/user/info` 与 `/change-credentials`。

```
POST /api/admin/login                登录（失败限流 10min/5 次）

# AdminAuth：普通管理员可读
GET  /api/admin/user/info            当前管理员信息
POST /api/admin/change-credentials   改自己的账号密码（首登强制）
GET  /api/admin/stats                概览统计
GET  /api/admin/users                用户列表
GET  /api/admin/pairs                情侣关系列表
GET  /api/admin/todos                待办列表
GET  /api/admin/app-versions         版本列表
GET  /api/admin/audit-logs           系统日志（管理员操作审计）
GET  /api/admin/network-logs         网络日志（API 请求日志）
GET  /api/admin/notify-templates     通知模板列表
GET  /api/admin/notify-records       通知记录列表

# AdminAuth + requireSuper：敏感操作一律限超管
POST /api/admin/upload                    后台文件上传（APK/LOGO）
PUT  /api/admin/users/:id/status          封禁 / 解封用户
POST /api/admin/pairs/:id/unbind          强制解绑
DELETE /api/admin/todos/:id               删待办
GET  /api/admin/photos                    相册照片审核（**只给元数据，不返回图片 URL**）
DELETE /api/admin/photos/:id              软删照片（进用户回收站，用户可自行恢复）
POST /api/admin/app-versions              发版
PUT  /api/admin/app-versions/:id/status   上下架
DELETE /api/admin/app-versions/:id        删版本
GET  /api/admin/admins                    管理员列表
POST /api/admin/admins                    新增管理员
PUT  /api/admin/admins/:id                改角色（白名单 admin/super）
PUT  /api/admin/admins/:id/status         启用 / 禁用管理员
POST /api/admin/admins/:id/reset-password 重置管理员密码
DELETE /api/admin/admins/:id              删管理员
GET  /api/admin/settings                  读系统设置（含 SMTP 主机/账号，故限超管）
PUT  /api/admin/settings                  改系统设置
POST /api/admin/settings/smtp-test        SMTP 连通性测试
PUT  /api/admin/notify-templates          新增 / 更新通知模板
DELETE /api/admin/notify-templates/:id    删通知模板
POST /api/admin/notify                    向用户群发通知
```

**为什么这些要收敛到 `requireSuper`**：此前只有 `POST /admins` 与 `PUT /settings` 挂了它，
其余全部裸奔，于是「普通 admin」事实上等于超管——能从 `GET /settings` 读出存储与 SMTP 密钥、
能全站群发、能删任意待办与照片、能封禁用户、能解绑他人情侣关系、能上传大文件落盘。
相册照片是全站最私密的内容，**列表与删除都限超管，普通 admin 连元数据都不给看**；
且列表接口不返回图片 URL——管理员没有用户 token，本就读不了 `/media/<id>`，
返回 URL 只会凭空多一条泄露面。

统一响应：`{"code":0,"message":"ok","data":{...}}`（后台 `/api/admin/*` 用 `{code,msg,data}` 信封）。

业务错误码：

| 码 | 含义 | 码 | 含义 |
|---|---|---|---|
| 1001 | 未绑定 / 已绑定 | 1013 | 邮件服务未配置 |
| 1002 | 参数错误 | 1014 | 验证码发送失败 |
| 1003 | 未授权（登录已失效） | 1015 | 验证码错误或已过期 |
| 1006 | 昵称 / 用户名 / 邮箱被占用 | 1016 | 客户端校验失败（`X-App-Key`）/ 验证码试错超限 |
| 1007 | 账号或密码错误 | 1017 | 无权访问该资源（越权） |
| 1008 | 邀请码生成失败 | 1018 | 账号已被禁用 |
| 1009 | 邀请码无效或已过期 | 1019 | 绑定尝试过于频繁 |
| 1010 | 操作失败 | 1020 | 相册上传超配额（200 张 / 500MB / 日） |
| 1011 | 响铃过于频繁 | | |
| 1012 | 登录尝试过于频繁 | | |

## 待接入 / 说明

1. **推送**：按设计决策**不接商业推送**，纯 WS + 离线重连补拉 + 本地 AlarmManager。`push.go` 保持占位，`/push/*` 接口为预留。
2. **图片上传**：本地磁盘，统一落 `uploadDir/upload/YYYY/MM/DD/<随机名>`，由 Go 自托管
   `/upload/*`（新）与 `/uploads/*`（旧兼容）两条静态路由，**均无鉴权**、均关目录列举。
   故相册照片不走这里，而是 `/media/<id>` 鉴权代理。
3. **状态历史**：客户端 5min 上报时服务端 `INSERT OR IGNORE` 落 `status_history`（幂等，SQLite 语法）；
   待办到点提醒每分钟扫描一次（`scanDueTodos` goroutine）。
4. **内存态即失**：在线态、伴侣最新状态、离线事件队列（**100 条上限 + 24h TTL**）、
   邮箱验证码、各类限流计数、相册当日配额全在进程内存（`memstore.go`），**进程重启即全部丢失**。
   表现为：双方短暂显示离线、未补推的离线事件永久丢失、验证码需重发、限流计数归零。
   这是换掉 Redis 换来的部署简化，代价已知且接受。
5. **单进程约束**：内存态与 `hub.go` 的 WS 路由表都在进程内，**起第二个副本会导致跨副本的
   伴侣互相看不到在线、消息转发丢失**。横向扩容必须先把两者换成外部共享存储
   （Redis + Pub/Sub 或网关路由）。
6. **SQLite 单写者**：`SetMaxOpenConns(1)` + WAL + `busy_timeout(5000)`。
   并发量足够，但慢查询会串行阻塞后续请求。
7. **本地运行需建目录**：`mkdir -p uploads data`（容器镜像里已由 Dockerfile 建好）。
