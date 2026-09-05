# LxDay 运营后台

本目录是林曦日记的 Vue 3 运营后台，不是通用模板。业务页面由 Go 服务端接口驱动，包含数据看板、用户、关系绑定、相册、APP 版本、通知、存储、运行参数、活动的一起听房间、审计日志和网络日志。

生产镜像会先构建本后台，再将 `dist` 嵌入服务端二进制。`dist` 是构建产物，禁止提交。

## 技术栈

- Vue 3 + TypeScript + Vite
- Element Plus + Tailwind CSS
- Pinia + 持久化插件
- ESLint、Prettier、Stylelint、`vue-tsc`

## 环境要求

- Node.js >= 20.19
- npm（仓库提交的 `package-lock.json` 是唯一依赖锁文件）

## 常用命令

```bash
npm ci                 # 严格按 package-lock.json 安装
npm run dev            # 本地开发
npm run build          # 类型检查并构建生产产物
npm run lint           # ESLint
```

开发环境的 API 地址由 Vite 环境配置决定；生产环境中后台、API 和 WebSocket 使用同一来源，服务端负责托管内嵌 SPA。

## 安全约束

- 后台访问令牌放在 `sessionStorage`，浏览器会话结束后自动清除。
- 路由守卫只负责导航体验，真正的认证、权限、数据归属和危险操作校验必须由服务端完成。
- 删除、解绑、清理等操作使用统一确认组件，并显示明确后果；组件不能替代服务端权限校验。
- 业务页使用轻量拟态表面；移动端编辑、删除和确认按钮保持可见、可触达，不依赖悬停状态。
- 禁止把 `JWT_SECRET`、SMTP/存储凭据、`APP_KEY`、访问令牌或签名文件写进源码、提交的 `.env`、截图或文档。

完整的开发和发行检查见 [../docs/DEVELOPMENT.md](../docs/DEVELOPMENT.md) 与 [../AGENTS.md](../AGENTS.md)。
