# 林曦日记

[![Server CI](https://github.com/Lxii-Build/LxDay/actions/workflows/build-server.yml/badge.svg)](https://github.com/Lxii-Build/LxDay/actions/workflows/build-server.yml)
[![Android CI](https://github.com/Lxii-Build/LxDay/actions/workflows/build-android.yml/badge.svg)](https://github.com/Lxii-Build/LxDay/actions/workflows/build-android.yml)

林曦日记是一个面向两个人的 Android 互动应用，包含 Go 服务端、Vue 运营后台和共同相册。它支持账号与伴侣绑定、状态共享、待办提醒、实时互动、相册与版本更新检查。

本仓库的运行口径以代码和 `AGENTS.md` 为准；版本历史见 [CHANGELOG.md](CHANGELOG.md)。部署、开发和安全边界分别见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)、[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) 和 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 组成

| 部分 | 实现 | 职责 |
| --- | --- | --- |
| Android | Kotlin + Jetpack Compose + miuix | 登录、状态采集、WebSocket、待办、互动、相册、一起听 |
| Server | Go 1.26 + Gin + SQLite | REST API、WebSocket、鉴权、图片处理、定时任务 |
| Admin | Vue 3 + TypeScript + Vite + Element Plus | 拟态运营后台、用户、关系、相册、通知、设置、一起听房间、审计与版本管理 |

服务端会通过 `go:embed` 内嵌后台构建产物，默认是一体化单容器。SQLite、公开资源和私密相册分别使用持久化目录；在线状态、限流和离线事件队列使用进程内存，因此当前部署模型是单实例。

## 主要能力

- 邮箱注册/登录、伴侣邀请码绑定、解除绑定和实时关系状态。
- 电量、充电、屏幕、前台应用、网络等状态共享，以及历史时间线和电量曲线。
- 求陪伴、求冷静、响铃提醒，支持服务端冷却和撤回。
- 双向待办、重复规则、服务端扫描和 Android 本地闹钟兜底。
- 共同相册：相册管理、批量上传、分页浏览、左右滑动查看、评论点赞、回收站和缩略图审核。
- 音乐与一起听：在“我的 → 音乐设置”完成网易云网页登录或扫码绑定；音乐库支持歌曲搜索、我的收藏、收藏切换、播放队列、音质、循环/随机和同步歌词/歌词搜索。每对情侣一个活动房间，已绑定伴侣进入一起听会自动加入（没有房间时自动创建）；双方各自在本机解析和播放歌曲，服务端只同步网易云歌曲 ID、展示元数据、成员控制和 WSS 时间轴，不保存 Cookie、密码或播放地址。
- 更新检查：从 GitHub Releases 的统一更新流获取正式版和 prerelease；更新说明来自仓库根目录 `CHANGELOG.md`，客户端展示版本号、发布时间、版本类型和历史日志。
- 后台可编辑用户、绑定关系、相册、APP 版本、通知、系统参数和审计记录，并可查看/关闭活动的一起听房间。

## 快速运行

### Docker Compose

```bash
cp .env.example .env
# 在 .env 中设置至少 32 字节的长随机 JWT_SECRET（例如：openssl rand -hex 32）
docker compose pull
docker compose up -d
```

容器监听 `7740`。生产环境请在容器前配置 HTTPS/WSS 反向代理，并将 `data`、`uploads` 和 `uploads-private` 持久化。详细步骤见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

### 本地开发

```bash
cd server
go test -timeout 400s ./...

cd ../admin
npm ci
npm run build
```

Android 构建要求 JDK 21、Android SDK 37 和 Gradle 9.7，命令及真机检查见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

## 安全边界

- APK 不包含共享通讯密钥。客户端可被解包，任何编入 APK 的密钥都不能作为信任根。
- REST 和 WebSocket 使用 JWT；服务端固定只接受 HS256，并实时校验账号状态与令牌版本。
- Android 访问令牌使用 Android Keystore 的 AES/GCM 加密保存，旧版明文令牌只做一次迁移，迁移失败即清除。
- 私密照片不通过静态目录公开，统一经 `/media/<id>` 鉴权代理读取；后台只允许查看 384px 缩略图。
- `JWT_SECRET` 只通过环境变量或未提交的运行配置提供。不要把 `.env`、`config.yaml`、签名文件或 APK 构建密钥提交到仓库。
- 当前不接入商业推送；实时消息由 WebSocket 和本地提醒承担，`push.go` 仅保留兼容适配入口。

## 目录

```text
.
├── android/              Android 客户端
├── admin/                Vue 运营后台
├── server/               Go 服务端与 SQLite schema
├── docs/                 部署、开发、相册、备份和签名文档
├── .github/workflows/    CI、镜像和发行版工作流
├── Dockerfile            一体化镜像构建
├── docker-compose.yml    单实例部署
├── AGENTS.md             仓库开发约束
└── CHANGELOG.md          客户端可读取的版本日志
```

## 文档导航

- [ARCHITECTURE.md](ARCHITECTURE.md)：组件边界、数据流、鉴权和部署模型。
- [DESIGN.md](DESIGN.md)：当前产品与交互决策，不保存已废弃的方案草稿。
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)：Docker、反向代理、升级、备份和恢复。
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)：工具链、测试和后台真机检查。
- [docs/README.md](docs/README.md)：完整文档索引。
- [docs/SCREENSHOTS.md](docs/SCREENSHOTS.md)：Android 音乐/歌词与运营后台示意图、真机截图流程。
- [docs/ALBUM.md](docs/ALBUM.md)：相册接口和媒体隐私约束。
- [docs/SIGNING.md](docs/SIGNING.md)：Android 签名与发行配置。
- [server/README.md](server/README.md)：服务端配置、路由和运行说明。
- [android/README.md](android/README.md)：客户端模块、权限和降级行为。
- [CONTRIBUTING.md](CONTRIBUTING.md)：贡献、验证和 PR 要求。
- [SECURITY.md](SECURITY.md)：漏洞报告和隐私边界。

## 贡献前检查

提交前至少执行：

```bash
cd server && gofmt -l . && go vet ./... && go test -timeout 400s ./...
cd ../admin && npm run build
cd ../android && gradle :app:compileDebugKotlin --no-daemon && gradle :app:testDebugUnitTest --no-daemon
```

不要提交构建产物、`server/webdist/`（占位 `index.html` 除外）、`server/*.exe`、`android/local.properties` 或任何密钥。
