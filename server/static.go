package main

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// webDistFS 内嵌后台前端产物。占位 webdist/index.html 保证无真实 dist 也能 go build；
// Dockerfile 构建时会以真实 admin dist 覆盖 server/webdist/。
//
//go:embed all:webdist
var webDistFS embed.FS

// registerStatic 去 Nginx 后由 Go 自托管静态资源：
//   - GET /healthz 存活检查、GET /readyz 依赖就绪检查
//   - /upload/*  日期分区公开上传（头像/日记图片，disk: uploadDir/upload/年/月/日/...），禁用目录列举
//   - /uploads/* 兼容旧路径（历史头像 / 后台 APK·LOGO，disk: uploadDir/*），禁用目录列举
//   - 其余非 /api、/ws、/upload(s)、/healthz、/readyz 的 GET/HEAD 请求交给内嵌 SPA（命中静态文件直返，否则回退 index.html）
func registerStatic(r *gin.Engine) {
	r.GET("/healthz", func(c *gin.Context) { c.String(http.StatusOK, "ok") })
	// /healthz 只回答进程是否存活；容器编排应使用 /readyz 判断数据库与
	// 两类媒体目录是否仍可用。将二者混为一谈会掩盖只读卷、磁盘满等故障。
	r.GET("/readyz", func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer cancel()
		if err := readinessCheck(ctx); err != nil {
			c.String(http.StatusServiceUnavailable, "not ready")
			return
		}
		c.String(http.StatusOK, "ready")
	})

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
			strings.HasPrefix(p, "/upload") || strings.HasPrefix(p, "/uploads") || p == "/healthz" || p == "/readyz" {
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

// readinessCheck 验证请求会命中的关键依赖：SQLite 必须能取得写锁，公开/私密
// 存储根目录必须可创建临时探针。UPDATE ... WHERE 1=0 不改变业务数据，却会在
// 只读数据库、满盘或无法取得写锁时失败，正好覆盖“进程活着但不能服务”的场景。
func readinessCheck(ctx context.Context) error {
	if st == nil || st.DB == nil {
		return fmt.Errorf("store unavailable")
	}
	if _, err := st.DB.ExecContext(ctx, "UPDATE app_setting SET v=v WHERE 1=0"); err != nil {
		return fmt.Errorf("database not writable: %w", err)
	}
	if err := checkWritableDirectory(uploadDir, 0o755); err != nil {
		return fmt.Errorf("public storage unavailable: %w", err)
	}
	if err := checkWritableDirectory(privateMediaDir(), 0o700); err != nil {
		return fmt.Errorf("private storage unavailable: %w", err)
	}
	return nil
}

func checkWritableDirectory(dir string, mode fs.FileMode) error {
	if err := os.MkdirAll(dir, mode); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".lxday-readyz-")
	if err != nil {
		return err
	}
	name := f.Name()
	if err := f.Close(); err != nil {
		_ = os.Remove(name)
		return err
	}
	return os.Remove(name)
}
