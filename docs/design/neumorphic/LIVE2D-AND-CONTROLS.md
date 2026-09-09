# 补充设计：主页 Live2D 陪伴角与完整拟态控件

Draft 1.1 · 2026-09-08。用户新增范围：APP内导入并显示现成Live2D模型；完善预览中原生状态选择框与裸文档链接的材质。本文件优先覆盖旧稿中对应范围；当前代码已接入安全导入/管理骨架，但不包含模型制作或购买，也不把占位预览当成真实渲染。

## 当前提供的 `live2d.zip` 检查结论

已对 `C:\Users\Administrator\Downloads\live2d.zip` 做只读检查：压缩包约 14.9 MiB、27 个条目，含 `yumi.model3.json`、`yumi.physics3.json`、`yumi.cdi3.json`、`yumi.8192/texture_00.png` 和若干 VTube Studio 表情/动作配置；但不含 `yumi.moc3`。而 `yumi.model3.json` 的 `FileReferences.Moc` 明确指向 `yumi.moc3`，所以它当前不能通过 APP 的完整模型校验，也不能作为默认可渲染模型。贴图头信息还是 8192×8192，超过本方案首轮移动端 4096 单边预算，即使补回 moc3 也要先做兼容性/降纹理方案。包内 `yumi.vtube.json` 还记录了 `WorkshopSharingForbidden: true`，在没有模型作者授权前不能把它复制进发布包或后台资源库。

导入器现在采用分层兼容性报告：先规范化 ZIP 条目与大小写冲突，再以 `model3.json` 所在目录为基准解析 `Moc`、贴图、物理、Pose、DisplayInfo、Expressions、Motions 和 Sound；安全的 `../` 兄弟目录引用可以工作，真正越出包根仍拒绝。已有的 `.vtube.json`、预览图、动作/表情资源会原样保留并写入本机元数据，缺失可选资源会单独列为警告。含 `.vtube.json` 但缺 `.moc3` 时，页面会明确显示“VTube Studio 配置不能替代 moc3”，而不是笼统的“模型不完整”。补齐同一模型的 `.moc3` 与授权后，仍需按本文件的 Core 版本、纹理预算和真机绘制验收，再决定是否把它放进 `android/app/src/main/assets` 作为内置默认模型。

后续修订：[Draft1.2](REFINEMENTS-1.2.md)已增加后台角色资源库、APP模型列表及具体文件选择器流程。本文件继续负责SDK/格式/渲染安全，不再限制为“没有后台管理”。本机私有导入仍不上传；后台上传仅限管理员主动提供且有分发授权的资源。

## 1. 位置：主页中段的「陪伴角」

最终建议放在 **伴侣状态之后、远程互动之前**，作为一个独立列表项，而不是贴在状态卡上、播放条上或全局悬浮在右下角。

```text
主页顶栏
双人关系／纪念日
伴侣状态与更新时间
┌──────────── 柔光拟态舞台 ────────────┐
│ 陪伴角              角色显示区域    │
│ 今天也在这里陪你       Live2D       │
│ [管理角色]           同色背景       │
└────────────────────────────────────┘
远程互动／本机信息
（播放时出现的迷你播放器＋主导航）
```

理由：首屏优先保留伴侣状态；角色是陪伴装饰，不伪装成伴侣实时在线，不抢音乐封面的位置。角色不跟随tab漂浮，不覆盖FAB或上传按钮，其他页面保持原布局。以后若用户更喜欢显眼位置，可调到关系区之后，但首轮不默认挤掉状态。

### 1.1 舞台尺寸与材质

- 外层沿用LxSurface Raised，圆角16、内边16、页边16、上下间距16～24；不再嵌套第二张凸起卡。
- 宽390dp时总高minHeight176；左说明区约40%，右模型区约60%，角色画布约180×152，按实际宽度变化。模型区不放文字覆盖层。
- 320dp或字体≥1.5时改上下排：标题／管理操作在上、舞台160～200高在下；文字增高而非缩到12sp。
- 用模型真实画布比例做contain适配，保留四边8～12安全距离；默认展示完整角色，不强裁头脚。模型原始画布含大量留白时在管理页提供缩放和纵向位置校准及“重置位置”。初值必须在实际模型上验收。
- 角色脚下可有极淡椭圆接触影，最多静态一层，不能盖住模型；颜色、服装保持模型本身，不强制染蓝或给人物身体加CSS阴影。
- 舞台背景使用当前surface色，角色渲染面可直接清屏同色；首轮不强求透明EGL。若使用透明渲染，必须验证alpha/预乘透明，避免黑底、白边。
- 没有模型时默认不占主页空间；入口常驻“我的→外观→陪伴角色”。用户启用后尚未选模型，可出现紧凑“导入角色”引导。
- 自定义模型可能与灰蓝风格差异很大，能保证容器协调，不能保证任意模型美术都统一。建议用户选透明背景、轮廓完整、色彩较柔和的模型，不据此硬性拒绝其他合法模型。

