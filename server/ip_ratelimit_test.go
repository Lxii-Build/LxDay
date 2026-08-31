package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

// ================= 按 IP 限流与单账号在飞行上限 =================
//
// 这两道闸门的共同前提是「攻击者已持有合法 APP_KEY」（它编在 APK 里，
// 逆向即得，不构成安全边界）。在那个前提下，此前所有限流的键
// （账号名/邮箱/uid）都由攻击者自己控制、换一个就从零开始。
// 详见 ip_ratelimit.go 顶部。

// TestAllowByIPEnforcesWindow 窗口内到量即拒。
func TestAllowByIPEnforcesWindow(t *testing.T) {
	withTestStore(t)
	spec := ipRateSpec{name: "unittest", limit: 3, window: time.Hour, msg: "x"}

	for i := 1; i <= 3; i++ {
		if !allowByIP(spec, "1.2.3.4") {
			t.Fatalf("第 %d 次（未到上限 %d）不该被拒", i, spec.limit)
		}
	}
	if allowByIP(spec, "1.2.3.4") {
		t.Error("第 4 次超过上限 3，必须被拒")
	}
	// 另一个 IP 不受影响 —— 否则一个攻击者就能顺带把所有人锁死。
	if !allowByIP(spec, "5.6.7.8") {
		t.Error("不同 IP 的计数必须相互独立，否则一个 IP 就能拖累全站")
	}
}

// TestAllowByIPFailsOpenOnEmptyIP 取不到 IP 时放行。
//
// 这里**故意**选 fail-open：拿不到 IP 就拒绝会把正常用户全拦住，
// 而兜底的内存安全闸门（像素上限/帧数扫描/并发闸门）都不依赖 IP。
func TestAllowByIPFailsOpenOnEmptyIP(t *testing.T) {
	withTestStore(t)
	spec := ipRateSpec{name: "emptyip", limit: 1, window: time.Hour, msg: "x"}
	for i := 0; i < 5; i++ {
		if !allowByIP(spec, "") {
			t.Fatal("空 IP 必须放行（fail-open），否则拿不到 IP 就等于全站不可用")
		}
	}
}

// TestIPRateLimitMiddlewareBlocksWithChineseMessage 中间件返回 429 + 中文文案，
// 且**不执行 handler**（限流必须在 handler 的开销之前生效）。
func TestIPRateLimitMiddlewareBlocksWithChineseMessage(t *testing.T) {
	withTestStore(t)
	gin.SetMode(gin.TestMode)

	spec := ipRateSpec{name: "mw", limit: 2, window: time.Hour, msg: "注册过于频繁，请稍后再试"}
	var handlerHits int
	r := gin.New()
	r.POST("/t", IPRateLimit(spec), func(c *gin.Context) {
		handlerHits++
		ok(c, gin.H{})
	})

	call := func() *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/t", strings.NewReader("{}"))
		req.RemoteAddr = "9.9.9.9:1234"
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		return w
	}

	for i := 1; i <= 2; i++ {
		if got := call().Code; got != 200 {
			t.Fatalf("第 %d 次应放行，实得 %d", i, got)
		}
	}
	w := call()
	if w.Code != http.StatusTooManyRequests {
		t.Errorf("超限应回 429，实得 %d", w.Code)
	}
	if !strings.Contains(w.Body.String(), "过于频繁") {
		t.Errorf("响应必须带中文提示（面向用户的文案用中文），实得 %s", w.Body.String())
	}
	if handlerHits != 2 {
		t.Errorf("被限流的请求不该进入 handler，handler 执行了 %d 次（应为 2）", handlerHits)
	}
}

// TestAcquireUserSlotCapsAndReleases 槽位到顶即拒，释放后可再取。
func TestAcquireUserSlotCapsAndReleases(t *testing.T) {
	withTestStore(t)
	const uid int64 = 42

	releases := make([]func(), 0, maxInFlightPerUser)
	for i := 0; i < maxInFlightPerUser; i++ {
		rel, okSlot := acquireUserSlot(uid)
		if !okSlot {
			t.Fatalf("第 %d 个槽位（上限 %d）不该被拒", i+1, maxInFlightPerUser)
		}
		releases = append(releases, rel)
	}
	if _, okSlot := acquireUserSlot(uid); okSlot {
		t.Errorf("超过 %d 个在飞请求必须被拒", maxInFlightPerUser)
	}

	// 释放一个后必须能再取一个 —— 否则用户传几张之后就永久传不了了，
	// 那比不限流更糟（表现为"上传莫名失败"且自己会恢复不了）。
	releases[0]()
	rel, okSlot := acquireUserSlot(uid)
	if !okSlot {
		t.Fatal("释放后必须能重新占用，否则槽位泄漏会让该账号永久无法上传")
	}
	rel()
	for _, f := range releases[1:] {
		f()
	}

	// 全部释放后计数应回到 0：泄漏在这里现形。
	if n := st.mem.count(inFlightKey(uid)); n != 0 {
		t.Errorf("全部释放后在飞计数应为 0，实得 %d —— 槽位泄漏", n)
	}
}

