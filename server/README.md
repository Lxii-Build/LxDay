# 林曦日记 · 服务端

Go 1.22 + Gin + gorilla/websocket + MySQL + Redis。

## 快速启动

```bash
# 1. 初始化数据库
mysql -uroot -p < sql/schema.sql

# 2. 配置
cp config.example.yaml config.yaml
# 编辑 config.yaml：jwt_secret / mysql.dsn / redis.addr / push.provider

# 3. 运行
go mod tidy
go run . config.yaml
```

## 目录

| 文件 | 说明 |
|---|---|
| main.go | 入口 + 路由 + 配置加载 |
| models.go | 数据模型 + WebSocket 消息协议 |
| store.go | MySQL/Redis 存储层 |
| hub.go | WebSocket 实时通道（单机版，多节点扩展点见注释） |
| push.go | 推送网关适配层（个推/极光，当前为占位实现） |
| handlers.go | 全部 HTTP handler + JWT |
| sql/schema.sql | 建库建表 |

## 接口速览

```
POST /api/v1/auth/register           注册
POST /api/v1/auth/login              登录
POST /api/v1/pair/create-invite      生成邀请码
POST /api/v1/pair/bind               绑定
GET  /api/v1/pair/status             绑定状态
GET  /api/v1/partner/status          对方实时状态
POST /api/v1/todos                   创建待办
GET  /api/v1/todos                   待办列表
PUT  /api/v1/todos/:id               编辑待办
POST /api/v1/todos/:id/complete      完成待办
DELETE /api/v1/todos/:id             删除待办
POST /api/v1/diaries                 发布日记
GET  /api/v1/diaries?date=YYYY-MM-DD 日记归档
PUT/DELETE /api/v1/diaries/:id       编辑/删除日记
POST /api/v1/interactions/comfort    求陪伴
POST /api/v1/interactions/calm       求冷静
POST /api/v1/interactions/ring       强制响铃（带冷却）
POST /api/v1/push/register-token     注册推送 token（预留）
DELETE /api/v1/push/token            注销推送 token（预留）
POST /api/v1/diaries/images          日记图片上传（multipart, 本地磁盘）
GET  /api/v1/status/history?date=&limit=&offset=   状态历史时间线
GET  /api/v1/status/history/battery?date=          24h 电量曲线
WS   /ws?token=<JWT>                 实时通道
```

统一响应：`{"code":0,"message":"ok","data":{...}}`，业务错误码：
`1001 未绑定 / 1002 参数错误 / 1003 未授权 / 1006 昵称占用 / 1007 账号或密码错误 / 1008 邀请码生成失败 / 1009 绑定失败 / 1010 操作失败 / 1011 操作过于频繁`。

## 待接入 / 说明

1. **推送**：按设计决策**不接商业推送**，纯 WS + 离线重连补拉 + 本地 AlarmManager。`push.go` 保持占位，`/push/*` 接口为预留。
2. **图片上传**：已实现本地磁盘存储（`uploads/diary/{pairId}/{uuid}.ext`），Nginx 静态映射 `/uploads/`。
3. **状态历史**：客户端 5min 上报时服务端 `INSERT IGNORE` 落 `status_history`（幂等）；待办到点提醒每分钟扫描一次。
4. **多节点部署**：`hub.go` 当前为单机内存路由；横向扩容时改为 Redis Pub/Sub 或网关路由。
5. **本地运行需建目录**：`mkdir -p uploads`。
