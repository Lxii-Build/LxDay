package main

import (
	"testing"
	"time"
)

// 崩溃回归：foreground_pkg/foreground_name/ssid 为 NULL 时，
// HistoryTimeline 必须正常返回、且 Ts 不能是零值。
//
// 曾经的链路：客户端未授「使用情况访问」→ pkgOf/nameOf 返回 nil → 这三列写 NULL
// → rows.Scan 进 string 报 "converting NULL to string is unsupported"
// → 调用方忽略 Scan 返回值 → 整行零值 → Ts.UnixMilli() = -62135596800000（每行相同）
// → 客户端 LazyColumn 的 key = { it.ts } 撞重复 key → IllegalArgumentException 崩溃。
func TestHistoryTimelineHandlesNullForegroundColumns(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "nha", "nhb", "NULLHIST")

	base := time.Now().Truncate(5 * time.Minute)
	// 三条记录：全 NULL / 部分 NULL / 全有值。ts 各不相同（唯一索引要求）。
	rows := []struct {
		pkg, name, ssid interface{}
		ts              time.Time
	}{
		{nil, nil, nil, base.Add(-10 * time.Minute)},
		{"com.tencent.mm", nil, nil, base.Add(-5 * time.Minute)},
		{"com.linxi.diary", "林曦日记", "MyWiFi", base},
	}
	for _, r := range rows {
		if _, err := s.DB.Exec(
			`INSERT INTO status_history
			 (pair_id,user_id,battery,charging,screen_on,locked,foreground_pkg,foreground_name,ssid,network,ts)
			 VALUES(?,?,?,?,?,?,?,?,?,?,?)`,
			pair.ID, uidA, 66, false, true, false, r.pkg, r.name, r.ssid, "wifi", r.ts,
		); err != nil {
			t.Fatalf("insert: %v", err)
		}
	}

	list, err := s.HistoryTimeline(pair.ID, uidA, "", 50, 0)
	if err != nil {
		t.Fatalf("HistoryTimeline 返回错误: %v", err)
	}
	if len(list) != 3 {
		t.Fatalf("应返回 3 行，实际 %d —— NULL 行被 Scan 错误吞掉了", len(list))
	}

	seen := map[int64]bool{}
	for i, h := range list {
		ms := h.Ts.UnixMilli()
		t.Logf("行%d ts=%d foreground=%q ssid=%q", i, ms, h.ForegroundAppName(), h.SSIDValue())
		if h.Ts.IsZero() {
			t.Fatalf("行%d 的 Ts 是零值（Scan 失败被忽略了）", i)
		}
		if ms < 0 {
			t.Fatalf("行%d 的 ts=%d 为负数（零时间序列化的产物，客户端会撞重复 key）", i, ms)
		}
		if seen[ms] {
			t.Fatalf("行%d 的 ts=%d 与前面重复 —— 客户端 LazyColumn 会崩", i, ms)
		}
		seen[ms] = true
	}
}

// 电量曲线同样不能被 NULL 列影响（它 SELECT 的列少，但走同一张表）。
func TestBatteryCurveWithNullColumns(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "bca", "bcb", "NULLCURV")

	// 基准锚到**当日本地正午**，而不是 time.Now().Truncate(5*time.Minute)。
	//
	// 原写法在本地时间 00:00~00:10 之间必红：base 会落到 00:00/00:05，
	// 减 10 分钟就跨到前一天 23:55，而 BatteryCurve 按 parseDayRange 只查当日
	// → 只剩 2 点。这与被测逻辑无关，纯粹是测试自身的时刻依赖
	//（0829 00:09 实测撞上）。正午前后各 10 分钟一定同属一天，与运行时刻无关。
	now := time.Now()
	base := time.Date(now.Year(), now.Month(), now.Day(), 12, 0, 0, 0, time.Local)
	day := base.Format("2006-01-02")
	for i := 0; i < 3; i++ {
		if _, err := s.DB.Exec(
			`INSERT INTO status_history
			 (pair_id,user_id,battery,charging,screen_on,locked,foreground_pkg,foreground_name,ssid,network,ts)
			 VALUES(?,?,?,?,?,?,NULL,NULL,NULL,?,?)`,
			pair.ID, uidA, 50+i, false, true, false, "wifi", base.Add(time.Duration(-i*5)*time.Minute),
		); err != nil {
			t.Fatalf("insert: %v", err)
		}
	}
	pts, err := s.BatteryCurve(pair.ID, uidA, day)
	if err != nil {
		t.Fatalf("BatteryCurve: %v", err)
	}
	if len(pts) != 3 {
		t.Fatalf("应返回 3 点，实际 %d", len(pts))
	}
	for i, p := range pts {
		if p.Ts.IsZero() || p.Ts.UnixMilli() < 0 {
			t.Fatalf("点%d 的 Ts 非法: %v", i, p.Ts)
		}
	}
}

