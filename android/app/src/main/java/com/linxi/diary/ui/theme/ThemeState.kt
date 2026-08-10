package com.linxi.diary.ui.theme

import android.content.Context
import android.content.SharedPreferences
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

    // 进程级单例常驻，监听器随单例一起注册且不注销；SharedPreferences 仅持弱引用，故强引用保存在此。
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key == "color_mode" || key.startsWith("appearance_"))) {
            _appearance.value = store.load()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        val next = transform(_appearance.value)
        store.save(next)
        _appearance.value = next
    }

    companion object {
        @Volatile private var instance: ThemeState? = null

        fun get(context: Context): ThemeState = instance ?: synchronized(this) {
            instance ?: ThemeState(
                context.applicationContext.getSharedPreferences("linxi_prefs", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}

@Composable
fun rememberThemeState(): ThemeState {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember { ThemeState.get(context) }
}
