package main

import (
	"embed"
	"io/fs"
	"log"
	"net/http"
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
//   - /uploads/* 从本地 uploadDir 提供（PublicURL 前缀仍 /uploads），禁用目录列举
//   - 其余非 /api、/ws、/uploads、/healthz 的 GET/HEAD 请求交给内嵌 SPA（命中静态文件直返，否则回退 index.html）
func registerStatic(r *gin.Engine) {
	r.GET("/healthz", func(c *gin.Context) { c.String(http.StatusOK, "ok") })

	// 本地上传静态资源（头像 / 日记图片 / APK 等），禁用目录列举
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
			strings.HasPrefix(p, "/uploads") || p == "/healthz" {
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
