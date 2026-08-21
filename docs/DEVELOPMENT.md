# 开发环境与验证流程

规范见仓库根 `AGENTS.md`。本文只讲"怎么跑"。

---

## 一、工具链

| 工具 | 版本 | 说明 |
|---|---|---|
| Go | 1.22+ | 纯 Go（`CGO_ENABLED=0`），SQLite 用 `modernc.org/sqlite` |
| JDK | Temurin 21 | Android 构建要求 |
| Android SDK | platform 36 + build-tools 36 | |
| Gradle | 9.7.0 | |
| Node | ≥20.19 | 后台前端（Vite 7 + Vue 3.5） |

`android/local.properties` 需要（该文件在 `.gitignore` 内，不提交）：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

冒号与反斜杠都要转义，写错会报「文件名、目录名或卷标语法不正确」。

---

## 二、日常命令

### 服务端

```bash
cd server
export CGO_ENABLED=0            # 纯 Go，不需要 C 工具链
export GOPROXY=https://goproxy.cn,direct

gofmt -l .                      # 输出为空才算通过；gofmt -w . 自动修
go vet ./...
go test -timeout 400s ./...     # 必须带 timeout，否则死锁会等 10 分钟
go build -o /tmp/lxday.exe .
```

**AVIF 编码极慢（约 195s/张），解码正常（约 400ms/张）。**
服务端只解不编，所以生产无影响；但**不要在测试里编码 AVIF**，会拖垮 CI。

### 客户端

```bash
cd android
gradle :app:compileDebugKotlin --no-daemon    # 首次约 11 分钟，之后快
gradle :app:testDebugUnitTest --no-daemon
```

### 后台

```bash
cd admin
npm run build                   # 含 vue-tsc --noEmit 类型检查
```

---

## 三、本地起完整服务（后台真机效果验证）

后台前端是 `go:embed` 进服务端二进制的，所以要先把产物拷进 `server/webdist/`：

```bash
cd admin && npm run build
cd .. && rm -rf server/webdist && mkdir -p server/webdist
cp -r admin/dist/* server/webdist/

cd server && CGO_ENABLED=0 go build -o /tmp/lxday.exe .

mkdir -p /tmp/lxrun/data && cd /tmp/lxrun
cat > config.yaml <<'EOF'
server:
  port: 7799
db:
  path: ./data/lx.db
storage:
  upload_dir: ./uploads
app:
  token_ttl_hours: 720
EOF
JWT_SECRET=dev /tmp/lxday.exe config.yaml
```

- 初始超管口令写在 `<数据目录>/initial-admin-password.txt`
- **必须走完 登录 → 首登改密 → 主界面** 才算测到位。只测登录页会漏掉主界面的问题
- **收尾务必清掉 `server/webdist/`**，它不该进提交

### ⚠️ 本机代理会造成假 502

如果 shell 里有 `ALL_PROXY` / `HTTP_PROXY`，大 body 请求会被代理桥打断，
表现为 502 或 `write ECONNRESET`——**这不是服务端的问题**。测本地服务时：

```bash
curl --noproxy '*' ...
# Python: urllib.request.build_opener(urllib.request.ProxyHandler({}))
```

排查 0821 那个"生产 502"时就在这上面浪费过时间：本地复现的 502 是代理假象，
绕过代理后 8.7MB 上传直接 200 成功；而生产的 502 是真的（根因见 `AGENTS.md` 2.3）。

---

## 四、后台移动端验证

```bash
cd admin
node scripts/mobile-audit.mjs http://127.0.0.1:7799
```

四档视口（一加 15 `412×915` / 最窄 `360×640` / iPhone `390×844` / 平板 `768×1024`）
逐页断言：无横向溢出、无控制台 error、无 4xx/5xx、非白屏。
截图落在 `admin/mobile-audit/`。

卡片化断点是 **768px**：窄屏走卡片列表，平板及以上仍是 el-table。

---

## 五、CI 查询（无 gh CLI 时）

用 GitHub REST API + `Authorization: Bearer <token>`。

**拉 job 日志要手动处理 302**：日志实际在 Azure 存储，重定向时不能带
`Authorization` 头，否则 401。做法是禁用自动重定向
（自定义 `HTTPRedirectHandler.redirect_request` 返回 None），
捕获 `HTTPError` 后从 `e.headers["Location"]` 再取。

手动触发工作流：`POST /actions/workflows/<file>/dispatches`，
body `{"ref":"main","inputs":{...}}`，返回 204 即成功。

---

## 六、数据库

- schema 唯一真源是 `server/sql/schema.sql`，启动时 `go:embed` 后自动执行
  （`CREATE TABLE IF NOT EXISTS`，幂等）
- 老库补列走 `migrations.go` 的 `schemaAddedColumns`
  （SQLite 的 `ALTER TABLE ADD COLUMN` 不支持 `IF NOT EXISTS`，
  所以要先查 `PRAGMA table_info`）
- 删表（功能下线）走 `DropRetiredTables`，**刻意不在 `runMigrations` 里自动执行**——
  否则新镜像一上线数据就没了，导出留档的接口会变成废物
- `MaxOpenConns(1)`：热路径不要重复查库，配置类读取一律走
  `settings.go` 的进程内缓存（0820 踩过自锁死锁）
