# 林曦日记 · 第五轮改造方案（0821）

> 依据：`C:\Users\Administrator\Downloads\TodoList_0821.md`
> 测试环境：一加 15 / Android 16 / 全程 Release
> 参考物料：`C:\Lx\KernelSU-main`、`C:\Lx\art-design-pro-main`、`C:\Lx\icon.jpg`、`C:\Lx\hl6`
> 回答方式：把每题 `选择:` 后面填上字母（可多选，如 `选择:A+C`），或直接写文字。填完存为 `ClaudeScheme_0821_Answer.md`。

---

## 卷首：我这一轮先做的事（不是猜，是查+跑）

上一轮的教训是「只看文件存在就断言修好了」。所以这次在提问之前，我把每条抱怨都追到了具体代码行，其中有一条**在本机用 Go 跑出了复现**。以下是结论速览，后面每个问题的「详情」都建立在这些事实上。

| 你的抱怨 | 我查到的真相 | 证据 |
|---|---|---|
| 原生加载和 miuix 并存 | 加载指示器**已经**只有 miuix 一种（`KernelScreen.kt:148`）。真正并存的是**别的**原生件：11 处 `material3.Icon`、1 处 `material3.IconButton`、1 处 `HorizontalDivider`、`MainActivity` 的 `MaterialTheme+Text`、`Theme.kt` 的 `MaterialTheme/Typography`、31 处 `Icons.Rounded/Filled.*` Material 图标、`activity_ring.xml` 整个原生 XML 页，以及 `themes.xml` 的 `android:Theme.Material.*` 启动帧 | 见 Q1 详情 |
| 上传格式太少 | 客户端其实**已经**把 HEIC 转 JPEG 了（`ImagePrepPolicy.kt:82`）。服务端只认 JPEG/PNG/GIF/WebP，**BMP 能过魔数但被拒**，且失败文案一律甩「格式不支持或超过 20MB」，把 OOM、解码失败、配额用尽全混在一起 | `avatar_format.go:22-29`、`album_media.go:130-134`、`AlbumDetailScreen.kt:175` |
| 有些图片消失、扫不到 | **根因找到了**：`MediaStoreImages.query` 写死 `limit=2000` 且按 `DATE_TAKEN DESC` 排序。截图、微信保存、下载来的图 `DATE_TAKEN` 是 0/NULL，在 DESC 排序里全被踢到队尾，一旦总图数超 2000 就整批被截断消失。且**没有按相册分桶**（QQ 有「相机/截屏/微信/下载」），也不查第二存储卷 | `MediaStoreImages.kt:33,49,56` |
| 进伴侣状态历史 APP 崩掉 | **根因找到了，并已本机实证**：`status_history.foreground_pkg/foreground_name` 列可为 NULL（未授「使用情况访问」或息屏无前台时必为 NULL），而 Go 侧扫进的是 `string`，`rows.Scan` 报 `converting NULL to string is unsupported`；`store.go:490` **没检查 Scan 返回值**，于是整行保持零值 → `h.Ts.UnixMilli()` = `-62135596800000`（所有行同一个数）→ 客户端 `items(timeline, key = { it.ts })` 撞上重复 key → `IllegalArgumentException` 崩溃。0820 那轮加的 `key` 正好点燃了这颗埋了很久的雷 | 我在本机跑的复现：`scan err = sql: Scan error on column index 0, name "fg": converting NULL to string is unsupported` / `ts = 0001-01-01` / `UnixMilli = -62135596800000` |
| 相册分组和照片删不了 | 删相册**能**，但入口只有「长按卡片」且**界面上零提示**，你不可能知道；删照片**只有大图页那个小图标**、且**零二次确认**；网格页**没有多选、没有删除**；回收站**只能恢复、不能彻底删**；服务端**全链路软删，磁盘文件从来不删** | `AlbumListScreen.kt:168,202`、`PhotoViewerScreen.kt:113-127`、`RecycleBinScreen.kt:126`、`album_store.go:192,343` |
| 多图显示要略缩图 | 这条**你误判了**，网格已经走 `/media/<id>/thumb`（服务端长边 512 等比缩放）。真问题是 Coil 磁盘缓存写死 **256MB** 且无任何清理入口——「不要太多了」这半句是对的 | `AlbumDetailScreen.kt:220`、`AlbumModels.kt:47`、`AppImageLoader.kt:55` |
| 后台没有相册配置 | 确认无。`admin.go` 里连一条 `/albums` 路由都没有，`settingKeys` 18 个键全是站点/存储/SMTP/推送，相册相关**零** | `admin.go:944-950` |
| 前台应用与息屏状态显示不好 | 查到**五条**独立缺陷：① `DeviceStatusHolder.screenOn/isLocked` 初值硬编码 `true`，进程被闹钟/开机拉起时首次上报必错 ② `foregroundApp()` 每次遍历**过去 24 小时**全部 UsageEvents 只为取最后一个 RESUMED ③ **不看 PAUSED/STOPPED**，回桌面后仍显示上一个应用 ④ 息屏时**不清空**前台应用，于是「对方在用微信」挂着不动 ⑤ 采集跑在**主线程** ⑥ WS 断线时 `pushNow()` 直接 `return` 丢弃状态，**无 HTTP 兜底**，且 UI 上**没有任何"数据是几分钟前的"提示** | `DeviceStatus.kt:66-67`、`StatusCollector.kt:45,51-56,134-148`、`StatusForegroundService.kt:194-216`、`StatusSyncManager.kt:155`、`NowScreen.kt:296-313` |
| 日记写得是一坨 | `DiaryScreen.kt` 198 行，只有「列表 + 纯文本发布」，**无编辑、无删除、无图片**（`DiaryItem.images` 字段解析了但从不渲染），服务端 `POST /diaries/images` **全仓零调用方**。而 0811 那轮就砍过一次、0820 又接回来了——这次要么彻底断根，要么还会再来一轮 | `DiaryScreen.kt`、`ApiClient.kt:328-333,375`、`CHANGELOG.md:15` |

还有一条你没提但我必须先说：**`server/webdist/` 和 `server/server.exe` 现在在工作区里**，前者是后台构建产物、后者是 14MB 的编译二进制。上一轮我误提交过 139 个 webdist 产物（`650639e` 才清掉）。这轮收尾我会确认它们不进提交。

---

# 一、客户端 · 第 1 条：miuix 全量替换原生

## 1.1 现状全量清点（这是「原生的」到底还剩什么）

我把 `android/app/src/main/java/com/linxi/diary` 全量扫了一遍，原生 Material 残留精确如下：

**A. `androidx.compose.material3.*` 组件（共 16 处引用）**

| 组件 | 处数 | 文件 |
|---|---|---|
| `material3.Icon` | 11 | `KernelScreen.kt:156`、`AboutScreen.kt:24`、`DiscoverScreen.kt:28`、`LoginScreen.kt:23`、`NowScreen.kt:11`、`PhotoPickerScreen.kt:31`、`PhotoViewerScreen.kt:24`、`ProfileEditScreen.kt:14`、`RegisterScreen.kt:29`、`SettingsScreen.kt:7`、`TodoScreen.kt:17` |
| `material3.IconButton` | 1 | `TodoScreen.kt:18` |
| `material3.HorizontalDivider` | 1 | `TodoScreen.kt:16` |
| `material3.MaterialTheme` + `material3.Text` | 2 | `MainActivity.kt:13-14`（SAFE_MODE 分支） |
| `material3.MaterialTheme` + `material3.Typography` | 2 | `Theme.kt:5-6`（这是 miuix 主题的底座，见 Q5） |

**B. Material 图标（`Icons.Rounded/Filled.*`，共 31 处、24 个不同图标）**
`Notifications`×5、`Person`×4、`Favorite`×3(+`FavoriteBorder`)、`CheckCircle`×3、`Lock`×2、`AccountCircle`×2、`SystemUpdate`、`Search`、`Pin`、`PhotoLibrary`、`MusicNote`、`Movie`、`Language`、`Explore`、`Email`、`Delete`、`Construction`、`Code`、`Book`、`Add`、`Share`、`Settings`、`Save`、`Info`、`History`、`Check`

**C. 原生 XML（Compose 之外的世界）**
- `res/layout/activity_ring.xml`：`LinearLayout` + 原生 `Button`「我知道了」——这是响铃全屏页，是全 App 观感最"安卓原生"的一屏
- `res/layout/notification_expanded.xml` 里也有一个原生 `Button`
- `res/values/themes.xml` + `values-night/themes.xml`：`parent="android:Theme.Material.Light.NoActionBar"` / `android:Theme.Material.NoActionBar`——**这是冷启动那一帧的观感来源**

**D. miuix 0.9.3 实际能提供什么（我解包 AAR 数出来的，不是猜）**
- `miuix.kmp.basic`：`Button Card Text TextField Icon IconButton Surface Divider Switch Checkbox RadioButton Slider ProgressIndicator PullToRefresh Scaffold TopAppBar NavigationBar TabRow SearchBar Search Snackbar Badge Dropdown ListPopup NumberPicker ColorPicker FloatingActionButton FloatingToolbar Tooltip ScrollBar SmallTitle ArrowRight ArrowUpDown Check Close ColorPalette Sidebar NavigationRail Component`
- `miuix.kmp.overlay`：`OverlayDialog OverlayBottomSheet OverlayListPopup OverlayCascadingListPopup`
- `miuix.kmp.icon.extended`：**约 140 个图标**，含 `Add AddCircle Alarm Album Back Backup Close Copy Create Delete Download Edit Email ExpandLess ExpandMore Favorites FavoritesFill File Filter Folder GridView Help Hide Home Image Info Layers Link ListView Location Lock Messages More MoreCircle Music Notes NotesFill Ok Pause Photos Pin Play Playlist Recent Refresh Rename Report Reset Search SelectAll Send Settings Share Show Sort Stopwatch Store Tasks Theme Timer Translate Tune Undo Unlock Update UploadCloud VolumeUp World Clock Years Months Weeks Th1..Th31（日期数字）ZoomOut …`
- `miuix.kmp.icon.basic`：`ArrowRight ArrowUpDown Check Close Search SearchCleanup Sidebar`

**E. Material→miuix 图标映射，我逐个对过（缺口只有 5 个）**

| Material | miuix 替代 | Material | miuix 替代 |
|---|---|---|---|
| `Add` | `Add` | `Delete` | `Delete` |
| `CheckCircle` / `Check` | `Ok` / `Check` | `Search` | `Search` |
| `Person` / `AccountCircle` | `Contacts` / `ContactsCircle` | `Settings` | `Settings` |
| `Favorite` / `FavoriteBorder` | `FavoritesFill` / `Favorites` | `Share` | `Share` |
| `Lock` | `Lock`（还有 `Unlock`） | `Info` | `Info` |
| `Email` | `Email` | `History` | `Recent` |
| `MusicNote` | `Music` | `PhotoLibrary` | `Photos` / `Album` |
| `Book` | `Notes` | `SystemUpdate` | `Update` |
| `Pin` | `Pin` | `Language` | `Translate` |
| `Save` | `Backup` | `Notifications` | **缺**（见下） |
| `Explore` | **缺** | `Movie` | **缺** |
| `Construction` | **缺**（`Tune`?） | `Code` | **缺**（`File`?） |

缺口 5 个：`Notifications`（通知权限项）、`Explore`（发现 Tab）、`Movie`（一起看卡）、`Construction`（开发中占位）、`Code`（关于页仓库链接）。

---

❓ **Q1** - **「旧的加载」到底指哪一个？**

**详情**：我得先跟你确认一件事——**加载指示器现在只有 miuix 一种**。`KernelScreen.LoadingRow()`（`KernelScreen.kt:143-150`）用的是 `miuix.kmp.basic.CircularProgressIndicator`，`TodoScreen.kt:167` 用的也是同一个 miuix 组件（`import top.yukonga.miuix.kmp.basic.CircularProgressIndicator`），0813 那轮就已经把 Material 那款删掉了。全仓 grep `androidx.compose.material3.CircularProgressIndicator` 是**零命中**。

所以你看到的"两种并存"，我推测是这几种可能之一：

- **A**：不是加载圈，是**图标**。全 App 有 31 处 Material 图标 + 11 处 `material3.Icon`，线条粗细/圆角与 miuix 图标明显不是一套，看起来就"一半原生一半 miuix"
- **B**：是**冷启动那一帧**。`themes.xml` 用 `android:Theme.Material.*`，App 点开的第一瞬间是纯原生底色的空白帧，之后才切到 Compose
- **C**：是**响铃全屏页** `activity_ring.xml`，那是彻底的原生 XML + 原生 Button
- **D**：是**下拉刷新**与**首屏加载圈**同时出现（比如某页 `isRefreshing` 与 `loading` 同真，转两个圈）
- **E**：是**图片加载时的空白**。Coil 没配 placeholder，网格里图片没加载出来时是纯空白格，不是 miuix 骨架屏
- **F**：以上都有。**按 F 我就全清一遍**：A~E 全部处理，全仓不留一处原生观感

➡️ **我的建议：F**。理由是你这句话说得很重（"你他妈把原生的全他妈换成miuix的啊"），说明不是某一处细节而是整体割裂感。我按 F 做的话，无论真凶是哪个都会被覆盖到，而且成本可控——总共就 16 处 material3 组件 + 31 处图标 + 2 个 XML + 1 个 theme。如果你能顺手拍一张截图指给我看是哪个圈，那就更准。

选项:A
B
C
D
E
F
选择:
我的意见:

---

❓ **Q2** - **图标缺口那 5 个怎么办**

**详情**：miuix 0.9.3 的 140 个图标里没有 `Notifications`(通知)、`Explore`(发现)、`Movie`(一起看)、`Construction`(开发中)、`Code`(源码)。这五个分别用在：设置页通知权限项、底部 Tab「发现」、发现页「一起看」卡、开发中占位页、关于页仓库链接。处理方式：

- **A**：**用 miuix 近似图标顶替**。`Notifications`→`Messages`、`Explore`→`Community`、`Movie`→`RecordingTape`、`Construction`→`Tune`、`Code`→`File`。零新增资源，但语义有点偏（"社区"当"发现"、"录像带"当"电影"）
- **B**：**手写 5 个 ImageVector**，照 miuix 的线宽（2dp 圆头线条）画，放 `ui/icons/MiuixExtraIcons.kt`。语义准、风格能对齐，代价是我要手写 5 段 path 数据，且画得好不好看得等你真机看
- **C**：**这 5 处改用纯文字/emoji**，不放图标
- **D**：**保留这 5 个 Material 图标**，其余 26 个换 miuix。代价是 `material-icons-extended` 依赖不能删（它有 ~1.2 万个图标，虽然 R8 会 shrink 掉未用的，但 debug 包会大）