// 日期查询必须按服务器本地时区切分当日，而非 UTC 零点。
// 曾经用 time.Parse（UTC），在 TZ=+8 的容器上"今天"实际是本地 08:00~次日 08:00，
// 凌晨 0~8 点的记录被算进"昨天"。
func TestHistoryTimelineUsesLocalDayBoundary(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "tza", "tzb", "TZBOUND1")

	// 构造"今天本地时间 01:00"这条记录——UTC 解析下它会落在前一天的窗口外。
	now := time.Now()
	localEarly := time.Date(now.Year(), now.Month(), now.Day(), 1, 0, 0, 0, time.Local)
	if _, err := s.DB.Exec(
		`INSERT INTO status_history
		 (pair_id,user_id,battery,charging,screen_on,locked,foreground_pkg,foreground_name,ssid,network,ts)
		 VALUES(?,?,?,?,?,?,NULL,NULL,NULL,?,?)`,
		pair.ID, uidA, 80, false, false, true, "wifi", localEarly,
	); err != nil {
		t.Fatalf("insert: %v", err)
	}

	list, err := s.HistoryTimeline(pair.ID, uidA, localEarly.Format("2006-01-02"), 50, 0)
	if err != nil {
		t.Fatalf("HistoryTimeline: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("本地 01:00 的记录应能被当日查询命中，实际返回 %d 行（时区错位）", len(list))
	}
}

// 保留天数清理必须真的删到行（SQLite 语法），且 days<=0 表示永久保留。
// 0820 那轮 netlog 的清理 SQL 写成 MySQL 语法，在 SQLite 上永久静默失败。
func TestCleanupStatusHistory(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "cla", "clb", "CLEANUP1")

	insert := func(ts time.Time) {
		if _, err := s.DB.Exec(
			`INSERT INTO status_history
			 (pair_id,user_id,battery,charging,screen_on,locked,foreground_pkg,foreground_name,ssid,network,ts)
			 VALUES(?,?,?,?,?,?,NULL,NULL,NULL,?,?)`,
			pair.ID, uidA, 60, false, true, false, "wifi", ts,
		); err != nil {
			t.Fatalf("insert: %v", err)
		}
	}
	now := time.Now()
	insert(now.AddDate(0, 0, -100)) // 100 天前，应被删
	insert(now.AddDate(0, 0, -95))  // 95 天前，应被删
	insert(now.AddDate(0, 0, -10))  // 10 天前，应保留
	insert(now)                     // 今天，应保留

	count := func() int {
		var n int
		if err := s.DB.QueryRow(`SELECT COUNT(*) FROM status_history`).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	if count() != 4 {
		t.Fatalf("前置数据应为 4 行，实际 %d", count())
	}

	// days<=0：永久保留，一行都不能删。
	if n, err := s.CleanupStatusHistory(0); err != nil || n != 0 {
		t.Fatalf("days=0 应不删任何行，实际删了 %d err=%v", n, err)
	}
	if count() != 4 {
		t.Fatalf("days=0 后仍应为 4 行，实际 %d", count())
	}

	// 90 天保留：删掉 100/95 天前那两条。
	deleted, err := s.CleanupStatusHistory(90)
	if err != nil {
		t.Fatalf("CleanupStatusHistory: %v", err)
	}
	if deleted != 2 {
		t.Fatalf("应删 2 行，实际删 %d（SQL 语法可能在 SQLite 上静默失败）", deleted)
	}
	if got := count(); got != 2 {
		t.Fatalf("清理后应剩 2 行，实际 %d", got)
	}
}

// PartnerOf：伴侣状态历史查的必须是对方，不是自己。
func TestPairPartnerOf(t *testing.T) {
	p := &Pair{ID: 1, UserAID: 10, UserBID: 20}
	if got := p.PartnerOf(10); got != 20 {
		t.Fatalf("PartnerOf(10) 应为 20，实际 %d", got)
	}
	if got := p.PartnerOf(20); got != 10 {
		t.Fatalf("PartnerOf(20) 应为 10，实际 %d", got)
	}
	if got := p.PartnerOf(999); got != 0 {
		t.Fatalf("非成员应返回 0，实际 %d", got)
	}
}
