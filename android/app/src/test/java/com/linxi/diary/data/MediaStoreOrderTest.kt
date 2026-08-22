package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「图片消失、扫不到」的回归测试。
 *
 * 根因（0821 查明）：旧实现按 `DATE_TAKEN DESC` 排序 + 写死 2000 条上限。
 * `DATE_TAKEN` 来自 EXIF，**只有相机直出才有**——截图、微信保存、下载的图普遍是 NULL。
 * SQLite 里 NULL 在 DESC 排序中排最后，于是这些图全被挤到队尾，
 * 一旦总图数超过 2000 就被整批截断，用户一张都看不到。
 * 管理员手机图片数 >2000（Q57=A），完全命中。
 *
 * 测的是 [MediaSortPolicy] 这层纯策略——真实 MediaStore 查询需要 Android 运行时，
 * 但排序规则本身是纯逻辑，把它抽出来才测得到（`Uri.parse` 在普通单测里不可用）。
 */
class MediaStoreOrderTest {

    /** 测试用的轻量条目：只有排序需要的字段。 */
    private data class Entry(val id: Long, val ts: Long, val bucket: String)

    private fun sortLikeQuery(items: List<Entry>): List<Entry> =
        items.sortedWith { a, b -> MediaSortPolicy.compare(a.ts, a.id, b.ts, b.id) }

    @Test
    fun `DATE_TAKEN缺失时必须回退DATE_ADDED`() {
        // 相机直出：有 EXIF 拍摄时间
        assertEquals(1_787_000_000_000L, MediaSortPolicy.effectiveTimestamp(1_787_000_000_000L, 0))
        // 截图/微信图/下载图：EXIF 缺失，回退入库时间（秒 → 毫秒）
        assertEquals(1_787_000_000_000L, MediaSortPolicy.effectiveTimestamp(0, 1_787_000_000L))
        // 两者都缺：归 0，UI 侧会归入「更早」分组
        assertEquals(0L, MediaSortPolicy.effectiveTimestamp(0, 0))
        // **关键**：回退后不能是 0，否则又会沉底被截断
        assertTrue(
            "无 EXIF 的图回退后时间戳必须 > 0，否则会重现『截图全部沉底』",
            MediaSortPolicy.effectiveTimestamp(0, 1_787_000_000L) > 0,
        )
    }

    @Test
    fun `无EXIF的新图不应沉到有EXIF的老图后面`() {
        val now = 1_787_000_000_000L
        // 3 张相机图（30/60/90 天前，有 EXIF）
        val cameraOld = listOf(
            Entry(1, now - 30L * 86_400_000, "Camera"),
            Entry(2, now - 60L * 86_400_000, "Camera"),
            Entry(3, now - 90L * 86_400_000, "Camera"),
        )
        // 3 张截图（1~3 小时前，EXIF 缺失但已回退 DATE_ADDED）
        val screenshotsNew = listOf(
            Entry(101, MediaSortPolicy.effectiveTimestamp(0, (now - 3_600_000) / 1000), "Screenshots"),
            Entry(102, MediaSortPolicy.effectiveTimestamp(0, (now - 7_200_000) / 1000), "Screenshots"),
            Entry(103, MediaSortPolicy.effectiveTimestamp(0, (now - 10_800_000) / 1000), "Screenshots"),
        )
        val sorted = sortLikeQuery(cameraOld + screenshotsNew)
        val topThree = sorted.take(3).map { it.bucket }
        assertTrue(
            "最近的截图应排在最前，实际顺序=${sorted.map { it.bucket }}",
            topThree.all { it == "Screenshots" },
        )
    }

    @Test
    fun `分页不应把无EXIF的新图切掉`() {
        val now = 1_787_000_000_000L
        // 2000 张有 EXIF 的老图
        val many = (1..2000).map { Entry(it.toLong(), now - (it + 100).toLong() * 3_600_000, "Camera") }
        // 5 张刚截的图（EXIF 缺失，已回退）
        val screenshots = (1..5).map {
            Entry(
                9000L + it,
                MediaSortPolicy.effectiveTimestamp(0, (now - it.toLong() * 60_000) / 1000),
                "Screenshots",
            )
        }
        val sorted = sortLikeQuery(many + screenshots)
        val firstPage = sorted.take(MediaStoreImages.PAGE_SIZE)
        assertEquals(
            "5 张最新截图应全部出现在第一页（旧实现会把它们排到 2000 名之后被 limit 截断）",
            5, firstPage.count { it.bucket == "Screenshots" },
        )
    }

