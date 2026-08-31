package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"log/slog"
	"sync"
	"time"
)

// memStore 单实例内存态，替代 Redis（本项目改为单容器 + SQLite 部署，无外部缓存）。
// 覆盖：在线态、伴侣最新状态缓存、离线事件补偿队列、通用 TTL 键值（邮箱验证码）、计数器（限频/冷却）。
// 单实例足够，多副本部署需另换共享存储。
//
// ★★ 0827：这里曾是生产 OOM 的根因，三个缺陷叠加 ★★
//
//  1. **只有惰性过期，没有任何清扫**。而"惰性"的前提是"过期后还会被再读一次"——
//     偏偏本项目最主要的两类键**永远不会被读第二次**：
//     - `login:fail:<account>` / `adminlogin:fail:<uname>`：key 由**未注册**的账号名构成，
//     枚举一轮就是一轮永久 entry；
//     - `media:cnt:<日期>:<uid>` / `media:bytes:<日期>:<uid>`：**按日期分桶**，
//     过了今天那个桶再也不会被访问，于是每天每个上传过的用户都留下两条永久垃圾。
//     跑几周必涨，且只增不减。
//
//  2. **`count()` 与 `kvSetNX()` 在过期分支里只读不删**（`count` 直接 `return 0`、
//     `kvSetNX` 直接覆写），所以连"被读到时顺手清掉"这条唯一的回收路径都是断的。
//     真正会删的只有显式 `del()`，而 `del` 只在**登录成功**时才调用——
//     失败的那些（也就是垃圾的全部来源）一条都不会被清。
//
//  3. **key 长度不设上限**，而 `account` / `email` 直接来自请求体。
//     单个几 MB 的 account 字段就能换来一条几 MB 的常驻 key，放大倍数极高。
//
// 现在的处置是三道独立的闸，任一道单独都足以兜住：
//   - `sweep()` 定时清扫全部四张表（`startMemStoreJanitor`，每分钟）；
//   - 所有读写口子统一过 `normalizeMemKey` 截断超长 key；
//   - `maxMemKeys` 容量上限 + 到顶时按"最早过期"淘汰，
//     让"内存无界"在结构上不可能，而不是依赖清扫一定跑得过增长。
type memStore struct {
	mu      sync.Mutex
	online  map[int64]time.Time
	status  map[int64]*DeviceStatus
	eventQ  map[int64][]queuedEvent
	kv      map[string]memEntry
	counter map[string]memCounter
}

// maxMemKeys 是 kv 与 counter 两张表各自的容量上限。
//
// 这道闸是**兜底**，正常运行永远撞不到：本项目是情侣应用，真实并发键数是个位数到几十。
// 它存在的意义是「即使清扫因为某个 bug 停了、或者攻击者的写入速度快过清扫周期，
// 内存也有一个确定的天花板」。到顶时淘汰最早过期的那批，而不是拒绝写入——
// 拒绝写入会让限流计数器写不进去，等于**攻击者把表填满就能关掉限流**（fail-open）。
const maxMemKeys = 20000

// maxMemKeyLen key 的最大长度；超过则改用其 SHA-256 摘要。
//
// 128 对所有正常键都绰绰有余（最长的形如 `media:bytes:2026-08-27:12345`，不到 40）。
// 用哈希而不是直接截断：截断会让 `a...a` 与 `a...ab` 撞成同一个键，
// 于是攻击者可以用一个超长账号名去顶掉别人的失败计数（等于替他人清零限流）。
const maxMemKeyLen = 128

// normalizeMemKey 收敛 key 长度。所有 kv/counter 的入口都必须过它。
//
// **不要在调用点各自截断**：入口有 8 个（incr/incrBy/count/del/kvSet/kvGet/kvDel/kvSetNX），
// 漏一个就等于这道闸不存在，而漏掉的那个必然是将来新加的那个。
func normalizeMemKey(key string) string {
	if len(key) <= maxMemKeyLen {
		return key
	}
	sum := sha256.Sum256([]byte(key))
	// 保留前缀便于日志排查（前缀是代码写死的，不含用户数据）
	return key[:32] + ":h:" + hex.EncodeToString(sum[:])
}

type memEntry struct {
	val string
	exp time.Time
}

type memCounter struct {
	n   int64
	exp time.Time
}

func newMemStore() *memStore {
	return &memStore{
		online:  map[int64]time.Time{},
		status:  map[int64]*DeviceStatus{},
		eventQ:  map[int64][]queuedEvent{},
		kv:      map[string]memEntry{},
		counter: map[string]memCounter{},
	}
}

func (m *memStore) setOnline(uid int64, online bool, ttl time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if online {
		m.online[uid] = time.Now().Add(ttl)
	} else {
		delete(m.online, uid)
	}
}

func (m *memStore) isOnline(uid int64) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	exp, ok := m.online[uid]
	if !ok {
		return false
	}
	if time.Now().After(exp) {
		delete(m.online, uid)
		return false
	}
	return true
}

