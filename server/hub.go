package main

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// ================= WebSocket Hub =================
// 单机版：内存维护 user_id -> conn。
// 多节点部署时：改为把消息写入 Redis Pub/Sub，各节点订阅后本地投递，
// 或使用 keyStatus 所在节点的 WS 路由，此处给出单机实现 + 扩展点。

// maxWSMessageBytes 单个 WS 帧的最大字节数。上行只有状态上报与互动事件，64KB 绰绰有余。
const maxWSMessageBytes = 64 * 1024

// maxStatusUpdatesPerSec 单用户 status_update 的写库频率上限。
// 每条 status_update 都会落 SQLite，不限频的话一个客户端死循环上报就能把库写满/拖垮。
const maxStatusUpdatesPerSec = 2

func statusRateKey(uid int64) string {
	return "statusrate:" + strconv.FormatInt(uid, 10)
}

// allowStatusUpdate 上行限频。
//
// 每条 status_update 都会写内存态并尝试落库，客户端若因 bug 死循环上报
// （或被恶意构造），能把 SQLite 写爆。超频只丢弃本条、不断连——
// 正常客户端偶发突发不该被踢下线。
//
// WS 与 REST 兜底（POST /status）共用同一个闸门，否则换条路径就能绕过限频。
func (h *Hub) allowStatusUpdate(uid int64) bool {
	return h.store.mem.incr(statusRateKey(uid), time.Second) <= maxStatusUpdatesPerSec
}

// applyStatusUpdate 是状态落地的**唯一实现**：写内存态 → 落历史 → 低电量提醒 → 转发对方。
//
// 抽出来是因为现在有两条入口（WS 的 status_update 与 REST 的 POST /status），
// 各写一份必然分叉——比如某天只在 WS 那份里加了新逻辑，
// 走 HTTP 兜底的用户就会得到不一样的行为，而这种 bug 极难发现。
func (h *Hub) applyStatusUpdate(pair *Pair, from int64, st *DeviceStatus) {
	partner := h.store.PartnerID(pair, from)

	st.UserID = from
	st.UpdatedAt = time.Now().UnixMilli()
	h.store.SaveStatus(st)

	// 落状态历史（5 分钟一条，INSERT OR IGNORE 幂等）。
	// 注意：客户端的周期上报由 SyncHeartbeat 按前台/后台/息屏分档驱动
	//（10s/60s/5min），并非固定 5 分钟，故这里靠 Truncate 去重而不依赖客户端节奏。
	now := time.Now().Truncate(5 * time.Minute)
	if err := h.store.InsertStatusHistory(pair, from, st, now); err != nil {
		slog.Error("insert status history failed", "err", err)
	}

	// 低电量(<15%)即时高优推送：状态变更 → 对方收到提醒
	if st.BatteryLevel > 0 && st.BatteryLevel < 15 {
		payload := map[string]interface{}{
			"battery": st.BatteryLevel, "ts": st.UpdatedAt,
		}
		if u, err := h.store.GetUserByID(from); err == nil {
			payload["from_name"] = u.Nickname
		}
		h.route(partner, WsMessage{Type: MsgLowBattery, Data: payload})
	}

	// 转发给对方。用序列化后的 st 而非原始 m.Data：
	// 这样 REST 与 WS 两条入口转发出去的载荷结构完全一致。
	h.route(partner, WsMessage{Type: MsgPartnerStatus, Data: st})
}

// wsClient 为单个连接封装写互斥：gorilla/websocket 禁止并发写同一连接，
// scanDueTodos 定时器、对方状态转发、后台群发可能并发写同一接收者，故所有写必须串行化。
type wsClient struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

func (c *wsClient) write(b []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteMessage(websocket.TextMessage, b)
}

type Hub struct {
	mu    sync.RWMutex
	conns map[int64]*wsClient
	store *Store
	push  *PushGateway
}

