# 阶段 2：资料同步客户端设计

**日期：** 2026-08-10  
**状态：** 已批准

## 目标

完成已存在的服务端资料与纪念日 API 在 Android 端的资料状态闭环。资料变更必须由服务端的完整权威响应驱动；`profile_updated` WebSocket 事件仅作为重新拉取资料的信号。此单元不实现动态头像上传和媒体处理。

## 范围

包含：

- `ProfileRepository` 统一管理 `CoupleProfile` 的内存状态、`SharedPreferences` 缓存与服务端同步；
- 启动和绑定成功后的资料拉取；
- `profile_updated` 触发的资料重新拉取；
- 将登录级 WebSocket 生命周期从状态共享开关中解耦；
- 退出登录和进入 Demo 模式时清理资料状态。

不包含：

- `POST /api/v1/profile/avatar`；
- 图片格式验证、裁剪、动态媒体 worker、缩略图下载与通知头像更新；
- 资料编辑或纪念日 UI。

## 架构

### ProfileRepository

`ProfileRepository` 是资料状态的唯一入口，提供：

- 可观察的 `CoupleProfile?` 内存状态；
- `refresh()`：调用 `GET /api/v1/pair/status`，仅接受 `bound=true` 的完整资料；
- 基于服务端响应的权威回写方法，供后续 `PUT /profile` 与 `PUT /pair/anniversary` 使用；
- 缓存读取与清除。

缓存使用现有 `UserPrefs` 的 `SharedPreferences`，以 `CoupleProfile.toCacheJson()` 保存 JSON。成功的服务端资料覆盖内存与缓存，并同步已有派生字段：

- `UserPrefs.pairId = profile.pairId`；
- `UserPrefs.partnerName = profile.partner.nickname`。

网络或解析失败时，Repository 保留已加载的内存或缓存资料，只记录失败，不将其清空为未绑定状态。

### 运行模式

真实资料同步的必要条件为：存在 token、真实 pair ID 且未处于 Demo 模式。它不要求状态共享已开启，也不要求已授予状态采集知情同意。

Demo 模式不调用资料 API，不创建 WebSocket，不读写真实资料缓存。Demo 页面保持使用本地示例数据。

### WebSocket

`StatusSyncManager` 继续管理唯一的登录级 WebSocket，连接前提改为真实资料同步条件，而非 `SharingRuntimePolicy.canRunNow()`。

事件处理分为两类：

1. `profile_updated`：通过 `WsEventRouter` 解析，只信任其中的 `user_id` 作为刷新信号；异步调用 `ProfileRepository.refresh()`。事件内任何昵称、头像或纪念日字段均不得使用。
2. 状态共享和互动事件：维持既有 `SharingRuntimePolicy.canRunNow()` 门控。共享关闭时，既不上传设备状态，也不展示或处理敏感状态事件；但资料刷新仍可正常运行。

`onOpen` 仅在状态共享允许时执行 `pushNow()`。网络恢复时允许重连登录级 WebSocket，但只在共享允许时调用 `pushNow()`。

退出登录或进入 Demo 模式时，调用 `StatusSyncManager.disconnect()` 和 `ProfileRepository.clear()`；缓存与派生资料字段一并清除。

## 生命周期与调用点

- `App.onCreate()`：初始化 `ProfileRepository`，使其可读取私有偏好缓存；
- `MainActivity.onCreate()`：真实资料同步条件成立时连接 WebSocket 并触发资料刷新；
- `BindScreen`：真实绑定成功后，以 `/pair/status` 刷新资料；Demo 分支不刷新并清空真实资料状态；
- 状态共享设置：不再关闭登录级 WebSocket；只开始或停止前台状态服务与状态发送；
- 退出登录：断开 WebSocket、清空 Repository、清除 token、绑定及派生资料状态；
- Boot 与网络恢复：真实资料同步条件成立时连接；只有共享允许时才启动状态服务或发送状态。

## 错误处理

- `profile_updated` 的未知类型、损坏 JSON 或缺少 `user_id` 由 `WsEventRouter` 丢弃；
- 刷新失败只记录日志，保留当前资料；
- 服务端返回 `bound=false` 仅在真实成功响应时清除 Repository 状态，调用方据此回到绑定流程；
- Repository 不基于 WebSocket payload 修改资料，避免不完整或伪造事件污染本地状态。

## 测试

先以 JVM 单元测试定义以下契约：

- `ProfileSyncPolicy` 在 token、真实 pair ID、非 Demo 条件下允许登录级连接，与状态共享/知情同意无关；Demo、无 token 或无 pair ID 时拒绝；
- `ProfileRepository` 对权威资料响应覆盖内存、缓存及派生 `UserPrefs` 字段；
- 失败刷新不清空已缓存资料；
- `profile_updated` 只产生刷新动作，未知或损坏消息无动作；
- 状态共享关闭时仍允许资料事件刷新，但禁止 `pushNow()` 和敏感状态事件处理；
- Demo 与退出登录清理资料状态且不触发 API/连接。

GitHub Actions 继续作为唯一门禁，执行 Android JVM 单测以及 Debug、Release 构建。