    @Test
    fun `同一时刻的条目排序必须稳定`() {
        // 同秒入库的多张图（批量保存时常见）：必须有确定的次序，
        // 否则翻页时同一张可能出现两次或被跳过。
        val ts = 1_787_000_000_000L
        val items = listOf(Entry(5, ts, "a"), Entry(9, ts, "b"), Entry(1, ts, "c"))
        val first = sortLikeQuery(items).map { it.id }
        val second = sortLikeQuery(items.reversed()).map { it.id }
        assertEquals("同时间戳应按 id 降序且顺序稳定", listOf(9L, 5L, 1L), first)
        assertEquals("换输入顺序结果必须一致", first, second)
    }

    @Test
    fun `分页切片边界`() {
        assertEquals(0 until 200, MediaSortPolicy.pageRange(1000, 0, 200))
        assertEquals(800 until 1000, MediaSortPolicy.pageRange(1000, 800, 200))
        // 末页不足一页
        assertEquals(900 until 1000, MediaSortPolicy.pageRange(1000, 900, 200))
        // 越界返回 null（调用方据此判定到底）
        assertNull(MediaSortPolicy.pageRange(1000, 1000, 200))
        assertNull(MediaSortPolicy.pageRange(0, 0, 200))
    }

    @Test
    fun `分页大小应足以流畅加载且不过大`() {
        assertTrue("分页过小会频繁请求", MediaStoreImages.PAGE_SIZE >= 100)
        assertTrue("分页过大会拖慢首屏", MediaStoreImages.PAGE_SIZE <= 500)
    }

    @Test
    fun `月份标签与缺失时间戳处理`() {
        assertEquals("更早", MediaStoreImages.monthLabelOf(0))
        assertEquals("更早", MediaStoreImages.monthLabelOf(-1))
        val label = MediaStoreImages.monthLabelOf(1_787_000_000_000L)
        assertTrue("月份标签格式异常：$label", label.contains("年") && label.contains("月"))
    }

    @Test
    fun `桶名翻译覆盖常见目录`() {
        assertEquals("全部", MediaStoreImages.bucketLabel(MediaStoreImages.BUCKET_ALL))
        assertEquals("相机", MediaStoreImages.bucketLabel("Camera"))
        assertEquals("相机", MediaStoreImages.bucketLabel("DCIM"))
        assertEquals("截屏", MediaStoreImages.bucketLabel("Screenshots"))
        assertEquals("下载", MediaStoreImages.bucketLabel("Download"))
        assertEquals("微信", MediaStoreImages.bucketLabel("WeiXin"))
        assertEquals("QQ", MediaStoreImages.bucketLabel("QQ_Images"))
        // 未知目录原样返回，不能吞掉
        assertEquals("MyAlbum", MediaStoreImages.bucketLabel("MyAlbum"))
    }
}

/**
 * 行 URI 形式的回归测试。
 *
 * 0821 把查询改成按卷遍历（为了覆盖 SD 卡）后，行 uri 也跟着变成按卷形式
 * `content://media/external_primary/images/media/<id>`，与之前的规范形式
 * `content://media/external/images/media/<id>` 不同。
 *
 * **为什么形态必须稳定**：uri 是被持久化的 —— `LocalPhotoIndex` 存
 * 「服务端 photoId → 本机原图 uri」，选择器也用 uri 判选中态。
 * 同一张图在不同版本给出不同 uri，老索引就全部失配，
 * 「自己传的照片直接读本机原图」（管理员 Q24 的方案）会静默退化成每次都走网络。
 */
class MediaUriPolicyTest {

    @Test
    fun `主卷用规范形式`() {
        assertTrue(
            "external_primary 应转规范形式，与 0821 之前保持一致",
            MediaUriPolicy.shouldUseCanonical("external_primary"),
        )
        assertTrue(MediaUriPolicy.shouldUseCanonical("external"))
    }

    @Test
    fun `取不到卷名时按主卷处理`() {
        // getVolumeName 可能抛异常（老版本/异常 uri），此时按主卷处理：
        // 绝大多数设备只有主卷。
        assertTrue(MediaUriPolicy.shouldUseCanonical(null))
    }

    @Test
    fun `SD卡等其它卷保留自身形式`() {
        // 规范形式定位不到 SD 卡上的行
        assertTrue(!MediaUriPolicy.shouldUseCanonical("1234-5678"))
        assertTrue(!MediaUriPolicy.shouldUseCanonical("sdcard1"))
    }
}
