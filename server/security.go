package main

import (
	"errors"
	"net/url"
	"strings"
	"unicode"

	"github.com/gin-gonic/gin"
)

// ================= 安全加固（响应头 / 可信代理 / 口令强度 / URL 白名单） =================

// SecurityHeaders 全局安全响应头中间件。
// 不下发这些头的后果：
//   - 缺 nosniff：上传目录里的文本被浏览器嗅探成 HTML/JS 执行 → 同源存储型 XSS；
//   - 缺 X-Frame-Options：后台被第三方页面 iframe 套壳做点击劫持（诱导超管点「删除管理员」）；
//   - 缺 Referrer-Policy：后台/日记页跳外链时把带图片路径的完整 URL 塞进 Referer 头，
//     情侣私密照片 URL 因此泄露给任意第三方站点（本项目图片仅靠随机文件名保密，URL 泄露=照片泄露）。
func SecurityHeaders() gin.HandlerFunc {
	// CSP 说明：后台是 Vue3 打包产物，运行时会注入 inline <style>，
	// 因此 style-src 必须保留 'unsafe-inline'，否则后台样式全废。
	// script 不给 'unsafe-inline'（打包产物是外链 js，无需 inline 脚本）。
	// connect-src 放开 ws/wss：/ws 与后台同域，需允许 WebSocket 握手。
	const csp = "default-src 'self'; " +
		"script-src 'self'; " +
		"style-src 'self' 'unsafe-inline'; " +
		"img-src 'self' data: blob:; " +
		"font-src 'self' data:; " +
		"connect-src 'self' ws: wss:; " +
		"object-src 'none'; " +
		"base-uri 'self'; " +
		"frame-ancestors 'none'"
	return func(c *gin.Context) {
		h := c.Writer.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "same-origin")
		h.Set("Content-Security-Policy", csp)
		// HSTS 只在确认走 HTTPS 时下发：HTTP 下发会被浏览器忽略，
		// 且若站点尚未全量 HTTPS，误发 HSTS 会把用户锁在无法访问的状态（无法回退 HTTP）。
		if strings.EqualFold(c.GetHeader("X-Forwarded-Proto"), "https") || c.Request.TLS != nil {
			h.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		}
		c.Next()
	}
}

// trustedProxyCIDRs 生产形态是宝塔 Nginx 反代在同机，故只信任本机与内网段。
// Gin 默认信任所有代理 → 任何人都能伪造 X-Forwarded-For，
// 后果：审计日志 IP 全是攻击者编的，且按 IP 的限流可通过换伪造 IP 无限绕过。
var trustedProxyCIDRs = []string{
	"127.0.0.1/32",
	"::1/128",
	"10.0.0.0/8",
	"172.16.0.0/12",
	"192.168.0.0/16",
	"fc00::/7", // IPv6 唯一本地地址（容器网络常用）
}

// ---------- 口令强度 ----------

// errWeakPassword 供调用方直接把文案回给前端（前端原样展示 message）。
var errWeakPassword = errors.New("密码至少 12 位，且需同时包含大写字母、小写字母与数字")

// validateStrongPassword 后台管理员口令强度：>=12 位且含大小写字母与数字。
// 原实现只校验 len>=6，超管能把口令设成 "123456"——后台一旦被爆破即全站数据（含情侣私密日记）失守。
func validateStrongPassword(pw string) error {
	if len([]rune(pw)) < 12 {
		return errWeakPassword
	}
	var hasUpper, hasLower, hasDigit bool
	for _, r := range pw {
		switch {
		case unicode.IsUpper(r):
			hasUpper = true
		case unicode.IsLower(r):
			hasLower = true
		case unicode.IsDigit(r):
			hasDigit = true
		}
	}
	if !hasUpper || !hasLower || !hasDigit {
		return errWeakPassword
	}
	return nil
}

// ---------- 管理员角色白名单 ----------

// adminRoles 角色白名单。原先 role 字段无校验，可写入任意字符串：
// 写成 "Super"（大小写不符）会让该账号永久失去超管能力，
// 写成随便一个词则该账号既不是 super 也不受 admin 语义约束，权限判定形同虚设。
var adminRoles = map[string]bool{"admin": true, "super": true}

func isValidAdminRole(role string) bool { return adminRoles[role] }

// ---------- 图片 URL 白名单 ----------

// validateUploadURL 校验客户端提交的图片 URL 必须指向本站 /upload/ 下的资源。
// 原实现把任意字符串直接入库并回显给双方客户端，可被用于：
//   - 注入 javascript:/data: 串 → 客户端 WebView 渲染时执行脚本；
//   - 注入外部图床/攻击者服务器 URL → 对方一打开日记就把 IP、UA、访问时间回传给攻击者（追踪像素）。
//
// 允许两种形态（与 publicUploadURL 的两种输出一致）：
//   - 相对路径 /upload/...（site.url 未配置时）
//   - 绝对 URL http(s)://<site.url 的 host>/upload/...（site.url 已配置时）
func validateUploadURL(raw string) error {
	s := strings.TrimSpace(raw)
	if s == "" {
		return errors.New("图片地址不能为空")
	}
	u, err := url.Parse(s)
	if err != nil {
		return errors.New("图片地址非法")
	}
	// 反斜杠与 .. 一律拒绝：避免 /upload/../../etc 之类穿越，以及 /\evil.com 这种协议相对绕过。
	if strings.Contains(s, "\\") || strings.Contains(u.Path, "..") {
		return errors.New("图片地址非法")
	}
	switch {
	case u.Scheme == "" && u.Host == "":
		// 相对路径：必须以 /upload/ 开头。
		if !strings.HasPrefix(u.Path, "/upload/") {
			return errors.New("图片地址必须是本站上传路径")
		}
	case u.Scheme == "http" || u.Scheme == "https":
		if !strings.HasPrefix(u.Path, "/upload/") {
			return errors.New("图片地址必须是本站上传路径")
		}
		// 站点地址已配置时校验同源；未配置则无法判定本站 host，此时不接受绝对 URL。
		base := siteBaseURL()
		if base == "" {
			return errors.New("图片地址必须是本站上传路径")
		}
		b, err := url.Parse(base)
		if err != nil || !strings.EqualFold(b.Host, u.Host) {
			return errors.New("图片地址必须是本站上传路径")
		}
	default:
		// javascript:、data:、file: 等一律拒绝。
		return errors.New("图片地址非法")
	}
	return nil
}

// validateUploadURLs 批量校验，返回第一个错误（含出错的下标信息由调用方决定是否展示）。
func validateUploadURLs(urls []string) error {
	for _, u := range urls {
		if err := validateUploadURL(u); err != nil {
			return err
		}
	}
	return nil
}