➡️ **我的建议：B**。A 里「Community 当发现」和「RecordingTape 当一起看」的语义偏差你一眼就能看出来，到时候还得再改一轮。B 我照 miuix 既有图标的视觉规格描（2dp 线宽、圆头、24dp 画布、留 2dp 边距），5 个图标而已，画完贴在方案末尾给你确认。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q3** - **响铃全屏页 `activity_ring.xml` 要不要改成 Compose**

**详情**：`RingActivity` 现在是纯原生 XML（`LinearLayout` + `TextView`×2 + 原生 `Button`），主题 `Theme.Linxi`(=`android:Theme.Material.*`)。它是「对方紧急找你」时全屏弹出的页面，`showWhenLocked=true` + `turnScreenOn=true`，会在锁屏上直接亮出来。

改造有个**真实风险**必须先讲：这个页面要在**锁屏之上、息屏唤醒瞬间**渲染，是全 App 时序最敏感的一屏。Compose 首帧比 XML 慢（要初始化 Composition），而且它跑在独立 Activity 里，会**第一次**在这个进程触发 miuix 主题 + backdrop 初始化。0813 那轮有过启动闪退排查史（`BuildConfig.SAFE_MODE` 就是那时留下的二分工具）。

- **A**：**改 Compose + miuix**，观感与全 App 统一。风险：锁屏唤醒时首帧变慢，极端情况可能白屏一瞬；若 miuix 初始化抛异常，等于「紧急找人」功能整个哑掉
- **B**：**保留 XML，但把观感做成 miuix 的样子**——改 `activity_ring.xml` 的背景色/圆角/字号/按钮样式对齐 miuix Design，主题换掉 `android:Theme.Material.*`。零时序风险，观感 95% 接近
- **C**：**A + 兜底**：改 Compose，但在 `setContent` 外层套 try/catch，异常时 `setContentView(R.layout.activity_ring)` 回退到 XML。两全，代价是两份 UI 都要维护
- **D**：不动

➡️ **我的建议：C**。这一屏的功能重要性（紧急找人）高于观感，但你明确要求"全他妈换成 miuix"，所以我选 C：正常路径走 miuix，异常路径保命。XML 那份只留最简结构（一段文字 + 一个停止按钮），不再维护样式。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q4** - **冷启动那一帧（`themes.xml`）怎么处理**

**详情**：`values/themes.xml` 的 `Theme.Linxi` 继承 `android:Theme.Material.Light.NoActionBar`，`windowBackground` 是 `@color/card_bg`。App 点开的第一帧就是这个纯色底 + 系统栏配色，之后 Compose 才接管。Android 12+ 还有 SplashScreen API 会在这之前插一帧系统图标动画。

- **A**：**换成 miuix 配色的启动帧**：`windowBackground` 改成与 miuix `colorScheme.background` 一致的色值（浅色 `#F7F7F7` / 暗色 `#0F0F0F` 之类），parent 换成 `android:Theme.DeviceDefault.NoActionBar`（不带 Material 视觉）。冷启动到 Compose 的过渡不再有色差跳变
- **B**：**A + 正式接 SplashScreen API**：`androidx.core:core-splashscreen`，中心放品牌 LOGO（`icon.jpg` 那个），品牌蓝底。观感最完整，代价是加一个依赖
- **C**：不动

➡️ **我的建议：A**。B 的 SplashScreen 会引入一个新依赖且在各厂商 ROM 上表现不一致（一加 ColorOS 有自己的启动动画策略），性价比不高。A 只改两个 XML 值，就能消掉"点开先闪一下白/灰再变色"这个最扎眼的割裂点。

选项:A
B
C
选择:
我的意见:

---

❓ **Q5** - **`Theme.kt` 里的 `material3.MaterialTheme` 能不能删**

**详情**：`ui/theme/Theme.kt:5-6` import 了 `material3.MaterialTheme` 与 `material3.Typography`。这不是随手写的——项目主题链路是 `materialkolor` 生成 M3 配色 → 喂给 miuix 的 `MiuixTheme`，`MaterialTheme` 在中间作为桥（`MaterialKolorMapping.kt`）。同时 `material-icons-*` 依赖也挂着。

如果要"彻底无 material3"，得把 materialkolor 那条链也拆掉，改成直接构造 miuix `Colors`。这会牵动主题的全部配色（浅色/暗色/品牌蓝 `#277AF7`），是**观感回归风险最大的一处**。

- **A**：**保留**。`Theme.kt` 与 `MaterialKolorMapping.kt` 这两个文件里的 material3 是"配色计算库"而非 UI 组件，用户看不见。只清 UI 层的 material3 组件与图标
- **B**：**彻底拆掉**，主题改为手写 miuix `Colors`（品牌蓝 + 中性灰阶两套）。依赖里能删掉 `material3` 与 `materialKolor`，包体减小，但全 App 配色要重新调一遍，你得真机复验浅色/暗色两套观感
- **C**：先做 A，B 留到下一轮

➡️ **我的建议：A**。这是我唯一想劝你别做的一处。material3 在这里的角色是"色彩算法"（从种子色推导整套配色），拆掉等于我要重新手调几十个色值，而你只能在真机上一处处看有没有变丑——投入产出比很差，且它对"割裂感"零贡献（用户看不到 `Typography` 对象）。

选项:A
B
C
选择:
我的意见:

---

❓ **Q6** - **通知栏那两个 RemoteViews 布局怎么办**

**详情**：常驻通知用 `notification_status_card.xml` / `notification_status_card_compact.xml` / `notification_expanded.xml`（后者含一个原生 `Button`）。RemoteViews **技术上无法用 Compose**——通知栏是系统进程渲染的，只能是 XML。

- **A**：**保留 XML，但样式对齐 miuix**（圆角、配色、字号跟 miuix Design 走），并把 `notification_expanded.xml` 里的原生 Button 换成 `ImageButton`+自绘背景以贴近 miuix 按钮观感
- **B**：**改用系统标准通知模板**（`NotificationCompat` 的 BigTextStyle 等），完全跟随系统观感，不再自定义 RemoteViews。一加 ColorOS 上会是原生 ColorOS 观感
- **C**：不动

➡️ **我的建议：A**。RemoteViews 是硬约束，但配色圆角能对齐。B 的问题是丢掉了现在的双人状态卡布局（伴侣头像 + 状态行 + 更新时间），信息密度会下降。

选项:A
B
C
选择:
我的意见:

---

# 二、客户端 · 第 2 条：图片格式与"图片消失扫不到"

## 2.1 现状拆解

**格式链路（两段，各有各的白名单）**

第一段·客户端 `ImagePrep`（`ImagePrepPolicy.kt:76-84`）：
```
PNG  → 保持 PNG
WebP → 保持 WebP（但会被重编码成静态 WEBP_LOSSY → 动图变一帧）
GIF  → 原样直传（不重编码，动图保住）
其它（JPEG/HEIC/HEIF/AVIF/BMP/未知）→ 全部转 JPEG（质量 85，长边压到 2048）
```
所以**HEIC 早就支持了**。`BitmapFactory` 在 Android 10+ 能解 HEIF，一加 15 的"高效格式"照片能正常转。

第二段·服务端魔数白名单（`avatar_format.go:22-29`、`album_media.go:130-134`）：
```
能识别：JPEG PNG BMP GIF WebP(含动图) HEIF AVIF
能解码：JPEG PNG GIF WebP     ← decodableInPureGo()
被拒绝：HEIF AVIF BMP         ← 统一报「暂不支持该图片格式（HEIC/AVIF），请改用 JPG、PNG、WebP 或 GIF」
```
注意 **BMP 是个明显的 bug**：魔数认它、但 `decodableInPureGo` 不放行，于是用户传 BMP 会收到一句"不支持 HEIC/AVIF"的莫名其妙的错误。不过实际上客户端会先把 BMP 转成 JPEG，所以只有绕过客户端才会命中。

**"图片消失、扫不到"的根因（`MediaStoreImages.kt`）**

```kotlin
suspend fun query(context: Context, limit: Int = 2000): List<LocalImage> =   // ← ①
    ...
    "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC",  // ← ②
    ...
    while (cursor.moveToNext() && out.size < limit) {                          // ← ③
```
- ① 写死 2000 张上限
- ② 按 `DATE_TAKEN DESC` 主排序。**截图、微信/QQ 保存的图、浏览器下载的图，`DATE_TAKEN` 普遍是 NULL 或 0**（这个字段来自 EXIF，只有相机直出才有）。SQLite 里 NULL 在 `DESC` 排序中排最后
- ③ 取满 2000 就 break

**所以：如果你手机图片总数超过 2000 张，所有截图和微信图会因为排在队尾而被整批截断——一张都看不到。** 这与你说的"有些图片还会消失，扫不到"完全吻合。

另外还差三件 QQ 有的能力：
- **没有按相册（bucket）分桶**。QQ 进选图器先列「相机 / 截屏 / 微信 / 下载 / 全部」，本项目只有一个混合大列表 + 按月分组
- **不查第二存储卷**（`EXTERNAL_CONTENT_URI` 只覆盖主卷；`MediaStore.getExternalVolumeNames()` 才能拿全）
- **不支持分页/懒加载**，一次性把 2000 条元数据全读进内存

---

❓ **Q7** - **相册扫描要做到什么程度**

**详情**：修法从轻到重：

- **A**：**只去掉截断**。`limit` 从 2000 提到无上限（或 50000），排序改成 `COALESCE(DATE_TAKEN, DATE_ADDED*1000) DESC` 让无 EXIF 的图也排到正确位置。改动最小，直接解决"扫不到"
- **B**：**A + 按相册分桶**（学 QQ）。顶部一行可横滑的相册切换（相机/截屏/微信/下载/全部），用 `BUCKET_DISPLAY_NAME` 分组，进入时默认「全部」
- **C**：**B + 覆盖全部存储卷**（`MediaStore.getExternalVolumeNames()` 遍历），并改成**分页加载**（每页 200 条，滚到底续拉），避免一次读几万条元数据卡住
- **D**：**C + 视频也能选**（QQ 是图片视频混排）。但服务端目前完全没有视频链路（无转码、无封面抽帧、无播放器），这是个大功能

➡️ **我的建议：C**。A 是必须的（那是 bug），B 是你点名要的"学学 QQ"，C 里的分页在你有几万张图时是刚需——不分页的话进选图器会先卡 2~3 秒。**D 我建议这轮不做**：视频要服务端转码（纯 Go 没有 ffmpeg，镜像里也没装）、要抽封面、要播放器，是独立一轮的量级，硬塞进来会拖垮整轮质量。你要是坚持这轮要视频，我需要你先答 Q8。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q8** - **（仅当 Q7 选 D 时回答）视频怎么落地**

**详情**：服务端是 alpine 镜像 + 纯 Go，**没有 ffmpeg / libvips**（0820 那轮头像上传 500 的根因就是 fork libvips CLI 而镜像里没有）。视频要做，方案只有：

- **A**：**镜像装 ffmpeg**（alpine 加 `apk add ffmpeg`，镜像大约 +80MB），服务端抽首帧做封面、不转码只存原始文件
- **B**：**客户端出封面**：客户端用 `MediaMetadataRetriever` 抽首帧，把封面当普通图片上传，视频文件原样上传，服务端零解码
- **C**：这轮不做视频

➡️ **我的建议：C**（即 Q7 选 C）。若你一定要，选 **B**——客户端抽帧零服务端依赖，且不给镜像加 80MB。

选项:A
B
C
选择:
我的意见:

---

❓ **Q9** - **服务端要不要真的支持 HEIC/AVIF 原图**

**详情**：现在客户端会把 HEIC 转 JPEG 再传，所以**你从本机相册选 HEIC 是能成功的**。但有三个场景会绕过转换、直接撞服务端的拒绝：
1. 客户端 `ImagePrep` 解码失败（超大图 OOM、损坏文件）→ 回退为失败，而不是原图直传
2. 未来若加"原图上传"开关
3. 别的客户端/调试工具直连接口

要让服务端真能吃 HEIC/AVIF，纯 Go 的选择：
- `github.com/strukturag/libheif` — cgo 绑定，**需要镜像装 libheif**，与"单容器纯 Go"的既定架构冲突
- `github.com/gen2brain/avif` / `jpegxl` — 部分纯 Go/wasm 实现，成熟度一般
- `golang.org/x/image` — **没有** HEIF/AVIF

- **A**：**保持现状**（服务端只认 JPEG/PNG/GIF/WebP），但**修两处**：① BMP 加入可解码（`x/image/bmp`，纯 Go，一行依赖）② 拒绝文案按真实格式说清楚（"这是 HEIC，请在设置里关掉'高效格式'或换张图"），不再一句话糊过去
- **B**：A + 引入纯 Go AVIF 解码（`gen2brain/avif`），HEIC 仍拒
- **C**：镜像装 libheif，两个都真支持（破坏纯 Go 架构，镜像变大，构建变慢）

➡️ **我的建议：A**。理由：客户端已经兜住了 HEIC（这是 0820 定的 Q12=C 决策），服务端再支持一遍是重复投资；而 C 会把"单容器 + 纯 Go + SQLite"这个你自己定的架构（0813 选 B）打破。A 里那两处修补是真 bug，必须修。

选项:A
B
C
选择:
我的意见:

---

❓ **Q10** - **动图（GIF / 动态 WebP）要不要保真**

**详情**：现在 GIF 走 `copyAsIs` 原样上传（动画保住），但**动态 WebP 会被 `ImagePrep` 重编码成静态 `WEBP_LOSSY`，动画丢失只剩一帧**（`ImagePrep.kt:80-84`）。服务端缩略图对 GIF 是取第一帧生成 PNG 静图（`album_media.go:173-180`），网格里 GIF 显示静态封面、点开大图才动——这个行为其实是对的（网格里一堆动图会很吵）。

- **A**：**修动态 WebP**：`ImagePrepPolicy.shouldRecompress` 把动态 WebP 也排除（跟 GIF 一样原样传）。需要先探测是不是动图（读 RIFF 的 `ANIM` chunk，服务端 `avatar_format.go:89-95` 已有同样逻辑可参照）
- **B**：A + 网格里 GIF/动图也播动画（Coil 3 加 `coil-gif` 依赖）
- **C**：不动

➡️ **我的建议：A**。B 会让相册网格滚动时同时解码多个动图，掉帧+耗电，而且视觉很乱。A 是修 bug（用户传的动图变静图，这是数据损失）。

选项:A
B
C
选择:
我的意见:

---

❓ **Q11** - **上传失败的原因要说到多细**

**详情**：现在失败只有一句「失败的照片可以重新选择上传（常见原因：格式不支持或超过 20MB）」（`AlbumDetailScreen.kt:175`），把这些完全不同的原因混成一句：客户端解码失败 / EXIF 读取失败 / 处理后仍超 20MB / 服务端魔数拒绝 / HEIC 拒绝 / 当日配额用尽（200张·500MB）/ 网络中断 / 挂接相册失败。

