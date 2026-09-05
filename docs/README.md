# 林曦日记 · 项目文档

这是一个标准的 Android + Go + Vue 单仓项目。先读根目录 [README.md](../README.md) 了解
定位和快速运行，再按角色选择下面的文档。产品说明、部署步骤、工程验证和隐私边界都以
当前实现为准，不保留已经废弃的方案草稿。

## 面向使用者

- [APP_INTRO.md](APP_INTRO.md)：功能、权限、隐私和开始使用。
- [SCREENSHOTS.md](SCREENSHOTS.md)：Android 音乐/歌词与后台界面示意图。
- [../CHANGELOG.md](../CHANGELOG.md)：版本更新记录；App 关于页同时展示版本号和提交短哈希。

## 面向部署者

| 文档 | 内容 |
| --- | --- |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Docker Compose、HTTPS/WSS、升级、备份和恢复 |
| [BACKUP.md](BACKUP.md) | SQLite、上传目录与私密媒体卷的备份策略 |
| [SIGNING.md](SIGNING.md) | Android 签名、CI Secret 和发行包 |
| [../server/README.md](../server/README.md) | 服务端配置、路由、迁移和运行参数 |
| [../admin/README.zh-CN.md](../admin/README.zh-CN.md) | 后台开发、构建和安全约束 |

## 面向开发者

| 文档 | 内容 |
| --- | --- |
| [DEVELOPMENT.md](DEVELOPMENT.md) | JDK/Go/Node 工具链、启动和验证命令 |
| [feature-index.md](feature-index.md) | 功能、页面、数据层和接口映射 |
| [android-ui.md](android-ui.md) | miuix 页面骨架、状态、触达尺寸和无障碍 |
| [SELFTEST.md](SELFTEST.md) | 服务端、后台和真机发布前检查清单 |
| [diagnostics.md](diagnostics.md) | 日志导出、故障定位和脱敏规则 |
| [foreground-notification.md](foreground-notification.md) | 状态同步前台服务和通知卡 |
| [ALBUM.md](ALBUM.md) | 相册分页、媒体 URL 和回收站隐私边界 |

## 产品边界

- Android 使用 Kotlin/Compose 与 miuix 组件；音乐功能以 NeriPlayer 的设备端模式为参考。
  网易云 Cookie 只在本机 Keystore 加密保存；搜索、收藏、歌词、播放地址解析和播放队列
  都在设备端完成。
- “我的 → 音乐设置”集中管理账号、音质、循环/随机、音频焦点、歌词翻译、搜索历史和
  灵动岛/播放胶囊。情侣已绑定后进入“一起听”会自动加入双方唯一房间。
- Go 服务端只同步 `source=netease`、歌曲 ID、展示元数据和毫秒时间轴，拒绝客户端提交
  第三方 Cookie 或播放 URL。
- 后台通过 Go `embed` 发布；截图中使用的图片若标记为示意图，就不代表生产数据或真机像素。

架构与安全基线：

- [../ARCHITECTURE.md](../ARCHITECTURE.md)：部署拓扑、数据流和鉴权不变量。
- [../DESIGN.md](../DESIGN.md)：当前有效的产品与交互决策。
- [../SECURITY.md](../SECURITY.md)：漏洞报告和隐私边界。
- [../CONTRIBUTING.md](../CONTRIBUTING.md)：贡献、代码风格和提交前检查。
