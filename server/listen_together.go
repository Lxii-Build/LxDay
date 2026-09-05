package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

// 一起听只同步第三方平台的逻辑曲目和时间轴，不代替本机播放器。网易云 Cookie、
// 密码和解析出的播放地址永远留在设备端；房间只保存 song_id/标题等展示元数据。
// audio_url 是旧协议兼容字段，但服务端始终丢弃它，不能成为新的客户端上传凭据
// 或媒体代理。
type listenRoomState struct {
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	Source     string `json:"source,omitempty"`
	SongID     int64  `json:"song_id,omitempty"`
	Album      string `json:"album,omitempty"`
	CoverURL   string `json:"cover_url,omitempty"`
	AudioURL   string `json:"audio_url,omitempty"`
	DurationMs int64  `json:"duration_ms"`
	PositionMs int64  `json:"position_ms"`
	Playing    bool   `json:"playing"`
	Mode       string `json:"mode"` // list/repeat/shuffle
	UpdatedAt  int64  `json:"updated_at"`
}

type listenRoomSettings struct {
	AllowMemberControl      bool `json:"allow_member_control"`
	AutoPauseOnMemberChange bool `json:"auto_pause_on_member_change"`
}

type listenRoomView struct {
	RoomID    string             `json:"room_id"`
	PairID    int64              `json:"pair_id"`
	Host      int64              `json:"host_user_id"`
	State     listenRoomState    `json:"state"`
	Settings  listenRoomSettings `json:"settings"`
	CreatedAt time.Time          `json:"created_at"`
	UpdatedAt time.Time          `json:"updated_at"`
	Members   int                `json:"members"`
}

type listenSession struct {
	RoomID string
	UID    int64
	Host   bool
}

