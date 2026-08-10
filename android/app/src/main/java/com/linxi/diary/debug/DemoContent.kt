package com.linxi.diary.debug

import com.linxi.diary.data.DiaryItem
import com.linxi.diary.data.TodoItem

object DemoMode {
    fun shouldUseDemo(enabled: Boolean): Boolean = enabled
}

object DemoContent {
    val todos = listOf(
        TodoItem(-1, 1, 1, 2, "今晚一起看电影", "选一部都喜欢的片子", null, 0, 0),
        TodoItem(-2, 1, 1, 2, "周末采购", "牛奶、水果和零食", null, 0, 0),
        TodoItem(-3, 1, 1, 2, "给对方准备惊喜", "示例数据不会同步", null, 0, 0),
    )

    val diaries = listOf(
        DiaryItem(-1, 1, "我", "第一次约会", "那天的晚风和笑声都记得。", "2026-08-01", emptyList(), 0),
        DiaryItem(-2, 2, "调试伴侣", "周末散步", "沿着熟悉的路慢慢走。", "2026-08-02", emptyList(), 0),
        DiaryItem(-3, 1, "我", "我们的纪念日", "这是一篇带图片占位的示例日记。", "2026-08-03", listOf("demo://image"), 0),
    )
}
