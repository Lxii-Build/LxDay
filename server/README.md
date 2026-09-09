# LxDay Server

Go + Gin 服务端，提供 Android REST API、WebSocket、运营后台静态资源、图片鉴权代理和后台管理 API。SQLite 使用纯 Go 驱动，默认单实例运行。

## 运行

要求 Go 1.26+。从 `server` 目录执行：

```bash
go test -timeout 400s ./...
go vet ./...
CGO_ENABLED=0 go build -trimpath -o linxi-server .
```

启动参数是可选配置文件路径：

```bash
JWT_SECRET="$(openssl rand -hex 32)" ./linxi-server ./config.yaml
```

没有配置文件时，服务使用默认值和环境变量；生产环境必须提供非默认且至少 32 字节的 `JWT_SECRET`。容器入口会创建并修复 `/app/data`、`/app/uploads` 和 `/app/uploads-private` 的权限，然后以非 root 用户运行服务。

## 配置

`config.example.yaml` 只包含启动级配置：

```yaml
app:
  port: 7740
  jwt_secret: ""
  token_ttl_hours: 720
  ring_cooldown_seconds: 600
  ring_cooldown_limit: 3
  app_key: ""        # 旧部署兼容字段，当前客户端不使用
db:
  path: data/lxday.db
storage:
  upload_dir: uploads
push:
  provider: none
```

环境变量 `JWT_SECRET`、`DB_PATH` 和旧兼容字段 `APP_KEY` 可覆盖对应配置。相册配额、保留期、限流、互动冷却和令牌 TTL 等运行参数必须在后台“系统设置”中调整；它们在 `app_setting` 中保存并即时生效，不要另加环境变量或改编排文件。

## 路由概览

公开接口：

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/send-code`
- `GET /api/v1/app/latest`
- `GET /healthz`、`GET /readyz`

用户 JWT 接口：

- `/pair/*`：关系状态、邀请码、绑定、解绑和纪念日；
- `/profile*`：资料和头像；
- `/todos*`：待办；
- `/interactions/*`：陪伴、冷静、响铃；
- `/status`、`/status/history*`：状态上报与历史；
- `/albums*`、`/photos*`、`/media`：相册、照片和回收站；
- `/listen/rooms*`：一起听/一起看共用按 `pair_id` 唯一创建的情侣房间，已绑定情侣可自动加入（没有房间时自动创建），支持网易云歌曲或经过 HTTPS 校验的观看链接、时间轴同步和离开；房间状态落 SQLite，实时事件走独立 WSS。网易云 Cookie、密码、解析出的播放地址和后台审计列表中的观看链接只留在用户侧/不向后台列表返回。
- `/push/*`：兼容 token 注册入口，当前不接入商业推送。

后台接口统一在 `/api/admin/*`，使用管理员 JWT 和 RBAC。用户、关系、待办、照片、通知、存储、设置、审计和网络日志的读写都在服务端再次校验权限与数据归属，不能依赖后台页面隐藏按钮。

WebSocket 地址为 `/ws`，状态同步令牌只允许放在 `Authorization: Bearer ...` 请求头中，不接受查询串令牌。一起听/一起看建立 `/api/v1/listen/rooms/<id>/ws` 时，JWT 与 `X-Lx-Listen-Token` 房间会话令牌都走请求头；查询串仅为旧客户端兼容保留。相册媒体地址统一走 `/media/<id>`、`/media/<id>/thumb` 和 `/media/<id>/preview` 鉴权代理。

## 数据和文件

- `server/sql/schema.sql` 是新库结构的基线；`migrations.go` 负责老库补列和索引升级。
- SQLite 使用 WAL、busy timeout 和单连接池。迁移顺序必须是建表、补列、建索引。
- 公开头像/历史资源在 `upload_dir` 下；私密相册在同级私密目录，禁止静态暴露真实路径。
- 在线态、验证码、限流、相册日配额、一起听 WS 会话和离线事件在内存；一起听房间元数据与播放状态落库。进程重启后需按情侣关系重新建立 WS 会话，服务当前不支持多实例横向扩展。
- 请求日志跳过私密媒体和公开上传路径，不记录认证头、请求体或共享密钥。

## 安全实现

- JWT 固定 HS256，用户和管理员令牌都校验签名、有效期、主体类型和令牌版本。
- 每个请求实时读取用户/管理员状态；封禁或令牌版本变更会立即撤销旧会话，WebSocket 连接也走相同校验。
- 登录失败响应不区分账号不存在、密码错误和账号被禁用，并受 IP/账号限流保护。
- JSON、邮箱、分页、上传文件、图片像素/帧数/派生图和 WebSocket 帧都有长度或资源上限；HTTP、SMTP 和 WS 写入有超时。
- 所有危险操作使用统一错误响应、审计记录和服务端权限检查。

## 构建后台内嵌镜像

仓库根 `Dockerfile` 会执行 `npm ci`、`npm run build`，把 `admin/dist` 放入临时 `server/webdist` 后编译。`server/webdist` 为构建输入，不要把后台构建产物提交到仓库；生产更新使用镜像 tag 和 [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md) 的升级流程。