type listenControlRequest struct {
	Action     string `json:"action"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	Source     string `json:"source"`
	SongID     int64  `json:"song_id"`
	Album      string `json:"album"`
	CoverURL   string `json:"cover_url"`
	AudioURL   string `json:"audio_url"`
	DurationMs int64  `json:"duration_ms"`
	PositionMs int64  `json:"position_ms"`
	Playing    *bool  `json:"playing"`
	Mode       string `json:"mode"`
}

type listenWSClient struct {
	conn  *websocket.Conn
	mu    sync.Mutex
	room  string
	uid   int64
	token string
}

func (c *listenWSClient) writeJSON(v interface{}) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.conn.SetWriteDeadline(time.Now().Add(wsWriteTimeout)); err != nil {
		return err
	}
	return c.conn.WriteJSON(v)
}

// ListenHub 维护房间 WS 会话。房间和播放状态落 SQLite，进程重启后仍可用；
// session token 只存在内存，重连时通过已绑定的情侣关系重新进入活动房间即可。
type ListenHub struct {
	store    *Store
	mu       sync.RWMutex
	sessions map[string]listenSession
	clients  map[string]map[*listenWSClient]struct{}
}

func NewListenHub(store *Store) *ListenHub {
	return &ListenHub{
		store:    store,
		sessions: make(map[string]listenSession),
		clients:  make(map[string]map[*listenWSClient]struct{}),
	}
}

func listenEnabled(c *gin.Context) bool {
	if settingsNow().ListenTogetherEnabled {
		return true
	}
	fail(c, http.StatusForbidden, 1031, "一起听功能当前已关闭")
	return false
}

func listenPair(c *gin.Context) (*Pair, bool) {
	uid := currentUID(c)
	pair, err := st.GetPairByUserID(uid)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			fail(c, http.StatusBadRequest, 1032, "请先完成伴侣绑定")
			return nil, false
		}
		fail(c, http.StatusInternalServerError, 1032, "读取伴侣关系失败")
		return nil, false
	}
	if pair == nil || pair.PartnerOf(uid) <= 0 {
		fail(c, http.StatusBadRequest, 1032, "请先完成伴侣绑定")
		return nil, false
	}
	return pair, true
}

func listenSecretHash(secret string) string {
	sum := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(sum[:])
}

func listenRandomString(n int, alphabet string) (string, error) {
	if n <= 0 || len(alphabet) == 0 {
		return "", errors.New("invalid random string length")
	}
	b := make([]byte, n)
	for i := range b {
		var one [1]byte
		if _, err := rand.Read(one[:]); err != nil {
			return "", err
		}
		b[i] = alphabet[int(one[0])%len(alphabet)]
	}
	return string(b), nil
}

func listenRoomID() (string, error) {
	return listenRandomString(6, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
}

func listenJoinSecret() (string, error) {
	return listenRandomString(8, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
}

func listenToken() (string, error) {
	return listenRandomString(48, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")
}

func listenDefaultState() listenRoomState {
	return listenRoomState{Mode: "list"}
}

func listenSettingsFromRuntime() listenRoomSettings {
	s := settingsNow()
	return listenRoomSettings{
		AllowMemberControl:      s.ListenAllowMemberControl,
		AutoPauseOnMemberChange: s.ListenAutoPauseOnMemberChange,
	}
}

func (h *ListenHub) loadRoom(roomID string) (*listenRoomView, string, error) {
	var pairID, hostID, allow, autoPause, status int
	var stateJSON string
	var created, updated time.Time
	if err := h.store.DB.QueryRow(`SELECT pair_id,host_user_id,allow_member_control,
		auto_pause_on_member_change,state_json,status,created_at,updated_at
		FROM listen_room WHERE id=?`, roomID).Scan(&pairID, &hostID, &allow, &autoPause,
		&stateJSON, &status, &created, &updated); err != nil {
		return nil, "", err
	}
	if status != 1 {
		return nil, "", sql.ErrNoRows
	}
	state := listenDefaultState()
	if strings.TrimSpace(stateJSON) != "" && stateJSON != "{}" {
		if err := json.Unmarshal([]byte(stateJSON), &state); err != nil {
			return nil, "", fmt.Errorf("decode listen room state: %w", err)
		}
	}
	state = normalizeListenState(state)
	h.mu.RLock()
	members := len(h.clients[roomID])
	h.mu.RUnlock()
	// 房间表保存创建时的快照，但管理员开关必须能立即止损已有房间：
	// 运行态为 false 时，不能靠旧房间行里的 true 绕过成员控制或自动暂停。
	liveSettings := listenSettingsFromRuntime()
	// Redact the legacy persisted URL at the response boundary as well as on
	// writes; otherwise an old room state could leak a third-party URL.
	state.AudioURL = ""
	return &listenRoomView{
		RoomID: roomID,
		PairID: int64(pairID),
		Host:   int64(hostID),
		State:  state,
		Settings: listenRoomSettings{
			AllowMemberControl:      allow != 0 && liveSettings.AllowMemberControl,
			AutoPauseOnMemberChange: autoPause != 0 && liveSettings.AutoPauseOnMemberChange,
		},
		CreatedAt: created,
		UpdatedAt: updated,
		Members:   members,
	}, stateJSON, nil
}

func (h *ListenHub) issueSession(roomID string, uid int64, host bool) (string, error) {
	// 同一用户在同一房间只保留一个活动会话。除了删除旧 token，还要主动
	// 关闭旧 WebSocket；否则旧连接虽然不能再发控制指令，仍会继续收到房间广播。
	h.revokeUserSessions(roomID, uid)
	token, err := listenToken()
	if err != nil {
		return "", err
	}
	h.mu.Lock()
	h.sessions[token] = listenSession{RoomID: roomID, UID: uid, Host: host}
	h.mu.Unlock()
	return token, nil
}

func (h *ListenHub) session(token string) (listenSession, bool) {
	h.mu.RLock()
	s, ok := h.sessions[token]
	h.mu.RUnlock()
	return s, ok
}

func (h *ListenHub) revokeRoomSessions(roomID string) {
	var clients []*listenWSClient
	h.mu.Lock()
	for token, session := range h.sessions {
		if session.RoomID == roomID {
			delete(h.sessions, token)
		}
	}
	for client := range h.clients[roomID] {
		clients = append(clients, client)
	}
	h.mu.Unlock()
	for _, client := range clients {
		_ = client.conn.Close()
	}
}

func (h *ListenHub) revokeUserSessions(roomID string, uid int64) {
	var clients []*listenWSClient
	h.mu.Lock()
	for token, session := range h.sessions {
		if session.RoomID == roomID && session.UID == uid {
			delete(h.sessions, token)
		}
	}
	for client := range h.clients[roomID] {
		if client.uid == uid {
			clients = append(clients, client)
		}
	}
	h.mu.Unlock()
	for _, client := range clients {
		_ = client.conn.Close()
	}
}

func (h *ListenHub) roomForUser(roomID string, uid int64) (*listenRoomView, error) {
	room, _, err := h.loadRoom(roomID)
	if err != nil {
		return nil, err
	}
	pair, err := h.store.GetPairByUserID(uid)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, sql.ErrNoRows
		}
		return nil, err
	}
	if pair == nil || pair.ID != room.PairID {
		return nil, sql.ErrNoRows
	}
	return room, nil
}

func (h *ListenHub) activeRoomForPair(pairID int64) (*listenRoomView, error) {
	var roomID string
	if err := h.store.DB.QueryRow(`SELECT id FROM listen_room
		WHERE pair_id=? AND status=1 ORDER BY updated_at DESC LIMIT 1`, pairID).Scan(&roomID); err != nil {
		return nil, err
	}
	room, _, err := h.loadRoom(roomID)
	return room, err
}

// closePairRooms is called when the relationship itself is revoked. A room is
// scoped to a pair, so leaving it open would let a previously authenticated WS
// connection keep receiving private playback metadata after unbinding.
func (h *ListenHub) closePairRooms(pairID int64) {
	if h == nil || h.store == nil || pairID <= 0 {
		return
	}
	rows, err := h.store.DB.Query(`SELECT id FROM listen_room WHERE pair_id=? AND status=1`, pairID)
	if err != nil {
		slog.Error("list active listen rooms for pair revocation failed", "pair_id", pairID, "err", err)
		return
	}
	var roomIDs []string
	for rows.Next() {
		var roomID string
		if err := rows.Scan(&roomID); err != nil {
			slog.Error("scan listen room for pair revocation failed", "pair_id", pairID, "err", err)
			_ = rows.Close()
			return
		}
		roomIDs = append(roomIDs, roomID)
	}
	if err := rows.Err(); err != nil {
		slog.Error("iterate listen rooms for pair revocation failed", "pair_id", pairID, "err", err)
		_ = rows.Close()
		return
	}
	if err := rows.Close(); err != nil {
		slog.Error("close listen room rows for pair revocation failed", "pair_id", pairID, "err", err)
		return
	}
	for _, roomID := range roomIDs {
		result, err := h.store.DB.Exec(`UPDATE listen_room SET status=0,updated_at=datetime('now') WHERE id=? AND status=1`, roomID)
		if err != nil {
			slog.Error("close listen room after pair revocation failed", "pair_id", pairID, "room_id", roomID, "err", err)
			continue
		}
		if affected, err := result.RowsAffected(); err != nil {
			slog.Error("read closed listen room count after pair revocation failed", "pair_id", pairID, "room_id", roomID, "err", err)
			continue
		} else if affected != 1 {
			continue
		}
		h.broadcast(roomID, gin.H{"type": "room_closed"})
		h.revokeRoomSessions(roomID)
	}
}

// joinRoomSession 是两条加入入口共用的收口：身份先由 JWT + pair 校验，
// 这里再签发只存在内存的 WS 会话，避免“自动进入”和“兼容口令加入”权限分叉。
func (h *ListenHub) joinRoomSession(room *listenRoomView, uid int64) (string, string, error) {
	host := room.Host == uid
	token, err := h.issueSession(room.RoomID, uid, host)
	if err != nil {
		return "", "", err
	}
	if !host && room.Settings.AutoPauseOnMemberChange && room.State.Playing {
		// 成员首次进入时先暂停，避免两台设备在不同进度上继续播放。
		if err := h.applyControl(listenSession{RoomID: room.RoomID, UID: room.Host, Host: true}, listenControlRequest{Action: "pause"}); err != nil {
			h.revokeUserSessions(room.RoomID, uid)
			return "", "", err
		}
		fresh, _, err := h.loadRoom(room.RoomID)
		if err != nil {
			h.revokeUserSessions(room.RoomID, uid)
			return "", "", err
		}
		*room = *fresh
	}
	h.broadcast(room.RoomID, gin.H{"type": "member_joined", "room": room})
	role := "member"
	if host {
		role = "host"
	}
	return token, role, nil
}

func (h *ListenHub) joinResponse(c *gin.Context, room *listenRoomView, uid int64) {
	token, role, err := h.joinRoomSession(room, uid)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1038, "加入房间失败")
		return
	}
	ok(c, gin.H{"room": room, "role": role, "token": token,
		"ws_path": "/api/v1/listen/rooms/" + room.RoomID + "/ws"})
}

func (h *ListenHub) createRoom(c *gin.Context) {
	if !listenEnabled(c) {
		return
	}
	pair, hasPair := listenPair(c)
	if !hasPair {
		return
	}
	uid := currentUID(c)
	// 创建是幂等操作：情侣已经有活动房间时直接复用，不能因为一次重试
	// 把另一台设备正在使用的房间替换掉。
	if existing, err := h.activeRoomForPair(pair.ID); err == nil {
		h.joinResponse(c, existing, uid)
		return
	} else if !errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusInternalServerError, 1033, "读取活动房间失败")
		return
	}
	joinSecret, err := listenJoinSecret()
	if err != nil {
		fail(c, http.StatusInternalServerError, 1033, "创建房间失败")
		return
	}
	stateJSON, err := json.Marshal(listenDefaultState())
	if err != nil {
		fail(c, http.StatusInternalServerError, 1033, "创建房间失败")
		return
	}
	settings := listenSettingsFromRuntime()
	tx, err := h.store.DB.Begin()
	if err != nil {
		fail(c, http.StatusInternalServerError, 1033, "创建房间失败")
		return
	}
	defer func() { _ = tx.Rollback() }()
	var roomID string
	for attempt := 0; attempt < 5; attempt++ {
		roomID, err = listenRoomID()
		if err != nil {
			break
		}
		_, err = tx.Exec(`INSERT INTO listen_room
			(id,pair_id,host_user_id,join_secret_hash,allow_member_control,
			auto_pause_on_member_change,state_json,status)
			VALUES(?,?,?,?,?,?,?,1)`, roomID, pair.ID, uid, listenSecretHash(joinSecret),
			settings.AllowMemberControl, settings.AutoPauseOnMemberChange, string(stateJSON))
		if err == nil {
			break
		}
	}
	if err != nil {
		// 两台设备可能同时点“创建”。唯一部分索引会让后一笔插入失败，
		// 此时直接复用已经提交的活动房间，避免把正常竞态显示成创建失败。
		_ = tx.Rollback()
		if existing, joinErr := h.activeRoomForPair(pair.ID); joinErr == nil {
			h.joinResponse(c, existing, uid)
			return
		}
		fail(c, http.StatusInternalServerError, 1033, "创建房间失败")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(c, http.StatusInternalServerError, 1033, "创建房间失败")
		return
	}
	token, err := h.issueSession(roomID, uid, true)
	if err != nil {
		_, _ = h.store.DB.Exec(`UPDATE listen_room SET status=0,updated_at=datetime('now') WHERE id=? AND status=1`, roomID)
		fail(c, http.StatusInternalServerError, 1033, "创建房间失败")
		return
	}
	room, _, err := h.loadRoom(roomID)
	if err != nil {
		_, _ = h.store.DB.Exec(`UPDATE listen_room SET status=0,updated_at=datetime('now') WHERE id=? AND status=1`, roomID)
		h.revokeRoomSessions(roomID)
		fail(c, http.StatusInternalServerError, 1033, "创建房间失败")
		return
	}
	ok(c, gin.H{"room": room, "role": "host", "token": token, "join_secret": joinSecret,
		"ws_path": "/api/v1/listen/rooms/" + roomID + "/ws"})
}

func (h *ListenHub) joinRoom(c *gin.Context) {
	if !listenEnabled(c) {
		return
	}
	var req struct {
		JoinSecret string `json:"join_secret" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, 1034, "请输入房间口令")
		return
	}
	roomID := strings.ToUpper(strings.TrimSpace(c.Param("roomID")))
	room, _, err := h.loadRoom(roomID)
	if err != nil {
		fail(c, http.StatusNotFound, 1035, "房间不存在或已关闭")
		return
	}
	pair, hasPair := listenPair(c)
	if !hasPair || pair.ID != room.PairID {
		if hasPair {
			fail(c, http.StatusForbidden, 1036, "只能加入伴侣创建的房间")
		}
		return
	}
	var expected string
	providedHash := listenSecretHash(strings.ToUpper(strings.TrimSpace(req.JoinSecret)))
	if err := h.store.DB.QueryRow(`SELECT join_secret_hash FROM listen_room WHERE id=? AND status=1`, roomID).Scan(&expected); err != nil || subtle.ConstantTimeCompare([]byte(expected), []byte(providedHash)) != 1 {
		fail(c, http.StatusForbidden, 1037, "房间口令不正确")
		return
	}
	h.joinResponse(c, room, currentUID(c))
}

