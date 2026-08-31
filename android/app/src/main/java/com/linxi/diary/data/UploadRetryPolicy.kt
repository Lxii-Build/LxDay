package com.linxi.diary.data

/**
 * 上传失败后「这张值不值得重试」的判定（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么要单独抽出来
 *
 * 这份映射必须与 `server/album_media.go` 的 `codeUpload*` 常量保持一致，
 * 而**判错的方向是不对称的**：
 *
 * - 把可重试的判成不可重试 → 照片永久停在失败列表里，用户只能重新选图。
 *   在批量上传（一次最多 100 张）里这就是管理员反馈过的「照片会消失」。
 * - 把不可重试的判成可重试 → 只是多试一次然后仍然失败，代价小得多。
 *
 * 所以默认值取「可重试」，未知码落在安全那一侧（与 AGENTS 2.21 里
 * 白名单/黑名单默认行为的取舍同一个道理）。
 *
 * 0829 踩到的实例：单账号在飞行上限刚加上时复用了 1020（当日配额用尽），
 * 而 1020 在这里被判为不可重试。结果是"等前面几张传完就能成功"这种
 * 纯瞬时状态被当成了永久失败。服务端因此另开了 1028。
 */
object UploadRetryPolicy {

    // 与服务端 album_media.go 的常量一一对应。
    const val CODE_QUOTA_FULL = 1020      // 当日配额用尽
    const val CODE_TOO_LARGE = 1021       // 超过单张上限
    const val CODE_BAD_FORMAT = 1022      // 魔数不认识
    const val CODE_NO_DECODER = 1023      // 认识容器但无解码器
    const val CODE_CORRUPTED = 1024       // 文件损坏/截断
    const val CODE_TOO_MANY_PX = 1025     // 像素数超上限
    const val CODE_DISK_FAILED = 1026     // 服务端落盘/入库失败
    const val CODE_DISABLED = 1027        // 相册功能被后台关闭
    const val CODE_IN_FLIGHT = 1028       // 该账号同时在传的张数到顶
    const val CODE_RATE_LIMITED = 1012    // 按 IP 限流（服务端通用「过于频繁」码）

    /**
     * 只有「换一张图或换一天才可能成功」的才判不可重试。
     *
     * 判据是**这张图再传一次有没有可能成功**，而不是「服务端是不是回了 4xx」：
     * 429 既可能是当日配额用尽（不可重试）也可能是限流（可重试）。
     */
    fun isRetryable(bizCode: Int): Boolean = when (bizCode) {
        // 图片本身的问题：重试多少次结果都一样。
        CODE_TOO_LARGE, CODE_BAD_FORMAT, CODE_NO_DECODER,
        CODE_CORRUPTED, CODE_TOO_MANY_PX -> false
        // 配额按天算，今天重试没用。
        CODE_QUOTA_FULL -> false
        // 功能被管理员关掉，客户端重试不会让它打开。
        CODE_DISABLED -> false
        // 以下都是瞬时状态，等一下就好。
        CODE_DISK_FAILED, CODE_IN_FLIGHT, CODE_RATE_LIMITED -> true
        // 未知码（含网络异常的 0）默认可重试：见类注释，这一侧的代价小得多。
        else -> true
    }
}