### 1.2 最小功能：只保证导入和真实显示

MVP必须有：选择ZIP→校验→预览→启用到主页；关闭展示；替换；删除本机副本；位置/缩放重置。仅一个启用模型，Draft1.2追加本机模型列表、后台管理与APP资源库；不做公开模型商店、本机私有模型云同步或伴侣远程推送。

显示首帧即是基本目标。没有动作文件也应正常显示默认姿态；有兼容Idle时可选轻待机，但**待机动画是第二步**，不能成为“没有动作就导入失败”的理由。默认无音频、不申请摄像头/麦克风/悬浮窗权限、不连接聊天AI、不读取伴侣状态驱动表情。

“只要能显示”不等于用PNG代替Live2D。最终必须从真实moc3加载、由Cubism渲染；HTML位置样板仅用明确标记的静态线框占位，绝不作为成功导入证据。

## 2. 模型应该怎样交给APP

### 2.1 支持的首轮格式

只支持普通未加密ZIP，里面是一套兼容所选Cubism SDK的**运行时导出包**，目录结构保持不变：

```text
my-character.zip
└─ Character/
   ├─ Character.model3.json      # 入口及资源引用，必需
   ├─ Character.moc3            # 编译后的模型，必需
   ├─ Character.2048/
   │  └─ texture_00.png         # model3.json引用的全部PNG贴图，必需
   ├─ Character.physics3.json   # 可选，声明并启用时需存在
   ├─ Character.pose3.json      # 可选
   ├─ motions/idle.motion3.json # 可选
   └─ expressions/smile.exp3.json # 可选
```

文件名不要求叫Character，贴图目录也不强制2048；按model3.json的引用读取，不扫描猜测“第一张PNG”。首轮包内只允许一个model3.json入口，多模型包提示用户拆分，不偷偷选第一个。接受外层目录和中文文件名，但校验规范化路径与大小写冲突。

不支持：仅PNG/JPG、PSD、Cubism编辑工程`.cmo3`、旧版Cubism2 `.moc`/`.model.json`、VRM/FBX/Spine、RAR/7z、加密包、只给一个model3.json而没贴图的包。SDK兼容版本以固定Core实际支持能力为准，不能写“所有Live2D模型通吃”。

关于可选资源：未声明即可缺省；如果model3.json声明了资源却缺文件，校验先给出明确错误，用户应修复完整包，首轮不静默生成篡改后的manifest。已有但MVP不使用的声音/动作附属资源可列为未启用；不自动播放声音。纹理或moc3缺失必须阻止启用。

### 2.2 如果只有图片，建模怎么来

APP负责导入和渲染，不负责把平面图自动建模。需要在Live2D Cubism Editor中完成分层原画、网格/变形器、参数与必要绑定，导出运行数据，再把引用资源一起打ZIP。可由模型作者提供成品包；用户不必自己建模。仅会画立绘的AI不等于能交付可用moc3。

给模型作者的简短要求：

> 请交付可在约定稳定版Cubism SDK for Java中显示的运行模型包（model3.json+moc3+全部贴图，原目录），默认姿态可见；无需口型、跟踪或音频。可另附轻待机。请说明导出SDK目标、纹理尺寸和授权范围，至少允许本机APP显示。若要随APK分发，请另行确认模型再分发许可。

## 3. 技术路线：首选官方 Java SDK，复用 Android 原生渲染

已核对项目：Kotlin/Compose/miuix，minSdk33；无现有Cubism集成；现有NeteaseWebLoginActivity只是认证WebView，不复用为角色引擎。官方CubismJavaSamples包含minimum样例、Framework以及单独取得的Core AAR，适合Kotlin互操作。