- **A**：**逐张记原因**，上传完成后列出「失败 3 张」并可展开看每张的具体原因（第 2 张：解码失败；第 5 张：超过 20MB；第 7 张：今日配额已用尽）
- **B**：A + **失败可重试**：失败列表旁给「重试失败项」按钮
- **C**：只把那句笼统文案改准确一点

➡️ **我的建议：B**。你这一轮抱怨里"有些图片还会消失"很可能就有一部分是**静默失败**（`ImagePrep` 返回 null 时只 `uploadFailed++`，用户以为传上去了）。A+B 能把这类问题变成你能直接告诉我的信息，而不是下一轮又一句"图片会消失"。

选项:A
B
C
选择:
我的意见:

---

❓ **Q12** - **单次选图上限（现在 20 张）**

**详情**：`PhotoPickerScreen.kt:58` 写死 `MAX_SELECT = 20`。上传是**串行逐张**（`AlbumDetailScreen.kt:122-145`），20 张 × (处理 0.5s + 上传 1~3s) ≈ 30~70 秒，期间页面停在上传进度卡。服务端日配额 200 张 / 500MB。

- **A**：提到 **50 张**，上传改**并发 3 路**
- **B**：提到 **100 张**，并发 3 路，且**上传移到前台 Service**（退出页面/切后台继续传，通知栏显示进度）
- **C**：保持 20
- **D**：**不设上限**（受日配额 200 约束），并发 3 路 + 前台 Service

➡️ **我的建议：B**。50 vs 100 差别不大，但"切后台继续传"是真实痛点——现在你选了 20 张，中途切出去看微信，`LaunchedEffect` 所在 composable 若被回收，剩下的就传不完了。前台 Service 上传是 QQ/微信的标准做法。

选项:A
B
C
D
选择:
我的意见:

---

# 三、客户端 · 第 3 条：头像选图器统一

**现状**：头像用的是**系统 Photo Picker**（`ProfileEditScreen.kt:117-118` 的 `ActivityResultContracts.PickVisualMedia`），相册用的是**自研 miuix 网格**（`PhotoPickerScreen.kt`）。0820 那轮我特意让头像走系统选择器，理由写在注释里（`ProfileEditScreen.kt:110-115`）：头像是单选，系统 Photo Picker 更轻且**完全不需要读取权限**。

你现在要求统一到自研那个。这里有个必须讲清的**代价**：自研选择器要 `READ_MEDIA_IMAGES` 权限。Android 14+ 用户如果选「仅选择部分照片」，`MediaStore` 只返回被授权那几张——用户会以为相册空了。`PhotoPickerScreen` 已经为此常驻了「系统相册」兜底入口（`PhotoPickerScreen.kt:128-136`）。

---

❓ **Q13** - **头像选图器统一方案**

**详情**：
- **A**：**头像改用 `PhotoPickerScreen`（单选模式）**，保留其顶栏「系统相册」兜底。导航上新增一个"选图目的地"参数（现在 `PhotoPicker` 返回后固定回 `AlbumDetail`，`LinxiApp.kt:217-220`，得改成能回 `ProfileEdit`）
- **B**：A + **加圆形裁剪页**（选完拖动缩放定裁剪框）。头像是圆形展示的，现在直接把整张图 `ContentScale.Crop` 居中裁——竖构图人像的头会被切掉
- **C**：A + 裁剪，且**裁剪页也用 miuix 手搓**（不引入第三方裁剪库如 uCrop——那是 Material 观感，又要引入割裂）

➡️ **我的建议：C**。B/C 的裁剪是我主动想加的：你现在换头像等于赌运气（构图偏一点头就没了）。裁剪页我手搓（一个 `Box` + 手势缩放平移 + 圆形遮罩 + 输出时按裁剪框 `Bitmap.createBitmap`），约 150 行，不引依赖不引割裂。

选项:A
B
C
选择:
我的意见:

---

❓ **Q14** - **选图器要不要加「点击预览大图」**

**详情**：现在网格只能点击选中/取消，**无法放大看**。QQ 是长按预览。相册要传的图往往需要确认一下是不是那张。

- **A**：**长按预览**（长按弹全屏大图，松手关闭）
- **B**：格子右下角加小「放大」角标，点它进预览页
- **C**：不加

➡️ **我的建议：A**，与 QQ 手势一致，不占额外 UI 空间。

选项:A
B
C
选择:
我的意见:

---

# 四、客户端 · 第 4 条：伴侣状态历史崩溃 ★根因已实证★

## 4.1 崩溃链路（我在本机跑出来的）

```
① 客户端未授「使用情况访问」 或 息屏无前台应用
   → StatusCollector.foregroundApp() 返回 null                 (StatusCollector.kt:41)
② DeviceStatus.toJson() 不写 foreground_app 字段               (DeviceStatus.kt:29-30)
③ 服务端 st.ForegroundApp == nil → pkgOf()/nameOf() 返回 nil   (store.go:457-468)
④ INSERT 把 foreground_pkg / foreground_name 写成 NULL         (store.go:449-453)
⑤ 查询时 rows.Scan(&h.ForegroundPkg /*string*/, ...) 报错：
   "converting NULL to string is unsupported"
⑥ store.go:490 没检查 Scan 的返回值 → 整行保持零值
⑦ h.Ts 是零时间 → h.Ts.UnixMilli() == -62135596800000（每行都一样）
⑧ 客户端 items(timeline, key = { it.ts }) 撞重复 key
   → java.lang.IllegalArgumentException: Key "-62135596800000" was already used
   → 崩溃
```

我在本机用项目自己的 `modernc.org/sqlite` 驱动跑了复现，输出：
```
scan err = sql: Scan error on column index 0, name "fg": converting NULL to string is unsupported
ts = 0001-01-01 00:00:00 +0000 UTC  UnixMilli = -62135596800000
```

**为什么 0820 之前不崩**：`key` 参数是 0820 那轮 `a0a699f` 加的（为了修"换日期后下标错位复用旧 item 状态"）。在那之前 LazyColumn 用默认下标做 key，重复的 ts 只会让**所有记录显示同一个时间（1970 年之前）**——是数据错，但不崩。加了 key 反而把静默数据错变成了显式崩溃。**所以这颗雷早就埋了，只是 0820 才引爆。**

## 4.2 同一段代码里我还查出三个问题

**(a) 这个页面查的是你自己的历史，不是伴侣的**
`handleHistoryTimeline`（`handlers.go:977`）用 `uid := currentUID(c)` 查 `WHERE user_id = uid`。而页面标题是「伴侣状态历史」（`HistoryScreen.kt:92`）。也就是说**你在这个页面看到的一直是自己的记录**。`handleBatteryCurve` 同理。

**(b) 日期查询有时区错位**
`HistoryTimeline` 里 `time.Parse("2006-01-02", date)` 得到的是 **UTC 零点**，而写入用的是 `time.Now().Truncate(5*time.Minute)`（**服务器本地时区**）。容器 TZ 若是 `Asia/Shanghai`（+8），"今天"的查询窗口实际是本地 08:00~次日 08:00 —— 凌晨 0~8 点的记录会被算进"昨天"。

**(c) 状态历史永久保留、无清理**
`models.go:64` 注释写着「5 分钟聚合，永久保留」，全仓无清理任务。按 5min 一条算：一人一年 ≈ 10.5 万条，两人 21 万条。SQLite 撑得住，但磁盘只增不减（0820 已经踩过一次 netlog 清理 SQL 写成 MySQL 语法导致永久失效的坑）。

---

❓ **Q15** - **崩溃修法（两端都要改，这是硬要求）**

**详情**：
- **A**：**只修服务端**。`StatusHistory.ForegroundPkg/ForegroundApp/SSID` 改 `sql.NullString`（或 `COALESCE(foreground_pkg,'')` 在 SQL 里兜），并**检查 `rows.Scan` 的返回值**（错误就跳过该行 + 打日志）。客户端不改
- **B**：**只修客户端**。`key` 改成不会重复的（用 `index` 或 `"$ts-$index"`）
- **C**：**两端都修**（服务端按 A、客户端按 B），并**补一个 Go 单测**：插一条 `foreground_pkg=NULL` 的记录，断言 `HistoryTimeline` 返回的 `Ts` 不是零值
- **D**：C + **全库自查**：`store.go` 里所有 `rows.Scan` 都不检查返回值（这是一整套的坏习惯），全部补上错误检查

➡️ **我的建议：D**。理由：A 单独做，客户端仍然脆（服务端将来任何一个 NULL 列都会再次引爆）；B 单独做，会把崩溃降级成"所有记录显示 1970 年"的静默数据错，更难发现。C 是两端都硬。D 里那句"全库自查"是我主动加的——我 grep 了 `store.go`，**所有** `rows.Scan` 都没检查返回值，这颗雷在待办、日记、相册、离线队列里都埋着，只是还没有哪个列变成 NULL。这是这轮最值得做的一次性排雷。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q16** - **「伴侣状态历史」查错人这件事怎么改**

**详情**：现在页面显示的是你自己的记录（详见 4.2a）。改法：

- **A**：**改成真的查伴侣**。`handleHistoryTimeline` 改用 `pair.partnerOf(uid)`，页面语义与标题一致
- **B**：**改成双方都能看**，页面顶部加「我 / 对方」切换。这是"双人 App"更合理的形态：你也会想看自己昨天什么时候睡的
- **C**：**改标题**，把页面改叫「我的状态历史」，接口不动
- **D**：不动（保持现状）

➡️ **我的建议：B**。理由：这功能的本意是双人互相了解（"他昨晚几点睡的"），A 只做一半（看不到自己）；C 是掩盖问题。B 的服务端改动很小（多一个 `?who=me|partner` 参数），客户端加一行分段按钮。**但这涉及跨端契约变更**，按你的额外要求 1，我在 Q45 里一并列出。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q17** - **状态历史的时区与保留天数**

**详情**：
- 时区（4.2b）：容器里 TZ 现在是什么我没法确认（取决于宿主机与镜像），但代码层面 UTC/本地混用是确定的
- 保留（4.2c）：永久保留、无清理

- **A**：**只修时区**。查询按服务器本地时区解析日期（`time.ParseInLocation`），与写入侧统一
- **B**：A + **加保留天数**，写死 90 天，每 6 小时清理一次（复用 netlog 那套 `datetime('now', ?)` 的 SQLite 写法）
- **C**：A + 保留天数**做成后台可配**（默认 90 天，0 = 永久），与网络日志保留天数并列放在后台"数据保留"分区
- **D**：只修时区，保留天数不管

➡️ **我的建议：C**。你这轮的核心诉求之一就是"后台配置项多一些"，数据保留天数是最典型的后台配置项。而且 0820 已经吃过一次亏：netlog 的清理 SQL 写成 MySQL 语法在 SQLite 上永久静默失败，磁盘必被打满 —— 这次我会写 Go 单测断言清理真的删到了行。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q18** - **这个页面还有别的毛病，一起修吗**

**详情**：我读 `HistoryScreen.kt` 时还看到：
1. 日期选择器是「±1/±7/±30 天」步进按钮（`HistoryScreen.kt:262-282`），跨月查历史要点很多次。miuix 没有日历组件，所以当初这么写的
2. 「加载更多」失败后直接 `reachedEnd = true`（`:73-75`），一次网络抖动就再也拉不到后面的数据，只能退出重进
3. 电量曲线图没有坐标轴、没有刻度、没有时间标签（`BatteryCurveChart`，`:208-234`），只是一条光秃秃的折线
4. 时间线条目是纯文字堆叠（`:176-186`），没有"这一段在干什么"的可读性（比如连续 8 条息屏可以合并成"23:10–07:30 息屏"）

- **A**：只修崩溃，这些不动
- **B**：修 1+2（日期选择器改用 miuix `NumberPicker` 做年月日三列滚轮；加载更多失败给重试而不是直接封死）
- **C**：B + 3（曲线图加坐标轴与时间刻度）
- **D**：B + 3 + 4（时间线做"同状态连续段合并"）

➡️ **我的建议：C**。2 是明确的 bug（网络抖一下就废）。1 用 miuix 自带的 `NumberPicker` 能直接做成滚轮，比现在的步进按钮好用得多，且不引入 Material DatePicker。3 成本低（Canvas 里多画几条线和几个文字）。4 我建议放下一轮——"状态段合并"要定合并规则（多长算一段？前台应用变了算不算新段？），会牵扯产品判断，塞这轮容易做歪。

选项:A
B
C
D
选择:
我的意见:

---

# 五、客户端 · 第 5 条：相册（这条最大，拆 12 问）

## 5.1 现状全量清点

**分组（相册）**
- 「未归类」是虚拟相册（`album_id=0`），固定排在最前（`AlbumListScreen.kt:154-161`）
- 新建：顶部「新建相册」按钮 ✅
- 删除/重命名：**只能长按卡片**（`AlbumListScreen.kt:168`），界面上**零提示**，你不可能发现
- 「未归类」卡不传 `onLongClick`，所以它不能长按（这是对的，虚拟相册不该能删）
- 删相册是**软删** + 里面照片 `album_id=0` 退回未归类（`album_store.go:343-353`）
- **无封面设置入口**（`ApiClient.setAlbumCover` 存在但 UI 里没人调）
- **无默认分组**（除虚拟的「未归类」）

**照片删除**
- 网格页（`AlbumDetailScreen`）：**没有删除、没有多选**，格子只能点击进大图
- 大图页（`PhotoViewerScreen.kt:113-127`）：有个删除图标，**点一下直接删，零二次确认**
- 回收站（`RecycleBinScreen.kt:126`）：**只有「恢复」，没有彻底删除、没有清空**
- 服务端：**全链路软删**（`status=2`），`album_store.go` 里**没有任何硬删或删磁盘文件的代码路径** → `/upload` 下的原图与缩略图**永久占盘**

**缩略图（你误判的那条）**
- 网格用 `p.displayUrl` = `thumbUrl.ifBlank { url }`（`AlbumModels.kt:47`）→ 走 `/media/<id>/thumb`
- 服务端上传时就生成缩略图，长边 512 等比（不方裁，`album_media.go:22,180`）
- 大图页用原图 `url`（`PhotoViewerScreen.kt:137`）
- **所以"多个照片显示时要缩略图"已经是这样了。** 但有两个真问题：① 512 对 3 列网格偏大（3 列格子实际约 120dp ≈ 360px，512 够但传得多） ② 从缩略图直接跳原图，中间没有"预览尺寸"，点开大图要等整张原图下完

**缓存**
- Coil 磁盘缓存写死 **256MB**（`AppImageLoader.kt:55`），内存 25%
- **无清理入口、无用量显示、无配置**
- 服务端响应头 `Cache-Control: private, max-age=86400`（一天）

**后台**
- `photo-table.vue` 只能：分页列表 + 搜 caption + 按 pair_id 筛 + 单条软删，**刻意不显示缩略图**
- **相册维度零接口零页面**，`settingKeys` 里相册配置**零**

