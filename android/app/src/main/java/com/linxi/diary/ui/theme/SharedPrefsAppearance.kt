package com.linxi.diary.ui.theme

import android.content.SharedPreferences

/** AppearancePrefs 的 SharedPreferences 实现，供生产环境持久化外观状态。 */
class SharedPrefsAppearance(private val sp: SharedPreferences) : AppearancePrefs {
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun getInt(key: String, default: Int): Int = sp.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = sp.getBoolean(key, default)
    override fun hasKey(key: String): Boolean = sp.contains(key)

    override fun edit(mutate: AppearancePrefsEditor.() -> Unit) {
        val editor = sp.edit()
        object : AppearancePrefsEditor {
            override fun putString(key: String, value: String?) { editor.putString(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
        }.mutate()
        editor.apply()
    }
}
