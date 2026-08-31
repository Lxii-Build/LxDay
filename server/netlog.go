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

// startRequestLogWorker 单消费者异步落库（避免每请求起 goroutine）；并周期跑数据保留清理。
func startRequestLogWorker() {
	go func() {
		for rl := range reqLogCh {
			st.InsertRequestLog(rl)
		}
	}()
	go func() {
		t := time.NewTicker(6 * time.Hour)
		defer t.Stop()
		runRetentionCleanup()
		for range t.C {
			runRetentionCleanup()
		}
	}()
}

// runRetentionCleanup 按后台配置的保留天数清理三类历史数据。
//
// 三处「磁盘只涨不跌」的来源（0821 排查结论）：
//
//	① request_log —— 保留天数此前写死 7 天，改不了
//	② status_history —— 注释写着「永久保留」，全仓无任何清理任务
//	③ 回收站照片 —— 全链路软删，/upload 下的原图与缩略图永久保留
//
// **每类都记录实际删除行数**：0820 那轮 netlog 的清理 SQL 误用 MySQL 语法
// （`NOW() - INTERVAL ? DAY`），在 SQLite 上永久静默失败、磁盘必被打满，
// 而调用方忽略了返回值所以完全无感。有了行数日志，下次再写错能立刻看出来。
func runRetentionCleanup() {
	s := settingsNow()

	if n, err := st.CleanupRequestLogsN(s.NetworkLogDays); err != nil {
		slog.Error("retention: request_log cleanup failed", "err", err)
	} else if n > 0 {
		slog.Info("retention: request_log cleaned", "rows", n, "keep_days", s.NetworkLogDays)
	}

	if n, err := st.CleanupStatusHistory(s.StatusHistoryDays); err != nil {
		slog.Error("retention: status_history cleanup failed", "err", err)
	} else if n > 0 {
		slog.Info("retention: status_history cleaned", "rows", n, "keep_days", s.StatusHistoryDays)
	}

	// 回收站到期照片：真删库行 + 真删磁盘文件。
	if cnt, freed, err := st.PurgeExpiredRecycleBin(s.RecycleBinDays); err != nil {
		slog.Error("retention: recycle bin purge failed", "err", err)
	} else if cnt > 0 {
		slog.Info("retention: recycle bin purged",
			"photos", cnt, "freed_mb", freed/(1024*1024), "keep_days", s.RecycleBinDays)
	}
}

// RequestLogger 请求日志中间件：注入 X-Request-Id，记录结构化 slog 行并异步入库。
func RequestLogger() gin.HandlerFunc {
	// 跳过的路径不入库。
	//
	// **`/upload` 必须在列表里**：实际落盘路径是 /upload/年/月/日/<随机名>，
	// 而此前只写了 `/uploads`（旧前缀），于是每张私密照片的完整 URL 都被写进 request_log。
	// 后台「网络日志」页对管理员是可读的 → 任何管理员都能从日志里直接点开情侣的私密相册。
	// 这些静态资源本身也没有记录价值，只会把日志表撑爆。
	skip := func(p string) bool {
		return p == "/healthz" ||
			strings.HasPrefix(p, "/ws") ||
			strings.HasPrefix(p, "/upload") || // 覆盖 /upload 与 /uploads
			strings.HasPrefix(p, "/media") || // 鉴权图片代理
			strings.HasPrefix(p, "/assets")
	}
	return func(c *gin.Context) {
		start := time.Now()
		rid := randomCode(16)
		if rid == "" {
			// 请求 ID 不是凭据；随机源异常时仍保持可观测性，避免写入空 ID。
			rid = strconv.FormatInt(time.Now().UnixNano(), 10)
		}
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
	if _, err := s.DB.Exec(
		`INSERT INTO request_log(method,path,status,latency_ms,ip,ua,request_id) VALUES(?,?,?,?,?,?,?)`,
		rl.Method, rl.Path, rl.Status, rl.LatencyMs, rl.IP, rl.UA, rl.RequestID); err != nil {
		slog.Error("insert request log failed", "method", rl.Method, "path", rl.Path, "err", err)
	}
}

// CleanupRequestLogs 删除 N 天前的请求日志。
//
// 这里必须用 SQLite 的 datetime() 而非 MySQL 的 `NOW() - INTERVAL ? DAY`——
// 后者在 SQLite 上直接语法报错，而调用方（定时任务）忽略了返回值，
// 于是清理**永久静默失败**、request_log 无限增长，最终把磁盘打满导致全站不可用。
// 这是 0813 从 MySQL 迁到 SQLite 时漏改的残留。
//
// 参数用 printf 拼进 modifier 字符串：SQLite 的 datetime modifier 不支持占位符，
// 故 days 必须是受信整数（调用方传常量），此处再做一次范围收敛以防注入。
// CleanupRequestLogsN 删除 N 天前的请求日志，返回实际删除行数。
// days<=0 表示永久保留（不清理）。
func (s *Store) CleanupRequestLogsN(days int) (int64, error) {
	if days <= 0 {
		return 0, nil // 永久保留
	}
	res, err := s.DB.Exec(
		`DELETE FROM request_log WHERE created_at < datetime('now', ?)`, negDaysModifier(days))
	if err != nil {
		slog.Error("cleanup request_log failed", "err", err, "days", days)
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		slog.Error("count cleaned request_log rows failed", "err", err, "days", days)
		return 0, err
	}
	return n, nil
}

// CleanupRequestLogs 保留旧签名（忽略返回值的调用点仍可用）。
func (s *Store) CleanupRequestLogs(days int) {
	_, _ = s.CleanupRequestLogsN(days)
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
	// 总数出错时留 0：列表仍能显示，只是分页器不准。但必须留痕 ——
	// 否则「列表有数据却显示共 0 条」这种矛盾现象查不出原因。
	if err := s.DB.QueryRow(
		"SELECT COUNT(*) FROM request_log "+where, args...,
	).Scan(&total); err != nil {
		slog.Error("count request_log failed", "err", err)
	}
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
		if err := rows.Scan(&r.ID, &r.Method, &r.Path, &r.Status, &r.LatencyMs, &r.IP, &r.UA, &r.RequestID, &created); err != nil {
			// 单行坏数据（如可空列为 NULL）不能静默变成零值：
			// 忽略 Scan 错误曾导致状态历史整行零值 → 客户端撞重复 key 崩溃。
			slog.Error("scan request_log row failed", "err", err)
			continue
		}
		r.CreatedAt = created.Format("2006-01-02 15:04:05")
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
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