| 路线 | 本项目取舍 |
| --- | --- |
| **Cubism SDK for Java + AndroidView + GL渲染** | 首选。原生Android集成，代码可从官方minimum样例裁剪；Core AAR包含原生部分，仍须检查ABI和发布构建，不是纯Java就没有native问题 |
| Cubism SDK for Web + 本地WebView | 备选，只有原生方案实测不合适再评估。需离线打包Core/Framework、WebGL与安全资源映射；不要加载远程看板娘网站，不复用网易云Cookie或JS桥 |
| Cubism SDK for Native/C++ | 本需求首轮不选；会引入更多C++/JNI/NDK集成工作 |
| Unity嵌入／全APP网页化 | 不选，体量和架构改变不适合“只显示一个角色” |

### 3.1 接入步骤，接手AI不可省略

1. 从官方发行包选稳定版Java SDK，固定版本和来源；Core、Framework、minimum样例版本配套。不要把develop HEAD或alpha版本当生产默认。
2. 先在独立测试页面运行minimum样例，使用**有许可的测试模型**；确认真实贴图、遮罩、首帧和方向正确，再接自己的导入包。
3. 只迁移模型加载、纹理管理、矩阵、GL renderer所需代码，不迁移样例Activity、签名、旧Gradle、Material组件或`abortOnError=false`。本项目的miuix/JDK/AGP/签名/版本设置继续生效。
4. Core AAR按官方条款放入明确依赖位置或受控构建输入；Framework作为固定版本模块/源引入并保留许可证。先验依赖与Gradle兼容，不假造Maven坐标。
5. 模型导入由Kotlin处理，提供已验证的私有目录与manifest；SDK加载器从该目录读文件。官方样例常从assets加载，必须显式改数据入口，不能把用户ZIP塞进只读APK assets。
6. 用Compose AndroidView托管原生渲染view；模型资源只在modelId变化或GL上下文重建时加载，不在每次recompose重新创建。
7. 渲染线程加载/释放GL纹理，UI线程只派发命令；IO/解压/JSON校验不在主线程。引擎建立、生命周期监听和Dispose都可重入且幂等。
8. 验证release R8/JNI需要的keep规则、arm64实际ABI、SDK原生库页尺寸适配；别为通过Debug关闭整个release压缩。新增AAR体积和实际内存写入报告。

### 3.2 嵌入滚动主页时的特别处理

GLSurfaceView最适合先验证官方样例，但它是独立Surface，不能假定Compose圆角clip、透明、滚动和弹窗遮挡全部自然正确。**禁止用setZOrderOnTop(true)把模型顶到按钮或弹窗前面。**

最终主页嵌入优先评估TextureView＋独立EGL渲染宿主，复用SDK的GL renderer，以适配正常View合成。它需要生命周期/EGL适配，不能把GLSurfaceView强转成TextureView，也不能声称官方样例开箱自带。先做最小滚动/遮罩试验，再决定：

- TextureView方案通过滚动裁切、弹窗层级、主题切换后再接主页。
- 若团队决定保留GLSurfaceView，必须实测同样场景并通过；不通过则不能把“独立页面能显示”当“主页已完成”。
- 测试主页miuix `layerBackdrop`/玻璃采样；不能假定外部GL内容能被采样或应反复截图。必要时只让玻璃采样基础背景；不得每帧readPixels供模糊或重建整屏Bitmap。
- 角色不接管整张卡触摸。MVP只展示，管理按钮由Compose绘制，列表滑动仍正常。若要开角色详情用独立48dp按钮，不把模型画布变成会吞拖动的全区域手势层。

### 3.3 首帧渲染顺序（概念）

```text
验证后的model3.json → 解析引用
    → moc3兼容性与完整性校验 → 创建SDK模型
    → 当前GL上下文创建renderer → 解码并绑定所有贴图
    → 设置模型/视图/投影矩阵、viewport和同色clear
    → 更新默认参数并draw → 输出加载状态
```

执行了draw不等于肉眼可见：若全透明/画布偏移/遮罩错也可能无异常。验收需要真机看见完整角色；必要时提供“重置位置”，但不能把丢贴图导致白模的状态宣布成功。moc3一致性检查必须开启（官方有对应API），它是校验不是恶意文件完全安全的保证。

### 3.4 生命周期与功耗

