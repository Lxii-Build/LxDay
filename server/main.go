package main

import (
	"context"
	"fmt"
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
	// 私密相册文件不能在迁移完成前注册 /upload(s) 静态路由，否则旧照片会继续绕过 /media 鉴权。
	if err := migratePhotoFilesToPrivateRoot(st.DB); err != nil {
		log.Fatalf("迁移私密相册文件失败: %v", err)
	}
	if err := st.EnsureSuperAdmin(); err != nil {
		log.Fatalf("初始化超级管理员失败: %v", err)
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
	// 内存态清扫：限流计数与验证码等键此前只有惰性过期，
	// 而「登录失败」「每日上传配额」这两类键按账号名/日期分桶、过期后再也不会被读到，
	// 于是永远没人去触发那次惰性回收 —— 这是 0827 生产 OOM 的直接来源。
	startMemStoreJanitor(st.mem)

	// 生产默认 release 模式：debug 模式会打印全部路由表与详细报错，
	// 属信息泄露面，也拖慢每次请求。可用 GIN_MODE=debug 临时覆盖排查问题。
	if os.Getenv("GIN_MODE") == "" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.New()
	// FormFile/ParseMultipartForm 的 file 部分最多只在内存保留这点大小，
	// 其余内容落临时盘；配合 multipartParseSlots，避免并发上传把内存推高。
	r.MaxMultipartMemory = 256 << 10
	// 只信任本机反代（生产是宝塔 Nginx 同机反代）。
	// 不设置时 Gin 信任所有代理，任何人都能用 X-Forwarded-For 伪造来源 IP，
	// 从而污染审计日志、并绕过一切按 IP 的限流。
	if err := r.SetTrustedProxies(trustedProxyCIDRs); err != nil {
		slog.Warn("SetTrustedProxies failed", "err", err)
	}
	r.Use(SecurityHeaders(), RequestLogger(), gin.Recovery(), LimitJSONBody())

	// ---- 公开路由 ----
	// 通讯密钥中间件仅拦截 /api/v1/*（app_key 为空则禁用）；不影响 /ws、/uploads、SPA、/healthz、/api/admin
	api := r.Group("/api/v1", AppKeyGuard())
	// ★ 这三条公开接口必须按 IP 限流 ★
	//
	// APP_KEY 编在 APK 里，逆向即可取得明文 —— 任何随客户端分发的密钥
	// 都不可能保密，所以它只能挡住顺手扫全网的爬虫，**不构成安全边界**。
	// 正确的威胁模型是「攻击者已持有合法 APP_KEY」，而在这个前提下
	// 此前所有限流的键（账号名/邮箱/uid）全都由攻击者自己控制、
	// 换一个就从零开始。IP 是唯一不受其随意支配的维度。
	// 详见 ip_ratelimit.go 顶部的说明。
	api.POST("/auth/register", IPRateLimit(ipRateRegister), handleRegister)
	api.POST("/auth/login", IPRateLimit(ipRateLogin), handleLogin)
	api.POST("/auth/send-code", IPRateLimit(ipRateSendCode), handleSendEmailCode)
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

	// 相册总开关 requireAlbumEnabled 挂在这一组上。
	//
	// 它此前**定义了却从未挂载过任何路由**（全仓零调用点），于是「相册功能总开关」
	// 关掉后只有上传那一条被 handler 内部的检查挡住，建相册、改名、挂照片、
	// 评论点赞照旧能写。管理员以为已经止损了，实际只关掉了入口的一半。
	// 中间件按方法放行 GET，所以关掉之后用户仍能查看和导出已有照片。
	album := auth.Group("", requireAlbumEnabled())
	album.GET("/albums", handleListAlbums)
	album.POST("/albums", handleCreateAlbum)
	album.GET("/albums/:id", handleAlbumByID) // :id=summary → 相册概要
	album.PUT("/albums/:id", handleUpdateAlbum)
	album.DELETE("/albums/:id", handleDeleteAlbum)
	album.GET("/albums/:id/photos", handleListAlbumPhotos)
	album.POST("/albums/:id/photos", handleAttachPhotos)

	// 上传再加一道按 IP 的限流：按账号的每日配额已有，但**新注册账号的配额是满的**，
	// 而注册在攻击者手里是廉价的。这条卡住"注册一批账号轮着传"的放大路径。
	album.POST("/media", IPRateLimit(ipRateUpload), handleUploadMedia)

	album.GET("/photos/:id", handlePhotoByID) // :id=on-this-day / recycled → 见 handlePhotoByID
	album.PUT("/photos/:id", handleUpdatePhoto)
	// :id=batch-delete / batch-move / purge-all → 由 handlePhotoActionByID 分派
	// （gin 不允许同层静态段与通配段并存，故沿用既有的通配分派套路）。
	album.POST("/photos/:id", handlePhotoActionByID)
	album.DELETE("/photos/:id", handleDeletePhoto)
	album.POST("/photos/:id/restore", handleRestorePhoto)
	// 彻底删除（真删磁盘，不可恢复）
	album.DELETE("/photos/:id/purge", handlePurgePhoto)
	// 评论/点赞再多一道 social 开关（同样是此前只有客户端隐藏、服务端零校验的一项）。
	social := album.Group("", requireSocialEnabled())
	social.POST("/photos/:id/like", handleLikePhoto)
	social.DELETE("/photos/:id/like", handleUnlikePhoto)
	social.POST("/photos/:id/comments", handleCreatePhotoComment)
	social.DELETE("/photos/:id/comments/:cid", handleDeletePhotoComment)

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
		// JWT 只允许放在 Authorization 头，不能放在 URL 查询串里；查询串会进入
		// 反向代理、负载均衡器和浏览器历史记录。
		uid, err := authUserByToken(bearerToken(c.GetHeader("Authorization")))
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
	// 超时必须显式设置：http.Server 的零值是**永不超时**，
	// 于是一条只发了半行请求头就不动的连接会永久占着一个 goroutine 与其读写缓冲
	//（slowloris）。生产上表现为内存与 goroutine 只涨不跌，且没有任何错误日志。
	//
	// 各值的取法：
	//   - ReadHeaderTimeout 是这里**最要紧**的一个，请求头永远是小的，15s 足够慢网络送完；
	//   - ReadTimeout / WriteTimeout 要覆盖最大的那次正常传输 —— 单张照片上限 20MB，
	//     移动网络下传完可能要几分钟，所以给 5 分钟而不是常见的 30 秒，
	//     否则弱网用户会在上传大图时被服务端掐断（表现和"上传总是失败"一模一样）；
	//   - IdleTimeout 管的是 keep-alive 空闲连接，90s 与常见反代默认值同量级。
	//
	// WebSocket 不受影响：gorilla 在 Hijack 之后会 SetDeadline(零值) 清掉这些期限
	//（server.go:251），此后由 hub 自己的 45s 读超时与写超时接管。
	srv := &http.Server{
		Addr:              ":" + cfg.App.Port,
		Handler:           r,
		ReadHeaderTimeout: 15 * time.Second,
		ReadTimeout:       5 * time.Minute,
		WriteTimeout:      5 * time.Minute,
		IdleTimeout:       90 * time.Second,
	}
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
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("create database directory %q: %w", dir, err)
		}
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
