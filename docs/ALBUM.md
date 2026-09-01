# 相册模块 · 接口文档

> 源码：`server/album_handlers.go`（HTTP 层）、`server/album_media.go`（上传 + 鉴权代理）、
> `server/album_store.go`（数据访问）、`server/sql/schema.sql`（四张表）。
> 路由注册见 `server/main.go`。本文档以源码为准。

## 0. 约定

- 用户端接口前缀 `/api/v1`，需 `Authorization: Bearer <JWT>`；Release 客户端通过 HTTPS 访问，不携带可提取的共享密钥。
- 响应信封：成功 `{"code":0,"message":"ok","data":...}`，失败 `{"code":<biz>,"message":"<中文原因>"}`。
  常见业务码：`1002` 参数错误、`1010` 服务端错误、`1017` 无权访问（越权/不存在）、`1020` 配额用尽。
- **所有接口都要求已绑定伴侣**（`mustPair`）：相册数据以 `pair_id` 隔离，未绑定直接失败。
- 分页参数一律 `?page=&size=`，`size` 收敛到 1..100，默认 30（与客户端网格三列布局匹配）。
- 时间字段：`taken_at` 来自 EXIF，是**本地墙钟**且可能为 `null`；`created_at` 由
  `CURRENT_TIMESTAMP` 写入，是 **UTC**。两者时区口径不同，跨日比较时务必注意（见 §4.2）。

## 1. 数据模型

四张表，全部带 `pair_id`——归属校验只查本表一次，不必为每次读写多跳一次 join。
`photo.pair_id` 相对 `album` 是冗余，但正是它让「未归类照片（`album_id=0`）」仍能判定归属。

| 表 | 关键列 | 说明 |
|---|---|---|
| `album` | `id` / `pair_id` / `name` / `cover_photo_id` / `created_by` / `status` | `status` 1 正常 2 已删除 |
| `photo` | `id` / `album_id` / `pair_id` / `uploader_id` / `url` / `thumb_url` / `width` / `height` / `size_bytes` / `mime` / `taken_at` / `caption` / `status` | `album_id=0` 表示未归类；`status` 1 正常 2 回收站 |
| `photo_comment` | `id` / `photo_id` / `pair_id` / `user_id` / `content` / `status` | 只能删自己的评论 |
| `photo_like` | `photo_id` + `user_id` 复合主键 | 主键天然去重，重复点赞不会产生第二行 |

索引：`idx_album_pair_status(pair_id,status)`、`idx_photo_pair_album_status(pair_id,album_id,status)`、
`idx_photo_pair_taken(pair_id,taken_at)`、`idx_photo_comment_photo(photo_id,status)`。

**Photo 的 JSON 形态**（`server/models.go`）：`url` / `thumb_url` 一律是 `/media/<id>`、
`/media/<id>/thumb`；真实磁盘相对路径存在 `diskPath`/`diskThumb`，json 标签为 `-`，**永不出网**。

## 2. 接口清单

### 2.1 相册 CRUD

| # | 方法 | 路径 | 入参 | 返回 |
|---|---|---|---|---|
| 1 | GET | `/albums` | — | `Album[]`，含派生字段 `photo_count`、`cover_thumb_url` |
| 2 | POST | `/albums` | `{"name":"…"}` 1–32 字 | 新建的 `Album` |
| 3 | GET | `/albums/summary` | — | `{photo_count,album_count,latest_thumb_url}` |
| 4 | GET | `/albums/:id` | — | `Album`（`photo_count` 现算） |
| 5 | PUT | `/albums/:id` | `{"name"?:"…","cover_photo_id"?:123}` | 更新后的 `Album` |
| 6 | DELETE | `/albums/:id` | — | `{"deleted":<id>}` |

`cover_photo_id` 必须是本 pair 名下 `status=1` 的照片，否则 1017：不校验的话，可以把别人的
照片 id 设成自己相册封面，再借相册列表把缩略图读出来（越权读图）。

### 2.2 相册内照片

