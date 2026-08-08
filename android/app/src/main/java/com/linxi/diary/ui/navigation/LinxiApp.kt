package com.linxi.diary.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.linxi.diary.ui.screens.*
import com.linxi.diary.util.UserPrefs
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem

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
    var screen by remember { mutableStateOf(Screen.Main) }
    var selected by remember { mutableStateOf("now") }

    LaunchedEffect(Unit) {
        screen = when {
            UserPrefs.pairId <= 0 -> Screen.Bind
            !UserPrefs.privacyConsented -> Screen.Consent
            else -> Screen.Main
        }
    }

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
    val selectedIndex = remember(selected) { tabs.indexOfFirst { it.route == selected }.coerceAtLeast(0) }

    Box(Modifier.fillMaxSize()) {
        // 页面内容
        Box(Modifier.fillMaxSize().padding(bottom = 88.dp)) {
            when (selected) {
                "now" -> NowScreen(onOpenHistory = onOpenHistory, onOpenBind = onOpenBind)
                "todo" -> TodoScreen()
                "diary" -> DiaryScreen()
                "mine" -> SettingsScreen(onOpenConsent = onOpenConsent, onOpenBind = onOpenBind)
            }
        }

        // 临时：始终用普通 miuix 导航栏（液态玻璃 backdrop 暂移除，定位闪退）
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 36.dp, vertical = 12.dp)
        ) {
            NavigationBar(
                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceContainer
            ) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = selected == t.route,
                        onClick = { onSelect(t.route) },
                        icon = t.icon,
                        label = t.label
                    )
                }
            }
        }
    }
}

private enum class Screen { Bind, Consent, Main, History }