func (m *memStore) saveStatus(uid int64, s *DeviceStatus) {
	m.mu.Lock()
	m.status[uid] = s
	m.mu.Unlock()
}

func (m *memStore) getStatus(uid int64) *DeviceStatus {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.status[uid]
}

// 离线事件队列的上限与保鲜期。
//
// 队列在进程内存里，此前**既无长度上限也无 TTL**：
// 对一个长期离线的用户持续触发事件（或反复调用后台群发），内存会无界增长直到 OOM。
// 且过期太久的事件补拉出来也没有意义（用户不需要在三天后收到"对方在找你"）。
const (
	maxEventQPerUser = 100
	eventQTTL        = 24 * time.Hour
)

type queuedEvent struct {
	msg string
	at  time.Time
}

func (m *memStore) pushEvent(uid int64, msg string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	q := append(m.eventQ[uid], queuedEvent{msg: msg, at: time.Now()})
	// 超上限时丢弃最旧的：新事件比陈旧事件更有价值。
	if len(q) > maxEventQPerUser {
		q = q[len(q)-maxEventQPerUser:]
	}
	m.eventQ[uid] = q
}

func (m *memStore) popEvents(uid int64) []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	queued := m.eventQ[uid]
	delete(m.eventQ, uid)
	cutoff := time.Now().Add(-eventQTTL)
	msgs := make([]string, 0, len(queued))
	for _, e := range queued {
		if e.at.Before(cutoff) {
			continue // 过期事件直接丢弃，不再补拉
		}
		msgs = append(msgs, e.msg)
	}
	return msgs
}

// kvSet 写入带 TTL 的字符串（如邮箱验证码）。
func (m *memStore) kvSet(key, val string, ttl time.Duration) {
	key = normalizeMemKey(key)
	m.mu.Lock()
	defer m.mu.Unlock()
	m.ensureKVRoomLocked(key)
	m.kv[key] = memEntry{val: val, exp: time.Now().Add(ttl)}
}

// kvGet 读取未过期的字符串。
func (m *memStore) kvGet(key string) (string, bool) {
	key = normalizeMemKey(key)
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.kv[key]
	if !ok || time.Now().After(e.exp) {
		if ok {
			delete(m.kv, key)
		}
		return "", false
	}
	return e.val, true
}

func (m *memStore) kvDel(key string) {
	key = normalizeMemKey(key)
	m.mu.Lock()
	delete(m.kv, key)
	m.mu.Unlock()
}

// kvSetNX 仅当键不存在（或已过期）时置位，返回是否置位成功（用于发送冷却）。
func (m *memStore) kvSetNX(key string, ttl time.Duration) bool {
	key = normalizeMemKey(key)
	m.mu.Lock()
	defer m.mu.Unlock()
	if e, ok := m.kv[key]; ok && time.Now().Before(e.exp) {
		return false
	}
	// 走到这里说明键不存在或已过期。过期的那条会被下面整体覆写，
	// 不需要单独 delete；但容量检查要在覆写之前做。
	m.ensureKVRoomLocked(key)
	m.kv[key] = memEntry{val: "1", exp: time.Now().Add(ttl)}
	return true
}

// incr 计数器自增；首次自增时设置 TTL 窗口。返回窗口内当前计数。
func (m *memStore) incr(key string, ttl time.Duration) int64 {
	return m.addCounter(key, 1, ttl)
}

// incrBy 按给定增量累加（相册上传配额要累加字节数，不是次数）。返回窗口内当前累计值。
func (m *memStore) incrBy(key string, delta int64, ttl time.Duration) int64 {
	return m.addCounter(key, delta, ttl)
}

// addCounter 是 incr/incrBy 的唯一实现。
// 合并成一处是因为两者原先各写一遍"过期则重置窗口"的逻辑，
// 容量检查这类新增约束很容易只加到其中一个上。
func (m *memStore) addCounter(key string, delta int64, ttl time.Duration) int64 {
	key = normalizeMemKey(key)
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	c, ok := m.counter[key]
	if !ok || now.After(c.exp) {
		if !ok {
			m.ensureCounterRoomLocked(key)
		}
		c = memCounter{n: 0, exp: now.Add(ttl)}
	}
	c.n += delta
	m.counter[key] = c
	return c.n
}

// count 返回计数器当前值（过期视为 0）。
//
// 过期时**必须 delete**：这是本表唯一的惰性回收点，而它原先只 `return 0`。
// 少了这一行，`login:fail:<不存在的账号>` 这类键就永远留在表里
// —— 攻击者枚举账号名的每一次尝试都会换来一条永久驻留的 entry。
func (m *memStore) count(key string) int64 {
	key = normalizeMemKey(key)
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.counter[key]
	if !ok {
		return 0
	}
	if time.Now().After(c.exp) {
		delete(m.counter, key)
		return 0
	}
	return c.n
}

func (m *memStore) del(key string) {
	key = normalizeMemKey(key)
	m.mu.Lock()
	delete(m.counter, key)
	delete(m.kv, key)
	m.mu.Unlock()
}

