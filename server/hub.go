package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// ================= WebSocket Hub =================
// 单机版：内存维护 user_id -> conn。
// 多节点部署时：改为把消息写入 Redis Pub/Sub，各节点订阅后本地投递，
// 或使用 keyStatus 所在节点的 WS 路由，此处给出单机实现 + 扩展点。

type Hub struct {
	mu    sync.RWMutex
	conns map[int64]*websocket.Conn
	store *Store
	push  *PushGateway
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     func(r *http.Request) bool { return true }, // 生产可收紧
}

func NewHub(s *Store, p *PushGateway) *Hub {
	return &Hub{conns: map[int64]*websocket.Conn{}, store: s, push: p}
}

func (h *Hub) ServeWS(w http.ResponseWriter, r *http.Request, uid int64) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("upgrade error: %v", err)
		return
	}
	defer conn.Close()

	h.mu.Lock()
	old := h.conns[uid]
	h.conns[uid] = conn
	h.mu.Unlock()
	if old != nil {
		old.Close()
	}
	h.store.SetOnline(uid, true)

	// 上线补偿：先推对方最新状态，再补离线事件
	h.pushLatestPartner(uid)
	for _, msg := range h.store.PopEventQ(uid) {
		conn.WriteMessage(websocket.TextMessage, []byte(msg))
	}

	// 写心跳由客户端 ping 触发（客户端已设 pingInterval=30s），
	// 服务端做 Pong 超时清理。
	conn.SetReadDeadline(time.Now().Add(90 * time.Second))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(90 * time.Second))
	})

	// 单读循环：客户端消息
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			break
		}
		h.handleIncoming(uid, msg)
	}

	h.mu.Lock()
	delete(h.conns, uid)
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
		// 状态写入 Redis，原样转发对方
		b, _ := json.Marshal(m.Data)
		var st DeviceStatus
		if err := json.Unmarshal(b, &st); err == nil {
			st.UserID = from
			st.UpdatedAt = time.Now().UnixMilli()
			h.store.SaveStatus(&st)
			// 落状态历史（5 分钟一条，INSERT IGNORE 幂等；客户端 5min 上报天然对齐）
			now := time.Now().Truncate(5 * time.Minute)
			if err := h.store.InsertStatusHistory(pair, from, &st, now); err != nil {
				log.Printf("insert status history error: %v", err)
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
		}
		h.route(partner, WsMessage{Type: MsgPartnerStatus, Data: m.Data})

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
		// 响铃限频
		if m.Type == MsgRingRequest && !h.store.RingCooldown(pair.ID) {
			// 超频：直接丢弃，避免骚扰
			log.Printf("ring too frequent pair=%d", pair.ID)
			return
		}
		h.route(partner, WsMessage{Type: m.Type, Data: payload})

	default:
		// 业务事件（todo_new / diary_new 等）由 HTTP handler 转发时调用
		h.route(partner, m)
	}
}

// route: 在线直转 WS，离线缓存事件 + 走厂商推送
func (h *Hub) route(to int64, m WsMessage) {
	b, _ := json.Marshal(m)

	h.mu.RLock()
	conn, ok := h.conns[to]
	h.mu.RUnlock()
	if ok {
		conn.WriteMessage(websocket.TextMessage, b)
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
	conn, ok := h.conns[uid]
	h.mu.RUnlock()
	if ok {
		conn.WriteMessage(websocket.TextMessage,
			[]byte(`{"type":"partner_status","data":`+string(b)+`}`))
	}
}

// 业务层转发工具（HTTP handler 在创建待办/日记后调用）
func (h *Hub) Notify(pair *Pair, from int64, m WsMessage) {
	partner := h.store.PartnerID(pair, from)
	h.route(partner, m)
}
