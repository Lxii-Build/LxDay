# 贡献指南

感谢你为林曦日记提交改进。提交前请先阅读根目录的 [AGENTS.md](AGENTS.md)，它是仓库唯一的长期开发约束来源。

## 开发环境

- Server：Go 1.26+。
- Admin：Node.js 20.19+ 与 npm。
- Android：JDK 21、Android SDK 37、Gradle 9.7。

## 工作流

1. 从 `main` 创建分支，说明问题或功能范围。
2. 先搜索仓库中已有 API、组件和测试，再实现改动。
3. UI 改动保持 Android 的 miuix 组件规范和后台的拟态表面；服务端功能必须在鉴权、权限和功能开关处同时校验。
4. 更新相应文档、测试和变更日志。
5. 提交前运行下面的检查，并在 PR 描述中标明无法运行的检查及原因。

## 提交前检查

```bash
cd server && gofmt -l . && go vet ./... && go test -timeout 400s ./...
cd ../admin && npm ci && npm run lint && npm run build
cd ../android && gradle :app:compileDebugKotlin --no-daemon && gradle :app:testDebugUnitTest --no-daemon
```

后台改动还需要用 `node admin/scripts/mobile-audit.mjs <server-url>` 走完登录、首登改密和主要菜单；相册、通知、保活和状态同步需要至少一台真实 Android 设备复核。

## Pull request

PR 标题使用动词开头，正文包含：背景、改动、验证命令、截图（若有 UI 变化）、迁移/回滚说明和隐私影响。不要提交密钥、构建产物、`server/webdist/`（占位 `index.html` 除外）、`server/*.exe` 或 `android/local.properties`。
