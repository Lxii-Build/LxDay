package main

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

// ================= memStore 不再泄露的回归测试 =================
//
// 这一组对应 0827 生产 OOM 的根因。每个测试都刻意针对一个**具体的旧行为**，
// 把实现改回旧写法时必须变红——否则它只是在描述现状，不是在防回归。

// TestCountDeletesExpiredKey 是最关键的一条。
//
// 旧实现的 count() 在过期分支里直接 `return 0`，不删键。而 `login:fail:<account>`
// 的 account 来自请求体、可以是任何不存在的账号名：每一次失败的登录尝试
// 都会留下一条**永远不会再被读到**的 entry（下次没人会再查这个账号），
// 于是惰性过期这条唯一的回收路径对它完全无效。
func TestCountDeletesExpiredKey(t *testing.T) {
	m := newMemStore()
	m.incr("login:fail:ghost", 10*time.Millisecond)
	if len(m.counter) != 1 {
		t.Fatalf("前置条件不成立：期望 1 条计数器，实得 %d", len(m.counter))
	}

	time.Sleep(20 * time.Millisecond)

	if got := m.count("login:fail:ghost"); got != 0 {
		t.Errorf("过期计数器应读作 0，实得 %d", got)
	}
	// ★ 这一行是本测试的全部意义：旧实现只 return 0，键会留在表里。
	if len(m.counter) != 0 {
		t.Errorf("count() 读到过期键时必须删除它，现在仍有 %d 条残留", len(m.counter))
	}
}

// TestSweepRemovesExpiredEntries 覆盖定时清扫。
//
// 单靠 count() 的惰性删除是不够的：`media:cnt:<日期>:<uid>` 这类键按日期分桶，
// 过了那天谁都不会再去读它，永远等不到那次惰性回收。必须有人主动扫。
func TestSweepRemovesExpiredEntries(t *testing.T) {
	m := newMemStore()

	// 过期的：应被清掉
	m.incr("media:cnt:2020-01-01:7", 10*time.Millisecond)
	m.kvSet("emailcode:old@example.com", "123456", 10*time.Millisecond)
	m.setOnline(42, true, 10*time.Millisecond)

	// 未过期的：必须留下
	m.incr("media:cnt:2099-01-01:7", time.Hour)
	m.kvSet("emailcode:fresh@example.com", "654321", time.Hour)
	m.setOnline(43, true, time.Hour)

	time.Sleep(20 * time.Millisecond)

	removed := m.sweep()
	if removed != 3 {
		t.Errorf("期望清掉 3 条（1 计数器 + 1 kv + 1 在线态），实得 %d", removed)
	}
	if len(m.counter) != 1 {
		t.Errorf("未过期计数器被误删：剩 %d 条，期望 1", len(m.counter))
	}
	if len(m.kv) != 1 {
		t.Errorf("未过期 kv 被误删：剩 %d 条，期望 1", len(m.kv))
	}
	if len(m.online) != 1 {
		t.Errorf("未过期在线态被误删：剩 %d 条，期望 1", len(m.online))
	}
	if v, ok := m.kvGet("emailcode:fresh@example.com"); !ok || v != "654321" {
		t.Errorf("清扫后未过期的值应可读，得 %q ok=%v", v, ok)
	}
}

// TestSweepDropsStaleEventQueue 离线事件队列的清扫。
//
// popEvents 只在用户**重新上线**时才丢弃过期事件，而"再也不上线的账号"
// 恰恰是最会堆积的那一类：对方每天给他建待办，事件一条条进队列，没人来取。
func TestSweepDropsStaleEventQueue(t *testing.T) {
	m := newMemStore()
	m.pushEvent(1, `{"type":"todo_new"}`)
	// 手动把入队时间改成 TTL 之前，模拟"很久没上线"。
	// 直接改内部字段而不是 sleep：eventQTTL 是 24 小时，不可能真等。
	m.eventQ[1][0].at = time.Now().Add(-eventQTTL - time.Minute)
	m.pushEvent(2, `{"type":"todo_new"}`) // 新的，要留

	if removed := m.sweep(); removed != 1 {
		t.Errorf("期望清掉 1 条过期事件，实得 %d", removed)
	}
	if _, exists := m.eventQ[1]; exists {
		t.Error("整条队列都过期的用户，其 map 项本身也应删除（否则 key 永久残留）")
	}
	if len(m.eventQ[2]) != 1 {
		t.Errorf("未过期事件被误删：用户 2 剩 %d 条，期望 1", len(m.eventQ[2]))
	}
}

