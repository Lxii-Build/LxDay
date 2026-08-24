package main

import (
	"context"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"gopkg.in/yaml.v3"
)

// ================= 配置 =================

type Config struct {
	App struct {
		Port                string `yaml:"port"`
		JWTSecret           string `yaml:"jwt_secret"`
		TokenTTLHours       int    `yaml:"token_ttl_hours"`
		RingCooldownSeconds int    `yaml:"ring_cooldown_seconds"`
		RingCooldownLimit   int    `yaml:"ring_cooldown_limit"`
		AppKey              string `yaml:"app_key"` // 通讯密钥；非空时校验 /api/v1/* 请求头 X-App-Key，可用环境变量 APP_KEY 覆盖
	} `yaml:"app"`
	DB struct {
		Path string `yaml:"path"` // SQLite 数据库文件路径（单容器，无外部数据库）
	} `yaml:"db"`
	Push struct {
		Provider string `yaml:"provider"`
	} `yaml:"push"`
	Storage struct {
		UploadDir string `yaml:"upload_dir"`
	} `yaml:"storage"`
}

func loadConfig() *Config {
	path := "config.yaml"
	if len(os.Args) > 1 {
		path = os.Args[1]
	}
	c := &Config{}
	if data, err := os.ReadFile(path); err != nil {
		// 配置文件可选：镜像内不打包 config.yaml，全部走默认值 + 环境变量（学 hl6：env 驱动）。
		slog.Warn("未找到配置文件，改用默认值 + 环境变量", "path", path)
	} else if err := yaml.Unmarshal(data, c); err != nil {
		log.Fatalf("解析配置失败: %v", err)
	}
	if c.App.TokenTTLHours == 0 {
		c.App.TokenTTLHours = 720
	}
	if c.App.Port == "" {
		c.App.Port = "7740"
	}
	// 环境变量覆盖（便于容器/宝塔用 .env 注入，避免把密钥写进提交的配置文件）
	if v := os.Getenv("APP_KEY"); v != "" {
		c.App.AppKey = v
	}
	if v := os.Getenv("JWT_SECRET"); v != "" {
		c.App.JWTSecret = v
	}
	if v := os.Getenv("PORT"); v != "" {
		c.App.Port = v
	}
	// JWT 密钥必须显式设置（不允许空/占位），否则令牌可被伪造。
	if c.App.JWTSecret == "" || strings.Contains(strings.ToLower(c.App.JWTSecret), "change") {
		log.Fatalf("jwt_secret 未设置：请通过环境变量 JWT_SECRET 或配置文件设置一个长随机串")
	}
	if c.DB.Path == "" {
		c.DB.Path = "data/lxday.db"
	}
	if v := os.Getenv("DB_PATH"); v != "" {
		c.DB.Path = v
	}
	return c
}

// ================= 全局依赖 =================

var (
	cfg  *Config
	st   *Store
	hub  *Hub
	push *PushGateway
)

