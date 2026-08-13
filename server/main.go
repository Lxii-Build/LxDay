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
	data, err := os.ReadFile(path)
	if err != nil {
		log.Fatalf("读取配置失败 %s: %v", path, err)
	}
	c := &Config{}
	if err := yaml.Unmarshal(data, c); err != nil {
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
	startRequestLogWorker()

	r := gin.New()
	r.Use(RequestLogger(), gin.Recovery())

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

	auth.POST("/diaries", handleCreateDiary)
	auth.GET("/diaries", handleListDiaries)
	auth.PUT("/diaries/:id", handleUpdateDiary)
	auth.DELETE("/diaries/:id", handleDeleteDiary)

	auth.POST("/interactions/comfort", handleComfort)
	auth.POST("/interactions/calm", handleCalm)
	auth.POST("/interactions/ring", handleRing)

	auth.POST("/push/register-token", handleRegisterPushToken)
	auth.DELETE("/push/token", handleUnregisterPushToken)

	// 状态历史
	auth.GET("/status/history", handleHistoryTimeline)
	auth.GET("/status/history/battery", handleBatteryCurve)

	// 日记图片上传（本地磁盘）
	auth.POST("/diaries/images", handleUploadDiaryImage)

	// ---- WebSocket ----
	r.GET("/ws", func(c *gin.Context) {
		token := c.Query("token")
		uid, err := ParseToken(token)
		if err != nil {
			c.JSON(401, gin.H{"code": 1003, "message": "未授权"})
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
