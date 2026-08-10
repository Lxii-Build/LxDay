package com.linxi.diary.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable

/** 外观偏好监听：加载完整 AppearanceSettings，任一外观键变化即刷新。 */
class ThemeState private constructor(
    private val prefs: SharedPreferences
) {
    private val store = AppearanceStore(SharedPrefsAppearance(prefs))
    private val _appearance = mutableStateOf(store.load())
    val appearance: State<AppearanceSettings> = _appearance

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key == "color_mode" || key.startsWith("appearance_"))) {
            _appearance.value = store.load()
        }
    }

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        val next = transform(_appearance.value)
        store.save(next)
        _appearance.value = next
    }

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