| # | 方法 | 路径 | 入参 | 返回 |
|---|---|---|---|---|
| 7 | GET | `/albums/:id/photos` | `?page=&size=` | `{list:Photo[],total,page,size}` |
| 8 | POST | `/albums/:id/photos` | `{"photo_ids":[…]}` 或 `{"photos":[{"id":1}\|{"url":"/media/1"}]}` | `{attached,album_id}` |

`:id=0` 是「未归类」虚拟相册，没有 `album` 行，故不做相册归属校验（照片自带 `pair_id`）。
挂入单次上限 200 张。`photos[].url` 形态是为让客户端把上传返回体原样回传，服务端用
`photoIDFromMediaURL` 拆 id。挂入成功后向伴侣推 WS 事件 `album_new`（普通优先级，不进高优离线补偿队列）。

### 2.3 上传

| # | 方法 | 路径 | 入参 | 返回 |
|---|---|---|---|---|
| 9 | POST | `/media` | `multipart/form-data`，字段名 `file` | 新建的 `Photo`（`album_id=0`） |

单张上限 20MB（可在后台调，见 `album.photo_max_mb`）；魔数白名单
JPEG / PNG / GIF / WebP / **BMP / HEIC / AVIF**。

另有**像素数上限**，默认 12M（约 4000×3000），见 `album.photo_max_megapixels`
（限超管，范围 4M~64M）。这是**内存安全**闸门而非业务规则：解码内存与像素数
成正比（RGBA 每像素 4 字节，12M ≈ 48MB），它直接决定单张最坏占用。
0828 由 64M 下调 —— 那时单张最坏约 256MB，配 3 路并发闸门就是 768MB。
客户端上传前一律把长边压到 2048（约 3.1M 像素），离 12M 还差 4 倍；
12M 又恰好是主流手机主摄的直出尺寸，所以"直接调接口传原图"也仍可用。

上传并发有两道闸：全局同时 3 个图片处理作业（`image_budget.go`），
以及**单账号同时最多 2 个**。后者是为了保证一个人批量上传时，
3 个槽位里永远有 1 个留给其他用户 —— 否则会出现"内存曲线平稳、
但别人都传不上去"的拒绝服务。撞到它回 1028，**可重试**。

`/auth/register`、`/auth/login`、`/auth/send-code`、`/media` 另有
**按来源 IP** 的限流（见 `ip_ratelimit.go`）。理由：`APP_KEY` 编在 APK 里、
逆向即得，**不构成安全边界**；而此前所有限流的键（账号名/邮箱/uid）
都由攻击者自己控制，换一个就从零开始。IP 是唯一不受其随意支配的维度。

HEIC/AVIF 自 0821 起**服务端能真解码**：用 `gen2brain/heic|avif`（底层 wazero，
纯 Go wasm 运行时），不必给 alpine 镜像装 libheif，也不破坏 `CGO_ENABLED=0` 静态编译。
代价是这两个包声明 `go >= 1.25`，`go.mod` / `Dockerfile` / CI 三处版本必须同步。
客户端上传前仍统一转 JPEG——本机转换比服务端解码快得多，也省上传流量；
服务端支持是为了兜住「直接调接口传 HEIC」与将来的其它客户端。

原图**按原字节保存不重编码**（重编码既损画质又抹掉 EXIF），另生成两档等比缩略图：
`_thumb`（长边 384，网格用）与 `_preview`（长边 1080，大图页先加载这档）。
落库同时解析 EXIF 拍摄时间，解析失败留空不阻断上传。

失败原因分了业务码（1020~1028：配额用尽 / 超单张上限 / 魔数不认识 / 无解码器 /
文件损坏 / 像素数超限 / 落盘失败 / 相册功能被关闭 / **本账号在传的张数到顶**），
客户端据此逐张显示原因并判断能否重试。

**新增业务码不要复用现成的**：客户端 `UploadRetryPolicy` 按码判"能否重试"，
而判错的方向不对称 —— 把可重试的判成不可重试会让照片永久停在失败列表，
用户只能重新选图，也就是管理员反馈过的"照片会消失"。
1028 就是这么来的：它最初复用了 1020（配额用尽），而 1020 在客户端
被判为不可重试，于是"等前面几张传完就好"变成了永久失败。
两者都回 429，但可重试性相反。

### 2.4 单张照片