首轮静态显示使用按需渲染：加载/尺寸/主题/校准变化才请求绘制；表情与动作均默认不运行。以后打开待机时目标上限30fps，帧率由调度器限制，而不是无限requestRender。

只有生命周期在前台、主页当前选中、卡片进入可见区域且未被全屏播放器/遮罩覆盖才渲染。HorizontalPager可能保留邻页，不能只判断Composable存在。NowScreen目前将许多内容放在一个Lazy item的Column，建议把陪伴角拆为带稳定key的独立item，以便可见性判断；若仍嵌套则用实际bounds，不能通过“整大item可见”让角色离屏后继续渲染。

离屏/切tab/后台停止调度；静态MVP无持续ticker。释放时删除纹理、renderer、模型等，按SDK顺序在正确线程执行；应用级Framework不要被一个view关闭误销毁另一个view。本轮同时最多一个渲染实例，主页与预览不能各持有活跃纹理。

上下文丢失／回前台需从已验证私有资源重建，不能继续用旧texture ID；失败只显示角色错误占位，不影响状态同步、音乐或整页返回。内存紧张主动释放角色资源并提示重试，不拿OOM做普通解析分支吞掉。

## 4. 安全导入与本机存储

### 4.1 完整流程

```text
系统文件选择器OpenDocument（ZIP）
 → 流式复制到本APP临时目录并计数
 → 解压/路径/格式/体积校验
 → 找唯一入口与资源引用
 → moc3版本/完整性与纹理预算校验
 → 独立预览并调整位置
 → 用户点“启用角色”
 → 私有版本目录落盘 + 原子更新activeModelId
 → 回主页显示
```

导入取消/失败不覆盖旧模型。替换前停止旧GPU实例，新预览失败时可重新加载旧模型；不同时持有两套大模型纹理追求无缝切换。临时文件清理只针对本次生成目录；不得删除系统选择器原始文件或用户下载目录。

建议目录：`context.noBackupFilesDir/live2d/models/<内部随机id>/`，临时目录放同一私有根的`staging/<id>/`以支持同文件系统激活；不进入相册、不上传服务端、不进入自动云备份。modelId由APP生成，不能直接把ZIP条目当外层目录名。元信息只保存必要名称、相对入口、校准值、文件校验和与导入时间。

### 4.2 接手AI必须实现的校验

- 禁绝Zip Slip：检查规范化绝对目标在本次staging根以内，比较路径边界不是字符串前缀。拒绝绝对路径、盘符、`..`越界、软链接/特殊条目、重复/大小写碰撞条目。
- 限制压缩输入字节、实际解压字节、单文件字节、条目数量和嵌套深度；不能只相信ZIP header大小。解压过程中超限立即停止，失败可恢复。
- manifest所有引用仅可指向模型根内相对文件；拒绝网络URL、file/content/data scheme、编码绕过、缺失贴图与绝对路径。不执行包里的JS/HTML/脚本/可执行文件；首轮可直接拒绝这类可执行内容。
- 用结构化JSON解析，限制JSON大小／嵌套／数组数量；不要eval，也不要把用户文件名拼成JS或shell。
- 解码贴图先查宽高和总像素；按**全套贴图合计**预算，不只逐张检查。GL上传后及时释放CPU位图；不要缩小贴图却不验证模型UV/遮罩质量。
- `.moc3`文件大小、版本与Core一致性均检查；解析前校验不保证native绝不崩，需保持SDK安全更新，测试损坏包。更强隔离属于后续安全加固，不做“任意不可信模型绝对安全”的承诺。

### 4.3 初始资源预算（项目策略，不是SDK官方上限）

建议首轮ZIP≤50MiB，实际解压≤100MiB，文件≤256，路径深度≤8，model3/单份JSON≤2MiB，moc3≤20MiB；PNG单边≤4096且不超过设备GL_MAX_TEXTURE_SIZE，所有贴图合计≤16,777,216像素。测试推荐模型优先2048贴图、总像素≤4,194,304。

16M像素RGBA约64MiB仅是一个像素副本，mipmap如启用还增加约1/3，CPU解码副本／模型缓冲／系统层另计；不能用ZIP的10MB断言内存只10MB。阈值须用一加15与当前音乐/相册共存场景实测再收敛；超限给清晰错误，不能私自把上限无限放开。允许适配更多模型是后续明确决策，不保证所有购买模型无需重新导出。