---

❓ **Q19** - **删相册的入口怎么改（长按太隐蔽）**

**详情**：
- **A**：**卡片右侧加「⋯」按钮**，点开 miuix `ListPopup`（重命名/换封面/删除）。长按同时保留
- **B**：**顶栏加「管理」模式**：点「管理」后每张卡出现勾选框，可批量删除（学系统相册）
- **C**：**左滑露出删除**（`SwipeToDismiss`，Material 组件——会引入原生观感，与第 1 条冲突）
- **D**：A + B 都做（单个走 ⋯，批量走管理模式）

➡️ **我的建议：A**。理由：miuix 有现成的 `ListPopup`，「⋯」是最通用的显式入口，一眼就知道能操作。B 的批量删相册是低频操作（谁会一次删 5 个相册），投入产出比低。C 会引入 Material 组件，与你第 1 条要求直接冲突。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q20** - **删照片要怎么做（现在网格里根本没有）**

**详情**：
- **A**：**网格加长按进多选**：长按任一格进入多选态，顶栏变成「已选 N 张 / 删除 / 移动到相册 / 取消」，学系统相册与 QQ
- **B**：A + **大图页的删除加二次确认**（现在点一下就删，误触即丢照片）
- **C**：B + **移动到其它相册**（多选后可移动，现在照片只能在上传时决定归属，之后无法调整）
- **D**：B + C + **设为封面**（多选态外，大图页加「设为相册封面」，接上已存在但没人调的 `setAlbumCover`）

➡️ **我的建议：D**。逐条理由：A 是你点名的"照片要可以删"；B 是安全底线（照片是不可再生数据，误触零成本删掉太危险）；C 是我主动想加的——现在照片一旦传错相册就永远待在那儿，只能删了重传；D 里的封面接口服务端已经写好了、客户端也有方法，**只差一个按钮**，不接上纯属浪费。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q21** - **回收站要做到什么程度**

**详情**：现在只能恢复，不能彻底删。且照片软删后磁盘文件永久保留。
- **A**：加「彻底删除」（单张）+「清空回收站」，**真删磁盘文件**（原图 + 缩略图）
- **B**：A + **自动清理**：回收站保留 N 天后自动彻底删除，N 后台可配（默认 30 天）
- **C**：A + B + **回收站显示剩余天数**（"7 天后自动删除"）
- **D**：只加彻底删除，不做自动清理

➡️ **我的建议：C**。理由：A 是必须的（不然磁盘只增不减，你的服务器迟早满）；B 是"后台配置多一些"的又一个落点；C 那句剩余天数是必要的知情——不然用户以为回收站是永久保险箱，结果照片自己没了会来找我。**注意**：真删磁盘文件是不可逆操作，我会在服务端加二次校验（只删 `status=2` 且属于该 pair 的记录、路径必须过 `safeUploadPath`）。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q22** - **「默认分组」是什么意思（你原话：有默认分组也可以添加分组）**

**详情**：现在唯一的"默认"是虚拟的「未归类」。你说的"默认分组"可能是：
- **A**：就是指现在的「未归类」，它已经有了，你只是想确认它在。**那这条已满足，只需保证它不能被删**
- **B**：**绑定情侣时自动创建几个预置相册**，比如「我们」「日常」「旅行」「截图」，用户可删可改
- **C**：**按拍摄时间自动分组的智能相册**（"2026 年 8 月""上个月"），虚拟的、不可删，与手动相册并列
- **D**：B + C 都要

➡️ **我的建议：A + B**（即先确认未归类不可删，再加 2~3 个预置相册）。C 的智能相册需要服务端按 `taken_at` 聚合出新接口，且与「这一天」功能重叠（那个已经是按月日回溯了），这轮我建议不做。B 里预置几个、叫什么名字，你在「我的意见」里写具体想要的名字最好。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q23** - **缩略图分几档**

**详情**：现在两档：thumb（长边 512）+ 原图。3 列网格里格子约 120dp（≈360px @3x），512 略大但可接受；大图直接下原图（手机直出 3~8MB，客户端已压到长边 2048 后再传，所以服务器上的"原图"其实是 2048 长边）。

- **A**：**保持两档**（512 thumb + 原图），只把 thumb 降到 **384**（更贴合 3 列网格，省流量省缓存）
- **B**：**三档**：`thumb`(256，网格) + `preview`(1080，大图页先显示) + `origin`(2048，双指放大时才拉)。点开大图秒出，放大才等原图
- **C**：三档 + **thumb 尺寸后台可配**
- **D**：保持现状不动

➡️ **我的建议：B**。理由：你说的"图片在查看详情和删除之前，在多个照片显示的时候是要略缩图的"——这个已经有了；但**点开大图那一下是真的慢**（要等 2048 长边的整张图下完，弱网 3~5 秒白屏）。三档能把"点开秒出"做到位，这是 QQ/微信的标准做法。C 里"后台可配缩略图尺寸"我不推荐：改了配置后**历史照片的缩略图不会重新生成**，会出现新旧尺寸混杂，除非做全量重生成任务（这轮不值得）。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q24** - **本地缓存"不要太多了"具体是多少**

**详情**：现在硬编码 256MB 磁盘缓存 + 25% 可用内存，无清理入口。
- **A**：**降到 128MB**，设置页加「清除图片缓存」（显示当前占用，点击清空）
- **B**：**降到 64MB** + 清理入口 + **按天自动清理**（超过 7 天未访问的缓存条目清掉）
- **C**：A，且缓存上限**做成客户端设置项**（用户可在设置里选 64/128/256MB）
- **D**：A，且缓存上限**由服务端下发**（后台配置 → 客户端拉取后生效）

➡️ **我的建议：A**。128MB 大约能缓存 1500~2500 张缩略图（384px JPEG 约 50~80KB），日常翻相册几乎不会再重复下载。B 的 64MB 会让缓存命中率明显下降（你翻回上个月的照片就要重下）。C/D 我不推荐：缓存大小是个技术细节，做成用户可选或服务端下发都是过度设计——Coil 的 LRU 自己会管，超了就淘汰最旧的。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q25** - **后台的相册配置项要哪些（你原话：后台连个相册相关的配置也没有）**

**详情**：我列了候选清单，每项都标了现在写死在哪。你圈要哪些：

| # | 配置项 | 现在写死的值 | 位置 |
|---|---|---|---|
| a | 单张照片大小上限 | 20MB | `album_media.go:20` |
| b | 每人每日上传张数 | 200 张 | `album_media.go:25` |
| c | 每人每日上传总字节 | 500MB | `album_media.go:26` |
| d | 缩略图长边 | 512 | `album_media.go:22` |
| e | 允许的图片格式 | JPEG/PNG/GIF/WebP | `avatar_format.go:22-29` |
| f | 回收站保留天数 | 无（永久） | 不存在 |
| g | 单个相册照片数上限 | 无限制 | 不存在 |
| h | 每对情侣相册数上限 | 无限制 | 不存在 |
| i | 相册名长度上限 | 32 | `album_handlers.go:15` |
| j | 照片描述长度上限 | 500 | `album_handlers.go:17` |
| k | 评论长度上限 | 500 | `album_handlers.go:18` |
| l | 相册功能总开关 | 无 | 不存在 |
| m | 评论/点赞功能开关 | 无 | 不存在 |
| n | 「这一天」功能开关 | 无 | 不存在 |
| o | 状态历史保留天数 | 无（永久） | 不存在（Q17） |
| p | 网络日志保留天数 | 7 天 | `netlog.go:59`（已存在但写死） |

- **A**：只要 a/b/c/f（大小、张数、字节、回收站天数）——最核心的四个
- **B**：A + d/i/j/k（缩略图尺寸 + 三个长度上限）
- **C**：A + l/m/n（功能开关）+ o/p（保留天数）
- **D**：**全要**（a~p 全部）

➡️ **我的建议：C**。逐条说理由：A 那四个是配额与磁盘，必须能调；l/m/n 功能开关很有价值（相册出问题时你能一键关掉而不用重新发版）；o/p 保留天数直接关系磁盘会不会满。**d 我建议不要**（理由见 Q23：改了历史缩略图不会重生成）；**i/j/k 也不建议**（长度上限改了客户端不知道，会出现"后台放宽到 100 字但客户端还是拦在 32 字"，要真做就得客户端拉配置，见 Q41）；**e 格式白名单不建议做成配置**（服务端能不能解码是代码能力，不是配置能改的事——你在后台勾上 HEIC，代码里没解码器一样会失败）。你选 D 的话我会把这些约束都实现成"客户端启动时拉取服务端配置"，摊子会大一圈。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q26** - **「一键用原来的」是什么意思**

**详情**：你原话「我希望是后台配置，那个我一键用原来的就好了」。我理解为：后台改了配置后，要能一键恢复默认值。

- **A**：设置页每个分区给一个「恢复默认」按钮，点了把该分区所有键重置为代码里的默认常量
- **B**：整个设置页一个「全部恢复默认」按钮
- **C**：A + B 都有
- **D**：我理解错了，你的意思是别的（请在「我的意见」写清楚）

➡️ **我的建议：C**。分区级更常用（只想重置相册配额，不想动 SMTP），全局级作为兜底。实现上服务端把默认值也吐给前端（`GET /settings` 多返回一个 `defaults` 字段），前端点恢复就填回默认值再提交——这样默认值永远与代码一致，不会出现"后台写的默认值和代码里的不一样"。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q27** - **"我测试环境我没改 compose 和 .env 文件"这句我要确认一下**

**详情**：你说「我测试环境我没改 compose 和 .env 文件，你最好是这一次给我一次适配成功」。我理解为：**你不想为了这轮改动去动服务器上的 `docker-compose.yml` 和 `.env`**，所有新配置必须通过后台页面完成、或者有安全的代码默认值。

这带来一个硬约束：**新配置项一律走数据库 `app_setting` 表 + 后台页面，不新增任何环境变量、不改 compose**。目前 `.env` 里的敏感项（`JWT_SECRET`/`APP_KEY`）保持不动。

同时有一件事我必须提醒：**后台前端是 `go:embed` 打进服务端二进制的**（`static.go`）。所以这轮的后台改动，你在服务器上必须 `docker compose pull && docker compose up -d` 拉新镜像才能看到——这不是改 compose 文件，只是拉镜像。0820 那轮你看到的是 8 月 13 日的旧后台，就是因为中间没有成功的 `build-server`。

- **A**：确认，我理解对了。所有新配置走 `app_setting` 表 + 后台页面，零新增环境变量、零 compose 改动
- **B**：不对，我可以改 compose/.env，你按最合理的来
- **C**：其它（请写明）

➡️ **我的建议：A**。这个约束我完全认同，而且技术上没有任何障碍——`app_setting` 表已经在用了（站点名/SMTP 都在里面），加十几个键是纯增量。

选项:A
B
C
选择:
我的意见:

---

❓ **Q28** - **后台要不要加相册管理页**

**详情**：现在后台只有「内容审核」里一个 photo tab（列表 + 软删，刻意不显示缩略图以保护隐私）。相册维度**零**。
- **A**：不加页面，只加配置项（Q25）
- **B**：加「相册管理」页：按 pair 列出相册、张数、占用空间，可删相册
- **C**：B + 显示磁盘占用统计（各 pair 占了多少 MB、总占用、回收站占用）
- **D**：C + 后台能看缩略图

➡️ **我的建议：C**。理由：磁盘占用统计是你作为运营方的核心需求（现在你完全不知道服务器磁盘被谁占了多少，直到它满）。**D 我强烈不建议**：0820 那轮我刚修掉「私密照片三重泄露」（`/upload` 全公开 + 网络日志页能点开情侣私照 + 无 Referrer-Policy），后台能看缩略图等于把这个洞重新开一遍。情侣私密照片不该让管理员看到，哪怕管理员是你自己——因为审计日志和截图都可能外泄。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q29** - **上传并发与失败重试（服务端侧）**

**详情**：客户端串行逐张上传（Q12 提到要改并发）。服务端侧对应要确认：
- 现在无并发限制，`checkUploadQuota` 是"先查后写"的两步（`album_media.go:42-58`），并发上传时**配额可能被击穿**（3 路并发同时查到 199 张，3 张都放过去）
- `st.mem` 是进程内存态，重启归零

- **A**：**配额检查改原子**（memstore 里 `incr` 后判断，超了再 `decr` 回退），并发安全
- **B**：A + 服务端**限制同一用户的并发上传数**（比如 3，超出返回 429 让客户端排队）
- **C**：不管，配额偶尔被击穿几张无所谓

➡️ **我的建议：A**。B 的并发限制没必要（客户端自己就限 3 路了，服务端再限一遍是重复；而且 429 会让客户端上传失败率升高）。C 不行——配额是防刷盘的护栏，"偶尔击穿几张"在恶意情况下就是"击穿几千张"。

选项:A
B
C
选择:
我的意见:

---

❓ **Q30** - **相册还有这些我发现的问题，修哪些**

**详情**：读代码时顺手记下的：
1. `AlbumDetailScreen.kt:212` 用 `photos.indexOf(p)` 取下标——**这是 O(n) 查找，放在网格 item 里等于每帧 O(n²)**。500 张照片时滚动会明显掉帧
2. `AlbumListScreen.kt:77` 未归类张数 = 总数 − 各相册张数之和。**软删的照片若统计口径不一致，这个减法会算出负数**（现在有 `coerceAtLeast(0)` 兜住，但显示会不准）
3. 相册列表**没有分页**，相册多了会一次性全拉
4. `PhotoViewerScreen` 大图**没有预加载相邻图片**，左右滑动每次都白屏等下载
5. 上传时 `attachPhotos` 失败只写日志（`AlbumDetailScreen.kt:138`），照片会**静默留在"未归类"**而不是目标相册——用户以为传进相册了，结果不在
6. `ManageAlbumDialog` 的"删除相册"与"保存名称"**同色同款**（`AlbumListScreen.kt:291,297`），危险操作无视觉区分（这条与额外要求 2 相关，见 Q46）

- **A**：只修 1+5（性能 bug + 静默失败）
- **B**：A + 4（大图预加载相邻）
- **C**：A + 4 + 2（统计口径改由服务端直接给未归类张数）
- **D**：全修（含 3 分页）

➡️ **我的建议：C**。1 是真性能 bug（`items` 的 lambda 里 O(n) 查找）；5 是静默数据错（照片没进目标相册且用户不知道）；4 是体验硬伤；2 改成服务端直接返回 `unclassified_count` 一劳永逸。3 我建议不做——相册数量本来就不会多（几个到几十个），分页是过度设计。

选项:A
B
C
D
选择:
我的意见:

---

# 六、客户端 · 第 6 条：删除日记功能

## 6.1 引用面全量清点（删除范围有多大）

