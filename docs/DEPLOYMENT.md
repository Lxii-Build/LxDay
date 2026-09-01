# 林曦日记 · 部署文档

本文覆盖三种部署方式：**Docker Compose（推荐）**、**Docker 单容器**、**前后端分离手动部署**。

**架构要点（去 Nginx / 单容器）**：Go 服务端已内嵌运营后台前端并自托管 后台静态(`/`) / API(`/api`) / WebSocket(`/ws`) / 上传文件(`/uploads`)；数据库用**内嵌 SQLite**、缓存与在线态/离线队列改**进程内存**。因此容器编排**只有一个 `app` 容器**（无 MySQL、无 Redis、无 Nginx）。**容器内外端口统一为 `7740`**（宝塔容器列表显示 7740 → 7740）；**HTTPS/WSS 的 TLS 由外部反向代理（宝塔面板 / Nginx / Caddy）终止**。默认对外域名 `https://love.lxii.cc`。

镜像：`ghcr.io/lxii-build/lxday`（由 `build-server.yml` 工作流构建推送，提供 `linux/amd64` 与 `linux/arm64`）。

---

## 一、Docker Compose（推荐，一键起全栈）

前置：安装 Docker 与 Docker Compose 插件。

1. 修改配置（务必）：
   - **密钥走 `.env`**：复制仓库根 `.env.example` 为 `.env`（与 `docker-compose.yml` 同目录，勿提交），填 `JWT_SECRET`（长随机串，如 `openssl rand -hex 32`）。服务端启动会读取它；`JWT_SECRET` 缺省或为占位值将拒绝启动。当前 APK 不再携带或发送可提取的共享通讯密钥。
   - 无需配置外部数据库/缓存：数据库为**内嵌 SQLite**（文件在 `db_data` 卷），无 MySQL/Redis 密码可改。
2. 启动（默认从 GHCR 拉取镜像，无需本地构建）：
   ```bash
   # 方式 A：从 GHCR 拉取（私有仓库需先 docker login ghcr.io）
   docker compose pull && docker compose up -d
   # 方式 B（国内推荐）：下载工作流产物 lxday-server-image（.tar.gz）离线导入后直接起
   gunzip -c lxday-image.tar.gz | docker load && docker compose up -d
   # 方式 C（本地自构建）：docker build -t lxday:local . 后在 .env 设 LXDAY_IMAGE=lxday:local 再 up
   ```
   镜像地址/端口由 `.env` 的 `LXDAY_IMAGE`/`APP_PORT` 控制（默认 `ghcr.io/lxii-build/lxday:latest`、`7740`）。
   **数据库零手动导入**：内嵌 SQLite 首次启动自动建表（文件在 `db_data` 卷，容器以 `app` 用户运行，镜像已准备好并授权数据目录）。超级管理员初始随机口令写入数据卷中的 `initial-admin-password.txt`（权限 `0600`，用 `docker compose exec app cat /app/data/initial-admin-password.txt` 查看），**首次登录强制改账号密码，改密前无法进行其它后台操作**；改密后请删除该文件。
3. 访问（容器直连，验证用）：
   - 后台管理：`http://<服务器IP>:7740/`
   - 客户端 API：`http://<服务器IP>:7740/api/v1/...`，WebSocket：`ws://<服务器IP>:7740/ws`
   - 存活检查：`http://<服务器IP>:7740/healthz`；就绪检查：`http://<服务器IP>:7740/readyz`（数据库与两类媒体目录均可写才返回 200）。
4. 常用运维：
   ```bash
   docker compose logs -f app        # 服务端日志（不输出超管初始口令）
   docker compose ps                 # 状态
   docker compose pull && docker compose up -d   # 拉取新镜像并更新（生产升级）
   docker compose down               # 停止（保留数据卷）
   ```

数据卷：`db_data`（SQLite 数据库文件）、`uploads`（头像/日记图片/APK 等公开资源）、
`uploads_private`（相册原图/缩略图，仅由 `/media/<id>` 鉴权代理读取）。清空数据需显式
`docker compose down -v`（谨慎）。

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

## 二、Docker 单容器

一体化镜像已内嵌后台前端与 SQLite，`docker run` 一条即可（无需外部数据库/缓存）。

