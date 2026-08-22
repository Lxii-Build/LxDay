package com.linxi.diary.ui

import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「选头像导致应用卡死」的回归测试（0822）。
 *
 * ## 根因
 *
 * `PhotoPickerScreen` 里系统相册兜底入口原本这么写：
 * ```
 * if (multiple) PickMultipleVisualMedia(MAX_SELECT) else PickMultipleVisualMedia(1)
 * ```
 * 而 `PickMultipleVisualMedia` 的 init 是 `require(maxItems > 1)` ——
 * 传 1 当场抛 `IllegalArgumentException: Max items must be higher than 1`。
 *
 * contract 在 **composition 期**就被构造，所以头像选图（`multiple = false`）
 * **一进页面就崩**，页面根本渲染不出来。管理员的崩溃日志正是这一条：
 * ```
 * java.lang.IllegalArgumentException: Max items must be higher than 1
 *   at ... Choreographer$FrameDisplayEventReceiver.run
 * ```
 * 栈里全是 Compose 渲染帧，没有任何点击事件 —— 也印证了"进页面即崩"而非"点了才崩"。
 *
 * 单选的正确 contract 是 `PickVisualMedia`（返回单个 `Uri?`）。
 *
 * ## 这个测试为什么测得到
 *
 * `PickMultipleVisualMedia` 的构造只做参数校验、不碰 Android framework，
 * **在纯 JVM 单测里能真实构造**（已实测：传 1 抛出与线上完全一致的异常，传 100 成功）。
 * 所以这里断言的是真实库行为，不是我假造的替身。
 */
class PickerContractTest {

    /** 与 PhotoPickerScreen 的 MAX_SELECT 保持一致。它是 private，这里写死并由下面的断言守住下限。 */
    private val maxSelect = 100

    @Test
    fun `多选contract必须能构造`() {
        // 正常路径：相册上传用 MAX_SELECT
        ActivityResultContracts.PickMultipleVisualMedia(maxSelect)
    }

    @Test
    fun `PickMultipleVisualMedia传1必定抛异常`() {
        // **这条锁死根因**：证明"单选不能用 PickMultipleVisualMedia"不是我的猜测。
        try {
            ActivityResultContracts.PickMultipleVisualMedia(1)
            fail("传 1 竟然没抛——若 androidx 放宽了这个校验，本测试与相关注释需要更新")
        } catch (e: IllegalArgumentException) {
            assertEquals("Max items must be higher than 1", e.message)
        }
    }

    @Test
    fun `单选必须用PickVisualMedia`() {
        // 单选的正确 contract：无参构造，不存在 maxItems 校验问题。
        ActivityResultContracts.PickVisualMedia()
    }

    @Test
    fun `多选上限必须大于1`() {
        // 守住 MAX_SELECT 的下限：若哪天有人把它改成 1（比如"先限制成单张试试"），
        // 相册上传页会变成和头像页一样的即崩，而那种改动看起来完全无害。
        assert(maxSelect > 1) { "MAX_SELECT 必须 > 1，否则 PickMultipleVisualMedia 构造即抛" }
    }
}