先说一件你可能忘了的事：**0811 那轮就砍过一次日记，0820 又把它接回来了**（`CHANGELOG.md:15`「日记功能入口恢复」，那是 0820 的 Q29=D 决策，你当时选的是"日记入口本轮加回"）。所以这次要么彻底断根，要么会有第三次。

**客户端（要删）**
- `ui/screens/DiaryScreen.kt`（198 行，整个文件）
- `data/Models.kt:77-100` `DiaryItem`
- `data/ApiClient.kt:328` `diaries()`、`:333` `diaryCount()`、`:375` `createDiary()`
- `debug/DemoContent.kt:18-20` 三条示例日记
- `sync/WsEventRouter.kt:23` `"diary_new"` 白名单、`sync/StatusSyncManager.kt:264-267` 收到推送发通知
- `ui/navigation/LinxiApp.kt:425`(枚举)、`:233`(分派)、`:248/:269/:339`(参数三次穿透)
- `ui/screens/DiscoverScreen.kt:52,62,70-72,103-105` 入口卡「日记 / 已写下 N 篇」
- **零单测**（android 侧没有任何日记测试）

**服务端（要删）**
- `handlers.go`：`handleCreateDiary:825`、`handleListDiaries:856`、`handleUpdateDiary:870`、`handleDeleteDiary:895`、`handleUploadDiaryImage:1043`
- `main.go:155-158`、`:172` 路由
- `store.go`：`CreateDiary:328`、`ListDiaries:340`、`DiaryImages:365`、`AddDiaryImages:385`、`UpdateDiary:398`、`DeleteDiary:416`、`DiaryPairID:426`
- `models.go:95-102` `Diary` struct、`:200-201` `MsgDiaryNew/MsgDiaryUpdated`、`:230` 高优先级白名单
- `push.go:79` case
- `sql/schema.sql:131-141` `diary` 表 + 索引、`:143-149` `diary_image` 表 + 索引

**⚠️ 必须保留的共用基建（删错了会连带炸头像和相册）**
- `uploadDatePath()`（`handlers.go:39`）—— 头像 `avatar_handler.go:63,134` 与相册 `album_media.go:147` 都依赖
- `publicUploadURL()`（`handlers.go:106`）—— 头像依赖
- `/upload` 静态挂载 —— 头像依赖
- `validateUploadURL()`（`security.go:127`）—— **目前唯一调用方是 `AddDiaryImages`**，删日记后它和 `security_test.go:53-76` 会变成孤儿

**后台（要删）**
- `admin/src/views/content-audit/modules/diary-table.vue`（136 行）+ `content-audit/index.vue:9-10,23` 那个 tab
- `admin/src/api/admin.ts:43-48`、`types/api/api.d.ts:77,134-144`
- `admin.go`：`ListDiariesAll:685-708`、`handleAdminDeleteDiary:738-742`、路由 `:1444`、`:1464`
- **仪表盘统计卡**：`admin.go:485` 的 `"diaries": COUNT(*) FROM diary` + `dashboard/index.vue:52,80-82`
- i18n：`zh.json:245,305,340-354` / `en.json:245,305,340-353`

**文档（要改）**
`README.md:3,14,15,67`、`ARCHITECTURE.md`(13 处含 ER 图 DIARY 实体)、`CHANGELOG.md:15,55,69`、`DESIGN.md`(§6 整节)、`docs/feature-index.md:9`、`docs/android-ui.md:34,36,40`、`docs/diagnostics.md:38`、`server/README.md:34,79,88-92,177,189,211`

**CI**：零命中，不用改。

**不受影响**：「这一天」功能纯查 photo 表（`album_handlers.go:511`），与日记无关；App 名「林曦日记」与包名 `com.linxi.diary` **不动**。

---

❓ **Q31** - **删到什么程度**

**详情**：
- **A**：**只删客户端入口**（发现页那张卡 + 路由），服务端接口与表都留着。改动最小，但服务端留一堆孤儿接口 —— **这正是 0811 那次的做法，结果 0820 又接回来了**
- **B**：**客户端 + 服务端 handler/路由/store 全删**，但**保留 `diary`/`diary_image` 两张表**（数据不丢，将来想恢复还有底）
- **C**：B + **`DROP TABLE diary, diary_image`**，彻底断根
- **D**：C + 连带清理：`validateUploadURL` 与其单测（变孤儿）、`MsgDiaryNew/MsgDiaryUpdated`、`push.go` 的 case、后台仪表盘那张统计卡、i18n 全部 key、所有文档

➡️ **我的建议：D，但表保留（即 D 中把 DROP 换成保留表）**。理由分三层：

1. **A 绝对不行**——那是 0811 的做法，事实证明会反弹。留着孤儿接口，将来的我（或你）看到接口还在就会以为功能还该有。
2. **表要不要 DROP**：我倾向**保留表但不再有任何代码读写**。因为 `schema.sql` 是"启动时 `CREATE TABLE IF NOT EXISTS`"的幂等建表（`migrations.go`），没有 DROP 的迁移机制；要 DROP 就得写一段一次性迁移代码，而它只在你的生产库上跑一次，之后永远是死代码。留着两张空表的代价是零（几 KB），而写一段一次性 DROP 迁移的风险是"迁移写错了炸整个启动流程"。**你要是坚持 DROP，我会写成独立的、带日志的、失败不阻断启动的迁移。**
3. **D 里那些连带项必须清**，尤其后台仪表盘那张"日记总数"卡——不清的话它会永远显示 0，比删掉更难看。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q32** - **发现页少一张卡怎么补**

**详情**：发现页现在四张卡：相册 / 日记 / 一起听 / 一起看（后两个是"开发中"占位）。删了日记就剩三张，其中两张是占位。

- **A**：**就剩三张**，不补
- **B**：**把「这一天」提到发现页**（现在它藏在相册页顶部的按钮里，`AlbumListScreen.kt:134`）。它是个独立功能，值得独立入口
- **C**：B + 补一张**「回收站」**卡（现在也藏在相册页里）
- **D**：**做掉一个占位**（一起听 或 一起看 真正实现）—— 这是新功能，量级很大

➡️ **我的建议：B**。「这一天」（去年今日的照片）是个情感价值很高的功能，藏在相册页里一个小按钮里很浪费。C 里回收站不该提到一级入口（它是相册的附属功能，放相册页里对）。D 明确不建议这轮做——"一起听/一起看"要做实时同播，涉及 WS 时序、播放器、版权源，是独立一轮甚至两轮的量级。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q33** - **已有的日记数据怎么处理**

**详情**：你生产库里可能已经有日记数据（你和伴侣写的）。删功能后这些数据就再也看不到了。

- **A**：**不管**，直接删功能（数据留在表里，只是没有任何入口）
- **B**：**删之前给一个导出**：加个临时接口/后台按钮，把日记导出成 JSON 或 Markdown 让你下载留档
- **C**：**迁移到相册的"照片描述"**（把日记正文变成某张照片的 caption）—— 语义不对，不推荐
- **D**：你确认库里没有值得留的日记，直接删

➡️ **我的建议：B**，如果你库里真写过日记的话。理由很直接：日记是你和伴侣写的文字，属于不可再生的个人内容，删功能之前给一次导出机会成本极低（后台加一个"导出日记"按钮，20 行代码）。如果你确认没写过或不在乎，那就 D。**这个只有你知道，我无法从代码判断。**

选项:A
B
C
D
选择:
我的意见:

---

# 七、客户端 · 第 7 条：前台应用与息屏亮屏状态

你说「你调用搜索服务区看怎么获取的啊，总是显示不好」——我搜了，也读了代码。**结论是：你的采集方式（UsageStatsManager + SCREEN_ON/OFF 广播）本身是对的，是业内唯一可行的非 root 方案。真正的问题是六个实现缺陷。** 下面逐个说。

## 7.1 六个缺陷（全部有代码行号）

**缺陷 ① 屏幕状态初值硬编码 `true`**
```kotlin
// core/DeviceStatus.kt:66-67
@Volatile var screenOn: Boolean = true      // ← 进程刚起来时凭什么认为屏幕是亮的？
@Volatile var isLocked: Boolean = true
```
`SCREEN_ON`/`SCREEN_OFF` **无法静态注册**，只能在前台服务里动态注册。所以进程被 `SyncHeartbeat`(AlarmManager) 或 `BootReceiver` 在**息屏状态下**拉起时，第一次采集必然上报 `screen_on=true` —— 对方看到"他亮着屏"，实际人在睡觉。这个错值会一直持续到下一次真实的亮/息屏广播。

**权威依据**（我搜到的）：Android 官方与社区一致结论是 `PowerManager.isScreenOn()`/`isInteractive()` **只反映"可交互"，不等于"屏幕亮"**（AOD 息屏显示、Doze 都会让它给出反直觉的值），要判断屏幕真实状态应该用 **`Display.getState()`** 并显式处理 `STATE_DOZE`/`STATE_DOZE_SUSPEND`（AOD 状态）。

**缺陷 ② 每次采集遍历过去 24 小时全部事件**
```kotlin
// core/StatusCollector.kt:44-57
val events = usm.queryEvents(end - 86_400_000L, end)   // ← 24 小时！
while (events.hasNextEvent()) { ... }                   // ← 全遍历，只为取最后一个
```
重度使用一天有几千到上万条 UsageEvents。前台档位是**每 10 秒采集一次**，等于每 10 秒遍历上万条记录。

**缺陷 ③ 不看 `ACTIVITY_PAUSED`/`ACTIVITY_STOPPED`**
只认 `ACTIVITY_RESUMED`/`MOVE_TO_FOREGROUND`。用户按 Home 回桌面后，最后一个 RESUMED 仍是微信 → 一直显示"正在使用微信"。

**缺陷 ④ 息屏时不清空前台应用**
`collectAll`（`StatusCollector.kt:134-148`）无条件调 `foregroundApp(c)`，不看 `screenOn`。息屏后仍报着息屏前那个应用。这就是你说的"总是显示不好"最直接的表现。

**缺陷 ⑤ 采集跑在主线程**
`StatusForegroundService.refreshNow()`（`:194-216`）没有任何线程切换，`collectAll` 里的 `queryEvents`(24h 遍历) + `queryAndAggregateUsageStats` 全在主线程。这是 ANR 风险，也会让 UI 卡顿。

**缺陷 ⑥ WS 断线时状态直接丢弃，且 UI 没有"数据已过期"提示**
```kotlin
// sync/StatusSyncManager.kt:155
val w = ws ?: return      // ← WS 没连上，这次采集的状态直接扔掉
```
服务端**没有任何 REST 写入端点**（只能 WS 上报，`main.go` 里 `/status/*` 只有读接口）。所以 WS 一断（地铁、电梯、切飞行模式），状态就完全停更。而 `NowScreen.kt:296-313` **不看 `ts`**，会把 2 小时前的旧状态当成"现在"显示。对方看到的是一个自信满满的错误信息，比显示"未知"糟糕得多。

---

❓ **Q34** - **屏幕状态怎么判定**

**详情**：
- **A**：**初值改成实时读取**：`DeviceStatusHolder` 初始化时用 `Display.getState()` 取真实值，不再硬编码 `true`。同时 `ScreenStateReceiver` 保持不变（负责变化时的即时上报）
- **B**：A + **`Display.getState()` 作为每次采集的权威来源**（广播只用于触发即时上报，状态值一律现读现取）。这样即便漏掉一次广播也不会长期错
- **C**：B + **区分 AOD（息屏显示）**：`STATE_DOZE`/`STATE_DOZE_SUSPEND` 单独作为"息屏显示"状态上报，不算亮屏也不算全黑。一加 15 有 AOD
- **D**：只用 `PowerManager.isInteractive()`

➡️ **我的建议：C**。理由：A 只修了初值，广播漏投（进程被杀后重启、Doze 深度休眠）时仍会长期错；B 是"每次现读"，从根上消除状态漂移。C 里 AOD 的区分是因为**你的测试机就是一加 15，AOD 默认开着**——AOD 亮着的时候 `isInteractive()` 是 false 但 `Display.getState()` 是 `STATE_DOZE`，如果只按二分法处理，会出现"屏幕明明显示着时间，App 说息屏"或反之。D 明确不行，搜索结果里官方与社区都指出它不反映屏幕真实状态。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q35** - **前台应用怎么取（性能 + 准确性）**

**详情**：
- **A**：**窗口从 24h 缩到 60s，加 PAUSED/STOPPED 判定**。查最近 60 秒事件，按时间排序取最后一个事件：若是 RESUMED → 那个应用在前台；若是 PAUSED/STOPPED 且之后无 RESUMED → 无前台应用（回桌面了）。60 秒内没有任何事件 → 沿用上次结果（说明用户一直停在同一个应用里）
- **B**：A + **缓存 + 递增回退**：60s 查不到就退到 5min、再退到 30min（避免长时间停在同一应用时判为"无前台"），结果缓存在内存里
- **C**：B + **息屏时直接不查、上报"无前台"**（省电 + 语义正确）
- **D**：C + **改用 `AccessibilityService`** 拿实时窗口变化（最准，但要用户开无障碍权限、Google Play 政策敏感、且被各家 ROM 优化杀）

➡️ **我的建议：C**。逐条理由：A 里 60s 窗口把遍历量从上万条降到几十条；PAUSED/STOPPED 判定修掉"回桌面还显示微信"；B 的递增回退是必要的——如果你连续看小说 20 分钟没切应用，60s 窗口内一条事件都没有，不回退就会误判为"无前台"；C 的息屏不查既省电又正确。**D 明确不推荐**：无障碍权限是重型权限，用户看到"林曦日记请求无障碍服务"会警惕，而且各家 ROM（尤其 ColorOS）会定期杀无障碍服务，可靠性反而更差。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q36** - **采集线程与 WS 断线兜底**

**详情**：缺陷 ⑤（主线程采集）与 ⑥（断线丢弃 + 无过期提示）。
- **A**：**采集移到 IO 线程**（`Dispatchers.IO` 或服务自己的 HandlerThread）
- **B**：A + **加 REST 兜底上报**：服务端新增 `POST /status`，WS 未连接时走 HTTP 上报。这样地铁里断 WS 也能在有网瞬间把状态送出去
- **C**：B + **本地离线队列**：完全无网时把状态快照存本地（最多留最近 N 条），来网后补传
- **D**：A + **NowScreen 加"数据时效"显示**（"3 分钟前"，超过 10 分钟显示"状态可能已过期"并把卡片置灰）

➡️ **我的建议：B + D 都做**（如果只能选一个，选 D）。理由：
- A 是必须的（ANR 风险）
- **D 是这一条里性价比最高的**：你说的"总是显示不好"，很大一部分是"显示的是旧数据但看不出来"。加一行"3 分钟前"，你立刻就能分辨"是没同步"还是"数据错"。这也让我们下一轮排查有据可依
- B 的 REST 兜底能实质性提升送达率，服务端加一个 handler + 复用现有 hub 逻辑，不算大
- C 的本地队列我建议不做：状态是**时效性数据**，5 分钟前的电量补传上去意义不大，反而会让历史曲线出现"迟到的过去"，逻辑更乱

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q37** - **一加/ColorOS 的额外保活引导要不要加**

