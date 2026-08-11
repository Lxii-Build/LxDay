# 林曦日记 · 部署文档

本文覆盖三种部署方式：**Docker Compose（推荐）**、**Docker 单容器**、**前后端分离手动部署**。

组件：Go 服务端（REST + WebSocket）、MySQL 8、Redis 7、Nginx（托管后台前端 + 反代 /api、/ws + 静态 /uploads）。默认对外域名 `https://love.lxii.cc`，后台在同域 `/`，接口在 `/api`。

---

## 一、Docker Compose（推荐，一键起全栈）

前置：安装 Docker 与 Docker Compose 插件。

1. 修改配置（务必）：
   - `deploy/config.docker.yaml`：把 `app.jwt_secret` 改成长随机串。
   - `docker-compose.yml`：修改 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`（改后同步 `config.docker.yaml` 的 DSN）。
2. 构建并启动：
   ```bash
   docker compose up -d --build
   ```
   首次启动会自动执行 `server/sql/schema.sql` 建库建表，Go 服务启动时再跑增量迁移（migrations），并创建超级管理员 `admin / 123456`（首次登录强制改账号密码并绑定邮箱）。
3. 访问：
   - 后台管理：`http://<服务器IP>/`（生产用域名 + HTTPS，见下「HTTPS」）。
   - 客户端 API：`http://<服务器IP>/api/v1/...`，WebSocket：`/ws`。
4. 常用运维：
   ```bash
   docker compose logs -f server     # 看服务端日志
   docker compose ps                 # 状态
   docker compose down               # 停止（保留数据卷）
   ```

数据卷：`mysql_data`、`redis_data`、`uploads`（头像/图片）。删除数据请显式 `docker compose down -v`（会清空，谨慎）。

### HTTPS（生产）
Compose 内的 `web`(Nginx) 监听 80。生产建议在其前面再放一层带证书的反代（如宿主机 Nginx / Caddy / Traefik），或在 `deploy/nginx.conf` 增加 443 + Let's Encrypt 证书。确保 `love.lxii.cc` 解析到服务器。

### 邮箱验证码 / 存储 / 站点信息
登录后台 → 系统设置：填写 SMTP（注册邮箱验证码）、存储方式、站点名/LOGO 等。这些存于数据库 `app_setting`，随时可改，无需重启。

<!-- APPEND-DEPLOY-1 -->

---

## 二、Docker 单容器（仅 Go 服务，外接 MySQL/Redis）

适合已有 MySQL/Redis、或后台前端单独托管的场景。

```bash
cd server
docker build -t linxi-server .
docker run -d --name linxi-server \
  -p 8080:8080 \
  -v $(pwd)/config.yaml:/app/config.yaml:ro \
  -v linxi-uploads:/app/uploads \
  linxi-server
```
其中 `config.yaml` 参照 `server/config.example.yaml`，把 `mysql.dsn`、`redis.addr` 指向你的实例。后台前端可另用任意静态服务器托管 `admin/dist`，并把 `/api`、`/ws`、`/uploads` 反代到本容器。

---

## 三、前后端分离手动部署（systemd + Nginx）

### 1. 服务端（Go）
```bash
cd server
cp config.example.yaml config.yaml    # 修改 dsn/redis/jwt_secret
go build -o linxi-server .
./linxi-server config.yaml            # 或注册为 systemd 服务
```
初始化数据库：`mysql < server/sql/schema.sql`（或让服务启动时自动迁移）。

systemd 示例 `/etc/systemd/system/linxi-server.service`：
```ini
[Unit]
After=network.target mysql.service redis.service
[Service]
WorkingDirectory=/opt/linxi
ExecStart=/opt/linxi/linxi-server /opt/linxi/config.yaml
Restart=always
User=linxi
[Install]
WantedBy=multi-user.target
```

### 2. 后台前端（Vue）
```bash
cd admin
npm install
npm run build      # 产物在 admin/dist
```
把 `admin/dist` 交给 Nginx 托管，并反代 `/api`、`/ws` 到 `127.0.0.1:8080`，静态映射 `/uploads/`。可直接参考 `deploy/nginx.conf`。

### 3. Nginx
参考 `deploy/nginx.conf`（把 `proxy_pass http://server:8080` 改为 `http://127.0.0.1:8080`，`root` 指向 `admin/dist`，`/uploads/` 指向服务端 `upload_dir`）。

---

## 四、CI/CD
- `.github/workflows/ci.yml`：push/PR 到 `main` 时构建测试（Go：vet/build/test；Android：debug+release APK）。
- `.github/workflows/deploy.yml`：push 到 `main` 且 `server/**` 变更时，构建 Linux 二进制并经 SSH 部署到服务器（需配置 `DEPLOY_HOST/USER/KEY` 等 Secrets）。

## 五、初始账号与安全清单
- 超级管理员：`admin / 123456`，**首次登录强制改账号+密码+绑定邮箱**。
- 上线前务必：改 `jwt_secret`、数据库密码；配置 HTTPS；后台填好 SMTP；确认 `/uploads` 目录可写。