// TestAcquireUserSlotIsolatesUsers 一个用户占满不能影响别人。
//
// 这正是加这道闸门的**目的**：全局解码闸门只有 3 个槽位，
// 一个账号发 3 个并发上传就能占满，其余用户全部排队到超时 ——
// 内存曲线平稳但别人都传不上去，是一种不打爆内存的拒绝服务。
func TestAcquireUserSlotIsolatesUsers(t *testing.T) {
	withTestStore(t)
	for i := 0; i < maxInFlightPerUser; i++ {
		if _, okSlot := acquireUserSlot(1); !okSlot {
			t.Fatalf("用户 1 的第 %d 个槽位不该被拒", i+1)
		}
	}
	if _, okSlot := acquireUserSlot(2); !okSlot {
		t.Error("用户 1 占满自己的额度后，用户 2 必须仍能上传")
	}
	// 单用户上限必须严格小于全局闸门，否则一个账号就能占满全部槽位。
	if maxInFlightPerUser >= maxConcurrentDecodes {
		t.Errorf("单用户上限 %d 必须小于全局闸门 %d，否则至少留 1 个槽位给其他人的保证不成立",
			maxInFlightPerUser, maxConcurrentDecodes)
	}
}

// TestAcquireUserSlotConcurrent 并发下不得被击穿。
//
// 必须用 incr（先原子自增，再按返回值判定并在超限时回退）而不是"先查后写"：
// 后者会让多个请求同时读到同一个旧值、全部通过 ——
// 这正是 reserveUploadQuota 修过的那个坑。
//
// ★ 为什么跑多轮而不是一轮 ★
// 实测把实现改回"先查后写"时，单轮只有约 1/4 的概率抓到（击穿需要恰好
// 交错在那个窗口里）。一个 3/4 概率放过 bug 的测试等于没有 —— 它会以
// "CI 偶尔红一次"的形式存在，然后被当成抖动忽略掉。
// 多轮 + 累计判定把它变成确定性的：25 轮全部漏掉的概率可以忽略。
func TestAcquireUserSlotConcurrent(t *testing.T) {
	withTestStore(t)
	const goroutines = 64
	const rounds = 60

	worstGranted := 0
	breachedRounds := 0
	for round := 0; round < rounds; round++ {
		uid := int64(1000 + round) // 每轮换 uid，避免上一轮的残留计数干扰
		var mu sync.Mutex
		granted := 0
		var releases []func()

		var wg sync.WaitGroup
		var ready sync.WaitGroup
		start := make(chan struct{})
		for i := 0; i < goroutines; i++ {
			wg.Add(1)
			ready.Add(1)
			go func() {
				defer wg.Done()
				ready.Done()
				<-start // 所有 goroutine 就位后再一起冲，最大化交错窗口
				if rel, okSlot := acquireUserSlot(uid); okSlot {
					mu.Lock()
					granted++
					releases = append(releases, rel)
					mu.Unlock()
				}
			}()
		}
		ready.Wait()
		close(start)
		wg.Wait()

		if granted > worstGranted {
			worstGranted = granted
		}
		if granted != maxInFlightPerUser {
			breachedRounds++
		}
		for _, rel := range releases {
			rel()
		}
		if n := st.mem.count(inFlightKey(uid)); n != 0 {
			t.Fatalf("第 %d 轮全部释放后计数应为 0，实得 %d —— 槽位泄漏", round, n)
		}
	}

	if breachedRounds > 0 {
		t.Errorf("%d/%d 轮被击穿（最坏一轮放行了 %d 个，上限 %d）—— "+
			"acquireUserSlot 必须先原子自增再判定，不能先查后写",
			breachedRounds, rounds, worstGranted, maxInFlightPerUser)
	}
}