**详情**：`OngoingStatusPolicy.kt:23-31` 已经有 `Vendor.fromManufacturer` 把 `oneplus` 识别为 `COLOROS`，但**这整套 `status/` 三件套在 main 代码里零引用**，只被单测用着（也就是说写了但没接）。而设置页的自启动引导（`SettingsScreen.kt:207-211`）**没有授权判断**，只是个跳转按钮。

一加 15 / Android 16 的 ColorOS 对后台有额外限制：电池优化、自启动管理、后台冻结、通知类别管控。前台服务在 Android 15+ 还有 `dataSync` 类型的 **6 小时/24 小时运行上限**。

- **A**：**接上 `status/` 那三件套**（既然写好了就用），并把设置页的保活引导做成检查清单（每项显示"已开启/未开启"）
- **B**：A + **加"保活自检"页**：一键检测通知权限/使用情况访问/电池优化/自启动/前台服务存活，逐项给出"去设置"按钮
- **C**：A + B + **前台服务被杀的自愈上报**：服务重启时记一条日志，设置页能看到"最近 24 小时被系统杀了 N 次"
- **D**：不动

➡️ **我的建议：B**。理由：这类"双人状态同步"App 的可靠性 90% 取决于用户有没有把保活权限开全，而现在的引导是一堆没有状态反馈的跳转按钮——你点完也不知道到底开没开。B 的检查清单能让"显示不好"这个模糊问题变成"哦，自启动没开"这种可定位的结论。C 的自愈统计很有价值但要新增本地存储与统计逻辑，可以放下一轮。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q38** - **`status/` 那三件套（写了但没接）怎么处理**

**详情**：`status/OngoingStatusPolicy.kt`、`OngoingStatusController.kt`、`OngoingStatusAdapters.kt` 三个文件 + 三个测试文件，实现了"按厂商选择常驻状态展示适配器（标准通知 / LiveUpdate）"，但 `main/` 里**零 import**，纯死代码（只有单测在用）。

- **A**：**接上**（Q37 选 A/B/C 就包含这个）
- **B**：**删掉**（连测试一起），死代码不该留
- **C**：留着不动

➡️ **我的建议：A**。它的设计意图（按厂商选展示策略）是对的，Android 16 有 Live Updates（进度型通知）API，一加会有自己的实现。既然代码和测试都写好了，接上比删掉划算。**如果 Q37 你选 D（不动），那这里就应该选 B**——留着不接的死代码是纯负债。

选项:A
B
C
选择:
我的意见:

---

# 八、服务端 · 第 1 条：后台可配置项扩充

## 8.1 现状

后台设置页现有 **11 个可见项**（`system-settings/index.vue:125-137`）：`site.name/url/logo/description`、`storage.driver`(只读展示 local)、`smtp.host/port/username/password/from/ssl`。
服务端 `settingKeys` 有 **18 个键**（`admin.go:944-950`），比前端多 6 个已废弃的（`storage.local_dir`、5 个 OSS 键、`push.provider`）。
存储表是 **`app_setting`**（`schema.sql:162-166`，`k/v/updated_at`），不是 `settings`。
路由挂在 `sup` 组 = **仅超管可读写**（`admin.go:1481-1483`）。

## 8.2 全部写死的常量清单（这是"可配置化"的候选池）

我把服务端所有可能想调的常量都挖出来了：

| 分类 | 常量 | 当前值 | 位置 |
|---|---|---|---|
| **上传/相册** | maxPhotoBytes | 20MB | `album_media.go:20` |
| | photoThumbEdge | 512 | `album_media.go:22` |
| | maxPhotosPerDay | 200 | `album_media.go:25` |
| | maxUploadBytesADay | 500MB | `album_media.go:26` |
| | 头像 MaxBytes / MaxDimension / ThumbSize | 15MB / 512 / 256 | `avatar_pipeline.go:33-40` |
| | maxAlbumNameLen / maxCaptionLen / maxCommentLen | 32 / 500 / 500 | `album_handlers.go:15-19` |
| **验证码/登录** | emailCodeTTL | 10min | `account.go:24` |
| | 发送冷却 | 60s | `account.go:177`（内联） |
| | maxEmailCodeAttempts | 5 | `account.go:33` |
| | 用户登录限流窗口 | 10min | `account.go:290` |
| | 后台登录限流 | 5 次 / 10min | `admin.go:334,340`（内联） |
| | adminTokenTTL | 2h | `admin.go:37` |
| | 用户 JWT TTL | 720h | yaml `app.token_ttl_hours` |
| **绑定** | inviteTTL | 1h | `handlers.go:309` |
| | bindAttemptLimit / Window | 5 / 10min | `invite.go:25-26` |
| **互动** | 响铃冷却 | 600s / 3 次 | `store.go:539-548`（yaml 可覆盖，后台不可配） |
| | 轻互动冷却 | 7s / 1 次 | `store.go:558-559` |
| **数据保留** | 网络日志保留 | 7 天 | `netlog.go:59`（内联，clamp 1~3650） |
| | 网络日志清理周期 | 6h | `netlog.go:58` |
| | 状态历史保留 | **永久** | 不存在 |
| | 回收站保留 | **永久** | 不存在 |
| **WS/同步** | maxWSMessageBytes | 64KB | `hub.go:21` |
| | maxStatusUpdatesPerSec | 2 | `hub.go:25` |
| | WS idleTimeout | 45s | `hub.go:110` |
| | 状态历史聚合粒度 | 5min | `hub.go:171` |
| | 客户端同步分档 | 10s/60s/300s | 安卓 `SyncIntervalPolicy.kt:19-25` |
| **分页** | 后台分页 size 上限 | 200 | `admin.go:443-452` |
| | onThisDayLimit | 200 | `album_store.go:391` |
| **死配置** | `storage.upload_max_mb: 10` | — | `config.example.yaml:19`，**代码里零引用** |

---

❓ **Q39** - **后台配置项要开放到什么范围**

**详情**：
- **A**：**保守**：只开放 相册配额（张数/字节/单张大小）+ 数据保留（网络日志/状态历史/回收站）+ 功能开关（相册/评论/这一天）。约 10 个新键
- **B**：A + **安全策略**：验证码有效期与冷却、登录限流阈值、JWT/后台 token 有效期、邀请码有效期与尝试次数。约 20 个新键
- **C**：B + **互动冷却**（响铃 600s/3次、轻互动 7s）+ **WS 参数**（心跳、消息上限、状态限频）。约 28 个新键
- **D**：**上表全部**（含长度上限、缩略图尺寸、分页上限、客户端同步分档）。约 35 个新键

➡️ **我的建议：B**。逐条理由：
- A 是你明确要的（相册配置 + 数据保留），必做
- B 增加的"安全策略"是运营方真正会调的东西：验证码 10 分钟太长/太短、限流被误伤、token 有效期太长不安全 —— 这些不该靠改代码发版
- **C 里 WS 参数我不建议开放**：`idleTimeout`/心跳/限频是协议层参数，客户端与服务端必须**成对匹配**（客户端 ping 15s、服务端 idle 45s，是 3 倍关系）。后台单方面改服务端会直接导致所有客户端掉线。要开放就得客户端也拉配置，风险不成比例。互动冷却可以开放（它是纯服务端判断），我会把它放进 B
- **D 里的"客户端同步分档"更不能放后台**：那是安卓侧 AlarmManager 的排程参数，服务端配了客户端也不知道（除非做配置下发，见 Q41）。缩略图尺寸见 Q23 的理由

所以我的具体建议是 **B + 互动冷却**，共约 22 个键，分成 5 个分区：站点 / SMTP / 相册与上传 / 安全与限流 / 数据保留。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q40** - **配置改了怎么生效**

**详情**：现在 `siteBaseURL()` 有个缓存 + `invalidateSiteBaseCache()`（`admin.go:1000`，因为 `MaxOpenConns(1)` 下重复查库会自锁）。新配置项如果每次请求都查库，在 `MaxOpenConns(1)` 的 SQLite 上是**真实的性能与死锁风险**（0820 已经踩过一次 `MaxOpenConns(1)` 自锁死锁，`eaf825a` 才修）。

- **A**：**进程内缓存 + 保存时失效**（照 `siteBaseURL` 的现成模式），改完立即生效，零重启
- **B**：**启动时一次性读入内存**，改完需要重启容器才生效（最简单最安全，但你得 `docker compose restart`）
- **C**：A + **配置版本号**：每次保存 `settings_version++`，热路径只比对版本号，变了才重读

➡️ **我的建议：A**。B 违背你"不想动服务器"的诉求（每改一次配置要重启）。C 是过度设计——配置项就二十来个，全量缓存在一个 struct 里、保存时整体重建即可，比对版本号纯属画蛇添足。**实现要点**：缓存用 `atomic.Pointer[Settings]` 整体替换（不用 mutex 逐字段读），避免读写竞争。

选项:A
B
C
选择:
我的意见:

---

❓ **Q41** - **客户端要不要拉取服务端配置**

**详情**：有些配置只有客户端能执行（单次选图上限、缓存大小、同步分档、文本长度上限）。要让后台能控它们，客户端必须拉配置。

- **A**：**不做**。客户端参数写死在代码里，要改就发版
- **B**：**做一个只读配置接口** `GET /api/v1/client-config`，客户端启动时拉一次（失败用内置默认值），下发：单张大小上限、单次选图上限、允许格式、文本长度上限、功能开关（相册/评论/这一天）
- **C**：B + 下发同步分档间隔与缓存大小
- **D**：B + **配置变更走 WS 推送**（改完立即生效，不用等重启 App）

➡️ **我的建议：B**。理由：
- 最有价值的是**功能开关**与**上限值对齐**。现在客户端拦 20MB、服务端也拦 20MB，两处写死，改一处就不一致（客户端放过了服务端拒绝，用户白等一趟）
- 尤其**功能开关**：相册出问题时你能在后台一键关掉入口，不用等我发版 —— 这对生产环境价值极高
- C 里同步分档下发我不建议：那涉及 AlarmManager 重排，配置一变要重新调度，容易出"档位卡在某个值"的怪问题
- D 的 WS 推送是锦上添花，但配置变更是极低频操作（一个月改一次？），为它加一条 WS 消息类型不值得。启动拉一次 + 每次进入相册页复查一次就够了

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q42** - **配置项的权限（现在全在超管组）**

**详情**：`GET/PUT /settings` 挂在 `sup` 组（仅超管），因为里面有 SMTP 密码与 OSS 密钥。新增的相册配额、保留天数这些**不含密钥**，普通 admin 改改也无妨。

- **A**：**全部维持超管专属**（最简单最安全）
- **B**：**拆两组**：含密钥的（SMTP/存储）留超管，不含密钥的（相册配额/保留天数/功能开关）开放给普通 admin
- **C**：B + 每次修改写审计日志（现在 `handleAdminUpdateSettings` 已经写审计了，但只记 `keys=N`，不记具体改了什么）

➡️ **我的建议：C**。B 的拆分符合 RBAC 初衷（0820 那轮刚把敏感路由收敛到超管，就是因为之前"普通 admin 事实等于超管"）。C 里审计改成记录 `键名: 旧值→新值`（密钥类脱敏）—— 现在只记 `keys=3`，出问题完全查不出谁把配额改成 0 了。

选项:A
B
C
选择:
我的意见:

---

❓ **Q43** - **死配置 `storage.upload_max_mb` 怎么办**

**详情**：`config.example.yaml:19` 写着 `upload_max_mb: 10`，但**代码里零引用**（真实上限是 `album_media.go:20` 的 20MB 常量）。你要是照着这个 yaml 配了 10MB，会发现完全没用。同理 `settingKeys` 里 5 个 OSS 键 + `storage.local_dir` + `push.provider` 也都是废弃项（前端已隐藏，后端还留着）。

- **A**：**删掉死配置**（yaml 里那行 + `settingKeys` 里 6 个废弃键），并把真实上限接到新的后台配置项
- **B**：**让它生效**（yaml 那行接到代码里）
- **C**：不动

➡️ **我的建议：A**。留着一个"配了没用"的配置项，比没有这个配置项更糟——它会让人（包括未来的我）以为改它有效，然后浪费时间排查。OSS 那 6 个键同理：0813 已定"只留 local 存储、隐藏 OSS/COS/Kodo"，后端还留着键就是半途而废。

选项:A
B
C
选择:
我的意见:

---

# 九、服务端 · 第 2 条：后台手机端适配

## 9.1 现状（我查到的精确情况）

**有基础响应式，但页面层几乎没适配。**

已有的：
- `views/index/index.vue` 布局有三档断点：`≤1180px` 换 `100dvh`、**`≤800px` 侧边栏 fixed 抽屉化**、`≤640px` 内容宽度收窄
- `art-sidebar-menu/index.vue:146` 有 `MOBILE_BREAKPOINT = 800` + `useWindowSize()` + 遮罩 + 窄屏自动收菜单
- `admin/index.html:6` viewport meta **存在**（`width=device-width, initial-scale=1.0`，无 `viewport-fit=cover`）
- Element Plus `2.11.4`，Tailwind v4（**无 `tailwind.config.*`，配置在 `assets/styles/core/tailwind.css` 的 `@theme` 块，且未定义任何自定义断点**）

缺的（这才是"手机上不能用"的真因）：
- **`el-table` 完全没有移动端处理**。统一走 `components/core/tables/art-table/index.vue`，里面**唯一**的移动端逻辑是分页器布局切换（`:152-165`）。**没有横向滚动容器、没有卡片化、没有按断点隐藏列**
- 11 个用表格的页面里，只有 `admin-manage` 和 `notify-templates` 设了列宽，其余 9 个全靠 el-table 自适应 → **手机上列被挤成一团，或整体溢出**
- **搜索区无栅格封装**（除 dashboard 用了 `:xs/:sm/:md`），一行放 3~4 个输入框，手机上直接挤爆
- 各种操作按钮（编辑/删除）在窄屏没有 `fixed="right"`，横滑找不到
- 无安全区处理（`env(safe-area-inset-*)`），iPhone 刘海屏/一加曲面屏底部会被遮

---

❓ **Q44** - **手机端适配做到什么程度**

**详情**：
- **A**：**能用就行**：给所有表格加横向滚动容器（不再挤压列）、搜索区改成窄屏纵向堆叠、操作列 `fixed="right"`、加安全区 padding。手机上需要左右滑动看表格，但不会错乱
- **B**：A + **表格卡片化**：`≤768px` 时把 `el-table` 换成卡片列表（每行一张卡，字段纵向排列，操作按钮在卡底）。这是移动端后台的标准做法，不需要横滑
- **C**：B + **每个页面单独调**：dashboard 图表改单列、表单弹窗改全屏 drawer、分页器简化为"上一页/下一页"
- **D**：**做独立的移动端后台**（单独一套路由与组件）