// joinCurrentRoom 是情侣场景的主入口：双方已经通过 pair 绑定，
// 不再要求用户复制和输入一串只会增加摩擦的口令。
func (h *ListenHub) joinCurrentRoom(c *gin.Context) {
	if !listenEnabled(c) {
		return
	}
	pair, hasPair := listenPair(c)
	if !hasPair {
		return
	}
	room, err := h.activeRoomForPair(pair.ID)
	if errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusNotFound, 1035, "伴侣还没有创建一起听房间")
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, 1039, "读取房间失败")
		return
	}
	h.joinResponse(c, room, currentUID(c))
}

func (h *ListenHub) getState(c *gin.Context) {
	if !listenEnabled(c) {
		return
	}
	room, err := h.roomForUser(strings.ToUpper(strings.TrimSpace(c.Param("roomID"))), currentUID(c))
	if errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusNotFound, 1035, "房间不存在或已关闭")
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, 1039, "读取房间失败")
		return
	}
	ok(c, room)
}

func (h *ListenHub) control(c *gin.Context) {
	if !listenEnabled(c) {
		return
	}
	roomID := strings.ToUpper(strings.TrimSpace(c.Param("roomID")))
	room, err := h.roomForUser(roomID, currentUID(c))
	if errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusNotFound, 1035, "房间不存在或已关闭")
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, 1039, "读取房间失败")
		return
	}
	var req listenControlRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, 1040, "播放操作格式不正确")
		return
	}
	if room.Host != currentUID(c) && !room.Settings.AllowMemberControl {
		fail(c, http.StatusForbidden, 1041, "房主未允许成员控制播放")
		return
	}
	if err := h.applyControl(listenSession{RoomID: roomID, UID: currentUID(c), Host: room.Host == currentUID(c)}, req); err != nil {
		if strings.Contains(err.Error(), "不支持") {
			fail(c, http.StatusBadRequest, 1042, err.Error())
		} else if strings.Contains(err.Error(), "权限") || strings.Contains(err.Error(), "未允许") {
			fail(c, http.StatusForbidden, 1041, err.Error())
		} else {
			fail(c, http.StatusInternalServerError, 1043, err.Error())
		}
		return
	}
	room, err = h.roomForUser(roomID, currentUID(c))
	if err != nil {
		fail(c, http.StatusInternalServerError, 1043, "读取更新后的房间状态失败")
		return
	}
	ok(c, room)
}

