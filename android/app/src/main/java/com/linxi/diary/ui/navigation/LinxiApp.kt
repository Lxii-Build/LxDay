package com.linxi.diary.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linxi.diary.data.ProfileRuntime
import com.linxi.diary.data.ApiClient
import com.linxi.diary.data.AuthEvents
import com.linxi.diary.data.ClientRuntimeConfig
import com.linxi.diary.service.StatusForegroundService
import com.linxi.diary.sync.StatusSyncManager
import com.linxi.diary.ui.liquid.miuix.FloatingBottomBar
import com.linxi.diary.ui.liquid.miuix.FloatingBottomBarItem
import com.linxi.diary.ui.screens.AlbumDetailScreen
import com.linxi.diary.ui.screens.AvatarCropScreen
import com.linxi.diary.ui.screens.AlbumListScreen
import com.linxi.diary.ui.screens.BindScreen
import com.linxi.diary.ui.screens.OnThisDayScreen
import com.linxi.diary.ui.screens.PhotoPickerScreen
import com.linxi.diary.ui.screens.PhotoViewerScreen
import com.linxi.diary.ui.screens.RecycleBinScreen
import com.linxi.diary.ui.screens.AboutScreen
import com.linxi.diary.ui.screens.AppearanceScreen
import com.linxi.diary.ui.screens.DiscoverPlaceholderScreen
import com.linxi.diary.ui.screens.DiscoverScreen
import com.linxi.diary.ui.screens.FeatureDisabledScreen
import com.linxi.diary.ui.screens.HistoryScreen
import com.linxi.diary.ui.screens.KeepAliveCheckScreen
import com.linxi.diary.ui.screens.LoginScreen
import com.linxi.diary.ui.screens.NowScreen
import com.linxi.diary.ui.screens.PrivacyConsentDialog
import com.linxi.diary.ui.screens.ProfileEditScreen
import com.linxi.diary.ui.screens.RegisterScreen
import com.linxi.diary.ui.screens.SettingsScreen
import com.linxi.diary.ui.screens.TodoScreen
import com.linxi.diary.ui.screens.UpdateDialog
import com.linxi.diary.ui.screens.UpdateInfo
import com.linxi.diary.util.Logs
import com.linxi.diary.util.UserPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("主页", MiuixIcons.FavoritesFill),
    TabItem("待办", MiuixIcons.Ok),
    TabItem("发现", MiuixIcons.Community),
    TabItem("我的", MiuixIcons.Contacts)
)

private enum class NavigationDirection {
    Forward,
    Back,
}

/**
 * 全局页面转场：所有 Screen 状态都从这里进出，避免某个二级页漏掉返回动画。
 * 进入子页时从右侧推入，返回父页时从左侧回退；不再叠加缩放，避免返回时弹跳。
 */
private fun AnimatedContentTransitionScope<Screen>.globalScreenTransition(
    direction: NavigationDirection,
): ContentTransform {
    val slideDirection =
        if (direction == NavigationDirection.Forward) SlideDirection.Left else SlideDirection.Right
    return ((slideIntoContainer(slideDirection, tween(320)) + fadeIn(tween(180))) togetherWith
        (slideOutOfContainer(slideDirection, tween(320)) + fadeOut(tween(220))))
        .using(SizeTransform(clip = true))
}

