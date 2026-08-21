package main

import (
	"database/sql"
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

// UserProfile 扩展个人资料（登录账号 + 性别/简介/生日），仅本人可见可编辑字段。
// 与 User 分开承载，避免影响伴侣资料(pairProfile)已固化的查询与测试。
type UserProfile struct {
	ID                 int64   `json:"id"`
	Username           *string `json:"username"`
	Email              *string `json:"email"`
	Nickname           string  `json:"nickname"`
	AvatarURL          *string `json:"avatar_url"`
	AvatarThumbnailURL *string `json:"avatar_thumbnail_url"`
	Gender             int     `json:"gender"` // 0保密 1男 2女
	Signature          *string `json:"signature"`
	Birthday           *string `json:"birthday"` // YYYY-MM-DD
}

type Pair struct {
	ID              int64      `json:"id"`
	UserAID         int64      `json:"user_a_id"`
	UserBID         int64      `json:"user_b_id"`
	InviteCode      string     `json:"invite_code"`
	AnniversaryDate *time.Time `json:"anniversary_date"`
}

// PartnerOf 返回 uid 的伴侣 id；uid 不属于该 pair 时返回 0。
// 供「伴侣状态历史」等需要按对方视角查询的接口使用。
func (p *Pair) PartnerOf(uid int64) int64 {
	switch uid {
	case p.UserAID:
		return p.UserBID
	case p.UserBID:
		return p.UserAID
	default:
		return 0
	}
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

// 状态历史记录（5 分钟聚合，保留天数见 settings 的 retention.status_history_days）
//
// **foreground_pkg / foreground_name / ssid 三列在库里可为 NULL**，
// 客户端未授「使用情况访问」或息屏无前台应用时 pkgOf()/nameOf() 就返回 nil。
// 这三个字段曾经是 `string`，`rows.Scan` 会直接报
// `converting NULL to string is unsupported`；而调用方忽略了 Scan 的返回值，
// 于是整行保持零值 → Ts 变成 0001-01-01 → 序列化出的 ts 是 -62135596800000（每行都一样）
// → 客户端 LazyColumn 的 `key = { it.ts }` 撞重复 key → IllegalArgumentException 崩溃。
// 这就是「进伴侣状态历史 APP 崩掉」的根因，故这三列必须用可空类型接。
type StatusHistory struct {
	PairID        int64          `json:"-"`
	UserID        int64          `json:"user_id"`
	BatteryLevel  int            `json:"battery"`
	IsCharging    bool           `json:"charging"`
	ScreenOn      bool           `json:"screen_on"`
	IsLocked      bool           `json:"locked"`
	ForegroundPkg sql.NullString `json:"-"`
	ForegroundApp sql.NullString `json:"-"`
	SSID          sql.NullString `json:"-"`
	NetworkType   string         `json:"network"`
	Ts            time.Time      `json:"ts"`
}

// ForegroundAppName / SSIDValue 供 handler 输出 JSON 时取值（NULL → 空串）。
func (h StatusHistory) ForegroundAppName() string { return h.ForegroundApp.String }
func (h StatusHistory) SSIDValue() string         { return h.SSID.String }

type Todo struct {
	ID            int64      `json:"id"`
	PairID        int64      `json:"pair_id"`
	CreatorID     int64      `json:"creator_id"`
	AssigneeID    int64      `json:"assignee_id"`
	Title         string     `json:"title"`
	Note          string     `json:"note,omitempty"`
	RemindAt      *time.Time `json:"remind_at,omitempty"`
	RemindType    int        `json:"remind_type"`    // 0普通 1强提醒
	RepeatType    int        `json:"repeat_type"`    // 0仅一次 1每天 2每周
	Weekdays      int        `json:"weekdays"`       // 位掩码 bit0=周一..bit6=周日（repeat_type=2 时有效）
	RemindEnabled bool       `json:"remind_enabled"` // 提醒开关，缺省 true；关闭后扫描跳过
	Status        int        `json:"status"`         // 0待办 1已完成 2已删除
	CompletedAt   *time.Time `json:"completed_at,omitempty"`
}

// ================= 相册 =================

// Album 相册。CoverPhotoID 为空时列表回退用最新一张照片的缩略图当封面。
type Album struct {
	ID           int64     `json:"id"`
	PairID       int64     `json:"pair_id"`
	Name         string    `json:"name"`
	CoverPhotoID *int64    `json:"cover_photo_id"`
	CreatedBy    int64     `json:"created_by"`
	Status       int       `json:"status"` // 1正常 2已删除
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
	// 列表用的派生字段（不落库）
	PhotoCount int    `json:"photo_count"`
	CoverThumb string `json:"cover_thumb_url,omitempty"`
}

// Photo 单张照片。
//
// URL/ThumbURL 对外一律是鉴权代理形态 /media/<id>、/media/<id>/thumb，
// **真实磁盘相对路径只存在库里**（diskPath/diskThumb，json 标签为 "-"）：
// /upload 静态目录无鉴权，一旦真实路径外泄，拿到 URL 的任何人都能看私密照片。
type Photo struct {
	ID         int64      `json:"id"`
	AlbumID    int64      `json:"album_id"` // 0=未归类
	PairID     int64      `json:"pair_id"`
	UploaderID int64      `json:"uploader_id"`
	URL        string     `json:"url"`
	ThumbURL   string     `json:"thumb_url"`
	Width      int        `json:"width"`
	Height     int        `json:"height"`
	SizeBytes  int64      `json:"size_bytes"`
	Mime       string     `json:"mime"`
	TakenAt    *time.Time `json:"taken_at,omitempty"` // EXIF 拍摄时间，解析不到则为空
	Caption    string     `json:"caption,omitempty"`
	Status     int        `json:"status"` // 1正常 2回收站
	CreatedAt  time.Time  `json:"created_at"`

	// PreviewURL 大图页先加载的中等尺寸（长边 1080）。
	// 三档（thumb 384 / preview 1080 / origin 2048）是为了让"点开大图"秒出：
	// 此前从缩略图直接跳原图，弱网下要白屏等 3~5 秒。
	PreviewURL string `json:"preview_url,omitempty"`
	// RecycleRemainingDays 仅回收站列表返回：还剩几天被自动彻底删除。
	// -1=永久保留，0=已到期。让用户知道回收站不是永久保险箱。
	RecycleRemainingDays *int `json:"recycle_remaining_days,omitempty"`

	diskPath    string // uploadDir 相对路径，仅服务端内部使用
	diskThumb   string
	diskPreview string
	deletedAt   sql.NullTime // 进回收站的时刻，用于计算保留期
}

// PhotoComment 照片评论。
type PhotoComment struct {
	ID        int64     `json:"id"`
	PhotoID   int64     `json:"photo_id"`
	PairID    int64     `json:"pair_id"`
	UserID    int64     `json:"user_id"`
	UserName  string    `json:"user_name"`
	Content   string    `json:"content"`
	Status    int       `json:"status"`
	CreatedAt time.Time `json:"created_at"`
}

type PushToken struct {
	UserID   int64  `json:"user_id"`
	Platform string `json:"platform"`
	Channel  string `json:"channel"`
	Token    string `json:"token"`
}

// AppVersion APP 版本发布记录（后台管理 + 客户端检查更新）
type AppVersion struct {
	ID          int64     `json:"id"`
	Platform    string    `json:"platform"` // android/ios
	VersionName string    `json:"version_name"`
	VersionCode int       `json:"version_code"`
	APKURL      string    `json:"apk_url"`
	Notes       string    `json:"notes"`
	ForceUpdate bool      `json:"force_update"`
	Status      int       `json:"status"` // 1已发布 0下架
	CreatedAt   time.Time `json:"created_at"`
}

// ================= WebSocket 消息协议 =================

type WsMessage struct {
	Type string      `json:"type"`
	Data interface{} `json:"data"`
}

const (
	MsgStatusUpdate   = "status_update"   // 客户端→服务端 上报状态
	MsgPartnerStatus  = "partner_status"  // 服务端→对方 转发状态
	MsgComfortRequest = "comfort_request" // 求陪伴
	MsgCalmRequest    = "calm_request"    // 求冷静
	MsgRingRequest    = "ring_request"    // 强制响铃
	MsgRingCancel     = "ring_cancel"     // 发送方撤回响铃（接收方据此立即停止）
	MsgRingStopped    = "ring_stopped"    // 接收方已关闭响铃的回执（发送方据此结束倒计时）
	MsgActionRejected = "action_rejected" // 服务端拒绝了一次上行动作（如超频），回给发送方
	MsgTodoNew        = "todo_new"        // 新待办
	MsgTodoCompleted  = "todo_completed"  // 待办完成
	MsgLowBattery     = "low_battery"     // 对方电量 <15%
	MsgWifiJoined     = "wifi_joined"     // 对方连接指定 WiFi
	MsgTodoRemind     = "todo_remind"     // 待办到点提醒
	MsgProfileUpdated = "profile_updated" // 资料变化后通知对方重新拉取
	MsgAdminNotice    = "admin_notice"    // 后台广播通知
	MsgPaired         = "paired"          // 绑定成功后通知邀请方（另一方绑定 → 邀请方据此进入主界面）
	MsgUnbound        = "unbound"         // 解除绑定后通知对方（对方据此回到绑定页）
	MsgAlbumNew       = "album_new"       // 伴侣上传了新照片
)

// 瞬时事件：对端离线则直接丢弃，**不得入离线补偿队列**。
// 撤回/回执一旦延迟送达就毫无意义甚至有害——用户重连后突然收到一小时前的
// "对方撤回了响铃"，只会造成困惑；而彼时本地响铃早已由 7s 定时器自行结束。
var transientEvents = map[string]bool{
	MsgRingCancel:     true,
	MsgRingStopped:    true,
	MsgActionRejected: true,
}

func isTransient(t string) bool { return transientEvents[t] }

// 高优事件：离线时必须入队（不接商业推送，靠重连补拉）
var highPriorityEvents = map[string]bool{
	MsgRingRequest:    true,
	MsgComfortRequest: true,
	MsgCalmRequest:    true,
	MsgTodoNew:        true,
	MsgTodoCompleted:  true,
	MsgLowBattery:     true,
	MsgWifiJoined:     true,
	MsgTodoRemind:     true,
}

func isHighPriority(t string) bool { return highPriorityEvents[t] }