func trimListenText(raw string, max int) string {
	raw = strings.TrimSpace(raw)
	if len([]rune(raw)) <= max {
		return raw
	}
	return string([]rune(raw)[:max])
}

func normalizeListenSource(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "netease", "cloudmusic", "cloud_music":
		return "netease"
	default:
		return ""
	}
}

func normalizeListenState(state listenRoomState) listenRoomState {
	state.Title = trimListenText(state.Title, 160)
	state.Artist = trimListenText(state.Artist, 160)
	state.Album = trimListenText(state.Album, 160)
	state.Source = normalizeListenSource(state.Source)
	if state.Source != "netease" || state.SongID <= 0 {
		state.Source = ""
		state.SongID = 0
		state.Album = ""
		state.CoverURL = ""
		state.AudioURL = ""
	}
	state.CoverURL = safeListenCoverURL(state.CoverURL)
	// Playback URLs are short-lived third-party credentials.  Keep the legacy
	// field in the wire model for compatibility, but never retain it in state.
	state.AudioURL = ""
	state.DurationMs = maxListenInt64(state.DurationMs, 0)
	state.PositionMs = clampListenPosition(state.PositionMs, state.DurationMs)
	if state.Mode != "list" && state.Mode != "repeat" && state.Mode != "shuffle" {
		state.Mode = "list"
	}
	return state
}

