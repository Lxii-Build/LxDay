package main

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// ================= 客户端配置下发 =================
//
// 管理员 Q41=B：做一个只读配置接口，客户端启动时拉一次（失败用内置默认值）。
//
// 为什么需要它：
//  1. **上限值对齐**。客户端拦 20MB、服务端也拦 20MB，两处各写一份常量，
//     改一处就不一致——客户端放过了、服务端拒绝，用户白等一趟传完才收到错误。
//  2. **功能开关**。相册出问题时管理员能在后台一键关掉入口，不必等发版。
//     这对生产环境价值最高：出故障到止损之间不再隔着一次 CI + 应用商店。
//
// 刻意**不**下发的东西：
//   - 同步分档间隔：那是客户端 AlarmManager 的排程参数，配置一变要重新调度，
//     容易出"档位卡在某个值"的怪问题（Q41 里我否掉了 C）。
//   - 图片缓存上限：Coil 的 LRU 自己会管，做成可配是过度设计。
//   - 任何密钥：这个接口只要求登录态，不要求绑定关系，故一个字节的敏感信息都不能有。

// handleClientConfig 下发客户端需要的运行参数与功能开关。
//
// 只挂 JWTAuth 不挂 mustPair：未绑定的用户也要能拿到配置
// （绑定页的文案长度上限、注册页的验证码冷却都要用）。
func handleClientConfig(c *gin.Context) {
	s := settingsNow()
	ok(c, gin.H{
		// ---- 上传相关：客户端据此提前拦截，避免白传一趟 ----
		"upload": gin.H{
			"photo_max_bytes":  s.PhotoMaxBytes,
			"photos_per_day":   s.PhotosPerDay,
			"bytes_per_day":    s.UploadBytesPerDay,
			"max_select_count": maxAttachPerCall,
			// 客户端会把一切非这四种的格式转成 JPEG 再传（见 ImagePrepPolicy），
			// 下发这个列表是为了让它知道"哪些可以原样传"。
			"passthrough_mime": []string{"image/jpeg", "image/png", "image/webp", "image/gif"},
		},
		// ---- 功能开关：入口按此隐藏 ----
		"features": gin.H{
			"album":        s.AlbumEnabled,
			"photo_social": s.PhotoSocialEnabled,
			"on_this_day":  s.OnThisDayEnabled,
		},
		// ---- 文本长度上限：与服务端校验保持一致，避免"客户端放过、服务端拒绝" ----
		"limits": gin.H{
			"album_name_len": maxAlbumNameLen,
			"caption_len":    maxCaptionLen,
			"comment_len":    maxCommentLen,
		},
		// ---- 数据保留：客户端在回收站页展示"N 天后自动删除" ----
		"retention": gin.H{
			"recycle_bin_days": s.RecycleBinDays,
		},
		// ---- 互动冷却：客户端据此显示按钮的禁用时长，不必靠猜 ----
		"interaction": gin.H{
			"ring_cooldown_sec":  s.RingCooldownSec,
			"light_cooldown_sec": s.InteractionCooldownSec,
		},
	})
}

// requireAlbumEnabled 相册功能总开关中间件。
//
// 关掉后除「读」以外一律拒绝：读仍放行是为了让用户还能看到/导出已有照片，
// 直接全拒会让人以为照片丢了。
//
// ★ 挂载点在 main.go 的 album 路由组。这个函数曾经**定义了却零挂载**，
// 整整几个版本里「相册功能总开关」都只是个装饰：关掉它只有上传被 handler
// 内部的检查拦下，建相册/改名/挂照片/评论点赞全部照旧可写。
func requireAlbumEnabled() gin.HandlerFunc {
	return func(c *gin.Context) {
		if settingsNow().AlbumEnabled {
			c.Next()
			return
		}
		if c.Request.Method == http.MethodGet {
			c.Next()
			return
		}
		fail(c, http.StatusForbidden, codeUploadDisabled, "相册功能当前已关闭")
		c.Abort()
	}
}

// requireSocialEnabled 评论/点赞开关。
//
// 与相册总开关同样的问题：`album.social_enabled` 此前**服务端零校验**，
// 纯靠客户端隐藏入口。而"隐藏入口"对旧版本 App、以及任何直接调接口的人都无效——
// 管理员关掉它是为了止损（比如出现骚扰性评论），却拦不住真正需要拦的那条路径。
//
// 这里全方法拒绝而不像相册那样放行 GET：评论与点赞的读取入口不在这一组
// （照片详情 handlePhotoByID 里一并返回），所以拒绝写就够了，不影响查看已有内容。
func requireSocialEnabled() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !settingsNow().PhotoSocialEnabled {
			fail(c, http.StatusForbidden, codeUploadDisabled, "评论与点赞功能当前已关闭")
			c.Abort()
			return
		}
		c.Next()
	}
}
