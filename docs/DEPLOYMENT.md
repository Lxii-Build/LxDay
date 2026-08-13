# 林曦日记 · 部署文档

本文覆盖三种部署方式：**Docker Compose（推荐）**、**Docker 单容器**、**前后端分离手动部署**。

**架构要点（去 Nginx / 单容器）**：Go 服务端已内嵌运营后台前端并自托管 后台静态(`/`) / API(`/api`) / WebSocket(`/ws`) / 上传文件(`/uploads`)；数据库用**内嵌 SQLite**、缓存与在线态/离线队列改**进程内存**。因此容器编排**只有一个 `app` 容器**（无 MySQL、无 Redis、无 Nginx）。**容器内外端口统一为 `7740`**（宝塔容器列表显示 7740 → 7740）；**HTTPS/WSS 的 TLS 由外部反向代理（宝塔面板 / Nginx / Caddy）终止**。默认对外域名 `https://love.lxii.cc`。

镜像：`ghcr.io/lxii-build/lxday`（由 `build-server.yml` 工作流构建推送，可见性随仓库）。

---

## 一、Docker Compose（推荐，一键起全栈）

前置：安装 Docker 与 Docker Compose 插件。

1. 修改配置（务必）：
   - **密钥走 `.env`**：复制仓库根 `.env.example` 为 `.env`（与 `docker-compose.yml` 同目录，勿提交），填 `JWT_SECRET`（长随机串，如 `openssl rand -hex 32`）与 `APP_KEY`（通讯密钥，需与安卓构建注入的一致）。服务端启动会读取这两个环境变量；`JWT_SECRET` 缺省或为占位值将拒绝启动。
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
   **数据库零手动导入**：内嵌 SQLite 首次启动自动建表（文件在 `db_data` 卷，容器以 root 运行、卷可直接写入）。超级管理员**初始随机口令仅在服务端启动日志打印一次**（`docker compose logs app` 查看），**首次登录强制改账号密码，改密前无法进行其它后台操作**。
3. 访问（容器直连，验证用）：
   - 后台管理：`http://<服务器IP>:7740/`
   - 客户端 API：`http://<服务器IP>:7740/api/v1/...`，WebSocket：`ws://<服务器IP>:7740/ws`
   - 健康检查：`http://<服务器IP>:7740/healthz`（compose 已配 healthcheck）
4. 常用运维：
   ```bash
   docker compose logs -f app        # 服务端日志（含超管初始口令）
   docker compose ps                 # 状态
   docker compose pull && docker compose up -d   # 拉取新镜像并更新（生产升级）
   docker compose down               # 停止（保留数据卷）
   ```

数据卷：`db_data`（SQLite 数据库文件）、`uploads`（头像/图片/APK）。清空数据需显式 `docker compose down -v`（谨慎）。

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
  -e APP_KEY="通讯密钥(与APK一致,可留空)" \
  -v $(pwd)/config.yaml:/app/config.yaml:ro \
  -v lxday-data:/app/data \
  -v lxday-uploads:/app/uploads \
  ghcr.io/lxii-build/lxday:latest
```
`config.yaml` 参照 `server/config.example.yaml`；SQLite 文件默认 `/app/data/lxday.db`（挂 `lxday-data` 卷持久化），`JWT_SECRET`/`APP_KEY` 用 `-e` 注入。同样在前面放一层反代做 TLS。

---

## 三、前后端分离手动部署

若你想前后端各自独立托管（例如前端上 CDN / 独立 Nginx）：

### 1. 后端（Go，仅 API + WS，可不内嵌前端）
```bash
cd server
cp config.example.yaml config.yaml    # 设 db.path；jwt_secret/app_key 建议用环境变量 JWT_SECRET/APP_KEY 注入
JWT_SECRET="长随机串" APP_KEY="可选" go build -o linxi-server . && ./linxi-server config.yaml   # 或注册 systemd
```
无需手工导入 SQL：服务端启动会自动建表（内嵌 SQLite）。注意：仓库根 `Dockerfile` 会内嵌前端；若走分离模式，可用 `server/Dockerfile`（仅后端）构建后端镜像。

### 2. 前端（Vue）
```bash
cd admin
npm install && npm run build           # 产物在 admin/dist
```
把 `admin/dist` 交给任意静态服务器（或 `admin/Dockerfile` 的 Nginx 镜像）托管，并把 `/api`、`/ws`、`/uploads` 反代到后端 `127.0.0.1:7740`。

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
- 超级管理员：`admin / 123456`，**首次登录强制改账号+密码+绑定邮箱**（改密前后端会拦截其它管理操作）。
- 上线前务必：在 `.env` 设好强随机 `JWT_SECRET`（缺省/占位会拒绝启动）、改数据库密码；配置反代 HTTPS；如需通讯密钥则两侧设好 `APP_KEY`（服务端环境变量 + 安卓构建注入一致）；后台填好 SMTP；确认 `/app/uploads` 卷可写。
- 后台「网络日志」记录 API 请求（方法/路径/状态码/耗时/IP/UA），默认保留 7 天，仅管理员可见。