func validateListenTrack(source string, songID int64) error {
	if normalizeListenSource(source) != "netease" {
		return errors.New("一起听目前只支持网易云歌曲")
	}
	if songID <= 0 || songID > 9_223_372_036_854_775_807 {
		return errors.New("网易云歌曲 ID 无效")
	}
	return nil
}

func maxListenInt64(v, min int64) int64 {
	if v < min {
		return min
	}
	return v
}

func clampListenPosition(position, duration int64) int64 {
	position = maxListenInt64(position, 0)
	if duration > 0 && position > duration {
		return duration
	}
	return position
}

func (h *ListenHub) leave(c *gin.Context) {
	if !listenEnabled(c) {
		return
	}
	roomID := strings.ToUpper(strings.TrimSpace(c.Param("roomID")))
	room, err := h.roomForUser(roomID, currentUID(c))
	if errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusNotFound, 1035, "房间不存在或已关闭")
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, 1039, "读取房间失败")
		return
	}
	if room.Host == currentUID(c) {
		result, err := h.store.DB.Exec(`UPDATE listen_room SET status=0,updated_at=datetime('now') WHERE id=? AND status=1`, roomID)
		if err != nil {
			fail(c, http.StatusInternalServerError, 1044, "关闭房间失败")
			return
		}
		count, err := result.RowsAffected()
		if err != nil {
			fail(c, http.StatusInternalServerError, 1044, "关闭房间失败")
			return
		}
		if count != 1 {
			fail(c, http.StatusNotFound, 1035, "房间不存在或已关闭")
			return
		}
		h.broadcast(roomID, gin.H{"type": "room_closed"})
		h.revokeRoomSessions(roomID)
	} else {
		h.revokeUserSessions(roomID, currentUID(c))
		h.broadcast(roomID, gin.H{"type": "member_left", "user_id": currentUID(c)})
	}
	ok(c, gin.H{"left": true})
}

