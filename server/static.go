package main

import (
	"embed"
	"io/fs"
	"log"
	"net/http"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
)

// webDistFS 内嵌后台前端产物。占位 webdist/index.html 保证无真实 dist 也能 go build；
// Dockerfile 构建时会以真实 admin dist 覆盖 server/webdist/。
//
//go:embed all:webdist
var webDistFS embed.FS

// registerStatic 去 Nginx 后由 Go 自托管静态资源：
//   - GET /healthz 健康检查
//   - /upload/*  日期分区上传（头像/日记图片，disk: uploadDir/upload/年/月/日/...），禁用目录列举
//   - /uploads/* 兼容旧路径（历史头像 / 后台 APK·LOGO，disk: uploadDir/*），禁用目录列举
//   - 其余非 /api、/ws、/upload(s)、/healthz 的 GET/HEAD 请求交给内嵌 SPA（命中静态文件直返，否则回退 index.html）
func registerStatic(r *gin.Engine) {
	r.GET("/healthz", func(c *gin.Context) { c.String(http.StatusOK, "ok") })

	// 上传目录安全响应头：禁用 MIME 嗅探；非图片强制下载，缓解上传 html/svg 的存储型 XSS。
	r.Use(func(c *gin.Context) {
		p := strings.ToLower(c.Request.URL.Path)
		if strings.HasPrefix(p, "/upload/") || strings.HasPrefix(p, "/uploads/") {
			c.Header("X-Content-Type-Options", "nosniff")
			isImg := strings.HasSuffix(p, ".jpg") || strings.HasSuffix(p, ".jpeg") ||
				strings.HasSuffix(p, ".png") || strings.HasSuffix(p, ".webp") || strings.HasSuffix(p, ".gif")
			if !isImg {
				c.Header("Content-Disposition", "attachment")
			}
		}
		c.Next()
	})

	// 新：日期分区上传，URL /upload/年/月/日/xxx → disk uploadDir/upload/年/月/日/xxx
	r.StaticFS("/upload", gin.Dir(filepath.Join(uploadDir, "upload"), false))
	// 旧：兼容历史头像与后台上传（APK/LOGO），URL /uploads/xxx → disk uploadDir/xxx
	r.StaticFS("/uploads", gin.Dir(uploadDir, false))

	// 内嵌后台前端（去掉 webdist/ 前缀，根即 SPA 根）
	dist, err := fs.Sub(webDistFS, "webdist")
	if err != nil {
		log.Printf("embed webdist error: %v", err)
		return
	}
	fileServer := http.FileServer(http.FS(dist))
	indexHTML, _ := fs.ReadFile(dist, "index.html")

	r.NoRoute(func(c *gin.Context) {
		req := c.Request
		if req.Method != http.MethodGet && req.Method != http.MethodHead {
			c.Status(http.StatusNotFound)
			return
		}
		p := req.URL.Path
		// 已有显式路由的前缀（未命中即真 404），不回退到 SPA
		if strings.HasPrefix(p, "/api") || strings.HasPrefix(p, "/ws") ||
			strings.HasPrefix(p, "/upload") || strings.HasPrefix(p, "/uploads") || p == "/healthz" {
			c.Status(http.StatusNotFound)
			return
		}
		// 命中内嵌静态文件（js/css/图片等）直接返回
		if name := strings.TrimPrefix(p, "/"); name != "" {
			if f, err := dist.Open(name); err == nil {
				info, statErr := f.Stat()
				f.Close()
				if statErr == nil && !info.IsDir() {
					fileServer.ServeHTTP(c.Writer, req)
					return
				}
			}
		}
		// 其余交给 SPA 前端路由：回退 index.html
		if len(indexHTML) > 0 {
			c.Data(http.StatusOK, "text/html; charset=utf-8", indexHTML)
			return
		}
		c.Status(http.StatusNotFound)
	})
}
