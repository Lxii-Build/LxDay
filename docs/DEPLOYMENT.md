# 林曦日记 · 部署文档

本文覆盖三种部署方式：**Docker Compose（推荐）**、**Docker 单容器**、**前后端分离手动部署**。

**架构要点（去 Nginx）**：Go 服务端已内嵌运营后台前端并自托管 后台静态(`/`) / API(`/api`) / WebSocket(`/ws`) / 上传文件(`/uploads`)。因此容器编排只有 **应用 + MySQL + Redis**，无独立 Nginx。容器内服务监听明文 `8080`，Compose 对外发布 `7740`；**HTTPS/WSS 的 TLS 由外部反向代理（宝塔面板 / Nginx / Caddy）终止**。默认对外域名 `https://love.lxii.cc`。

镜像：`ghcr.io/lxii-build/lxday`（由 `build-server.yml` 工作流构建推送，可见性随仓库）。

---

## 一、Docker Compose（推荐，一键起全栈）

前置：安装 Docker 与 Docker Compose 插件。

1. 修改配置（务必）：
   - `deploy/config.docker.yaml`：把 `app.jwt_secret` 改成长随机串；如需通讯密钥，设 `app.app_key`（或用环境变量 `APP_KEY`，需与安卓构建注入的一致）。
   - `docker-compose.yml`：修改 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`（改后同步 `config.docker.yaml` 的 DSN）。
2. 启动：
   ```bash
   # 方式 A：本地用仓库根 Dockerfile 构建（含前端）
   docker compose up -d --build
   # 方式 B：改用已发布镜像（把 docker-compose.yml 的 server.build 换成 image: ghcr.io/lxii-build/lxday:latest 后）
   docker compose pull && docker compose up -d
   ```
   首次启动自动执行 `server/sql/schema.sql` 建表，Go 启动时再跑增量迁移，并创建超级管理员 `admin / 123456`（首次登录强制改账号密码并绑定邮箱）。
3. 访问（容器直连，验证用）：
   - 后台管理：`http://<服务器IP>:7740/`
   - 客户端 API：`http://<服务器IP>:7740/api/v1/...`，WebSocket：`ws://<服务器IP>:7740/ws`
   - 健康检查：`http://<服务器IP>:7740/healthz`
4. 常用运维：
   ```bash
   docker compose logs -f server     # 服务端日志
   docker compose ps                 # 状态
   docker compose pull && docker compose up -d   # 拉取新镜像并更新（生产升级）
   docker compose down               # 停止（保留数据卷）
   ```

数据卷：`mysql_data`、`redis_data`、`uploads`（头像/图片/APK）。清空数据需显式 `docker compose down -v`（谨慎）。

<!-- APPEND-DEPLOY-MORE -->

### HTTPS / 反向代理（生产必做）

容器只暴露明文 `7740`，需在其前面放一层带证书的反代，把域名的 443 转发到 `127.0.0.1:7740`，并放行 WebSocket 升级。

- **宝塔面板**（推荐，你的部署方式）：
  1. 网站 → 添加站点，绑定域名 `love.lxii.cc`（不建目录）。
  2. 站点设置 → SSL → Let's Encrypt 申请并开启「强制 HTTPS」。
  3. 站点设置 → 反向代理 → 添加：目标 URL `http://127.0.0.1:7740`，发送域名 `$host`；宝塔默认已带 WebSocket 支持（若有开关请开启）。一个反代即可覆盖 后台/`api`/`ws`/`uploads`（都在同一端口）。
- **Caddy**（自动证书，一条足矣）：
  ```
  love.lxii.cc {
      reverse_proxy 127.0.0.1:7740
  }
  ```
  Caddy 自动处理 WebSocket 与证书续期。

### 邮箱验证码 / 存储 / 站点信息
登录后台 → 系统设置：填 SMTP（注册邮箱验证码）、存储方式、站点名/LOGO 等，存于数据库 `app_setting`，随时可改、无需重启。

---

## 二、Docker 单容器（外接 MySQL/Redis）

适合已自备 MySQL/Redis 的场景。一体化镜像已内嵌后台前端。

```bash
# 拉取已发布镜像（或本地 docker build -t lxday -f Dockerfile . 自行构建）
docker run -d --name lxday \
  -p 7740:8080 \
  -v $(pwd)/config.yaml:/app/config.yaml:ro \
  -v lxday-uploads:/app/uploads \
  ghcr.io/lxii-build/lxday:latest
```
`config.yaml` 参照 `server/config.example.yaml`，把 `mysql.dsn`、`redis.addr` 指向你的实例（Redis 为强依赖，必须可用）。同样在前面放一层反代做 TLS。

---

## 三、前后端分离手动部署

若你想前后端各自独立托管（例如前端上 CDN / 独立 Nginx）：

### 1. 后端（Go，仅 API + WS，可不内嵌前端）
```bash
cd server
cp config.example.yaml config.yaml    # 改 dsn/redis/jwt_secret/app_key
go build -o linxi-server .
./linxi-server config.yaml             # 或注册 systemd
```
初始化库：`mysql < server/sql/schema.sql`（或启动时自动迁移）。注意：仓库根 `Dockerfile` 会内嵌前端；若走分离模式，可用 `server/Dockerfile`（仅后端）构建后端镜像。

### 2. 前端（Vue）
```bash
cd admin
npm install && npm run build           # 产物在 admin/dist
```
把 `admin/dist` 交给任意静态服务器（或 `admin/Dockerfile` 的 Nginx 镜像）托管，并把 `/api`、`/ws`、`/uploads` 反代到后端 `127.0.0.1:8080`。

---

## 四、通讯密钥（可选）
- 服务端：`config.yaml` 的 `app.app_key`（或环境变量 `APP_KEY`）非空时，校验客户端请求头 `X-App-Key`，仅作用于 `/api/v1/*`；留空则禁用。
- 客户端：由 `build-android.yml` / `release.yml` 工作流的 `app_key` 输入注入 APK。两侧必须一致，否则 App 所有业务请求会被拒。

## 五、CI/CD（三条工作流）
- `build-server.yml`：push 到 main（`server/**`、`admin/**`、`Dockerfile`）或手动 → `go vet`/`go test` → 构建一体化镜像推 `ghcr.io/lxii-build/lxday`。
- `build-android.yml`：手动触发，输入 服务端地址/通讯密钥/构建类型/版本号 → 产出 APK 工件。
- `release.yml`：手动触发的发行版 → Release APK + 带版本 tag 的镜像 + GitHub Release（附 APK、关联 `CHANGELOG.md`）。
- 生产更新：不自动部署，服务器 `docker compose pull && docker compose up -d` 手动升级。

## 六、初始账号与安全清单
- 超级管理员：`admin / 123456`，**首次登录强制改账号+密码+绑定邮箱**。
- 上线前务必：改 `jwt_secret`、数据库密码；配置反代 HTTPS；如需通讯密钥则两侧设好 `app_key`/`APP_KEY`；后台填好 SMTP；确认 `/app/uploads` 卷可写。
