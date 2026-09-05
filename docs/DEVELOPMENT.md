# 开发与验证

硬性仓库约束见根目录 [AGENTS.md](../AGENTS.md)。本文只记录可重复的开发入口和验证顺序。

## 工具链

| 部分 | 要求 |
| --- | --- |
| Server | Go 1.26+，`CGO_ENABLED=0` |
| Admin | Node.js 20.19+、npm |
| Android | JDK 21、Android SDK 37、Gradle 9.7 |

依赖版本以 `server/go.mod`、`admin/package-lock.json` 和 `android/gradle/libs.versions.toml` 为准。新增 Go 依赖前先确认其最低 Go 版本，并同步 Dockerfile 与 CI。

## 日常验证

### Server

```bash
cd server
gofmt -l .
go vet ./...
go test -timeout 400s ./...
CGO_ENABLED=0 go build -trimpath -o linxi-server .
```

`gofmt -l .` 必须没有输出。测试要覆盖旧 SQLite 库升级、NULL 列、分页去重、上传图片预算、清理任务和并发限流；不要只用全新数据库测试迁移。

### Admin

```bash
cd admin
npm ci
npm run build
npm run lint
```

`npm run build` 包含 `vue-tsc --noEmit`。前端改动后应使用 `node scripts/mobile-audit.mjs <server-url>` 逐页检查登录、首登改密、用户、关系、相册、版本、设置、审计和网络日志页面，而不是只打开登录页。

### Android

```bash
cd android
gradle :app:compileDebugKotlin --no-daemon
gradle :app:testDebugUnitTest --no-daemon
```

真机还要验证状态共享默认开启、权限降级、WebSocket 重连、互动撤回、待办闹钟、相册多图滑动、图片失败占位、返回动画和更新弹窗。真机行为不能由 JVM 单测代替。

## 本地运行完整服务

后台构建产物只用于本地嵌入测试，不要提交：

```powershell
cd admin
npm run build
cd ../server
Remove-Item -Recurse -Force webdist -ErrorAction SilentlyContinue
New-Item -ItemType Directory webdist | Out-Null
Copy-Item ../admin/dist/* webdist -Recurse
go build -o linxi-server .
```

用独立数据目录启动，避免污染真实数据库：

```powershell
$env:JWT_SECRET = (openssl rand -hex 32) # 仅本机测试；生产环境请使用独立随机密钥
./linxi-server ./config.example.yaml
```

配置中的数据库和上传路径应改到临时目录；服务会在数据目录写入初始管理员口令文件。测试结束删除整个临时运行目录，保留仓库中的 `webdist/index.html` 占位文件。

## 数据库与迁移

- `server/sql/schema.sql` 是新库基线；`server/migrations.go` 负责老库补列和索引。
- 迁移严格按“建表 → 补列 → 建索引”执行。
- 新增列必须有“旧表结构 → 运行迁移”的测试；不能只测试新库。
- `MaxOpenConns(1)` 下，rows 遍历期间禁止发起额外查询；配置需显式预热或使用进程缓存。
- 功能下线不在启动时静默删表或删文件；不可逆清理必须有独立迁移、备份和审计。

## 安全检查

```powershell
rg -n --hidden --glob '!.git/**' --glob '!.tmp-lint-report/**' -i 'ghp_|github_pat_|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|JWT_SECRET\s*[:=]\s*[^$<{[:space:]]|APP_KEY\s*[:=]\s*[^$<{[:space:]]' .
```

命令不应匹配真实凭据。配置、签名文件、APK 和访问令牌只通过环境变量或 CI Secret 提供。客户端可以被逆向，不能把共享通讯密钥当作安全边界。

## 前端真机审计

服务启动后运行：

```bash
node admin/scripts/mobile-audit.mjs http://127.0.0.1:7740
```

脚本覆盖窄屏、手机和平板视口，重点检查横向溢出、白屏、控制台错误和 API 失败。若前端更新后线上仍是旧页面，检查运行镜像：后台通过 `go:embed` 进入服务端二进制，重新构建前端文件本身不会改变已运行的容器。
