package com.linxi.diary.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.liquid.miuix.FloatingBottomBar
import com.linxi.diary.ui.liquid.miuix.FloatingBottomBarItem
import com.linxi.diary.ui.screens.*
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用导航（轻量状态机）：
 *  未绑定 → BindScreen
 *  已绑定未授权 → PrivacyConsentScreen
 *  已授权 → 底部悬浮液态 Tab 栏（此刻/待办/日记/我的）+ 历史全屏
 *
 * 液态玻璃：页面内容录制进 LayerBackdrop，悬浮 Tab 栏的玻璃「看穿」内容。
 */
private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("now", "此刻", Icons.Default.Favorite),
    TabItem("todo", "待办", Icons.Default.CheckCircle),
    TabItem("diary", "日记", Icons.AutoMirrored.Filled.List),
    TabItem("mine", "我的", Icons.Default.Person)
)

@Composable
fun LinxiApp() {
    // 关键：初值直接按绑定状态判定，避免首帧就组合 MainTabs（液态玻璃底栏），
    // 规避 backdrop AGSL 在未绑定场景的首帧 native 崩溃。
    var screen by remember {
        mutableStateOf(
            when {
                UserPrefs.pairId <= 0 -> Screen.Bind
                !UserPrefs.privacyConsented -> Screen.Consent
                else -> Screen.Main
            }
        )
    }
    var selected by remember { mutableStateOf("now") }
    LaunchedEffect(Unit) { }

    when (screen) {
        Screen.Bind -> BindScreen(onBound = { screen = Screen.Consent })
        Screen.Consent -> PrivacyConsentScreen(onConsented = { screen = Screen.Main })
        Screen.History -> HistoryScreen(onBack = { screen = Screen.Main })
        Screen.Main -> MainTabs(
            selected = selected,
            onSelect = { selected = it },
            onOpenHistory = { screen = Screen.History },
            onOpenBind = { screen = Screen.Bind },
            onOpenConsent = { screen = Screen.Consent }
        )
    }
}

/** 主界面：页面内容 + 悬浮液态 Tab 栏 */
@Composable
private fun MainTabs(
    selected: String,
    onSelect: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBind: () -> Unit,
    onOpenConsent: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val mainState = rememberMainPagerState(pagerState, coroutineScope)
    val selectedIndex = mainState.selectedPage

    // 悬浮栏选中 → 平滑切页（MainPagerState.animateToPage）；页面滑动 → 同步选中
    LaunchedEffect(mainState) {
        snapshotFlow { pagerState.currentPage }.collect { mainState.syncPage() }
    }

    // 液态玻璃：页面背景录制进 LayerBackdrop，Tarbar 玻璃「看穿」内容
    val backdrop = rememberLayerBackdrop()

    Box(
        Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
    ) {
        // 页面内容：HorizontalPager 支持手滑 + 悬浮栏联动
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> NowScreen(onOpenHistory = onOpenHistory, onOpenBind = onOpenBind)
                1 -> TodoScreen()
                2 -> DiaryScreen()
                3 -> SettingsScreen(onOpenConsent = onOpenConsent, onOpenBind = onOpenBind)
            }
        }

        // 悬浮液态底栏（KernelSU 同款 miuix，可在设备运行）
        if (UserPrefs.liquidGlassEnabled != false) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 36.dp, vertical = 12.dp)
            ) {
                FloatingBottomBar(
                    selectedIndex = { selectedIndex },
                    onSelected = { i -> mainState.animateToPage(i) },
                    backdrop = backdrop,
                    tabsCount = tabs.size
                ) {
                    tabs.forEachIndexed { index, t ->
                        FloatingBottomBarItem(onClick = { mainState.animateToPage(index) }) {
                            Icon(
                                t.icon,
                                contentDescription = t.label,
                                tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(bottom = 2.dp)
                                    .size(22.dp)
                            )
                            top.yukonga.miuix.kmp.basic.Text(
                                t.label,
                                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 36.dp, vertical = 12.dp)
            ) {
                NavigationBar(
                    color = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    tabs.forEachIndexed { index, t ->
                        NavigationBarItem(
                            selected = mainState.selectedPage == index,
                            onClick = { mainState.animateToPage(index) },
                            icon = t.icon,
                            label = t.label
                        )
                    }
                }
            }
        }
    }
}

private enum class Screen { Bind, Consent, Main, History }