| # | 方法 | 路径 | 入参 | 返回 |
|---|---|---|---|---|
| 10 | GET | `/photos/:id` | — | `{photo,comments,like_count,liked}` |
| 11 | PUT | `/photos/:id` | `{"caption":"…"}` ≤500 字 | 更新后的 `Photo` |
| 12 | DELETE | `/photos/:id` | — | `{"deleted":<id>}`，软删进回收站 |
| 13 | POST | `/photos/:id/restore` | — | 恢复后的 `Photo` |
| 14 | GET | `/photos/recycled` | `?page=&size=` | `{list,total,page,size}` |
| 15 | GET | `/photos/on-this-day` | `?month=&day=`（缺省服务器当天） | `{month,day,list,total}` |

删除只改 `status`、**不删盘上文件**：照片是不可再生资产，误删必须可恢复。
「这一天」单次最多返回 200 张（回忆入口，不是全量浏览）。

**回收站与彻底删除**（0821 补齐，客户端已接入 `RecycleBinScreen`）：

| # | 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|---|
| — | DELETE | `/photos/:id/purge` | — | **彻底删除单张**：删库行 + 真删磁盘上的原图/预览/缩略图 |
| — | POST | `/photos/purge-all` | — | **清空回收站**：同上，批量 |
| — | POST | `/photos/batch-delete` | `{"ids":[…]}` | 批量软删（网格多选） |
| — | POST | `/photos/batch-move` | `{"ids":[…],"album_id":N}` | 批量移动到其它相册 |

> 后三条实际注册的是 `POST /photos/:id`，由 `handlePhotoActionByID` 按 `:id` 的字面值
> （`batch-delete` / `batch-move` / `purge-all`）内部分派 —— gin 不允许同层的静态段与
> 通配段并存，故沿用本文件其它地方（`/albums/summary`、`/photos/recycled`）同样的套路。

软删只改 `status` 并记 `deleted_at`，**不删盘上文件**；彻底删除才落到磁盘。
回收站每张返回「还剩 N 天自动删除」（`recycle_remaining_days`），
保留天数见后台 `retention.recycle_bin_days`，到期由定时任务真删。

`PUT /albums/0` 是「未归类」改名（它不是真实相册行，故单独走这条）；
「未归类」可改名但不可删。

### 2.5 点赞 / 评论

| # | 方法 | 路径 | 入参 | 返回 |
|---|---|---|---|---|
| 16 | POST | `/photos/:id/like` | — | `{like_count,liked}` |
| 17 | DELETE | `/photos/:id/like` | — | `{like_count,liked}` |
| 18 | POST | `/photos/:id/comments` | `{"content":"…"}` 1–500 字 | 新建的 `PhotoComment` |
| 19 | DELETE | `/photos/:id/comments/:cid` | — | `{"deleted":<cid>}` |

删评论把 `user_id` 一并写进 `WHERE`，受影响行数为 0 即判越权（1017「只能删除自己的评论」）。

### 2.6 图片读取（鉴权代理，挂在根路径而非 `/api/v1`）

| # | 方法 | 路径 | 返回 |
|---|---|---|---|
| 20 | GET | `/media/:id` | 原图字节流 |
| 21 | GET | `/media/:id/thumb` | 缩略图字节流 |

挂在根路径（不在 `/api/v1` 下），只挂 `JWTAuth()`；
路径前缀也与 netlog 的 skip 列表对齐（照片 URL 不进 `request_log`）。

客户端侧用自建 Coil `ImageLoader`（`android/.../data/AppImageLoader.kt`）而非默认实例，
在拦截器里给每个图片请求补 `Authorization: Bearer <JWT>`；不携带可从 APK 提取的共享密钥。

响应头 `Cache-Control: private, max-age=2592000, immutable`（禁止中间代理与 CDN 留副本）、
`X-Content-Type-Options: nosniff`、`Content-Disposition: inline`。

### 2.7 后台审核（`/api/admin`，**仅超级管理员**）

| 方法 | 路径 | 入参 | 返回 |
|---|---|---|---|
| GET | `/api/admin/photos` | `current`/`size`/`keyword`（搜 caption）/`pair_id` | `{records,total,current,size}` |
| DELETE | `/api/admin/photos/:id` | — | `{"ok":true}`，软删进用户回收站 |