func (h *ListenHub) serveWS(c *gin.Context) {
	if !settingsNow().ListenTogetherEnabled {
		c.Status(http.StatusForbidden)
		return
	}
	// 优先从自定义请求头读取会话 token，避免它进入反向代理访问日志；
	// 查询串仅作为旧客户端兼容入口，且仍必须同时通过 JWT 校验。
	token := strings.TrimSpace(c.GetHeader("X-Lx-Listen-Token"))
	if token == "" {
		token = strings.TrimSpace(c.Query("token"))
	}
	session, ok := h.session(token)
	if !ok || session.RoomID != strings.ToUpper(strings.TrimSpace(c.Param("roomID"))) {
		c.Status(http.StatusUnauthorized)
		return
	}
	uid, err := authUserByToken(bearerToken(c.GetHeader("Authorization")))
	if err != nil || uid != session.UID {
		c.Status(http.StatusUnauthorized)
		return
	}
	room, err := h.roomForUser(session.RoomID, session.UID)
	if err != nil {
		c.Status(http.StatusNotFound)
		return
	}
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		slog.Warn("listen ws upgrade failed", "err", err)
		return
	}
	defer conn.Close()
	client := &listenWSClient{conn: conn, room: session.RoomID, uid: session.UID, token: token}
	h.mu.Lock()
	if h.clients[session.RoomID] == nil {
		h.clients[session.RoomID] = make(map[*listenWSClient]struct{})
	}
	h.clients[session.RoomID][client] = struct{}{}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.clients[session.RoomID], client)
		if len(h.clients[session.RoomID]) == 0 {
			delete(h.clients, session.RoomID)
		}
		h.mu.Unlock()
	}()
	_ = client.writeJSON(gin.H{"type": "welcome", "role": map[bool]string{true: "host", false: "member"}[session.Host], "room": room})
	const idle = 45 * time.Second
	conn.SetReadLimit(maxWSMessageBytes)
	_ = conn.SetReadDeadline(time.Now().Add(idle))
	conn.SetPingHandler(func(appData string) error {
		_ = conn.SetReadDeadline(time.Now().Add(idle))
		client.mu.Lock()
		defer client.mu.Unlock()
		return conn.WriteControl(websocket.PongMessage, []byte(appData), time.Now().Add(10*time.Second))
	})
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(idle)) })
	for {
		if active, valid := h.session(client.token); !valid || active.RoomID != client.room || active.UID != client.uid {
			return
		}
		_, payload, err := conn.ReadMessage()
		if err != nil {
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(idle))
		var req listenControlRequest
		if err := json.Unmarshal(payload, &req); err != nil {
			_ = client.writeJSON(gin.H{"type": "error", "message": "播放操作格式不正确"})
			continue
		}
		if !settingsNow().ListenTogetherEnabled {
			_ = client.writeJSON(gin.H{"type": "error", "message": "一起听功能当前已关闭"})
			return
		}
		// WS 与 REST 共用同一份校验和状态落库逻辑，避免成员权限或边界在两条入口分叉。
		if err := h.applyControl(session, req); err != nil {
			_ = client.writeJSON(gin.H{"type": "error", "message": err.Error()})
		}
	}
}