// checkWSOrigin 收紧 WebSocket 跨域（防 CSWSH）：
// 原生 App（OkHttp）不带 Origin → 放行；带 Origin 时仅允许与请求 Host 同源（后台同域）。
func checkWSOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true // 原生客户端无 Origin
	}
	i := strings.Index(origin, "://")
	if i < 0 {
		return false
	}
	host := origin[i+3:]
	// 去掉可能的路径
	if s := strings.IndexByte(host, '/'); s >= 0 {
		host = host[:s]
	}
	return strings.EqualFold(host, r.Host)
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     checkWSOrigin,
}

func NewHub(s *Store, p *PushGateway) *Hub {
	return &Hub{conns: map[int64]*wsClient{}, store: s, push: p}
}

func (h *Hub) ServeWS(w http.ResponseWriter, r *http.Request, uid int64) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		slog.Warn("ws upgrade failed", "err", err)
		return
	}
	defer conn.Close()

	client := &wsClient{conn: conn}
	h.mu.Lock()
	old := h.conns[uid]
	h.conns[uid] = client
	h.mu.Unlock()
	if old != nil {
		old.conn.Close()
	}
	h.store.SetOnline(uid, true)

	// 上线补偿：先推对方最新状态，再补离线事件（含未绑定时错过的 paired 事件）
	h.pushLatestPartner(uid)
	for _, msg := range h.store.PopEventQ(uid) {
		client.write([]byte(msg))
	}

	// 单帧体积上限：不设时单个连接发一个超大帧即可让服务端分配同等内存，
	// 一条连接就能把进程打到 OOM。业务上行最大的是 status_update，远小于 64KB。
	conn.SetReadLimit(maxWSMessageBytes)

	// 空闲保活：客户端每 15s 发 PING，服务端在收到 PING 时刷新读超时并回 PONG。
	// 判死 45s ≈ 3 个心跳周期（原为 90s，对方断网后最长 90 秒这边还以为在线）。
	const idleTimeout = 45 * time.Second
	conn.SetReadDeadline(time.Now().Add(idleTimeout))
	conn.SetPingHandler(func(appData string) error {
		conn.SetReadDeadline(time.Now().Add(idleTimeout))
		client.mu.Lock()
		_ = conn.WriteControl(websocket.PongMessage, []byte(appData), time.Now().Add(10*time.Second))
		client.mu.Unlock()
		return nil
	})
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(idleTimeout))
	})

	// 单读循环：客户端消息
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			break
		}
		conn.SetReadDeadline(time.Now().Add(idleTimeout))
		h.handleIncoming(uid, msg)
	}

	h.mu.Lock()
	if h.conns[uid] == client {
		delete(h.conns, uid)
	}
	h.mu.Unlock()
	h.store.SetOnline(uid, false)
}