列表**只回元数据、不含任何图片 URL**。管理员没有用户 token，本就读不了 `/media/<id>`，
返回 URL 只是凭空造一条泄露面。后台页因此只做元数据表格，不显示缩略图
（`admin/src/views/content-audit/modules/photo-table.vue`）。

## 3. 特别说明

### 3.1 为什么图片 URL 是 `/media/<id>` 而不是真实路径

`/upload` 与 `/uploads` 两个静态目录**完全无鉴权**，只靠随机文件名保密。真实路径一旦经
截图、日志、Referer 外泄，任何人都能直接看到情侣私密照片——无需登录、无需是伴侣、无需 App。
对一个存放私密照片的功能，这是最致命的隐私面。

所以：真实相对路径只存在库里（`Photo.diskPath`，json 标签 `-`），对外一律 `/media/<id>`，
读取统一过 `serveMedia` 的 pair 归属校验。归属不符与 id 不存在返回**同一个响应**——
区别对待等于给出「该 id 存在」的探测信号。库里的相对路径还要过 `safeUploadPath` 防路径穿越：
即便库值被写坏（或历史脏数据带 `../`），也不能让请求读到 `uploadDir` 之外的文件。

照片 URL 也不进 `request_log`（netlog 的 skip 前缀包含 `/media`），避免任何后台管理员
从网络日志里直接点开私密相册。

### 3.2 为什么 `/albums/summary` 与 `/photos/on-this-day` 由通配 handler 内部分派

gin 的路由树**不允许同一层级同时存在静态段与通配段**。同时注册 `/albums/summary` 与
`/albums/:id` 会在**启动时 panic**，整个服务起不来——不是 404，是起不来。

故只注册通配路由，在 handler 内识别保留字：

- `handleAlbumByID`：`:id == "summary"` → `handleAlbumSummary`
- `handlePhotoByID`：`:id == "on-this-day"` → `handlePhotosOnThisDay`；`:id == "recycled"` → `handleListRecycledPhotos`

对外路径不变，客户端无感。代价是 `summary` / `on-this-day` / `recycled` 成了保留字，
不能作为照片 id 使用（数字 id 天然不冲突）。

### 3.3 上传配额

**每人每天 200 张 / 500MB**（`maxPhotosPerDay` / `maxUploadBytesADay`）。超限返回
HTTP 429 + 业务码 `1020`。

计数落在**进程内存**（`memStore`，键按日期分桶，TTL 25 小时），重启归零属**可接受损失**：
这是防刷盘的护栏，不是计费，宁可偶尔放宽也不要为它引入外部存储。

记账时机：**落盘成功后才记**（`commitUploadQuota`）——失败的上传不该消耗用户额度。
判额在落盘前（`checkUploadQuota`），且请求体先被 `http.MaxBytesReader` 限死，
否则超大 body 会在 `file.Size` 检查之前就把内存/磁盘吃掉。

## 4. 两个容易踩的时区细节

### 4.1 排序口径

列表统一按 `COALESCE(taken_at, created_at) DESC, id DESC`：有 EXIF 用拍摄时间，
没有则退化用上传时间，保证「按拍摄先后」的直觉不被无 EXIF 的图打乱。

### 4.2 「这一天」为什么要放宽到前后各一天

`taken_at` 存本地墙钟，`created_at` 存 UTC。对没有 EXIF 的照片（退化用 `created_at`），
字面 `MM-DD` 就是 UTC 日期——在 UTC+8 下，本地 00:00~08:00 上传的照片其 `created_at`
字面日期是「前一天」，只比对当天会把它们整段漏掉。

故 SQL 先用 `substr` 取 `MM-DD` 粗筛并放宽一天，Go 再用解析后的本地时间精确复核
（`photoMatchesMonthDay` 是最终权威），既不漏也不多返回。

不用 `strftime(...,'localtime')` 归一：那依赖容器的 `TZ` 环境变量（alpine 镜像通常是 UTC），
等于把结果正确性押在部署环境的时区配置上，同一份代码在不同机器上行为不同。
