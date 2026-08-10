package main

import (
	"context"
	"log"
	"os"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
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
	} `yaml:"app"`
	MySQL struct {
		DSN string `yaml:"dsn"`
	} `yaml:"mysql"`
	Redis struct {
		Addr     string `yaml:"addr"`
		Password string `yaml:"password"`
		DB       int    `yaml:"db"`
	} `yaml:"redis"`
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
	return c
}

// ================= 全局依赖 =================

var (
	cfg *Config
	st  *Store
	hub *Hub
	push *PushGateway
)

func main() {
	cfg = loadConfig()
	initUploadDir()

	// 初始化存储
	store, err := initStore(cfg)
	if err != nil {
		log.Fatalf("初始化存储失败: %v", err)
	}
	st = store
	push = NewPushGateway(cfg.Push.Provider, st)
	hub = NewHub(st, push)

	r := gin.New()
	r.Use(gin.Logger(), gin.Recovery())

	// ---- 公开路由 ----
	api := r.Group("/api/v1")
	api.POST("/auth/register", handleRegister)
	api.POST("/auth/login", handleLogin)

	// ---- 需鉴权路由 ----
	auth := api.Group("", JWTAuth())
	auth.GET("/pair/status", handlePairStatus)
	auth.POST("/pair/create-invite", handleCreateInvite)
	auth.POST("/pair/bind", handleBind)
	auth.PUT("/pair/anniversary", handleUpdateAnniversary)

	auth.GET("/profile", handleGetProfile)
	auth.PUT("/profile", handleUpdateProfile)
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

	log.Printf("林曦日记服务端启动 :%s", cfg.App.Port)
	if err := r.Run(":" + cfg.App.Port); err != nil {
		log.Fatal(err)
	}
}

func initStore(c *Config) (*Store, error) {
	db, err := sqlOpen(c.MySQL.DSN)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(50)
	db.SetMaxIdleConns(10)
	db.SetConnMaxLifetime(2 * time.Hour)
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	if err := runMigrations(db); err != nil {
		db.Close()
		return nil, err
	}

	rdb := redis.NewClient(&redis.Options{
		Addr:     c.Redis.Addr,
		Password: c.Redis.Password,
		DB:       c.Redis.DB,
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := rdb.Ping(ctx).Err(); err != nil {
		return nil, err
	}
	return &Store{DB: db, Rdb: rdb}, nil
}