// ---------- 容量上限与清扫 ----------

// ensureKVRoomLocked / ensureCounterRoomLocked 在插入**新键**前保证不越过容量上限。
// 调用方必须已持有 m.mu。
//
// 顺序是「先清过期 → 仍然满则淘汰最早过期的一批」：绝大多数情况第一步就够了，
// 第二步只在真被灌爆时才会走到。
func (m *memStore) ensureKVRoomLocked(key string) {
	if _, exists := m.kv[key]; exists || len(m.kv) < maxMemKeys {
		return
	}
	m.sweepLocked(time.Now())
	if len(m.kv) < maxMemKeys {
		return
	}
	evicted := evictEarliest(m.kv, func(e memEntry) time.Time { return e.exp }, maxMemKeys/10)
	slog.Warn("memstore kv at capacity, evicted earliest entries",
		"evicted", evicted, "size", len(m.kv), "limit", maxMemKeys)
}

func (m *memStore) ensureCounterRoomLocked(key string) {
	if _, exists := m.counter[key]; exists || len(m.counter) < maxMemKeys {
		return
	}
	m.sweepLocked(time.Now())
	if len(m.counter) < maxMemKeys {
		return
	}
	evicted := evictEarliest(m.counter, func(c memCounter) time.Time { return c.exp }, maxMemKeys/10)
	slog.Warn("memstore counter at capacity, evicted earliest entries",
		"evicted", evicted, "size", len(m.counter), "limit", maxMemKeys)
}

// evictEarliest 从 map 里删掉「最早过期」的 n 条，返回实际删除数。
//
// 没有用堆或 LRU 链表：这条路径正常运行永远不会被触发（容量 20000 而真实键数是几十），
// 为它引入一套需要在每次读写都维护的数据结构，反而是把复杂度加在了热路径上。
// 一次 O(size) 的扫描在被灌爆这种异常态下完全可以接受。
func evictEarliest[V any](mp map[string]V, expOf func(V) time.Time, n int) int {
	if n <= 0 {
		n = 1
	}
	type kt struct {
		key string
		exp time.Time
	}
	// 只保留当前最早的 n 个候选，避免为全表排序再分配一份等长切片。
	cands := make([]kt, 0, n)
	for k, v := range mp {
		e := expOf(v)
		if len(cands) < n {
			cands = append(cands, kt{k, e})
			continue
		}
		worst := 0
		for i := 1; i < len(cands); i++ {
			if cands[i].exp.After(cands[worst].exp) {
				worst = i
			}
		}
		if e.Before(cands[worst].exp) {
			cands[worst] = kt{k, e}
		}
	}
	for _, c := range cands {
		delete(mp, c.key)
	}
	return len(cands)
}

// sweep 清扫全部四张表里的过期项。由 janitor 定时调用，也可在测试里直接调。
func (m *memStore) sweep() (removed int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sweepLocked(time.Now())
}

func (m *memStore) sweepLocked(now time.Time) (removed int) {
	for k, e := range m.kv {
		if now.After(e.exp) {
			delete(m.kv, k)
			removed++
		}
	}
	for k, c := range m.counter {
		if now.After(c.exp) {
			delete(m.counter, k)
			removed++
		}
	}
	for uid, exp := range m.online {
		if now.After(exp) {
			delete(m.online, uid)
			removed++
		}
	}
	// 离线事件队列：整条队列都过期的用户，把 map 项本身也删掉。
	// popEvents 只在用户**重新上线**时才会清理，而"再也不上线的账号"正是会堆积的那种。
	cutoff := now.Add(-eventQTTL)
	for uid, q := range m.eventQ {
		kept := q[:0]
		for _, e := range q {
			if e.at.Before(cutoff) {
				removed++
				continue
			}
			kept = append(kept, e)
		}
		if len(kept) == 0 {
			delete(m.eventQ, uid)
			continue
		}
		m.eventQ[uid] = kept
	}
	// status 不清扫：它是「伴侣最新状态」缓存，上界为用户数，
	// 且过期概念不适用（最后一次状态永远有展示价值）。
	return removed
}

// memStoreSweepInterval 清扫周期。
//
// 一分钟远快于任何一类键的 TTL 下限（最短是状态限频的 1 秒，最长是验证码 15 分钟），
// 所以垃圾的驻留时间上界就是 TTL + 1 分钟，与运行时长无关——这才是"不再泄露"的含义。
const memStoreSweepInterval = time.Minute

// startMemStoreJanitor 起常驻清扫协程。
//
// 故意**不在 newMemStore 里起**：测试会创建几十个 store，
// 每个都拖一条永不退出的协程反而是另一种泄露。生产由 main 显式起一条。
func startMemStoreJanitor(ctx context.Context, wg *sync.WaitGroup, m *memStore) {
	wg.Add(1)
	go func() {
		defer wg.Done()
		t := time.NewTicker(memStoreSweepInterval)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if n := m.sweep(); n > 0 {
					slog.Debug("memstore swept", "removed", n)
				}
			}
		}
	}()
}
