package main

import (
	"log/slog"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// initLogger 配置全局结构化日志（slog）：统一 时间/级别/属性，替代零散 log.Printf 的非结构化输出。
// 级别可由环境变量 LOG_LEVEL=debug|info|warn|error 控制，默认 info。
func initLogger() {
	level := slog.LevelInfo
	switch strings.ToLower(os.Getenv("LOG_LEVEL")) {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level})))
}

// ================= 网络日志（API 请求日志） =================
// 记录每次 API 请求：方法/路径/状态码/耗时/IP/UA/请求ID，异步落库，后台"网络日志"页查询。
// 与"系统日志(管理员操作审计)"区分。排除 /ws、/uploads、/healthz、静态资源，且不记录请求体/密钥。

type RequestLog struct {
	ID        int64  `json:"id"`
	Method    string `json:"method"`
	Path      string `json:"path"`
	Status    int    `json:"status"`
	LatencyMs int64  `json:"latency_ms"`
	IP        string `json:"ip"`
	UA        string `json:"ua"`
	RequestID string `json:"request_id"`
	CreatedAt string `json:"created_at"`
}

var reqLogCh = make(chan RequestLog, 1024)

// PLACEHOLDER_NETLOG

// startRequestLogWorker 单消费者异步落库（避免每请求起 goroutine）；并每 6 小时清理超保留期记录。
func startRequestLogWorker() {
	go func() {
		for rl := range reqLogCh {
			st.InsertRequestLog(rl)
		}
	}()
	go func() {
		t := time.NewTicker(6 * time.Hour)
		defer t.Stop()
		st.CleanupRequestLogs(7)
		for range t.C {
			st.CleanupRequestLogs(7)
		}
	}()
}

// RequestLogger 请求日志中间件：注入 X-Request-Id，记录结构化 slog 行并异步入库。
func RequestLogger() gin.HandlerFunc {
	skip := func(p string) bool {
		return p == "/healthz" || strings.HasPrefix(p, "/ws") ||
			strings.HasPrefix(p, "/uploads") || strings.HasPrefix(p, "/assets")
	}
	return func(c *gin.Context) {
		start := time.Now()
		rid := randomCode(16)
		c.Writer.Header().Set("X-Request-Id", rid)
		c.Set("request_id", rid)
		c.Next()

		p := c.Request.URL.Path
		if skip(p) {
			return
		}
		rl := RequestLog{
			Method: c.Request.Method, Path: p, Status: c.Writer.Status(),
			LatencyMs: time.Since(start).Milliseconds(), IP: c.ClientIP(),
			UA: c.Request.UserAgent(), RequestID: rid,
		}
		slog.Info("http", "rid", rid, "method", rl.Method, "path", rl.Path,
			"status", rl.Status, "ms", rl.LatencyMs, "ip", rl.IP)
		select {
		case reqLogCh <- rl:
		default: // 队列满：丢弃日志，绝不阻塞业务
		}
	}
}

// ---------- 存储 ----------

func (s *Store) InsertRequestLog(rl RequestLog) {
	s.DB.Exec(
		`INSERT INTO request_log(method,path,status,latency_ms,ip,ua,request_id) VALUES(?,?,?,?,?,?,?)`,
		rl.Method, rl.Path, rl.Status, rl.LatencyMs, rl.IP, rl.UA, rl.RequestID)
}

func (s *Store) CleanupRequestLogs(days int) {
	s.DB.Exec(`DELETE FROM request_log WHERE created_at < (NOW() - INTERVAL ? DAY)`, days)
}

func (s *Store) ListRequestLogs(method, path string, status, limit, offset int) ([]RequestLog, int, error) {
	where := "WHERE 1=1"
	args := []interface{}{}
	if method != "" {
		where += " AND method=?"
		args = append(args, method)
	}
	if path != "" {
		where += " AND path LIKE ?"
		args = append(args, "%"+path+"%")
	}
	if status > 0 {
		where += " AND status=?"
		args = append(args, status)
	}
	var total int
	s.DB.QueryRow("SELECT COUNT(*) FROM request_log "+where, args...).Scan(&total)
	q := "SELECT id,method,path,status,latency_ms,ip,ua,request_id,created_at FROM request_log " +
		where + " ORDER BY id DESC LIMIT ? OFFSET ?"
	args = append(args, limit, offset)
	rows, err := s.DB.Query(q, args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []RequestLog{}
	for rows.Next() {
		var r RequestLog
		var created time.Time
		rows.Scan(&r.ID, &r.Method, &r.Path, &r.Status, &r.LatencyMs, &r.IP, &r.UA, &r.RequestID, &created)
		r.CreatedAt = created.Format("2006-01-02 15:04:05")
		out = append(out, r)
	}
	return out, total, nil
}

// ---------- 后台接口 ----------

func handleAdminListNetworkLogs(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	method := strings.ToUpper(strings.TrimSpace(c.Query("method")))
	path := strings.TrimSpace(c.Query("path"))
	status, _ := strconv.Atoi(c.DefaultQuery("status", "0"))
	list, total, err := st.ListRequestLogs(method, path, status, limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

