package com.linxi.diary.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「缩略图是透明的」回归测试（0822）。
 *
 * ## 根因
 *
 * 服务端图片地址是**条件绝对**的：后台 `site.url` 配了给绝对 URL，没配回退相对路径
 * `/media/<id>/thumb`。而 `app_setting` 表无种子数据 → 默认就是相对路径。
 * 客户端此前没有任何补全，Coil 拿到无 scheme 的字符串会当**本机文件**找，
 * 必然失败；`AsyncImage` 未配 error 占位就渲染空白 —— 在毛玻璃卡片上看起来就是透明。
 *
 * ## 为什么「点进大图能看到」曾经误导了排查
 *
 * 大图页优先读 `LocalPhotoIndex` 记的**本机原图 uri**（管理员是上传者本人），
 * 压根没请求服务端。所以"大图正常"完全不能证明网络链路是好的 ——
 * 换伴侣账号看同一张照片会是空白。相册封面走同一条链路，也一起坏。
 */
class MediaUrlPolicyTest {

    /** 生产实际配置：注意带 `/api/v1`，而图片挂在根路径。 */
    private val base = "https://love.lxii.cc/api/v1"

    @Test
    fun `取origin必须丢掉api路径`() {
        // **这是最容易写错的一步**：直接拿 BASE_URL 拼会得到
        // https://love.lxii.cc/api/v1/media/1/thumb → 404
        assertEquals("https://love.lxii.cc", MediaUrlPolicy.originOf(base))
        assertEquals("https://love.lxii.cc", MediaUrlPolicy.originOf("https://love.lxii.cc"))
        assertEquals("https://love.lxii.cc", MediaUrlPolicy.originOf("https://love.lxii.cc/"))
        // 带端口要保留
        assertEquals("http://192.168.1.10:7799", MediaUrlPolicy.originOf("http://192.168.1.10:7799/api/v1"))
        // 解析不出来给空串，调用方据此原样返回
        assertEquals("", MediaUrlPolicy.originOf(""))
        assertEquals("", MediaUrlPolicy.originOf("love.lxii.cc/api/v1"))
    }

    @Test
    fun `相对路径的缩略图地址必须补成绝对URL`() {
        // 服务端 site.url 未配置时的真实返回值（server/album_media_test.go 断言的就是这个形态）
        assertEquals(
            "https://love.lxii.cc/media/123/thumb",
            MediaUrlPolicy.absolutize("/media/123/thumb", base),
        )
        assertEquals(
            "https://love.lxii.cc/media/123/preview",
            MediaUrlPolicy.absolutize("/media/123/preview", base),
        )
        assertEquals(
            "https://love.lxii.cc/media/123",
            MediaUrlPolicy.absolutize("/media/123", base),
        )
        // 头像走的静态上传目录，同样是相对路径
        assertEquals(
            "https://love.lxii.cc/upload/2026/08/22/x.png",
            MediaUrlPolicy.absolutize("/upload/2026/08/22/x.png", base),
        )
    }

    @Test
    fun `补出来的地址不能带api前缀`() {
        // 这条单独立一个断言：拼错成 /api/v1/media/... 是最可能的错法，且照样"看起来像个 URL"
        val out = MediaUrlPolicy.absolutize("/media/1/thumb", base)
        assert(!out.contains("/api/")) { "补全后的图片地址不该带 API 路径前缀，实际=$out" }
        assertEquals("https://love.lxii.cc/media/1/thumb", out)
    }

    @Test
    fun `已经是绝对URL的不能动`() {
        // site.url 配好之后服务端就返回绝对 URL，此时补全必须是无操作
        val abs = "https://love.lxii.cc/media/9/thumb"
        assertEquals(abs, MediaUrlPolicy.absolutize(abs, base))
        assertEquals(
            "http://10.0.0.2:8080/media/9/thumb",
            MediaUrlPolicy.absolutize("http://10.0.0.2:8080/media/9/thumb", base),
        )
    }

    @Test
    fun `本机uri不能动`() {
        // 自己上传的照片走 LocalPhotoIndex 记的 MediaStore uri，
        // 被改写就等于把"读本机原图"这条优化改坏了。
        assertEquals(
            "content://media/external/images/media/1024",
            MediaUrlPolicy.absolutize("content://media/external/images/media/1024", base),
        )
        assertEquals(
            "file:///storage/emulated/0/a.jpg",
            MediaUrlPolicy.absolutize("file:///storage/emulated/0/a.jpg", base),
        )
    }

    @Test
    fun `空地址保持为空`() {
        // 空必须还是空：变成 "https://love.lxii.cc" 会让 UI 去请求域名根、
        // 拿回一个 HTML 页面，反而把"该显示占位图"的判断带偏。
        assertEquals("", MediaUrlPolicy.absolutize("", base))
        assertEquals("", MediaUrlPolicy.absolutize("   ", base))
    }

    @Test
    fun `不以斜杠开头的相对路径也要能补`() {
        assertEquals(
            "https://love.lxii.cc/upload/x.png",
            MediaUrlPolicy.absolutize("upload/x.png", base),
        )
    }

    @Test
    fun `basePath异常时原样返回而不是拼出坏URL`() {
        // 拿不到 origin 时保持原样：至少不比现在更糟，也不会拼出 "null/media/1"
        assertEquals("/media/1/thumb", MediaUrlPolicy.absolutize("/media/1/thumb", ""))
        assertEquals("/media/1/thumb", MediaUrlPolicy.absolutize("/media/1/thumb", "not-a-url"))
    }
}