➡️ **我的建议：B**。理由：
- A 是底线，但"手机上要左右滑动才能看表格"的体验，你用两次就会再来跟我说"手机上还是难用"
- B 的卡片化是移动端后台的通行解法，且能收敛成**一个通用组件**（`art-table` 内部按断点切换渲染模式），11 个页面零改动就全部受益 —— 这是投入产出比最高的做法
- C 里的弹窗改全屏 drawer 有价值但可以增量做；图表单列 dashboard 已经用了栅格，基本够
- **D 明确不建议**：两套代码双倍维护，且后台功能会逐渐不对齐

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q45** - **手机端适配的目标屏宽与验证方式**

**详情**：我本机有 Playwright（0820 装好的，chromium 已下载），能用它在指定视口尺寸下真开页面截图验证 —— 上一轮"后台白屏"就是靠它才找到真凶（是我自己加的 CSP 把 Vite inline 脚本拦了）。

- **A**：按 **一加 15 实际视口**验证（约 `412×915` CSS px，DPR 3）+ 一个小屏兜底 `360×640`
- **B**：A + iPhone 尺寸（`390×844`）+ 平板（`768×1024`）
- **C**：只按 Tailwind 默认断点（sm 640 / md 768 / lg 1024）验证，不针对具体机型
- **D**：不用 Playwright 验证，我自己在手机上看

➡️ **我的建议：B**。理由：你的测试机是一加 15，那是必测的；`360×640` 是最窄的现实场景（老机型/分屏），过了它基本不会有更糟的；平板 768 是"卡片化 vs 表格"的切换边界，必须验证切换点不会出现两边都难看的中间态。我会用 Playwright 逐页截图，走完 **登录 → 首登改密 → 每一个菜单页**（0820 的教训是"只测登录页会漏掉主界面的问题"），并断言：无横向溢出（`scrollWidth <= clientWidth`）、无控制台错误、无失败请求。

选项:A
B
C
D
选择:
我的意见:

---

# 十、额外要求

## 10.1 额外要求 1：跨端改动必须先协商

你的原话：「任何触及到服务端和APP端修改的，需要同时协商好，以免出现意外」。

以下是这轮**会改变服务端与客户端之间契约**的全部改动，我一次列清。**每一项都需要你确认**（如果 Q1~Q45 相关题你已选了，这里就是复核清单）：

| # | 契约变更 | 服务端侧 | 客户端侧 | 关联问题 |
|---|---|---|---|---|
| 1 | 状态历史 NULL 修复 | `StatusHistory` 三字段改 `sql.NullString` + 检查 Scan | LazyColumn key 改不重复 | Q15 |
| 2 | 状态历史支持"看谁" | `GET /status/history?who=me\|partner` 新增参数 | 加分段切换 | Q16 |
| 3 | 状态历史保留天数 | 新增清理任务 + 后台配置键 | 无 | Q17 |
| 4 | 未归类张数 | `GET /albums/summary` 增 `unclassified_count` 字段 | 不再自己做减法 | Q30 |
| 5 | 照片批量删除 | 新增 `POST /photos/batch-delete`（或复用单条 N 次） | 多选态 | Q20 |
| 6 | 照片移动相册 | 新增 `PUT /photos/:id/album` 或批量版 | 多选态"移动到" | Q20 |
| 7 | 回收站彻底删除 | 新增 `DELETE /photos/:id/purge` + `POST /photos/purge-all`，**真删磁盘** | 回收站加按钮 | Q21 |
| 8 | 回收站保留天数 | 清理任务 + 配置键 | 显示剩余天数 | Q21 |
| 9 | 预置默认相册 | 绑定时自动建 | 无（自然显示） | Q22 |
| 10 | 三档缩略图 | 上传时多生成一档 `preview`(1080)，`photo` 增 `preview_url` 字段；`GET /media/:id/preview` | 大图页先加载 preview | Q23 |
| 11 | REST 状态上报兜底 | 新增 `POST /status` | WS 断线时走 HTTP | Q36 |
| 12 | 客户端配置下发 | 新增 `GET /client-config` | 启动拉取 | Q41 |
| 13 | 删日记 | 删 5 个 handler + 路由 + store + 后台接口 | 删页面/模型/入口 | Q31 |
| 14 | 相册功能开关 | 配置键 + 接口层拦截 | 入口按开关隐藏 | Q25/Q41 |
| 15 | 上传失败原因细化 | 各拒绝分支返回不同 `code` 与 message | 按 code 显示具体原因 | Q11 |
| 16 | BMP 支持 | `decodableInPureGo` 放行 BMP + `x/image/bmp` | 无 | Q9 |

❓ **Q46** - **契约变更清单确认**

**详情**：上表 16 项。
- **A**：**全部同意**，按各问题的选择执行
- **B**：**只做与我明确提出的 7 条抱怨直接相关的**（1,2,4,5,6,7,13,15），主动优化项（3,8,9,10,11,12,14,16）砍掉
- **C**：分批：这轮做 1~9 + 13 + 15，10~12 + 14 + 16 留下一轮
- **D**：逐条来（请在「我的意见」里写明要哪些编号）

➡️ **我的建议：A**。理由：这 16 项里没有一项是"我想炫技"——3/8 是防磁盘打满（你的服务器迟早会满），10 是"点开大图秒出"（弱网体验），11 是"地铁里状态停更"，12/14 是"出问题能后台关掉不用等发版"，16 是修 bug。全做的话服务端接口新增 6 个、改 3 个，量在可控范围。若你担心一次太多，选 C 也行——C 的划分保证这轮结束时你的 7 条抱怨全部闭环。

选项:A
B
C
D
选择:
我的意见:

---

## 10.2 额外要求 2：弹窗与全局风格规范（写入 AGENTS.md）

你的原话：「我之前每次都有要求，弹窗相关的，确认按钮和取消按钮的按钮背景颜色不一样，而且都要像标准一样学习，你写入agents.md，规范以后所有的风格，确保统一无割裂感」。

**先坦白：这条我之前确实没做到。** 我查了全仓 11 处 `OverlayDialog`，现状如下：

| 位置 | 取消侧 | 确认侧 | 是否合规 |
|---|---|---|---|
| `AboutScreen.kt:208` 解绑 | `LxButton` Neutral（灰） | `LxButton`（红/蓝） | ✅ |
| `AboutScreen.kt:282` 更新 | `LxButton "稍后"` Neutral | `LxButton` Positive | ✅ |
| `PrivacyConsentScreen.kt:57` | `LxButton` Neutral | `LxButton` Positive | ✅ |
| `AlbumListScreen.kt:239` 建相册 | 裸 miuix `Button` | 裸 miuix `Button` | ❌ **同色** |
| `AlbumListScreen.kt:277` 管理相册 | 裸 miuix `Button` | 「删除相册」与「保存名称」**完全同色** | ❌ **危险操作无区分** |
| `DiaryScreen.kt:149` 写日记 | 裸 miuix `Button` | 裸 miuix `Button` | ❌ **同色**（本轮会删） |
| `HistoryScreen.kt:251` 选日期 | 裸 miuix `Button` | 裸 miuix `Button` | ❌ **同色** |
| `TodoScreen.kt:511` 添加待办 | — | — | 待复核 |
| `SettingsScreen.kt:236` 日志 | miuix `Button "取消"` | — | 单按钮 |
| `PhotoViewerScreen.kt:113` 删照片 | **无弹窗，直接删** | — | ❌ **零确认** |

现成的组件是 `ui/components/LxButton.kt`（58 行）：`LxButtonVariant.Positive`=`BrandBlue` / `Negative`=`BrandRed` / `Neutral`=`onBackground α0.08`。**它存在，但相册/日记/历史三处弹窗都没用它。**

❓ **Q47** - **风格规范的具体标准（这会写进 AGENTS.md 成为长期约束）**

**详情**：我拟的标准草案，你确认或修改：

**按钮语义与配色**
- 正面/确认（保存、创建、同意、确定）→ `LxButtonVariant.Positive` = 品牌蓝 `#277AF7`，白字
- 危险/破坏（删除、清空、解绑、彻底删除）→ `Negative` = 品牌红，白字
- 取消/次要（取消、稍后、关闭、返回）→ `Neutral` = 中性灰底（`onBackground` α0.08），深字
- **同一弹窗内确认与取消的背景色必须不同**，且危险操作一律用红
- 弹窗按钮**一律用 `LxButton`**，禁止裸 `miuix.Button`（裸 Button 无语义配色）

**弹窗结构**
- 全部用 `miuix.kmp.overlay.OverlayDialog`，`renderInRootScaffold = true`
- 两按钮时：取消在左、确认在右，`weight(1f)` 等宽，间距 8dp
- 危险操作**必须二次确认**，确认文案说清后果（"删除后 X 张照片会退回未归类"这种，不是干巴巴的"确定吗"）
- 不可逆操作（彻底删除、清空回收站）除二次确认外，**确认按钮延迟 1 秒可点**（防手滑连点）
- 处理中：按钮文案变"删除中…"并禁用，弹窗不可点外部关闭

**其它统一项**
- 图标一律 `MiuixIcons`（缺的自绘，见 Q2），禁止 `androidx.compose.material.icons.*`
- 组件一律 `miuix.kmp.basic.*`，禁止 `androidx.compose.material3.*`（例外：`Theme.kt` 配色链，见 Q5）
- 圆角：卡片 16dp、按钮 16dp、缩略图 8dp
- 列表加载态一律 `KernelScreen(loading=)` 或 `LoadingRow()`，禁止各页自写
- 页面骨架一律 `KernelScreen`，二级页必须有 `BackHandler` + `BackAction`
- 错误态必须可重试（有"重试"按钮），空态与错误态**文案必须区分**
- 所有面向用户的文案用中文，日志用英文

- **A**：**照此草案写入 AGENTS.md**，并把 6 处不合规的弹窗全部改合规
- **B**：A + **加"延迟 1 秒可点"之外的更严格防护**（危险操作需要输入相册名确认，像 GitHub 删仓库那样）
- **C**：只写文档，不改现有不合规处（下一轮再改）
- **D**：草案要改（请在「我的意见」写你的标准）

➡️ **我的建议：A**。B 的"输入名称确认"对情侣 App 太重了（删一个相册要打字？），延迟 1 秒 + 明确后果说明已经足够。C 不行——文档写了但代码不合规，等于没写。

**关于 AGENTS.md**：全仓现在**没有** `AGENTS.md` 也没有 `CLAUDE.md`（只有 `.claude/skills/`、`.agents/skills/`、`agent/skills/` 三个高度重叠的 skills 目录）。我会新建仓库根 `AGENTS.md`，除上述 UI 规范外，还写入：本机工具链路径与验证命令、三端提交前的自检清单、CI 触发规则、`server/webdist` 与 `server.exe` 不得提交、"写代码前先 grep 项目内既有先例"（这是我反复犯错的地方：凭印象写出过 `SuperDialog`/`getObject()`/`deleteJson()` 这些不存在的 API）。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q48** - **AGENTS.md 放哪、要不要同时给 CLAUDE.md**

**详情**：`AGENTS.md` 是通用 agent 约定文件；`CLAUDE.md` 是 Claude Code 专用（会被自动读取）。两者内容会高度重叠。参考项目 `C:\Lx\KernelSU-main` 根目录就有 `AGENTS.md`。

- **A**：只建 `AGENTS.md`（根目录），另建一个 1 行的 `CLAUDE.md` 写 `@AGENTS.md` 引用它
- **B**：只建 `AGENTS.md`
- **C**：两份都写全（重复维护）
- **D**：内容拆开：`AGENTS.md` 写项目规范（UI/代码风格/契约），`docs/DEVELOPMENT.md` 写工具链与验证流程

➡️ **我的建议：A + D 结合**：根 `AGENTS.md` 写规范（你要的 UI 风格标准 + 代码约定），根 `CLAUDE.md` 一行引用，`docs/DEVELOPMENT.md` 写工具链与验证命令细节。这样 `AGENTS.md` 保持精简可读（你也会看它），操作细节沉到 docs 里。

选项:A
B
C
D
选择:
我的意见:

---

## 10.3 额外要求 3：我主动发现但你没提的问题（毫无保留版）

按严重程度排。前面已经在各问题里覆盖的不重复。

### 🔴 严重（会丢数据 / 会崩 / 会打满磁盘）

**P1 · `store.go` 全部 `rows.Scan` 都不检查返回值**
不只是状态历史那处。待办、日记、相册、离线队列的查询循环全是 `rows.Scan(...)` 后直接 `append`。任何一列变成 NULL，就会静默产生一整行零值数据。这是这轮崩溃的同源问题。→ 已并入 Q15 选项 D。

**P2 · 磁盘只增不减（三处）**
① 相册照片软删后文件永久保留（Q21）② 状态历史永久保留（Q17）③ 头像被替换后旧文件是否清理我还没查证 —— 我会在施工时确认。你的服务器磁盘现在处于"只涨不跌"状态。

**P3 · 上传配额并发可击穿**
`checkUploadQuota` 先查后写（`album_media.go:42-58`），并发上传时配额形同虚设。→ Q29。

**P4 · WS 断线状态直接丢弃**
`StatusSyncManager.kt:155` 的 `?: return`。→ Q36。

### 🟠 重要（体验硬伤 / 安全面）

**P5 · `NowScreen` 把旧状态当新的显示**
不看 `ts`，2 小时前的状态和 2 秒前的长得一模一样。→ Q36 选项 D。

**P6 · `handleHistoryTimeline` 查错人**
「伴侣状态历史」显示的是你自己的。→ Q16。

**P7 · 日期查询时区错位**
写入用本地时区、查询按 UTC 解析。→ Q17。

**P8 · `AlbumDetailScreen` 网格 O(n²)**
`photos.indexOf(p)` 在 item lambda 里。→ Q30。

**P9 · `attachPhotos` 失败静默**
照片留在未归类，用户以为传进相册了。→ Q30。

**P10 · 大图页删照片零确认**
→ Q20 选项 B。

**P11 · 「加载更多」失败即封死**
`HistoryScreen.kt:73-75` 一次网络抖动就再也拉不到后续数据。→ Q18。

**P12 · 头像上传后旧文件是否清理未确认**
`avatar_handler.go` 我这轮没细读，会在施工时查。若不清理，每次换头像都留一份垃圾。

**P13 · `server/webdist/` 与 `server/server.exe` 在工作区里**
前者是构建产物（上轮误提交过 139 个文件，`650639e` 才清）、后者是 14MB 二进制。`.gitignore` 已加 `server/webdist/*` + `!index.html`，但 `server.exe` 我要确认是否被忽略。

### 🟡 值得做（技术债 / 一致性）

**P14 · `status/` 三件套是死代码** → Q38。