@Composable
fun LinxiApp() {
    val context = LocalContext.current.applicationContext
    val albumEnabled = ClientRuntimeConfig.albumEnabled
    val photoSocialEnabled = ClientRuntimeConfig.photoSocialEnabled
    val onThisDayEnabled = ClientRuntimeConfig.onThisDayEnabled
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
    var navigationDirection by remember { mutableStateOf(NavigationDirection.Forward) }
    fun navigate(to: Screen, direction: NavigationDirection = NavigationDirection.Forward) {
        if (screen == to) return
        navigationDirection = direction
        screen = to
    }
    LaunchedEffect(screen) {
        Logs.i("Nav", "screen=$screen pairId=${UserPrefs.pairId} consented=${UserPrefs.privacyConsented}")
    }

    // 相册需要带参导航（相册 id/名称、大图列表与初始下标），而这套导航是手写的
    // `enum + var screen` 状态机，本身不支持传参。此处用一个轻量参数载体补上，
    // 不改动既有的 enum 分发结构（改成 sealed class 或引入 Navigation 组件
    // 会牵动全部页面，风险不成比例）。
    var albumArg by remember { mutableStateOf(0L to "相册") }
    var viewerPhotos by remember { mutableStateOf<List<com.linxi.diary.data.PhotoItem>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    var viewerReturnScreen by remember { mutableStateOf(Screen.AlbumDetail) }
    // 选图结果：选择器页返回后由相册详情页消费。
    var pickedUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    // 选图的目的地：相册上传 or 头像。此前 PhotoPicker 返回后固定回 AlbumDetail，
    // 头像要复用同一个选择器（Q13=C）就必须知道该回哪儿。
    var pickerTarget by remember { mutableStateOf(PickerTarget.Album) }
    // 待裁剪的图与裁剪结果
    var cropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var croppedAvatar by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(Unit) {
        ProfileRuntime.actions.collect { action ->
            if (action.navigateToBind) {
                StatusForegroundService.stop(context)
                navigate(Screen.Bind)
            }
        }
    }
    // 登录失效（任何 API 收到 401：token 失效 / 服务端重建后用户不存在）→ 清空本地会话并回登录页，
    // 修复"用旧 token 不登录直接进去、随后处处报错(500/403)"。
    LaunchedEffect(Unit) {
        AuthEvents.unauthorized.collect {
            UserPrefs.token = null
            UserPrefs.pairId = 0
            UserPrefs.partnerName = ""
            UserPrefs.privacyConsented = false
            UserPrefs.sharingEnabled = false
            StatusSyncManager.disconnect()
            StatusForegroundService.stop(context)
            ProfileRuntime.clearSession()
            navigate(Screen.Login)
        }
    }
    // 启动检查更新 + 会话校验：登录态下先探一次受鉴权接口，旧 token 失效会 401 触发上面的自动登出。
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        if (UserPrefs.token != null) {
            runCatching { ApiClient.pairStatus() }
            runCatching {
                UpdateInfo.fromJson(
                    ApiClient.checkUpdate(
                        com.linxi.diary.BuildConfig.VERSION_CODE,
                        com.linxi.diary.BuildConfig.UPDATE_CHANNEL,
                    ),
                )
            }
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
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    globalScreenTransition(navigationDirection)
                },
                contentKey = { it },
                label = "global-screen-transition",
            ) { target ->
                when (target) {
                Screen.Login -> LoginScreen(
                    onLoggedIn = {
                        // 已绑定则直接进主页（退出登录不解绑），未绑定才进绑定页。
                        mainInitialPage = 0
                        navigate(if (UserPrefs.pairId > 0) Screen.Main else Screen.Bind)
                    },
                    onNavigateRegister = { navigate(Screen.Register) },
                )
                Screen.Register -> RegisterScreen(
                    onRegistered = { navigate(Screen.Bind) },
                    onBack = { navigate(Screen.Login, NavigationDirection.Back) },
                )
                Screen.Bind -> BindScreen(
                    onBound = {
                        mainInitialPage = 0
                        navigate(Screen.Main)
                    },
                    onBack = { navigate(Screen.Login, NavigationDirection.Back) },
                )
                Screen.History -> HistoryScreen(onBack = {
                    mainInitialPage = 3
                    navigate(Screen.Main, NavigationDirection.Back)
                })
                Screen.Appearance -> AppearanceScreen(
                    onBack = {
                        mainInitialPage = 3
                        navigate(Screen.Main, NavigationDirection.Back)
                    },
                )
                Screen.DiscoverAlbum -> if (!albumEnabled) {
                    FeatureDisabledScreen("相册", onBack = { mainInitialPage = 2; navigate(Screen.Main, NavigationDirection.Back) })
                } else {
                    AlbumListScreen(
                        onBack = { mainInitialPage = 2; navigate(Screen.Main, NavigationDirection.Back) },
                        onOpenAlbum = { id, name -> albumArg = id to name; navigate(Screen.AlbumDetail) },
                        onOpenOnThisDay = { navigate(Screen.OnThisDay) },
                        onOpenRecycleBin = { navigate(Screen.RecycleBin) },
                        onThisDayEnabled = onThisDayEnabled,
                    )
                }
                Screen.RecycleBin -> if (!albumEnabled) {
                    FeatureDisabledScreen("回收站", onBack = { mainInitialPage = 2; navigate(Screen.Main, NavigationDirection.Back) })
                } else {
                    RecycleBinScreen(onBack = { navigate(Screen.DiscoverAlbum, NavigationDirection.Back) })
                }
                Screen.AlbumDetail -> if (!albumEnabled) {
                    FeatureDisabledScreen("相册", onBack = { mainInitialPage = 2; navigate(Screen.Main, NavigationDirection.Back) })
                } else {
                    AlbumDetailScreen(
                        albumId = albumArg.first,
                        albumName = albumArg.second,
                        onBack = { navigate(Screen.DiscoverAlbum, NavigationDirection.Back) },
                        onOpenPhoto = { list, index ->
                            viewerPhotos = list
                            viewerIndex = index
                            viewerReturnScreen = Screen.AlbumDetail
                            navigate(Screen.PhotoViewer)
                        },
                        onPickPhotos = {
                            pickerTarget = PickerTarget.Album
                            navigate(Screen.PhotoPicker)
                        },
                        pickedUris = pickedUris,
                        onPickedConsumed = { pickedUris = emptyList() },
                    )
                }
                Screen.PhotoPicker -> PhotoPickerScreen(
                    title = if (pickerTarget == PickerTarget.Avatar) "选择头像" else "选择照片",
                    multiple = pickerTarget == PickerTarget.Album,
                    onBack = {
                        navigate(
                            if (pickerTarget == PickerTarget.Avatar) Screen.ProfileEdit else Screen.AlbumDetail,
                            NavigationDirection.Back,
                        )
                    },
                    onPicked = { uris ->
                        if (pickerTarget == PickerTarget.Avatar) {
                            // 头像是单选：拿第一张进裁剪页
                            cropUri = uris.firstOrNull()
                            navigate(
                                if (cropUri != null) Screen.AvatarCrop else Screen.ProfileEdit,
                                if (cropUri != null) NavigationDirection.Forward else NavigationDirection.Back,
                            )
                        } else {
                            pickedUris = uris
                            navigate(Screen.AlbumDetail, NavigationDirection.Back)
                        }
                    },
                )
                Screen.AvatarCrop -> {
                    val target = cropUri
                    if (target == null) {
                        navigate(Screen.ProfileEdit, NavigationDirection.Back)
                    } else {
                        AvatarCropScreen(
                            uri = target,
                            onCancel = { cropUri = null; navigate(Screen.ProfileEdit, NavigationDirection.Back) },
                            onCropped = { file ->
                                croppedAvatar = file
                                cropUri = null
                                navigate(Screen.ProfileEdit, NavigationDirection.Back)
                            },
                        )
                    }
                }
                Screen.PhotoViewer -> PhotoViewerScreen(
                    photos = viewerPhotos,
                    initialIndex = viewerIndex,
                    onBack = { navigate(viewerReturnScreen, NavigationDirection.Back) },
                    onDeleted = { navigate(viewerReturnScreen, NavigationDirection.Back) },
                    photoSocialEnabled = photoSocialEnabled,
                )
                Screen.OnThisDay -> if (!albumEnabled || !onThisDayEnabled) {
                    FeatureDisabledScreen("这一天", onBack = { mainInitialPage = 2; navigate(Screen.Main, NavigationDirection.Back) })
                } else {
                    OnThisDayScreen(
                        onBack = { navigate(Screen.DiscoverAlbum, NavigationDirection.Back) },
                        onOpenPhoto = { list, index ->
                            viewerPhotos = list
                            viewerIndex = index
                            viewerReturnScreen = Screen.OnThisDay
                            navigate(Screen.PhotoViewer)
                        },
                    )
                }
                Screen.DiscoverListen -> DiscoverPlaceholderScreen("一起听", onBack = { mainInitialPage = 2; navigate(Screen.Main, NavigationDirection.Back) })
                Screen.DiscoverWatch -> DiscoverPlaceholderScreen("一起看", onBack = { mainInitialPage = 2; navigate(Screen.Main, NavigationDirection.Back) })
                Screen.ProfileEdit -> ProfileEditScreen(
                    onBack = { mainInitialPage = 3; navigate(Screen.Main, NavigationDirection.Back) },
                    onPickAvatar = {
                        pickerTarget = PickerTarget.Avatar
                        navigate(Screen.PhotoPicker)
                    },
                    croppedAvatar = croppedAvatar,
                    onCroppedConsumed = { croppedAvatar = null },
                )
                Screen.KeepAliveCheck -> KeepAliveCheckScreen(
                    onBack = { mainInitialPage = 3; navigate(Screen.Main, NavigationDirection.Back) },
                )
                Screen.About -> AboutScreen(
                    onBack = { mainInitialPage = 3; navigate(Screen.Main, NavigationDirection.Back) },
                    onLogout = { navigate(Screen.Login) },
                    onUnbound = { navigate(Screen.Bind) },
                )
                Screen.Main -> MainTabs(
                    initialPage = mainInitialPage,
                    albumEnabled = albumEnabled,
                    onOpenHistory = { navigate(Screen.History) },
                    onOpenBind = { navigate(Screen.Bind) },
                    onOpenAppearance = { navigate(Screen.Appearance) },
                    onOpenAlbum = { navigate(Screen.DiscoverAlbum) },
                    onOpenListen = { navigate(Screen.DiscoverListen) },
                    onOpenWatch = { navigate(Screen.DiscoverWatch) },
                    onOpenProfileEdit = { navigate(Screen.ProfileEdit) },
                    onOpenAbout = { navigate(Screen.About) },
                    onOpenKeepAliveCheck = { navigate(Screen.KeepAliveCheck) },
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
    albumEnabled: Boolean,
    onOpenHistory: () -> Unit,
    onOpenBind: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenWatch: () -> Unit,
    onOpenProfileEdit: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenKeepAliveCheck: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabs.size })
    val mainState = rememberMainPagerState(pagerState)
    val mainFabState = remember { MainFabState() }
    // 知情同意页内 Dialog：只要已绑定(含调试跳过的 demo)且未同意，进入主页即强制弹出；我的页点击弹只读态。
    var forcedConsent by remember {
        mutableStateOf(UserPrefs.pairId > 0 && !UserPrefs.privacyConsented)
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

    // 主 tab 的返回键：非首页先回主页，首页需两秒内再按一次才退出。
    // 此前主界面完全没有 BackHandler（只有二级页有），在任意 tab 按返回就直接退到桌面，
    // 双人状态 App 被误退后前台服务与 WS 连接都会受影响。
    val scope = rememberCoroutineScope()
    var backArmedAt by remember { mutableStateOf(0L) }
    val context = LocalContext.current
    BackHandler {
        if (pagerState.currentPage != 0) {
            scope.launch { pagerState.animateScrollToPage(0) }
            backArmedAt = 0L
            return@BackHandler
        }
        val now = System.currentTimeMillis()
        if (now - backArmedAt < 2000L) {
            (context as? android.app.Activity)?.finish()
        } else {
            backArmedAt = now
            android.widget.Toast
                .makeText(context, "再按一次退出", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    // C2 定期同步：前台每 30s 拉一次 /pair/status（内部按"已绑定且非 demo"门控），
    // 与 WS 推送互补，保证信息（伴侣资料/绑定态/纪念日）及时同步。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            ProfileRuntime.refreshAsync()
        }
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
                        albumEnabled = albumEnabled,
                    )
                    3 -> SettingsScreen(
                        onOpenKeepAliveCheck = onOpenKeepAliveCheck,
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
            // 仿 KernelSU：FAB 随列表滚动做位移隐藏/显示（下滑下移出屏，上滑回位），350ms。
            if (fabAction != null) {
                val fabOffsetY by animateDpAsState(
                    targetValue = if (mainFabState.fabVisible) 0.dp else 120.dp,
                    animationSpec = tween(350),
                    label = "fabOffset",
                )
                FloatingActionButton(
                    onClick = { fabAction.invoke() },
                    modifier = Modifier.offset { IntOffset(0, fabOffsetY.roundToPx()) },
                ) {
                    Icon(MiuixIcons.Add, contentDescription = "添加待办")
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

// 注：Wallpaper 已移除——AppearanceScreen 精简后没有任何入口能到达它，
// 整个壁纸裁剪页与 WallpaperProcessor 都是死代码（决策 Q29）。
private enum class Screen {
    Login, Register, Bind, Main, History, Appearance, ProfileEdit, About,
    DiscoverAlbum, AlbumDetail, PhotoPicker, PhotoViewer, OnThisDay, RecycleBin,
    AvatarCrop, KeepAliveCheck,
    DiscoverListen, DiscoverWatch,
}

/** 选图器的用途。决定单选/多选、标题，以及选完该回哪个页面。 */
private enum class PickerTarget { Album, Avatar }
