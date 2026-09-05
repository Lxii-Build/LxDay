package com.linxi.diary.data

/** 照片选择结果；上限必须显式反馈，不能静默忽略用户点击。 */
enum class SelectionToggleResult { Selected, Deselected, LimitReached }

/**
 * 与 Android Uri 无关的选择策略，方便 JVM 单测覆盖单选、多选和数量上限。
 */
object PhotoSelectionPolicy {
    fun <T> toggle(
        selected: MutableList<T>,
        item: T,
        multiple: Boolean,
        maxItems: Int,
    ): SelectionToggleResult {
        if (selected.remove(item)) return SelectionToggleResult.Deselected
        if (!multiple) {
            selected.clear()
            selected.add(item)
            return SelectionToggleResult.Selected
        }
        if (selected.size >= maxItems) return SelectionToggleResult.LimitReached
        selected.add(item)
        return SelectionToggleResult.Selected
    }
}