// TestNormalizeMemKeyBoundsLength 超长 key 必须被收敛。
//
// account/email 直接来自请求体且无长度校验，一个几 MB 的 account 字段
// 原先会换来一条几 MB 的常驻 key。
func TestNormalizeMemKeyBoundsLength(t *testing.T) {
	long := "login:fail:" + strings.Repeat("a", 1<<20) // 1MB
	got := normalizeMemKey(long)
	if len(got) > maxMemKeyLen+80 {
		t.Errorf("归一化后的 key 仍然过长：%d 字节", len(got))
	}
	if !strings.HasPrefix(got, "login:fail:") {
		t.Errorf("应保留前缀便于排查，实得 %q", got[:min(40, len(got))])
	}

	// 短 key 必须原样返回，否则所有既有键的语义都变了。
	if k := normalizeMemKey("login:fail:alice"); k != "login:fail:alice" {
		t.Errorf("正常长度的 key 不应被改写，实得 %q", k)
	}

	// ★ 用哈希而非截断的理由：截断会让两个不同的超长账号名撞成同一个键，
	// 于是攻击者能用一个超长账号名去顶掉别人的失败计数（替他人清零限流）。
	a := normalizeMemKey("login:fail:" + strings.Repeat("a", 300))
	b := normalizeMemKey("login:fail:" + strings.Repeat("a", 300) + "b")
	if a == b {
		t.Error("两个不同的超长 key 归一化后相同 —— 会导致限流计数被互相顶掉")
	}
}

// TestMemStoreCapacityBounded 容量上限兜底。
//
// 即使清扫因为某个 bug 停了、或攻击者写入速度快过清扫周期，
// 表的大小也必须有确定的天花板。
func TestMemStoreCapacityBounded(t *testing.T) {
	m := newMemStore()
	// 全部用长 TTL，确保清扫无从下手，只能靠容量上限生效。
	for i := 0; i < maxMemKeys+500; i++ {
		m.incr(fmt.Sprintf("login:fail:user%d", i), time.Hour)
	}
	if len(m.counter) > maxMemKeys {
		t.Errorf("计数器表越过容量上限：%d > %d", len(m.counter), maxMemKeys)
	}

	for i := 0; i < maxMemKeys+500; i++ {
		m.kvSet(fmt.Sprintf("emailcode:u%d@x.com", i), "1", time.Hour)
	}
	if len(m.kv) > maxMemKeys {
		t.Errorf("kv 表越过容量上限：%d > %d", len(m.kv), maxMemKeys)
	}
}

// TestKvSetNXExpiredKeyReusable 过期的 NX 键要能被重新置位。
//
// 验证码发送冷却依赖这个语义：冷却过去之后必须能再发一次。
func TestKvSetNXExpiredKeyReusable(t *testing.T) {
	m := newMemStore()
	if !m.kvSetNX("emailcode:cd:a@x.com", 10*time.Millisecond) {
		t.Fatal("首次置位应成功")
	}
	if m.kvSetNX("emailcode:cd:a@x.com", time.Hour) {
		t.Error("冷却期内不应能再次置位")
	}
	time.Sleep(20 * time.Millisecond)
	if !m.kvSetNX("emailcode:cd:a@x.com", time.Hour) {
		t.Error("冷却过期后应能重新置位（否则用户永远发不了第二次验证码）")
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
