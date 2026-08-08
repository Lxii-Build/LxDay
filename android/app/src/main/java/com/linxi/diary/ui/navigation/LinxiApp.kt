package com.linxi.diary.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.linxi.diary.ui.screens.*
import com.linxi.diary.util.UserPrefs

/**
 * 应用导航（轻量状态机，避免引入 navigation-compose 额外依赖）：
 *  未绑定 → BindScreen
 *  已绑定未授权 → PrivacyConsentScreen
 *  已授权 → 底部 4 Tab（此刻/待办/日记/我的）+ 历史全屏
 */
private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("now", "此刻", Icons.Default.Favorite),
    TabItem("todo", "待办", Icons.Default.CheckCircle),
    TabItem("diary", "日记", Icons.Default.MenuBook),
    TabItem("mine", "我的", Icons.Default.Person)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinxiApp() {
    var screen by remember { mutableStateOf(Screen.Main) }
    var selected by remember { mutableStateOf("now") }

    // 首启判定
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
        Screen.Main -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = selected == t.route,
                            onClick = { selected = t.route },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (selected) {
                    "now" -> NowScreen(
                        onOpenHistory = { screen = Screen.History },
                        onOpenBind = { screen = Screen.Bind })
                    "todo" -> TodoScreen()
                    "diary" -> DiaryScreen()
                    "mine" -> SettingsScreen(
                        onOpenConsent = { screen = Screen.Consent },
                        onOpenBind = { screen = Screen.Bind })
                }
            }
        }
    }
}

private enum class Screen { Bind, Consent, Main, History }
