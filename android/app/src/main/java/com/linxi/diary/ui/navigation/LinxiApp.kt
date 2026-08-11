package com.linxi.diary.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Explore
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.ProfileRefreshAction
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.data.ApiClient
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.ui.liquid.miuix.FloatingBottomBar
import com.linxi.diary.ui.liquid.miuix.FloatingBottomBarItem
import com.linxi.diary.ui.screens.BindScreen
import com.linxi.diary.ui.screens.AboutScreen
import com.linxi.diary.ui.screens.AppearanceScreen
import com.linxi.diary.ui.screens.DiscoverPlaceholderScreen
import com.linxi.diary.ui.screens.DiscoverScreen
import com.linxi.diary.ui.screens.HistoryScreen
import com.linxi.diary.ui.screens.LoginScreen
import com.linxi.diary.ui.screens.NowScreen
import com.linxi.diary.ui.screens.PrivacyConsentDialog
import com.linxi.diary.ui.screens.ProfileEditScreen
import com.linxi.diary.ui.screens.RegisterScreen
import com.linxi.diary.ui.screens.SettingsScreen
import com.linxi.diary.ui.screens.TodoScreen
import com.linxi.diary.ui.screens.UpdateDialog
import com.linxi.diary.ui.screens.UpdateInfo
import com.linxi.diary.ui.screens.WallpaperScreen
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
    TabItem("发现", Icons.Rounded.Explore),
    TabItem("我的", Icons.Rounded.Person)
)

@Composable
fun LinxiApp() {
    val context = LocalContext.current.applicationContext
    var mainInitialPage by remember { mutableStateOf(0) }
    var screen by remember {
        mutableStateOf(
            when {
                UserPrefs.token == null -> Screen.Login
                UserPrefs.pairId <= 0 -> Screen.Bind
                else -> Screen.Main
            }
        )
    }
    LaunchedEffect(screen) {
        Logs.i("Nav", "screen=$screen pairId=${UserPrefs.pairId} consented=${UserPrefs.privacyConsented}")
    }
    LaunchedEffect(Unit) {
        ProfileRuntime.actions.collect { action ->
            if (action.navigateToBind) {
                StatusForegroundService.stop(context)
                screen = Screen.Bind
            }
        }
    }
    // 启动检查更新：登录态下静默拉取，有新版则弹提示。
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        if (UserPrefs.token != null) {
            runCatching { UpdateInfo.fromJson(ApiClient.checkUpdate(com.linxi.diary.BuildConfig.VERSION_CODE)) }
                .onSuccess { if (it.hasUpdate) pendingUpdate = it }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { rootPadding ->
        Box(Modifier.fillMaxSize().padding(rootPadding)) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInHorizontally(tween(320)) { it / 10 }) togetherWith
                        fadeOut(tween(180))
                },
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.Login -> LoginScreen(
                    onLoggedIn = { screen = Screen.Bind },
                    onNavigateRegister = { screen = Screen.Register },
                    onSkipDebug = { screen = Screen.Bind },
                )
                Screen.Register -> RegisterScreen(
                    onRegistered = { screen = Screen.Bind },
                    onBack = { screen = Screen.Login },
                )
                Screen.Bind -> BindScreen(onBound = {
                    mainInitialPage = 0
                    screen = Screen.Main
                })
                Screen.History -> HistoryScreen(onBack = {
                    mainInitialPage = 3
                    screen = Screen.Main
                })
                Screen.Appearance -> AppearanceScreen(
                    onBack = {
                        mainInitialPage = 3
                        screen = Screen.Main
                    },
                )
                Screen.Wallpaper -> WallpaperScreen(onBack = { screen = Screen.Appearance })
                Screen.DiscoverAlbum -> DiscoverPlaceholderScreen("相册", onBack = { mainInitialPage = 2; screen = Screen.Main })
                Screen.DiscoverListen -> DiscoverPlaceholderScreen("一起听", onBack = { mainInitialPage = 2; screen = Screen.Main })
                Screen.DiscoverWatch -> DiscoverPlaceholderScreen("一起看", onBack = { mainInitialPage = 2; screen = Screen.Main })
                Screen.ProfileEdit -> ProfileEditScreen(onBack = { mainInitialPage = 3; screen = Screen.Main })
                Screen.About -> AboutScreen(
                    onBack = { mainInitialPage = 3; screen = Screen.Main },
                    onLogout = { screen = Screen.Login },
                )
                Screen.Main -> MainTabs(
                    initialPage = mainInitialPage,
                    onOpenHistory = { screen = Screen.History },
                    onOpenBind = { screen = Screen.Bind },
                    onOpenAppearance = { screen = Screen.Appearance },
                    onOpenAlbum = { screen = Screen.DiscoverAlbum },
                    onOpenListen = { screen = Screen.DiscoverListen },
                    onOpenWatch = { screen = Screen.DiscoverWatch },
                    onOpenProfileEdit = { screen = Screen.ProfileEdit },
                    onOpenAbout = { screen = Screen.About },
                )
                }
            }
            pendingUpdate?.let { info -> UpdateDialog(info) { pendingUpdate = null } }
        }
    }
}

/** KernelSU 完整悬浮玻璃开启态，只替换林曦日记页面与 Tab 业务。 */
@Composable
private fun MainTabs(
    initialPage: Int,
    onOpenHistory: () -> Unit,
    onOpenBind: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenWatch: () -> Unit,
    onOpenProfileEdit: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabs.size })
    val mainState = rememberMainPagerState(pagerState)
    val mainFabState = remember { MainFabState() }
    // 知情同意页内 Dialog：绑定后强制弹出（可关闭），我的页点击弹只读态。
    var forcedConsent by remember {
        mutableStateOf(UserPrefs.pairId > 0 && !UserPrefs.demoMode && !UserPrefs.privacyConsented)
    }
    var reviewConsent by remember { mutableStateOf(false) }
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
                    2 -> DiscoverScreen(
                        onOpenAlbum = onOpenAlbum,
                        onOpenListen = onOpenListen,
                        onOpenWatch = onOpenWatch,
                    )
                    3 -> SettingsScreen(
                        onOpenConsent = { reviewConsent = true },
                        onOpenBind = onOpenBind,
                        onOpenHistory = onOpenHistory,
                        onOpenAppearance = onOpenAppearance,
                        onOpenProfileEdit = onOpenProfileEdit,
                        onOpenAbout = onOpenAbout
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
            AnimatedVisibility(
                visible = fabAction != null && mainFabState.fabVisible,
                enter = fadeIn() + scaleIn(initialScale = 0.7f),
                exit = fadeOut() + scaleOut(targetScale = 0.7f),
            ) {
                FloatingActionButton(onClick = { fabAction?.invoke() }) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加待办")
                }
            }
        }
    ) { innerPadding ->
        pagerContent(innerPadding.calculateBottomPadding())
    }

    PrivacyConsentDialog(
        show = forcedConsent || reviewConsent,
        reviewMode = !forcedConsent && reviewConsent,
        onConsented = { forcedConsent = false; reviewConsent = false },
        onDismiss = { forcedConsent = false; reviewConsent = false },
    )
}

private enum class Screen { Login, Register, Bind, Main, History, Appearance, Wallpaper, ProfileEdit, About, DiscoverAlbum, DiscoverListen, DiscoverWatch }