// 处理客户端上行消息
func (h *Hub) handleIncoming(from int64, data []byte) {
	var m WsMessage
	if err := json.Unmarshal(data, &m); err != nil {
		return
	}
	pair, err := h.store.GetPairByUserID(from)
	if err != nil {
		return // 未绑定不转发
	}
	partner := h.store.PartnerID(pair, from)

	switch m.Type {
	case MsgStatusUpdate:
		if !h.allowStatusUpdate(from) {
			return
		}
		b, _ := json.Marshal(m.Data)
		var st DeviceStatus
		if err := json.Unmarshal(b, &st); err != nil {
			return
		}
		h.applyStatusUpdate(pair, from, &st)

	case MsgWifiJoined:
		// 客户端检测到本机连接了「关注 WiFi」，转发给对方
		payload := map[string]interface{}{"ts": time.Now().UnixMilli()}
		if u, err := h.store.GetUserByID(from); err == nil {
			payload["from_name"] = u.Nickname
		}
		h.route(partner, WsMessage{Type: MsgWifiJoined, Data: payload})

	case MsgRingRequest, MsgComfortRequest, MsgCalmRequest:
		var raw struct {
			Data map[string]interface{} `json:"data"`
		}
		json.Unmarshal(data, &raw)
		payload := raw.Data
		if payload == nil {
			payload = map[string]interface{}{}
		}
		// 附加发送方昵称
		if u, err := h.store.GetUserByID(from); err == nil {
			payload["from_name"] = u.Nickname
		}
		payload["ts"] = time.Now().UnixMilli()
		// 限频：响铃与安抚/冷静各自独立计数。
		// 超频不再静默丢弃——必须回执给发送方，否则客户端 UI 仍显示"已发送"（假成功）。
		switch m.Type {
		case MsgRingRequest:
			if !h.store.RingCooldown(pair.ID) {
				slog.Warn("ring rejected by cooldown", "pair_id", pair.ID, "uid", from)
				h.rejectAction(from, m.Type, "对方 10 分钟内已被响铃 3 次，请稍后再试")
				return
			}
		case MsgComfortRequest, MsgCalmRequest:
			if !h.store.InteractionCooldown(pair.ID, m.Type) {
				slog.Warn("interaction rejected by cooldown", "type", m.Type, "pair_id", pair.ID, "uid", from)
				h.rejectAction(from, m.Type, "操作过于频繁，请稍等几秒再试")
				return
			}
		}
		h.route(partner, WsMessage{Type: m.Type, Data: payload})

	case MsgRingCancel, MsgRingStopped:
		// 撤回与回执：原样透传给对方，不限频、不入离线队列（见 transientEvents）。
		// 撤回被限频会导致"想停却停不了"，比骚扰更糟。
		var raw struct {
			Data map[string]interface{} `json:"data"`
		}
		json.Unmarshal(data, &raw)
		payload := raw.Data
		if payload == nil {
			payload = map[string]interface{}{}
		}
		payload["ts"] = time.Now().UnixMilli()
		h.route(partner, WsMessage{Type: m.Type, Data: payload})

	default:
		// 业务事件（todo_new / diary_new 等）由 HTTP handler 转发时调用
		h.route(partner, m)
	}
}

// rejectAction 把"这次上行动作被拒绝"回执给发送方本人。
// 此前超频只 log 后静默 return，发送方 UI 仍显示"已发送"，用户以为送达了。
func (h *Hub) rejectAction(to int64, action, reason string) {
	h.route(to, WsMessage{Type: MsgActionRejected, Data: map[string]interface{}{
		"action": action,
		"reason": reason,
		"ts":     time.Now().UnixMilli(),
	}})
}

// route: 在线直转 WS，离线缓存事件 + 走厂商推送
func (h *Hub) route(to int64, m WsMessage) {
	b, _ := json.Marshal(m)

	h.mu.RLock()
	client, ok := h.conns[to]
	h.mu.RUnlock()
	if ok {
		if err := client.write(b); err == nil {
			return
		}
		// 写失败（连接已坏）：降级为离线补偿
	}
	// 瞬时事件（撤回/回执/拒绝）不入队：迟到送达无意义，重连后突然收到一小时前的
	// "对方撤回了响铃"只会造成困惑，而彼时本地响铃早已由 7s 定时器自行结束。
	if isTransient(m.Type) {
		return
	}
	// 离线：先入补偿队列
	h.store.PushEventQ(to, string(b))
	// 高优事件再走系统推送，保证离线必达
	if isHighPriority(m.Type) {
		h.push.Send(to, m.Type, m.Data)
	}
}

// 上线时推送对方最新状态
func (h *Hub) pushLatestPartner(uid int64) {
	pair, err := h.store.GetPairByUserID(uid)
	if err != nil {
		return
	}
	partner := h.store.PartnerID(pair, uid)
	st, err := h.store.GetStatus(partner)
	if err != nil || st == nil {
		return
	}
	b, _ := json.Marshal(st)
	h.mu.RLock()
	client, ok := h.conns[uid]
	h.mu.RUnlock()
	if ok {
		client.write([]byte(`{"type":"partner_status","data":` + string(b) + `}`))
	}
}

// 业务层转发工具（HTTP handler 在创建待办/日记后调用）
func (h *Hub) Notify(pair *Pair, from int64, m WsMessage) {
	partner := h.store.PartnerID(pair, from)
	h.route(partner, m)
}
