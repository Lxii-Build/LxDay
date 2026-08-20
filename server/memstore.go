package main

import (
	"sync"
	"time"
)

// memStore 单实例内存态，替代 Redis（本项目改为单容器 + SQLite 部署，无外部缓存）。
// 覆盖：在线态、伴侣最新状态缓存、离线事件补偿队列、通用 TTL 键值（邮箱验证码）、计数器（限频/冷却）。
// 全部带惰性过期；单实例足够，多副本部署需另换共享存储。
type memStore struct {
	mu      sync.Mutex
	online  map[int64]time.Time
	status  map[int64]*DeviceStatus
	eventQ  map[int64][]queuedEvent
	kv      map[string]memEntry
	counter map[string]memCounter
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
	m.mu.Lock()
	m.kv[key] = memEntry{val: val, exp: time.Now().Add(ttl)}
	m.mu.Unlock()
}

// kvGet 读取未过期的字符串。
func (m *memStore) kvGet(key string) (string, bool) {
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
	m.mu.Lock()
	delete(m.kv, key)
	m.mu.Unlock()
}

// kvSetNX 仅当键不存在（或已过期）时置位，返回是否置位成功（用于发送冷却）。
func (m *memStore) kvSetNX(key string, ttl time.Duration) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if e, ok := m.kv[key]; ok && time.Now().Before(e.exp) {
		return false
	}
	m.kv[key] = memEntry{val: "1", exp: time.Now().Add(ttl)}
	return true
}

// incr 计数器自增；首次自增时设置 TTL 窗口。返回窗口内当前计数。
func (m *memStore) incr(key string, ttl time.Duration) int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	c, ok := m.counter[key]
	if !ok || now.After(c.exp) {
		c = memCounter{n: 0, exp: now.Add(ttl)}
	}
	c.n++
	m.counter[key] = c
	return c.n
}

// incrBy 按给定增量累加（相册上传配额要累加字节数，不是次数）。返回窗口内当前累计值。
func (m *memStore) incrBy(key string, delta int64, ttl time.Duration) int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	c, ok := m.counter[key]
	if !ok || now.After(c.exp) {
		c = memCounter{n: 0, exp: now.Add(ttl)}
	}
	c.n += delta
	m.counter[key] = c
	return c.n
}

// count 返回计数器当前值（过期视为 0）。
func (m *memStore) count(key string) int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.counter[key]
	if !ok || time.Now().After(c.exp) {
		return 0
	}
	return c.n
}

func (m *memStore) del(key string) {
	m.mu.Lock()
	delete(m.counter, key)
	delete(m.kv, key)
	m.mu.Unlock()
}
