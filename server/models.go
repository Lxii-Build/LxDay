package main

import (
	"time"
)

// ================= 数据模型 =================

type User struct {
	ID                 int64     `json:"id"`
	Nickname           string    `json:"nickname"`
	AvatarURL          *string   `json:"avatar_url"`
	AvatarThumbnailURL *string   `json:"avatar_thumbnail_url"`
	PasswordHash       string    `json:"-"`
	CreatedAt          time.Time `json:"-"`
}

type Pair struct {
	ID               int64      `json:"id"`
	UserAID          int64      `json:"user_a_id"`
	UserBID          int64      `json:"user_b_id"`
	InviteCode       string     `json:"invite_code"`
	AnniversaryDate *time.Time `json:"anniversary_date"`
}

type AppInfo struct {
	Pkg  string `json:"pkg"`
	Name string `json:"name"`
}

type MusicInfo struct {
	Title   string `json:"title"`
	Artist  string `json:"artist"`
	Playing bool   `json:"playing"`
}

type DeviceStatus struct {
	UserID        int64      `json:"-"`
	BatteryLevel  int        `json:"battery"`
	IsCharging    bool       `json:"charging"`
	ScreenOn      bool       `json:"screen_on"`
	IsLocked      bool       `json:"locked"`
	ForegroundApp *AppInfo   `json:"foreground_app,omitempty"`
	Music         *MusicInfo `json:"music,omitempty"`
	SSID          string     `json:"ssid,omitempty"`
	NetworkType   string     `json:"network"`
	UpdatedAt     int64      `json:"ts"`
}

// 状态历史记录（5 分钟聚合，永久保留）
type StatusHistory struct {
	PairID        int64     `json:"-"`
	UserID        int64     `json:"user_id"`
	BatteryLevel  int       `json:"battery"`
	IsCharging    bool      `json:"charging"`
	ScreenOn      bool      `json:"screen_on"`
	IsLocked      bool      `json:"locked"`
	ForegroundPkg string    `json:"-"`
	ForegroundApp string    `json:"foreground_app,omitempty"`
	SSID          string    `json:"ssid,omitempty"`
	NetworkType   string    `json:"network"`
	Ts            time.Time `json:"ts"`
}

type Todo struct {
	ID          int64      `json:"id"`
	PairID      int64      `json:"pair_id"`
	CreatorID   int64      `json:"creator_id"`
	AssigneeID  int64      `json:"assignee_id"`
	Title       string     `json:"title"`
	Note        string     `json:"note,omitempty"`
	RemindAt    *time.Time `json:"remind_at,omitempty"`
	RemindType  int        `json:"remind_type"` // 0普通 1强提醒
	Status      int        `json:"status"`      // 0待办 1已完成 2已删除
	CompletedAt *time.Time `json:"completed_at,omitempty"`
}

type Diary struct {
	ID        int64     `json:"id"`
	PairID    int64     `json:"pair_id"`
	AuthorID  int64     `json:"author_id"`
	AuthorName string   `json:"author_name"`
	Title     string    `json:"title"`
	Content   string    `json:"content"`
	DiaryDate string    `json:"diary_date"` // YYYY-MM-DD
	Images    []string  `json:"images,omitempty"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

type PushToken struct {
	UserID   int64  `json:"user_id"`
	Platform string `json:"platform"`
	Channel  string `json:"channel"`
	Token    string `json:"token"`
}

// ================= WebSocket 消息协议 =================

type WsMessage struct {
	Type string      `json:"type"`
	Data interface{} `json:"data"`
}

const (
	MsgStatusUpdate    = "status_update"    // 客户端→服务端 上报状态
	MsgPartnerStatus   = "partner_status"   // 服务端→对方 转发状态
	MsgComfortRequest  = "comfort_request"  // 求陪伴
	MsgCalmRequest     = "calm_request"     // 求冷静
	MsgRingRequest     = "ring_request"     // 强制响铃
	MsgTodoNew         = "todo_new"         // 新待办
	MsgTodoCompleted   = "todo_completed"   // 待办完成
	MsgDiaryNew        = "diary_new"        // 新日记
	MsgDiaryUpdated    = "diary_updated"    // 日记更新
	MsgLowBattery      = "low_battery"      // 对方电量 <15%
	MsgWifiJoined      = "wifi_joined"      // 对方连接指定 WiFi
	MsgTodoRemind      = "todo_remind"      // 待办到点提醒
	MsgProfileUpdated  = "profile_updated"  // 资料变化后通知对方重新拉取
)

// 高优事件：离线时必须入队（不接商业推送，靠重连补拉）
var highPriorityEvents = map[string]bool{
	MsgRingRequest:    true,
	MsgComfortRequest: true,
	MsgCalmRequest:    true,
	MsgTodoNew:        true,
	MsgTodoCompleted:  true,
	MsgDiaryNew:       true,
	MsgLowBattery:     true,
	MsgWifiJoined:     true,
	MsgTodoRemind:     true,
}

func isHighPriority(t string) bool { return highPriorityEvents[t] }
