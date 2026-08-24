package com.linxi.diary.data

/**
 * 分页追加时的去重（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么必须去重：OFFSET 分页 + LazyColumn key = 会崩
 *
 * 服务端用的是 `ORDER BY ... LIMIT ? OFFSET ?`，而客户端把 `已加载条数` 当 offset。
 * 这在**列表持续增长**时会错位：
 *
 * ```
 * 拉第 1 页（offset=0, limit=50） → 拿到 [1..50]
 * 此刻新写入 1 条 → 全体下移一位
 * 拉第 2 页（offset=50）         → 服务端第 51 条现在是原来的第 50 条 → 重复！
 * ```
 *
 * 而 `LazyColumn` 的 `items(key = ...)` **要求 key 唯一，重复直接抛
 * `IllegalArgumentException` 崩溃**（0821 那次崩溃就是重复 key，只不过原因是
 * 服务端时间戳全为同一个零值）。
 *
 * 这个场景不是理论上的：
 * - 状态历史每 5 分钟落一条，看「今天」时列表一直在长；傍晚超过 50 条后滚到底必触发，
 *   而滚到底是 `LaunchedEffect` 自动的，用户什么都没做就崩了。
 * - 相册详情里**对方**上传照片同样让列表增长（自己上传走整页重载，所以自测很难发现）。
 *
 * 数据库的唯一约束拦不住这个 —— 每条记录本身都是合法且唯一的，
 * 重复只发生在「客户端拼接出来的那份列表」里。
 *
 * 根治要改成游标分页（`before_ts` / `before_id`），但那要动接口；
 * 追加时去重能完全消除崩溃，且对正确性无损（重复项本就是同一条记录）。
 */
object PagingMerge {

    /**
     * 把 [more] 追加到 [current] 后面，丢掉 key 已存在的项。
     *
     * @param key 取稳定唯一键。状态历史用 `ts`，照片用 `id`。
     * @return 追加后的新列表；若 [more] 全是重复项，返回的内容与 [current] 等价
     */
    fun <T> appendDistinct(current: List<T>, more: List<T>, key: (T) -> Long): List<T> {
        if (more.isEmpty()) return current
        val seen = HashSet<Long>(current.size * 2)
        current.forEach { seen.add(key(it)) }
        val added = more.filter { seen.add(key(it)) }
        if (added.isEmpty()) return current
        return current + added
    }

    /**
     * 本次追加是否**一条新项都没有**。
     *
     * 调用方据此判定「到底了」：若服务端返回满页但全是重复（错位严重时会这样），
     * 不停下来就会无限拉同一页 —— 表现为滚到底后一直转圈、流量白跑。
     */
    fun <T> allDuplicates(current: List<T>, more: List<T>, key: (T) -> Long): Boolean {
        if (more.isEmpty()) return true
        val seen = HashSet<Long>(current.size * 2)
        current.forEach { seen.add(key(it)) }
        return more.none { key(it) !in seen }
    }
}