func (h *ListenHub) applyControl(session listenSession, req listenControlRequest) error {
	room, err := h.roomForUser(session.RoomID, session.UID)
	if err != nil {
		return errors.New("房间不存在或已关闭")
	}
	if !session.Host && !room.Settings.AllowMemberControl {
		return errors.New("房主未允许成员控制播放")
	}
	state := room.State
	switch strings.ToLower(strings.TrimSpace(req.Action)) {
	case "play":
		state.Playing = true
	case "pause":
		state.Playing = false
	case "seek":
		state.PositionMs = clampListenPosition(req.PositionMs, state.DurationMs)
	case "set_track":
		if err := validateListenTrack(req.Source, req.SongID); err != nil {
			return err
		}
		state.Title = trimListenText(req.Title, 160)
		state.Artist = trimListenText(req.Artist, 160)
		state.Source = normalizeListenSource(req.Source)
		state.SongID = req.SongID
		state.Album = trimListenText(req.Album, 160)
		state.CoverURL = safeListenCoverURL(req.CoverURL)
		// A playback URL is a short-lived third-party credential.  Keep accepting
		// the legacy request field so old clients remain forward-compatible, but
		// never persist or broadcast it.
		state.AudioURL = ""
		state.DurationMs = maxListenInt64(req.DurationMs, 0)
		state.PositionMs = 0
		if req.Playing != nil {
			state.Playing = *req.Playing
		}
	case "playback_mode":
		if req.Mode != "list" && req.Mode != "repeat" && req.Mode != "shuffle" {
			return errors.New("不支持的播放模式")
		}
		state.Mode = req.Mode
	case "heartbeat":
		if req.Playing != nil {
			state.Playing = *req.Playing
		}
		state.PositionMs = clampListenPosition(req.PositionMs, state.DurationMs)
	default:
		return errors.New("不支持的播放操作")
	}
	state.UpdatedAt = time.Now().UnixMilli()
	state = normalizeListenState(state)
	encoded, err := json.Marshal(state)
	if err != nil {
		return errors.New("保存播放状态失败")
	}
	result, err := h.store.DB.Exec(`UPDATE listen_room SET state_json=?,updated_at=datetime('now') WHERE id=? AND status=1`, string(encoded), session.RoomID)
	if err != nil {
		return errors.New("保存播放状态失败")
	}
	if affected, err := result.RowsAffected(); err != nil || affected != 1 {
		return errors.New("房间不存在或已关闭")
	}
	room.State = state
	room.UpdatedAt = time.Now()
	h.broadcast(session.RoomID, gin.H{"type": "room_state_updated", "room": room})
	return nil
}

func safeListenCoverURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if len(raw) > 2048 {
		return ""
	}
	parsed, err := url.Parse(raw)
	if err != nil || !strings.EqualFold(parsed.Scheme, "https") || parsed.Host == "" || parsed.User != nil {
		return ""
	}
	return raw
}

func (h *ListenHub) broadcast(roomID string, message interface{}) {
	h.mu.RLock()
	clients := make([]*listenWSClient, 0, len(h.clients[roomID]))
	for client := range h.clients[roomID] {
		clients = append(clients, client)
	}
	h.mu.RUnlock()
	for _, client := range clients {
		if err := client.writeJSON(message); err != nil {
			_ = client.conn.Close()
		}
	}
}

// Admin-facing helpers. Only metadata is returned; join secrets and tokens never leave
// the user-facing flow and are intentionally absent from this list.
func (h *ListenHub) listRooms(c *gin.Context) {
	var rows *sql.Rows
	var err error
	rows, err = h.store.DB.Query(`SELECT id,pair_id,host_user_id,state_json,created_at,updated_at
		FROM listen_room WHERE status=1 ORDER BY updated_at DESC LIMIT 200`)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1045, "读取一起听房间失败")
		return
	}
	defer rows.Close()
	rooms := make([]*listenRoomView, 0)
	for rows.Next() {
		var id string
		var pairID, hostID int64
		var stateJSON string
		var created, updated time.Time
		if err := rows.Scan(&id, &pairID, &hostID, &stateJSON, &created, &updated); err != nil {
			slog.Error("scan listen room failed", "err", err)
			continue
		}
		state := listenDefaultState()
		if err := json.Unmarshal([]byte(stateJSON), &state); err != nil {
			slog.Error("decode listen room state failed", "room_id", id, "err", err)
			continue
		}
		state = normalizeListenState(state)
		// 后台只需审计房间是否活跃及正在播放的标题；第三方音频地址是
		// 用户侧可选的私密元数据，不应随普通管理员列表返回。
		state.AudioURL = ""
		h.mu.RLock()
		members := len(h.clients[id])
		h.mu.RUnlock()
		rooms = append(rooms, &listenRoomView{RoomID: id, PairID: pairID, Host: hostID,
			State: state, CreatedAt: created, UpdatedAt: updated, Members: members})
	}
	if err := rows.Err(); err != nil {
		fail(c, http.StatusInternalServerError, 1045, "读取一起听房间失败")
		return
	}
	ok(c, rooms)
}

func (h *ListenHub) closeRoom(c *gin.Context) {
	roomID := strings.ToUpper(strings.TrimSpace(c.Param("roomID")))
	result, err := h.store.DB.Exec(`UPDATE listen_room SET status=0,updated_at=datetime('now') WHERE id=? AND status=1`, roomID)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1046, "关闭一起听房间失败")
		return
	}
	count, err := result.RowsAffected()
	if err != nil {
		fail(c, http.StatusInternalServerError, 1046, "关闭一起听房间失败")
		return
	}
	if count == 0 {
		fail(c, http.StatusNotFound, 1035, "房间不存在或已关闭")
		return
	}
	h.broadcast(roomID, gin.H{"type": "room_closed"})
	// 先让客户端收到明确的关闭事件，再回收 token 与连接；否则它只能看到
	// 一个无原因的断线，无法给用户显示“房间已被关闭”并停止重连。
	h.revokeRoomSessions(roomID)
	ok(c, gin.H{"closed": true, "room_id": roomID})
}
