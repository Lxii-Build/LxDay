package com.linxi.diary.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.ui.liquid.miuix.FloatingBottomBar
import com.linxi.diary.ui.liquid.miuix.FloatingBottomBarItem
import com.linxi.diary.ui.screens.BindScreen
import com.linxi.diary.ui.screens.DiaryScreen
import com.linxi.diary.ui.screens.HistoryScreen
import com.linxi.diary.ui.screens.NowScreen
import com.linxi.diary.ui.screens.PrivacyConsentScreen
import com.linxi.diary.ui.screens.SettingsScreen
import com.linxi.diary.ui.screens.TodoScreen
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("主页", Icons.Rounded.Favorite),
    TabItem("待办", Icons.Rounded.CheckCircle),
    TabItem("日记", Icons.AutoMirrored.Rounded.Article),
    TabItem("我的", Icons.Rounded.Person)
)

@Composable
fun LinxiApp() {
    var mainInitialPage by remember { mutableStateOf(0) }
    var screen by remember {
        mutableStateOf(
            when {
                UserPrefs.pairId <= 0 -> Screen.Bind
                !UserPrefs.privacyConsented -> Screen.Consent
                else -> Screen.Main
            }
        )
    }
    LaunchedEffect(screen) {
        Logs.i("Nav", "screen=$screen pairId=${UserPrefs.pairId} consented=${UserPrefs.privacyConsented}")
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { rootPadding ->
        Box(Modifier.fillMaxSize().padding(rootPadding)) {
            when (screen) {
                Screen.Bind -> BindScreen(onBound = { screen = Screen.Consent })
                Screen.Consent -> PrivacyConsentScreen(
                    onConsented = {
                        mainInitialPage = 0
                        screen = Screen.Main
                    }
                )
                Screen.ConsentReview -> PrivacyConsentScreen(
                    reviewMode = true,
                    onConsented = {},
                    onBack = {
                        mainInitialPage = 3
                        screen = Screen.Main
                    },
                )
                Screen.History -> HistoryScreen(onBack = {
                    mainInitialPage = 3
                    screen = Screen.Main
                })
                Screen.Main -> MainTabs(
                    initialPage = mainInitialPage,
                    onOpenHistory = { screen = Screen.History },
                    onOpenBind = { screen = Screen.Bind },
                    onOpenConsent = { screen = Screen.ConsentReview },
                    onLogout = { screen = Screen.Bind }
                )
            }
        }
    }
}

/** KernelSU 完整悬浮玻璃开启态，只替换林曦日记页面与 Tab 业务。 */
@Composable
private fun MainTabs(
    initialPage: Int,
    onOpenHistory: () -> Unit,
    onOpenBind: () -> Unit,
    onOpenConsent: () -> Unit,
    onLogout: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabs.size })
    val mainState = rememberMainPagerState(pagerState)
    val mainFabState = remember { MainFabState() }
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    LaunchedEffect(pagerState.currentPage) {
        mainState.syncPage()
    }

    val pagerContent: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { bottomInnerPadding ->
        CompositionLocalProvider(
            LocalMainBottomPadding provides bottomInnerPadding,
            LocalMainFabState provides mainFabState,
        ) {
            HorizontalPager(
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                state = pagerState
            ) { page ->
                when (page) {
                    0 -> NowScreen(onOpenBind = onOpenBind)
                    1 -> TodoScreen()
                    2 -> DiaryScreen()
                    3 -> SettingsScreen(
                        onOpenConsent = onOpenConsent,
                        onOpenBind = onOpenBind,
                        onOpenHistory = onOpenHistory,
                        onLogout = onLogout
                    )
                }
            }
        }
    }

    val bottomBar = @Composable {
        Box(Modifier.fillMaxWidth()) {
            FloatingBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(
                        bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                selectedIndex = { mainState.selectedPage },
                onSelected = mainState::animateToPage,
                backdrop = backdrop,
                tabsCount = tabs.size,
                isBlurEnabled = true
            ) {
                tabs.forEachIndexed { index, item ->
                    FloatingBottomBarItem(
                        onClick = { mainState.animateToPage(index) },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                    ) {
                        Icon(imageVector = item.icon, contentDescription = item.label)
                        Text(text = item.label, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    val fabDestination = MainFabDestination.forPage(mainState.selectedPage)
    val fabAction = mainFabState.actionFor(fabDestination)
    Scaffold(
        bottomBar = bottomBar,
        floatingActionButton = {
            if (fabAction != null) {
                FloatingActionButton(onClick = fabAction) {
                    Icon(Icons.Rounded.Add, contentDescription = if (fabDestination == MainFabDestination.Todo) "添加待办" else "发布日记")
                }
            }
        }
    ) { innerPadding ->
        pagerContent(innerPadding.calculateBottomPadding())
    }
}

private enum class Screen { Bind, Consent, ConsentReview, Main, History }