**P15 · `BuildConfig.SAFE_MODE` 与 `debug/DemoContent.kt`**
SAFE_MODE 是 0813 排查启动闪退时留的二分工具（`MainActivity.kt:62`），`DemoContent` 是 demo 模式残留（0813 已定"彻底删 demo/skip 整链"，但 `DemoContent.kt` 还在，`TodoScreen` 里还有 `demo` 变量分支）。→ 见 Q49。

**P16 · `settingKeys` 里 6 个废弃键** → Q43。

**P17 · `config.example.yaml` 的 `upload_max_mb` 是死配置** → Q43。

**P18 · `ImagePrep` 动态 WebP 丢动画** → Q10。

**P19 · BMP 能过魔数但被拒，且报错文案说的是 HEIC** → Q9。

**P20 · 后台 `el-table` 无移动端处理** → Q44。

**P21 · 电量曲线图无坐标轴** → Q18。

**P22 · 日期选择器是步进按钮而非滚轮** → Q18（miuix 有 `NumberPicker` 可用）。

**P23 · 相册封面接口写了但没 UI** → Q20 选项 D。

**P24 · 三个 skills 目录高度重叠**
`.claude/skills/`(≈30)、`.agents/skills/`(≈27)、`agent/skills/`(≈20)，内容大量重复，还有个 `skills-lock.json`。这些是工具配置不是项目代码，但占仓库体积。→ 见 Q49。

**P25 · 缩略图对 GIF 生成 PNG 静图**
行为是对的（网格里不该一堆动图），但 PNG 比 JPEG 大很多。GIF 首帧转 JPEG 更省。

**P26 · `Cache-Control: private, max-age=86400`**
照片一天后重新下载。照片内容不变（URL 是 `/media/<id>`，id 不变内容就不变），可以设更长（30 天）+ `ETag`，进一步省流量。

---

❓ **Q49** - **P15（SAFE_MODE / DemoContent）与 P24（三个 skills 目录）怎么处理**

**详情**：
- **A**：**都清掉**：删 `SAFE_MODE` 分支与 `BuildConfig` 字段、删 `debug/DemoContent.kt` 与 `TodoScreen` 里的 demo 分支；skills 目录保留 `.claude/` 一份，删 `.agents/` 与 `agent/`
- **B**：只清 P15（代码里的死分支），skills 目录不动（那是工具配置，与项目无关）
- **C**：只清 skills 目录，SAFE_MODE 留着（万一又要排查启动闪退）
- **D**：都不动

➡️ **我的建议：B**。理由：SAFE_MODE 与 DemoContent 是明确的死代码（0813 就定了删 demo 整链，没删干净），留着会让人以为还有 demo 模式。skills 目录我建议**不动**——它们是 agent 工具配置，删了可能影响你别的工作流（比如 `skills-lock.json` 记录了来源锁定），而且我不清楚 `.agents/` 和 `agent/` 是不是别的工具在用。这个你比我清楚。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q50** - **P26 缓存策略与 P25 缩略图格式要不要顺手改**

**详情**：都是省流量/省磁盘的小优化。
- **A**：都改（照片 `max-age` 提到 30 天 + `ETag`；GIF 缩略图改出 JPEG 首帧）
- **B**：只改缓存头
- **C**：都不改

➡️ **我的建议：A**。两处加起来不到 20 行，能明显减少重复下载与磁盘占用。缓存头改长是安全的：`/media/<id>` 的内容天然不可变（照片不会被原地修改，编辑描述不影响图片本体）。

选项:A
B
C
选择:
我的意见:

---

# 十一、施工与验证

## 11.1 我能在本机验证到什么程度

这一点跟上几轮不一样了，工具链**已经装齐**：

| 能力 | 状态 |
|---|---|
| Go 编译/vet/单测 | ✅ Go 1.22.12 已装（我这轮已经用它跑出了崩溃复现） |
| Android 编译 | ✅ JDK 21 + Android SDK 36 + Gradle 9.7.0 已装，`gradle :app:compileDebugKotlin` 可跑 |
| 后台前端构建 | ✅ `npm run build` |
| 后台真机效果 | ✅ Playwright + chromium 已装，能起本地服务真开页面截图 |
| SQLite DDL/SQL | ✅ 本机 python sqlite3 + Go 驱动双验 |
| CI 状态查询 | ✅ GitHub REST API + token（无 gh CLI，需手动处理日志 302 重定向） |
| **APK 真机行为** | ❌ 只能你在一加 15 上验 |
| **推送/WS 真实时序** | ❌ 需真机双端 |

所以这轮的验证策略是：**Go 与 Android 全部本地编译过 + 单测跑绿，后台用 Playwright 逐页截图，最后才 push 触发 CI**。上几轮"没编译过一行就推"的情况不会再有。

---

❓ **Q51** - **施工顺序与推送策略**

**详情**：我拟的顺序（按依赖关系排，不是按重要性）：

```
P0  崩溃与数据正确性（Q15 Q16 Q17 + P1 全库 Scan 排雷）
    → 这是唯一"你现在就崩"的问题，必须最先
P1  服务端配置基建（Q39 Q40 Q42 Q43）+ 客户端配置下发（Q41）
    → 后面很多功能挂在配置上，先把地基打好
P2  相册功能（Q19~Q24 Q28~Q30）
    → 删除/多选/回收站/缩略图三档/缓存
P3  图片格式与选图器（Q7~Q14）
    → 扫描修复/分桶/头像选图器统一/裁剪
P4  状态采集（Q34~Q38）
    → 屏幕状态/前台应用/线程/兜底上报/保活自检
P5  删日记（Q31~Q33）
    → 放这么后是因为它会动到发现页与后台仪表盘，先让其它改动稳定
P6  miuix 全量替换（Q1~Q6）
    → 放最后是因为它触及几乎每个 UI 文件，早做会与 P2~P4 的改动疯狂冲突
P7  后台移动端适配（Q44 Q45）
P8  AGENTS.md + 文档 + CHANGELOG（Q47 Q48）
P9  本地全量验证 → push → CI → 手动触发 build-android
```

- **A**：按此顺序，**中途不停**，全做完一次性交付（你的原话："哪怕分多个步骤我也只会验证最后的成品"）
- **B**：按此顺序，但 **P0 做完先 push 一次**（让你能先拿到不崩的版本），其余照旧一路做完
- **C**：顺序要调（请在「我的意见」写）
- **D**：分两轮：P0~P4 这轮，P5~P8 下轮

➡️ **我的建议：B**。理由：P0 是"进某个页面就崩"的生产问题，而 P6（miuix 全量替换）会碰几十个文件、CI 可能要反复迭代几次才绿。先把崩溃修复单独推一次，你能马上装到不崩的包；万一后面 P6 折腾很久，你手里至少有个能用的版本。这不违背你"最后才交付"的要求——我不会在 P0 之后停下来等你验证，只是多打一个 commit 而已，之后继续一路做到 P9。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q52** - **版本号与发行版**

**详情**：`build.gradle.kts` 的 `versionName` 默认 `1.0.0`、`versionCode` 由 CI 从 tag 推导（`v1.2.3`→`10203`，0820 修的）。这轮改动量很大（三端全动 + 删一个功能）。

- **A**：这轮结束发 **v1.3.0**（功能变更 + 删除日记，算 minor）
- **B**：发 **v2.0.0**（删功能是破坏性变更）
- **C**：只出 Debug APK 给你测，暂不发行版
- **D**：你决定版本号（写在「我的意见」）

➡️ **我的建议：C 然后 A**：先出 Debug 包给你真机验（尤其相册删除、状态采集、miuix 观感这三块必须真机看），你确认没问题后再走 `release.yml` 发 v1.3.0。理由：这轮改动面太大，直接发正式版风险高；而 Debug 包与 Release 包现在签名都是固定的（0820 已证实两次 Release 构建指纹完全一致），装 Debug 包不会有覆盖安装问题。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q53** - **CI 与 push 授权**

**详情**：0813 那轮你曾明确授权"本轮 push main 无需再确认"，但那是**仅限那轮**的临时授权。常规约定是"每次 push main 前先与管理员确认"。这轮我会本地编译验证过再推，但 CI 仍可能红。

- **A**：**这轮授权我自由 push main + 反复迭代直到 CI 全绿**，不用每次问你
- **B**：**每次 push 前问你一次**
- **C**：**P0 那次可以直接推**（崩溃修复），后面的大改动推之前问一次
- **D**：全部做完、本地验证全绿之后，一次性推

➡️ **我的建议：A**。理由：这轮涉及 Android 编译（miuix 全量替换极可能有编译错误要迭代几轮）+ Go + 后台三端，CI 红了要改、改了要再推，如果每次都等你确认，整轮会被拖成好几天。push main 只会触发镜像构建（`build-server.yml`），**不会自动部署生产**（`deploy.yml` 0811 就删了），生产要你在服务器上手动 `docker compose pull`，所以推 main 的实际风险是零。安卓与发行版都是手动触发工作流。

选项:A
B
C
D
选择:
我的意见:

---

❓ **Q54** - **本轮结束时的验收标准**

**详情**：我想跟你对齐"什么算做完了"，避免我以为做完了你觉得没做完。
- **A**：**代码维度**：Go `vet`+`test` 绿、Android `compileDebugKotlin`+单测绿、后台 `npm run build` 绿、CI 三个工作流全绿、Playwright 逐页截图无溢出无报错
- **B**：A + **我出一份自测清单**（逐条列出这轮改的每个点该怎么在真机上验，你照着点一遍）
- **C**：B + **我把关键改动前后对比截图**（后台移动端、miuix 替换前后）贴在交付说明里
- **D**：只要 CI 绿就行

➡️ **我的建议：C**。理由：你说"我只会验证最后的成品"，那我就得让你的验证成本尽可能低。B 的自测清单能让你 15 分钟走完全部改动点，而不是自己摸索"他到底改了什么"。C 的截图对比尤其针对 miuix 替换与后台移动端 —— 这两块是纯观感，文字描述不如截图。

选项:A
B
C
D
选择:
我的意见:

---

# 十二、我需要你补充的信息（不是选择题）

这几条我从代码里查不到，只有你知道：

❓ **Q55** - **崩溃日志能给我吗**

**详情**：我已经从代码推断出崩溃根因并本地实证了（NULL Scan → 重复 key），但**如果实际崩溃是另一个原因，我的修复就打偏了**。App 有崩溃捕获（`CrashHandler` 写到 `files/crash/`，经 `LogSanitizer` 脱敏），设置页有「发送日志」入口能导出诊断包。

如果你能在「我的」页面点「发送日志」导出一份，或者用 `adb logcat` 抓一段，把 `IllegalArgumentException` 或任何异常的堆栈贴给我，我就能 100% 确认。**不给也行**，我按已实证的根因修，同时把"重复 key"和"NULL Scan"两条路都堵上，覆盖面足够。

选项:A（我提供日志）
B（不提供，你按推断的修）
选择:
我的意见:

---

❓ **Q56** - **你库里有日记数据吗**

**详情**：Q33 需要这个信息。删功能前要不要给你一次导出机会，取决于你有没有写过日记。

选项:A（有，要导出）
B（有，但不用导出，直接删）
C（没写过，直接删）
选择:
我的意见:

---

❓ **Q57** - **相册里现在有多少照片、你手机相册有多少张图**

**详情**：这影响两个判断：
- 你手机图片总数 > 2000 吗？如果是，那"图片消失"就 100% 是我找到的截断 bug（Q7）
- 服务器相册里照片数量，决定要不要做相册列表分页（Q30 的 3）与三档缩略图的收益（Q23）

选项:A（手机 >2000 张）
B（手机 <2000 张）
选择:
我的意见（服务器相册张数、磁盘占用如果知道也告诉我）:

---

❓ **Q58** - **「使用情况访问」权限你开了吗**

**详情**：这决定崩溃复现路径。如果你**没开**，那 `foreground_pkg` 必然写 NULL，与我实证的根因完全吻合。如果你**开了**，那 NULL 来自另一条路径（息屏时无前台应用），结论一样但我想确认一下。

顺便：`ACCESS_FINE_LOCATION`（读 WiFi 名）你开了吗？没开的话 `ssid` 也是空。

选项:A（使用情况访问已开）
B（未开）
选择:
我的意见（定位权限状态）:

---

❓ **Q59** - **还有没有我漏掉的抱怨**

**详情**：你这份 TodoList 里我逐条拆了 7+2 条。但你平时用着可能还有些没写进来的小别扭（哪个按钮位置不顺手、哪个文案看不懂、哪个操作要点太多次）。这轮既然要动这么多地方，能一起说了最省事。

选项:A（就这些）
B（还有，见下）
选择:
我的意见:

---

# 附：问题索引

| 区块 | 问题 |
|---|---|
| miuix 全量替换 | Q1 加载真凶 / Q2 图标缺口 / Q3 响铃页 / Q4 启动帧 / Q5 主题链 / Q6 通知布局 |
| 图片格式与扫描 | Q7 扫描程度 / Q8 视频 / Q9 服务端 HEIC / Q10 动图 / Q11 失败原因 / Q12 选图上限 |
| 头像选图器 | Q13 统一方案 / Q14 预览 |
| 状态历史崩溃 | Q15 崩溃修法★ / Q16 查错人 / Q17 时区与保留 / Q18 页面其它毛病 |
| 相册 | Q19 删分组入口 / Q20 删照片 / Q21 回收站 / Q22 默认分组 / Q23 缩略图档位 / Q24 缓存大小 / Q25 后台配置项 / Q26 一键恢复默认 / Q27 不改 compose 确认 / Q28 后台相册页 / Q29 并发配额 / Q30 其它相册问题 |
| 删日记 | Q31 删除程度 / Q32 发现页补卡 / Q33 已有数据 |
| 状态采集 | Q34 屏幕状态 / Q35 前台应用 / Q36 线程与兜底 / Q37 保活引导 / Q38 死代码 |
| 后台配置 | Q39 开放范围 / Q40 生效方式 / Q41 客户端拉配置 / Q42 权限 / Q43 死配置 |
| 后台移动端 | Q44 适配程度 / Q45 验证方式 |
| 额外要求 | Q46 契约清单★ / Q47 风格规范★ / Q48 AGENTS.md 位置 |
| 主动发现 | Q49 死代码清理 / Q50 缓存与缩略图 |
| 施工 | Q51 顺序★ / Q52 版本号 / Q53 push 授权 / Q54 验收标准 |
| 需你补充 | Q55 崩溃日志 / Q56 日记数据 / Q57 照片数量 / Q58 权限状态 / Q59 补充抱怨 |

★ = 影响面最大、最需要你明确表态的。

---

**回答完存为 `ClaudeScheme_0821_Answer.md`（放 `C:\Users\Administrator\Downloads\` 或仓库根都行），我读完直接开工，不再中途请示。中间若因网络中断，我会接着做，不重来。**