这些是本机资源防护硬上限，统一收口在Live2DImportPolicy，不新增服务器环境变量。Draft1.2的后台资源库还需独立服务端上传／库存预算；可调参数按AGENTS走runtimeSettingSpecs并贯通读取端，本机取服务端有效限制与本地安全上限的较小者，不允许服务端调高绕过本机硬上限。

## 5. 管理页与错误文案

入口：我的→外观→陪伴角色。使用KernelScreen、BackHandler、BackAction。按顺序显示：主页展示开关→当前模型摘要→固定舞台预览→位置/缩放调节与重置→导入/替换→删除本机副本。

导入/启用蓝，取消灰，删除红且LxConfirmDialog/destructive/1秒延迟。删除文案：“删除APP内的角色副本，不会删除你选择的原始模型包。”处理中使用busy，避免重复操作。静态显示切换不会停止音乐。

| 情况 | 显示与处理 |
| --- | --- |
| 无模型 | “还没有陪伴角色”＋“导入模型包” |
| 导入中 | “正在检查模型包…”；可取消尚未提交的导入任务 |
| 仅cmo3/PNG | “这不是运行模型包，请提供model3.json、moc3和贴图” |
| 缺资源 | “模型缺少贴图：<经过清洗的相对文件名>” |
| 多入口 | “模型包包含多个角色，请拆分后导入” |
| 不兼容 | “模型导出版本暂不支持，请使用兼容版本重新导出” |
| 超预算 | “模型贴图过大，请降低导出贴图尺寸后重试” |
| GL失败／上下文丢失 | “角色暂时无法显示”＋重试／管理角色；不使主页白屏 |
| 无动作 | 仍显示默认姿态，不当成错误 |
| 成功 | “模型已导入”，预览可见；用户启用后主页才展示 |

错误文案中文，日志英文，不打印源文件绝对路径、用户模型内容或伴侣数据。

## 6. 拟态完整性修正：下拉与文档入口

用户指出的是预览工具栏，但同样的控件规范适用于APP/后台自有UI。

### 6.1 状态选择框

本样板只有4个互斥状态，**替换为内凹轨道中的分段选择**，无需再弹系统原生select。浅/深都同色系轨道，选中项为微凸按钮＋勾选标识＋明确文字；触达48，窄屏2×2折行。不使用只改颜色但弹出后还是系统样式的伪完整拟态。

生产有很多选项时：自有触发器48高（内凹输入面）＋miuix选项弹层／Element Plus下拉（浮起面），选项有勾选、焦点、键盘选择、取消；保留原有表单label、disabled/error与屏幕阅读器语义。底层库不支持定制时先报告，不引入裸Material3。

### 6.2 “完整设计说明”入口

改成16圆角、48触达的微凸实体入口，文字深色、可有文件图标；按下时收影，不用裸蓝色下划线，也去掉装饰性的外跳箭头。网页仍保留`<a href>`的导航语义和键盘能力，只是按钮化外观；不是把导航改成无语义div。

为避免打开说明后又落入没有样式的Markdown页面，HTML样板改为弹出同材质“设计资料”面板，各文档用实体导航块打开；原始Markdown是交接文件，不是APP页面。用户只要求APP时，生产无需“设计说明”入口。

所有工具栏、错误重试、分页、分段、弹层、关闭按钮、文件入口都走统一材质；文本内容保持可读。完全拟态化不等于给每一行文字加阴影，系统文件选择器／系统权限框仍由Android绘制，不能承诺改造系统界面。

## 7. 工程落点与交接批次

下列名称是建议新增，不是已有API：

| 文件／位置（Android包根同主稿） | 职责 |
| --- | --- |
| `live2d/Live2DImportPolicy.kt` | 路径、文件、预算、manifest验证，纯逻辑测试 |
| `live2d/Live2DModelRepository.kt` | SAF导入、staging、私有目录、激活/删除 |
| `ui/components/Live2DPreviewHost.kt` | SDK与GL生命周期，单活跃实例；首页与管理页共用 |
| `live2d/Live2DVisibilityPolicy.kt` | 前台/当前tab/可见区域/覆盖层决定调度 |
| `ui/components/Live2DCompanionCard.kt` | 同材质容器＋AndroidView，不含导入IO |
| `ui/screens/CompanionSettingsScreen.kt` | 导入、预览、启用、校准、移除 |
| `ui/screens/NowScreen.kt` | 在PartnerStatusCard之后挂独立陪伴item |
| `ui/screens/AppearanceScreen.kt`、`ui/navigation/LinxiApp.kt` | 新入口与路由/返回，保留其他导航 |
| `android/app/build.gradle.kts`等 | 明确Core AAR与Framework版本、ABI、必要R8；不改签名版本 |
| `docs/design/neumorphic/design-preview.html` | 分段状态选择、拟态文档入口、位置线框；无SDK/真实模型 |