func main() {
	initLogger()
	cfg = loadConfig()
	initUploadDir()

	// 初始化存储
	store, err := initStore(cfg)
	if err != nil {
		log.Fatalf("初始化存储失败: %v", err)
	}
	st = store
	if err := st.EnsureSuperAdmin(); err != nil {
		log.Printf("初始化超级管理员失败: %v", err)
	}
	push = NewPushGateway(cfg.Push.Provider, st)
	hub = NewHub(st, push)
	// 先载入后台可配的运行参数（相册配额/保留期/限流/互动冷却），
	// 再起清理任务——后者要读保留天数。未配置的键一律回退代码里的默认常量。
	reloadRuntimeSettings()
	// **必须在 startRequestLogWorker 之前**：那里会同步先跑一次 runRetentionCleanup，
	// 而清理回收站要遍历 photo 行、每行都过 scanPhoto → mediaURL → siteBaseURL。
	// 若此时站点地址缓存还是冷的，那次查库会去等一条正被 rows 占用、
	// 且要等遍历结束才释放的连接（MaxOpenConns(1)）——自己等自己，永久死锁，
	// 那条连接再也不回池，全站所有 DB 操作随之挂死。
	// 注意 reloadRuntimeSettings 不覆盖 site.url（它属于 settingKeys，不在
	// runtimeSettingSpecs 里），所以这里必须单独预热一次。
	warmSiteBaseCache()
	startRequestLogWorker()

	// 生产默认 release 模式：debug 模式会打印全部路由表与详细报错，
	// 属信息泄露面，也拖慢每次请求。可用 GIN_MODE=debug 临时覆盖排查问题。
	if os.Getenv("GIN_MODE") == "" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.New()
	// 只信任本机反代（生产是宝塔 Nginx 同机反代）。
	// 不设置时 Gin 信任所有代理，任何人都能用 X-Forwarded-For 伪造来源 IP，
	// 从而污染审计日志、并绕过一切按 IP 的限流。
	if err := r.SetTrustedProxies([]string{"127.0.0.1", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"}); err != nil {
		slog.Warn("SetTrustedProxies failed", "err", err)
	}
	r.Use(SecurityHeaders(), RequestLogger(), gin.Recovery())

	// ---- 公开路由 ----
	// 通讯密钥中间件仅拦截 /api/v1/*（app_key 为空则禁用）；不影响 /ws、/uploads、SPA、/healthz、/api/admin
	api := r.Group("/api/v1", AppKeyGuard())
	api.POST("/auth/register", handleRegister)
	api.POST("/auth/login", handleLogin)
	api.POST("/auth/send-code", handleSendEmailCode)
	api.GET("/app/latest", handleCheckUpdate)

	// ---- 需鉴权路由 ----
	auth := api.Group("", JWTAuth())
	auth.GET("/pair/status", handlePairStatus)
	auth.POST("/pair/create-invite", handleCreateInvite)
	auth.POST("/pair/bind", handleBind)
	auth.POST("/pair/unbind", handleUnbind)
	auth.POST("/pair/cancel-invite", handleCancelInvite)
	auth.PUT("/pair/anniversary", handleUpdateAnniversary)

	auth.GET("/profile", handleGetProfile)
	auth.PUT("/profile", handleUpdateProfile)
	auth.GET("/profile/me", handleGetMyProfile)
	auth.PUT("/profile/me", handleUpdateMyProfile)
	auth.POST("/profile/avatar", handleUploadAvatar)

	auth.GET("/partner/status", handlePartnerStatus)

	auth.POST("/todos", handleCreateTodo)
	auth.GET("/todos", handleListTodos)
	auth.PUT("/todos/:id", handleUpdateTodo)
	auth.POST("/todos/:id/complete", handleCompleteTodo)
	auth.DELETE("/todos/:id", handleDeleteTodo)

	auth.POST("/interactions/comfort", handleComfort)
	auth.POST("/interactions/calm", handleCalm)
	auth.POST("/interactions/ring", handleRing)

	auth.POST("/push/register-token", handleRegisterPushToken)
	auth.DELETE("/push/token", handleUnregisterPushToken)

	// 状态上报的 REST 兜底：WS 断线时客户端走这条，避免状态完全停更。
	auth.POST("/status", handleReportStatus)

	// 状态历史
	auth.GET("/status/history", handleHistoryTimeline)
	auth.GET("/status/history/battery", handleBatteryCurve)

	// ---- 相册 ----
	// 注意路由形状：gin 不允许同层同时注册静态段与通配段（会在启动时 panic），
	// 故 /albums/summary、/photos/on-this-day、/photos/recycled 一律由通配 handler 内部分派，
	// 对外路径不变（见 handleAlbumByID / handlePhotoByID）。
	// 客户端配置下发：只读，不含密钥，未绑定用户也能拉（Q41=B）。
	auth.GET("/client-config", handleClientConfig)

	auth.GET("/albums", handleListAlbums)
	auth.POST("/albums", handleCreateAlbum)
	auth.GET("/albums/:id", handleAlbumByID) // :id=summary → 相册概要
	auth.PUT("/albums/:id", handleUpdateAlbum)
	auth.DELETE("/albums/:id", handleDeleteAlbum)
	auth.GET("/albums/:id/photos", handleListAlbumPhotos)
	auth.POST("/albums/:id/photos", handleAttachPhotos)

	auth.POST("/media", handleUploadMedia)

	auth.GET("/photos/:id", handlePhotoByID) // :id=on-this-day / recycled → 见 handlePhotoByID
	auth.PUT("/photos/:id", handleUpdatePhoto)
	// :id=batch-delete / batch-move / purge-all → 由 handlePhotoActionByID 分派
	// （gin 不允许同层静态段与通配段并存，故沿用既有的通配分派套路）。
	auth.POST("/photos/:id", handlePhotoActionByID)
	auth.DELETE("/photos/:id", handleDeletePhoto)
	auth.POST("/photos/:id/restore", handleRestorePhoto)
	// 彻底删除（真删磁盘，不可恢复）
	auth.DELETE("/photos/:id/purge", handlePurgePhoto)
	auth.POST("/photos/:id/like", handleLikePhoto)
	auth.DELETE("/photos/:id/like", handleUnlikePhoto)
	auth.POST("/photos/:id/comments", handleCreatePhotoComment)
	auth.DELETE("/photos/:id/comments/:cid", handleDeletePhotoComment)

	// ---- 相册图片鉴权代理 ----
	// 挂在根路径而非 /api/v1：对外图片 URL 就是 /media/<id>，与 netlog 的 skip 前缀对齐
	// （照片 URL 不进 request_log，避免任何后台管理员能从日志直接点开私密相册）。
	// 只挂 JWTAuth、不挂 AppKeyGuard：图片由客户端图片库加载，只能确保带上 Authorization 头。
	media := r.Group("/media", JWTAuth())
	media.GET("/:id", handleGetMedia)
	media.GET("/:id/thumb", handleGetMediaThumb)
	// preview：长边 1080 的中间档，大图页先加载它再按需拉原图（三档缩略图）。
	media.GET("/:id/preview", handleGetMediaPreview)

	// ---- WebSocket ----
	r.GET("/ws", func(c *gin.Context) {
		// 与 JWTAuth 共用 authUserByToken：WS 同样要校验封禁状态与 token_ver，
		// 否则被封禁用户仍能保持长连接持续接收对方实时状态（位置/电量/前台应用）。
		uid, err := authUserByToken(c.Query("token"))
		if err != nil {
			c.JSON(401, gin.H{"code": 1003, "message": "登录已失效"})
			return
		}
		hub.ServeWS(c.Writer, c.Request, uid)
	})

	// 待办到点提醒定时扫描
	go scanDueTodos()

	// ---- 后台管理路由 /api/admin ----
	registerAdminRoutes(r)

	// ---- 静态托管（去 Nginx）：SPA + /uploads + /healthz ----
	registerStatic(r)

	log.Printf("林曦日记服务端启动 :%s", cfg.App.Port)
	srv := &http.Server{Addr: ":" + cfg.App.Port, Handler: r}
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %v", err)
		}
	}()
	// 优雅关闭：收到 SIGINT/SIGTERM 后停止接收新请求并释放 DB 连接。
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit
	slog.Info("server shutting down")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
	if st != nil && st.DB != nil {
		st.DB.Close()
	}
}

func initStore(c *Config) (*Store, error) {
	// 确保 SQLite 文件所在目录存在
	if dir := filepath.Dir(c.DB.Path); dir != "" && dir != "." {
		os.MkdirAll(dir, 0o755)
	}
	dsn := "file:" + c.DB.Path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(on)"
	db, err := sqlOpen(dsn)
	if err != nil {
		return nil, err
	}
	// SQLite 单写者：限制单连接避免 "database is locked"（低并发的情侣应用足够）。
	db.SetMaxOpenConns(1)
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	if err := runMigrations(db); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{DB: db, mem: newMemStore()}, nil
}
