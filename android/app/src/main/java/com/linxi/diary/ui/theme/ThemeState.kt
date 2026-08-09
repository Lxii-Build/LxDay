package com.linxi.diary.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable

/** 主题偏好监听：仅 color_mode，支持旧 AMOLED 值迁移为深色。 */
class ThemeState private constructor(
    private val prefs: SharedPreferences
) {
    private val _appSettings = mutableStateOf(readSettings())
    val appSettings: State<AppSettings> = _appSettings

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "color_mode") _appSettings.value = readSettings()
    }

    private fun readSettings() = AppSettings(
        colorMode = ColorMode.fromValue(prefs.getInt("color_mode", 0))
    )

    fun startListening() = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun stopListening() = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        @Volatile private var instance: ThemeState? = null

        fun get(context: Context): ThemeState = instance ?: synchronized(this) {
            instance ?: ThemeState(
                context.getSharedPreferences("linxi_prefs", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}

@Composable
fun rememberThemeState(): ThemeState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { ThemeState.get(context) }
    DisposableEffect(state) {
        state.startListening()
        onDispose { state.stopListening() }
    }
    return state
}
