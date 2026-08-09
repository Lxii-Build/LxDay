# Android UI 实现索引

## 主题

- 实现：`android/app/src/main/java/com/linxi/diary/ui/theme/Theme.kt`
- 偏好监听：`ui/theme/ThemeState.kt`
- 入口：`MainActivity.AppTheme()`

主题模式只有三种：

| `color_mode` | 模式 |
|---:|---|
| 0 | 跟随系统 |
| 1 | 浅色 |
| 2 | 深色 |

旧的 AMOLED 值 `3` 在读取时按深色兼容。主题固定使用 `LinxiSeedPink`，不提供动态取色或 AMOLED 设置项。

## 主 Tab 与悬浮玻璃

- 主装配：`ui/navigation/LinxiApp.kt`
- Pager 状态：`ui/navigation/MainPagerState.kt`
- 页面底部 Padding Local：`ui/navigation/MainLayout.kt`
- 悬浮栏：`ui/liquid/miuix/FloatingBottomBar.kt`
- 液态效果：`ui/liquid/miuix/{Lens,Vibrancy,InnerShadow,CombinedBackdrop,InteractiveHighlight,DampedDragAnimation,DragGestureInspector}.kt`

主界面固定使用 KernelSU 同款完整开启态：

1. `HorizontalPager` 写入 `LayerBackdrop`，作为玻璃采样源。
2. `FloatingBottomBar` 使用系统 `WindowInsets.navigationBars` 进行底部避让。
3. 每个 Tab 使用 76dp 最小宽度，使用 miuix `Icon` 与 `Text` 接收选中态 `LocalContentColor`。
4. 玻璃层、复制层、选中胶囊、拖动和高光均来自同一套 miuix/KernelSU 代码。

Tab 业务映射：此刻 → `NowScreen`、待办 → `TodoScreen`、日记 → `DiaryScreen`、我的 → `SettingsScreen`。

## 页面骨架

`ui/components/KernelScreen.kt` 统一提供：Miuix Scaffold、模糊顶栏、LazyColumn、过滚动、滚动结束触感反馈和底栏安全距离。

## 真机验收

- 启动默认显示悬浮玻璃底栏。
- 底栏没有固定 36dp 边距；底部距离随系统导航栏 Insets 变化。
- 四项 Tab 宽度、文字大小、选中胶囊和拖动效果与 KernelSU 开启态一致。
- 在三种主题模式下，系统栏图标和 Miuix 颜色保持可读。