```bash
# 拉取已发布镜像（或本地 docker build -t lxday -f Dockerfile . 自行构建）
docker run -d --name LxDay \
  -p 7740:7740 \
  -e JWT_SECRET="替换为长随机串" \
  -v $(pwd)/config.yaml:/app/config.yaml:ro \
  -v lxday-data:/app/data \
  -v lxday-uploads:/app/uploads \
  -v lxday-uploads-private:/app/uploads-private \
  ghcr.io/lxii-build/lxday:latest
```
`config.yaml` 参照 `server/config.example.yaml`；SQLite 文件默认 `/app/data/lxday.db`（挂 `lxday-data` 卷持久化），`JWT_SECRET` 用 `-e` 注入。同样在前面放一层反代做 TLS。旧部署中的 `APP_KEY` 字段可以保留，但当前客户端认证不再依赖它。

---

## 三、前后端分离手动部署

若你想前后端各自独立托管（例如前端上 CDN / 独立 Nginx）：

### 1. 后端（Go，仅 API + WS，可不内嵌前端）
```bash
cd server
cp config.example.yaml config.yaml    # 设 db.path；jwt_secret/app_key 建议用环境变量 JWT_SECRET/APP_KEY 注入
JWT_SECRET="长随机串" go build -o linxi-server . && ./linxi-server config.yaml   # 或注册 systemd
```
无需手工导入 SQL：服务端启动会自动建表（内嵌 SQLite）。注意：仓库根 `Dockerfile` 会内嵌前端；若走分离模式，可用 `server/Dockerfile`（仅后端）构建后端镜像。

### 2. 前端（Vue）
```bash
cd admin
npm install && npm run build           # 产物在 admin/dist
```
把 `admin/dist` 交给任意静态服务器（或 `admin/Dockerfile` 的 Nginx 镜像）托管，并把 `/api`、`/ws`、
`/media` 反代到后端 `127.0.0.1:7740`；`/upload`、`/uploads` 与后端共享公开上传目录；`/media` 不能配置成静态目录，必须保留后端鉴权。仓库提供的 `deploy/nginx.conf` 已按服务端容器端口 `7740` 配置。

---

## 四、客户端与服务端安全
- APK 可以被逆向，任何写入 APK 的共享密钥都不能当作可信凭据；当前 Release 包不编译 `APP_KEY`，REST 强制 HTTPS，WebSocket 强制 WSS。
- 服务端业务安全由 JWT、封禁后的实时令牌校验、IP 限流和相册资源鉴权提供。`APP_KEY`/`app.app_key` 仅为旧部署兼容字段，不再校验 `X-App-Key`。

## 五、CI/CD（三条工作流）
- `build-server.yml`：push 到 main（`server/**`、`admin/**`、`Dockerfile`）或手动 → `go vet`/`go test` → 构建一体化镜像推 `ghcr.io/lxii-build/lxday`。
- `build-android.yml`：手动触发，输入 服务端地址/构建类型/版本号 → 产出 APK 工件；旧 `APP_KEY` 输入不会进入 APK。
- `release.yml`：手动触发的发行版 → Release APK + 带版本 tag 的镜像 + GitHub Release（附 APK、关联 `CHANGELOG.md`）。
- 生产更新：不自动部署，服务器 `docker compose pull && docker compose up -d` 手动升级。

## 六、备份与恢复演练

SQLite 数据库与公开/私密媒体都在 Docker 卷中，升级不会替代备份。执行方法、SHA-256 校验和隔离恢复演练见 [BACKUP.md](BACKUP.md)。

## 七、初始账号与安全清单
- 超级管理员：用户名 `admin`，随机初始口令见 `/app/data/initial-admin-password.txt`，**首次登录强制改账号+密码+绑定邮箱**（改密前后端会拦截其它管理操作）。不要在文档、日志或工单中写死口令。
- 上线前务必：在 `.env` 设好强随机 `JWT_SECRET`（缺省/占位会拒绝启动）；配置反代 HTTPS/WSS；后台填好 SMTP；确认 `/app/uploads` 与 `/app/uploads-private` 卷可写。
- 后台「网络日志」记录 API 请求（方法/路径/状态码/耗时/IP/UA），默认保留 7 天，仅管理员可见。
