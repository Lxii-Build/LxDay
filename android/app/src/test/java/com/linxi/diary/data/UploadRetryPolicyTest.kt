package com.linxi.diary.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传重试判定的回归测试。
 *
 * 这份映射与 `server/album_media.go` 的 `codeUpload*` 常量是**跨端契约**，
 * 而两边不一致时的表现极具误导性：照片被永久标成失败、用户以为"照片消失了"。
 * 0829 就踩了一次（在飞上限复用了不可重试的 1020）。
 */
class UploadRetryPolicyTest {

    /** 瞬时状态必须可重试 —— 判错这一侧会造成"照片消失"。 */
    @Test
    fun transientFailuresAreRetryable() {
        assertTrue(
            "在飞上限是「等前面几张传完就好」，必须可重试；" +
                "判成不可重试会让批量上传里的照片永久失败",
            UploadRetryPolicy.isRetryable(UploadRetryPolicy.CODE_IN_FLIGHT),
        )
        assertTrue(
            "按 IP 限流过一会儿就恢复，必须可重试",
            UploadRetryPolicy.isRetryable(UploadRetryPolicy.CODE_RATE_LIMITED),
        )
        assertTrue(
            "服务端落盘失败值得重试",
            UploadRetryPolicy.isRetryable(UploadRetryPolicy.CODE_DISK_FAILED),
        )
    }

    /** 图片本身的问题重试无意义，否则只是白耗流量与用户耐心。 */
    @Test
    fun permanentFailuresAreNotRetryable() {
        val permanent = mapOf(
            UploadRetryPolicy.CODE_TOO_LARGE to "超过单张上限",
            UploadRetryPolicy.CODE_BAD_FORMAT to "魔数不认识",
            UploadRetryPolicy.CODE_NO_DECODER to "无解码器",
            UploadRetryPolicy.CODE_CORRUPTED to "文件损坏",
            UploadRetryPolicy.CODE_TOO_MANY_PX to "像素数超限",
            UploadRetryPolicy.CODE_QUOTA_FULL to "当日配额用尽",
            UploadRetryPolicy.CODE_DISABLED to "相册功能被关闭",
        )
        permanent.forEach { (code, why) ->
            assertFalse("$code（$why）重试无意义", UploadRetryPolicy.isRetryable(code))
        }
    }

    /**
     * 在飞上限与当日配额**必须是不同的码**。
     *
     * 这条正是 0829 那个 bug 的直接回归：两者都回 429，但一个是瞬时、
     * 一个是当天无解，共用一个码就必然有一方被判错。
     */
    @Test
    fun inFlightAndQuotaAreDistinctCodes() {
        assertTrue(
            "在飞上限(${UploadRetryPolicy.CODE_IN_FLIGHT})与当日配额" +
                "(${UploadRetryPolicy.CODE_QUOTA_FULL})不能是同一个码：" +
                "前者可重试、后者不可，共用必然判错一方",
            UploadRetryPolicy.CODE_IN_FLIGHT != UploadRetryPolicy.CODE_QUOTA_FULL,
        )
        assertTrue(UploadRetryPolicy.isRetryable(UploadRetryPolicy.CODE_IN_FLIGHT))
        assertFalse(UploadRetryPolicy.isRetryable(UploadRetryPolicy.CODE_QUOTA_FULL))
    }

    /**
     * 未知码默认可重试。
     *
     * 默认值的方向是刻意选的：漏判成可重试只多试一次，
     * 漏判成不可重试会让照片永久失败。服务端将来新增业务码时，
     * 旧版 App 会自动落在安全那一侧。
     */
    @Test
    fun unknownCodesDefaultToRetryable() {
        listOf(0, 1, 500, 1029, 1099, 9999, -1).forEach { code ->
            assertTrue(
                "未知码 $code 应默认可重试（旧版 App 遇到服务端新增码时的安全侧）",
                UploadRetryPolicy.isRetryable(code),
            )
        }
    }
}
