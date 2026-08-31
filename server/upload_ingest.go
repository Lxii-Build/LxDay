package main

import (
	"log/slog"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// maxJSONBodyBytes 给所有 JSON 接口设统一上限。Gin 的 ShouldBindJSON 会先把请求体
// 交给 decoder；没有这个闸门时，登录/注册/设置等接口都能被超大 JSON 请求拖垮。
const maxJSONBodyBytes = 2 << 20

// LimitJSONBody 在路由处理器之前限制 JSON 请求体；multipart 上传由各自的业务上限处理。
func LimitJSONBody() gin.HandlerFunc {
	return func(c *gin.Context) {
		contentType := strings.ToLower(strings.TrimSpace(strings.SplitN(c.GetHeader("Content-Type"), ";", 2)[0]))
		if contentType == "application/json" {
			c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxJSONBodyBytes)
		}
		c.Next()
	}
}

// multipartParseSlots 限制同时进行 multipart 解析的请求数。
// 解析发生在 FormFile 内部，必须在调用它之前占槽；否则用户级图片闸门来得太晚。
const maxConcurrentMultipartParses = 8

var multipartParseSlots = make(chan struct{}, maxConcurrentMultipartParses)

func acquireMultipartParseSlot() (func(), bool) {
	select {
	case multipartParseSlots <- struct{}{}:
		return func() { <-multipartParseSlots }, true
	default:
		return nil, false
	}
}

// releaseParsedMultipartForm 释放 Gin 解析产生的内存/临时文件。
// 保存到业务临时文件后，原始 multipart 内容已经不再需要。
func releaseParsedMultipartForm(c *gin.Context) {
	if c.Request.MultipartForm == nil {
		return
	}
	if err := c.Request.MultipartForm.RemoveAll(); err != nil {
		slog.Warn("remove parsed multipart temp files failed", "err", err)
	}
	c.Request.MultipartForm = nil
}

func rejectMultipartBusy(c *gin.Context, admin bool) {
	if admin {
		afail(c, http.StatusServiceUnavailable, 1028, "服务器正忙，请稍后重试")
		return
	}
	fail(c, http.StatusServiceUnavailable, codeUploadInFlight, "服务器正忙，请稍后重试")
}
