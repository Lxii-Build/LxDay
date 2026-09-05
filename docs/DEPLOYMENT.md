# 部署与运维

推荐使用仓库根目录的 Docker Compose。服务是单实例 Go 容器，内嵌 SQLite、后台 SPA、REST API、WebSocket 和媒体代理；不需要 MySQL、Redis 或容器内 Nginx。生产 HTTPS/WSS 由宿主机反向代理终止。

## 1. Compose 部署

要求 Docker Engine 和 Compose 插件。

```bash
cp .env.example .env
# 编辑 .env，至少设置 JWT_SECRET；它必须是至少 32 字节的长随机值，不能使用示例占位符
docker compose pull
docker compose up -d
docker compose ps
```

默认镜像为 `ghcr.io/lxii-build/lxday:latest`，容器端口为 `7740`。私有 GHCR 包需要先登录：

```bash
echo "$CR_PAT" | docker login ghcr.io -u <github-user> --password-stdin
docker compose pull
docker compose up -d
```

无法直连 GHCR 时，可从工作流下载镜像归档后导入：

```bash
gunzip -c lxday-image.tar.gz | docker load
docker compose up -d
```

镜像、端口和密钥只从 `.env` 注入。`APP_KEY` 是旧部署兼容字段，当前客户端不使用，不能当作安全措施。

## 2. 数据卷与初次登录

Compose 创建三个命名卷：

| 卷 | 内容 |
| --- | --- |
| `db_data` | SQLite 数据库和初始管理员口令文件 |
| `uploads` | 头像和历史公开资源 |
| `uploads_private` | 相册原图、缩略图和预览图 |

首次启动会自动建库。超级管理员随机口令写入容器内 `/app/data/initial-admin-password.txt`，只通过以下命令读取，不写入日志：

```bash
docker compose exec app cat /app/data/initial-admin-password.txt
```

登录后立即完成首次改密，并在确认新凭据可用后删除口令文件：

```bash
docker compose exec app rm -f /app/data/initial-admin-password.txt
```

不要使用 `docker compose down -v`，除非确认要同时删除所有命名卷和用户数据。

## 3. 反向代理

只把宿主机回环地址暴露给反代，公网只开放 HTTPS。反代必须转发 WebSocket Upgrade，并把 `/`、`/api`、`/ws`、`/media` 和公开资源路径转发到 `127.0.0.1:7740`。

Caddy 示例：

```text
example.example {
    reverse_proxy 127.0.0.1:7740
}
```

上线后检查：

```bash
curl -fsS https://example.example/healthz
curl -fsS https://example.example/readyz
```

`healthz` 只表示进程存活；`readyz` 还会检查 SQLite、公开目录和私密媒体目录是否可写。容器直连 `http://<host>:7740` 只适合本机排障，不能替代 TLS。

## 4. 升级与回滚

生产不自动部署。升级前先备份数据库和两个媒体目录，再执行：

```bash
docker compose pull
docker compose up -d
docker compose ps
docker compose logs --tail=200 app
```

启动失败先看 `docker compose logs app` 和 `readyz`。回滚时把 `.env` 中的 `LXDAY_IMAGE` 改为已验证的旧 tag，再运行同样的 `pull`/`up`；不要删除数据卷。

后台前端已嵌入服务端二进制，线上看不到新后台时，先确认实际运行镜像 tag 和容器创建时间，再拉取并重建容器。

## 5. 备份与恢复

SQLite 文件与相册文件必须成套备份；只备份数据库会留下不可用的媒体记录，只备份媒体会留下孤儿文件。按 [BACKUP.md](BACKUP.md) 执行一致性备份、SHA-256 校验和隔离恢复演练。

备份至少包括：

- `db_data` 中的 `lxday.db`；
- `uploads`；
- `uploads_private`；
- 未提交的运行配置和密钥应进入独立的密钥管理系统，不要打包进代码仓库或普通备份链接。

## 6. 安全清单

- [ ] `.env` 中使用强随机 `JWT_SECRET`，且没有把它写进 Git。
- [ ] 公网只开放反向代理的 HTTPS 端口，容器端口只监听本机或受限网络。
- [ ] 已完成初始管理员改密并删除口令文件。
- [ ] SQLite、公开资源和私密相册目录均可写且按卷持久化。
- [ ] `/media` 没有被配置为静态目录，私密照片仍经过服务端鉴权。
- [ ] 已验证备份能在隔离目录恢复。
- [ ] 升级后已检查 `/healthz`、`/readyz`、后台登录、WebSocket 和相册读取。

## 7. 前后端分离（特殊场景）

需要独立托管后台时，可以在 `admin` 中执行 `npm ci && npm run build`，再使用 `server/Dockerfile` 或本地 Go 二进制运行后端。独立前端必须把 `/api`、`/ws` 和 `/media` 反代到后端；私密媒体仍不能由静态服务器直接暴露。默认的一体化镜像更容易保持版本一致，应优先使用。
