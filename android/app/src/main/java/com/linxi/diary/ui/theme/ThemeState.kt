package com.linxi.diary.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * 主题状态：从 SharedPreferences 读取 AppSettings，监听变化即时更新。
 * 与 UserPrefs 解耦：这里封装「主题专属」的读取/监听，供 Compose 观察。
 */
class ThemeState private constructor(
    private val prefs: SharedPreferences
) {
    private val _appSettings = mutableStateOf(readSettings())
    val appSettings: State<AppSettings> = _appSettings

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "color_mode" || key == "key_color") {
            _appSettings.value = readSettings()
        }
    }

    private fun readSettings(): AppSettings = AppSettings(
        colorMode = ColorMode.fromValue(prefs.getInt("color_mode", 0)),
        keyColor = prefs.getInt("key_color", 0),
        paletteStyle = PaletteStyle.TonalSpot,
        colorSpec = ColorSpec.SpecVersion.SPEC_2025,
    )

    fun startListening() {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopListening() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        @Volatile
        private var instance: ThemeState? = null

        fun get(context: Context): ThemeState =
            instance ?: synchronized(this) {
                instance ?: ThemeState(
                    context.getSharedPreferences("linxi_prefs", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}

/** Composable 取当前主题状态（单例，无需 remember） */
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