执行分四小步：L0 SDK许可/模型来源与稳定版本确认；L1 独立页真实模型首帧；L2 ZIP验证/本机存储/预览启用；L3 主页位置、离屏暂停、主题和回归。L1没通过不得花大量时间做互动动画。

## 8. 验收用例

- [ ] 合法完整ZIP真实显示；无motion也可显示；替换/重启后重新加载有效模型。
- [ ] 缺model3/moc3/贴图、多个入口、旧moc、过新版本、损坏moc均有明确失败，不破坏旧模型。
- [ ] 路径穿越、软链/重复条目、压缩炸弹、极大贴图、恶意远程引用被拒；失败仅清自己的staging。
- [ ] 启用后在指定位置显示，滚动不穿透卡片、弹窗盖住角色、播放器不被覆盖。
- [ ] 切tab/锁屏/后台停止渲染；GL上下文丢失可恢复；反复进入退出无GPU/线程增长。
- [ ] 黑暗/浅色、320宽、大字、长角色名、不同画布比例、横屏可用；不强裁头脚。
- [ ] 关闭/删除模型不删原始ZIP、不改变音乐/相册/伴侣状态。
- [ ] release APK真机验证，不只Debug；检查ABI/native页尺寸、R8和资源打包。
- [ ] 对新增纯策略测试临时改坏实现，确认红再恢复；ZIP预算要检查实际字节而非只header。
- [ ] 无许可结论前不分发含SDK/模型的新APK，不把官方测试样例默认当可随意再分发素材。

## 9. 授权是发布门槛，不要忽略

SDK Core、Framework和模型素材的许可不是一回事。**“允许用户不断导入自有模型”很可能涉及Live2D的Expandable Applications规则**，这应由Live2D根据本APP实际用途确认；官方该规则明确包含可扩展模型的情形，并不因个人/小规模就自动免除发行审查。不能因为SDK可下载就写“永久免费随意发布”。

首次发行前向Live2D说明：情侣私用/是否公开分发、是否收费、只显示一个本机导入角色、无跟踪/聊天/模型商店，请其确认适用许可。固定内置一个已授权模型是可评估的更小范围备选，但也必须核对SDK发行和模型分发许可；不是规避条款的方法。此处不作法律结论，也不代用户接受条款或购买模型。

## 10. 第一方资料（2026-09-08核对）

- [Cubism SDK for Java](https://docs.live2d.com/en/cubism-sdk-manual/cubism-sdk-for-java/)：Android Java SDK入口。
- [官方Java Samples](https://github.com/Live2D/CubismJavaSamples)：minimum/Framework/Core的职责，Core需另从官方发行包取得。
- [官方样例依赖文件](https://github.com/Live2D/CubismJavaSamples/blob/develop/Sample/build.gradle)：Core AAR集成参考，不复制其旧依赖、签名或lint配置。
- [模型运行数据结构](https://docs.live2d.com/en/cubism-sdk-manual/model/)与[导出嵌入数据](https://docs.live2d.com/en/cubism-editor-manual/export-moc3-motion3-files/)：moc3与model3.json及资源引用。
- [moc3完整性校验](https://docs.live2d.com/en/cubism-sdk-manual/moc3-consistency/)：Java校验入口与兼容注意。
- [SDK发行许可](https://www.live2d.com/en/sdk/license/)与[Expandable Applications](https://www.live2d.com/en/sdk/license/expandable/)：导入扩展模型的发行许可风险。
- [Web备选本地内容加载](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)：若改走Web方案，使用受控本地来源而不是任意file跨域。

本次未取得真实模型、未下载Core、未运行Cubism。因此这是具体可实施的集成方案，不是“已成功显示模型”的报告。
