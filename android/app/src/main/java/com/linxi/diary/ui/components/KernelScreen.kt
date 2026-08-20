package com.linxi.diary.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.navigation.LocalMainBottomPadding
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 照抄 KernelSU 的页面骨架：
 * Scaffold + BlurredBar 毛玻璃顶栏 + LazyColumn(spacedBy 12dp) + 页面内容录制为玻璃采样源。
 * 各页面（此刻/待办/日记/我的）复用此骨架，只提供 title/actions 与 LazyColumn content。
 */
@Composable
fun KernelScreen(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    enableBlur: Boolean = true,
    bottomPadding: androidx.compose.ui.unit.Dp = 12.dp,
    listState: LazyListState = rememberLazyListState(),
    floatingActionButton: @Composable () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    /**
     * 首次加载态：在列表首位渲染统一的 miuix 加载指示器。
     * 收敛到这里是为了让全 App 的加载动画只有一种观感（管理员要求「统一和待办的一样」），
     * 各页不再各写一份 CircularProgressIndicator。
     */
    loading: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    title = title,
                    color = barColor,
                    navigationIcon = { navigationIcon() },
                    actions = { actions() },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        floatingActionButton = floatingActionButton,
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        val listBox: @Composable () -> Unit = {
            Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = if (header != null) 8.dp else innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + LocalMainBottomPadding.current + bottomPadding
                    ),
                    overscrollEffect = null,
                ) {
                    if (loading) {
                        item(key = "__kernel_loading__") { LoadingRow() }
                    }
                    content()
                }
            }
        }
        Column(Modifier.fillMaxHeight()) {
            if (header != null) {
                Box(Modifier.padding(top = innerPadding.calculateTopPadding(), start = 12.dp, end = 12.dp)) {
                    header()
                }
            }
            if (onRefresh != null) {
                val pullState = rememberPullToRefreshState()
                PullToRefresh(
                    isRefreshing = isRefreshing,
                    pullToRefreshState = pullState,
                    onRefresh = onRefresh,
                    refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新成功"),
                    // 刷新指示器必须让开悬浮的毛玻璃 TopAppBar，否则下拉时被顶栏完全盖住看不见。
                    // miuix 的 RefreshHeader 靠 contentPadding.calculateTopPadding() 下移自己；
                    // 此前写死 0.dp，于是指示器贴在容器 y=0 —— 正好在顶栏底下。
                    // 有 header 时（如待办页搜索框）header 已把内容整体挤到顶栏之下，故只需 6dp 呼吸位。
                    contentPadding = PaddingValues(
                        top = if (header != null) 6.dp else innerPadding.calculateTopPadding() + 6.dp
                    ),
                ) { listBox() }
            } else {
                listBox()
            }
        }
    }
}

/**
 * 全 App 统一的列表加载行：居中的 miuix CircularProgressIndicator。
 * 与待办页原有观感一致（padding 24dp），作为唯一的加载动画样式。
 */
@Composable
fun LoadingRow() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        top.yukonga.miuix.kmp.basic.CircularProgressIndicator(Modifier.padding(24.dp))
    }
}

/** KernelSU 顶栏 actions 的返回/历史按钮辅助 */
@Composable
fun BackAction(onBack: () -> Unit) {
    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
        androidx.compose.material3.Icon(
            MiuixIcons.Back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onBackground
        )
    }
}
