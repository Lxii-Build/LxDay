package main

import (
	"log/slog"
	"strconv"
	"strings"
	"sync/atomic"
)

// ================= 可后台配置的运行参数 =================
//
// 设计要点（0821 决策 Q39=B / Q40=A / Q26=C / Q27=A）：
//
//  1. **一律走数据库 `app_setting` 表 + 后台页面，零新增环境变量、零 compose 改动**。
//     管理员明确不想为改配置去动服务器上的 docker-compose.yml 与 .env。
//
//  2. **进程内缓存 + 保存时整体替换**（`atomic.Pointer`）。
//     不能每次请求都查库：SQLite 走 `MaxOpenConns(1)`，热路径重复查询会自锁
//     （0820 的 `eaf825a` 就是修这个死锁）。用 atomic 整体换指针而非逐字段加锁，
//     读侧零竞争、零开销。
//
//  3. **默认值是代码里的常量，且会一并下发给后台前端**（`defaults` 字段）。
//     「一键恢复默认」由前端填回这些值再提交，保证默认值永远与代码一致，
//     不会出现"后台写的默认值和代码里的不一样"这种分裂。
//
//  4. **不放开的东西**：WS 协议参数（idleTimeout/心跳/限频）必须与客户端成对匹配，
//     后台单方面改会让所有客户端掉线；缩略图尺寸改了历史照片也不会重新生成，
//     只会造成新旧尺寸混杂。这两类刻意不做成配置。

// RuntimeSettings 是一份不可变快照。任何修改都重建整份并 atomic 替换。
type RuntimeSettings struct {
	// ---- 相册与上传 ----
	PhotoMaxBytes      int64 // 单张照片上限
	PhotosPerDay       int   // 每人每日张数
	UploadBytesPerDay  int64 // 每人每日总字节
	AlbumEnabled       bool  // 相册功能总开关
	PhotoSocialEnabled bool  // 评论/点赞开关
	OnThisDayEnabled   bool  // 「这一天」开关

	// ---- 数据保留（天；0 = 永久保留） ----
	NetworkLogDays    int
	StatusHistoryDays int
	RecycleBinDays    int

	// ---- 安全与限流 ----
	EmailCodeTTLMinutes  int
	EmailCodeCooldownSec int
	EmailCodeMaxAttempts int
	LoginRateWindowMin   int
	AdminLoginMaxFails   int
	AdminTokenTTLHours   int
	UserTokenTTLHours    int
	InviteTTLMinutes     int
	BindAttemptLimit     int

	// ---- 互动冷却 ----
	RingCooldownSec        int
	RingCooldownLimit      int
	InteractionCooldownSec int
}

// defaultRuntimeSettings 是全部默认值的唯一来源。
// 这些数字此前散落在 album_media.go / account.go / admin.go / invite.go / netlog.go 的常量里。
func defaultRuntimeSettings() RuntimeSettings {
	return RuntimeSettings{
		PhotoMaxBytes:      20 * 1024 * 1024,
		PhotosPerDay:       200,
		UploadBytesPerDay:  500 * 1024 * 1024,
		AlbumEnabled:       true,
		PhotoSocialEnabled: true,
		OnThisDayEnabled:   true,

		NetworkLogDays:    7,
		StatusHistoryDays: 90,
		RecycleBinDays:    30,

		EmailCodeTTLMinutes:  10,
		EmailCodeCooldownSec: 60,
		EmailCodeMaxAttempts: 5,
		LoginRateWindowMin:   10,
		AdminLoginMaxFails:   5,
		AdminTokenTTLHours:   2,
		UserTokenTTLHours:    720,
		InviteTTLMinutes:     60,
		BindAttemptLimit:     5,

		RingCooldownSec:        600,
		RingCooldownLimit:      3,
		InteractionCooldownSec: 7,
	}
}

// settingSpec 描述一个可配置项：键名、取值范围、读写方式。
// 范围收敛是硬要求——把「每日张数」配成 0 或负数会让相册直接不可用，
// 把 token 有效期配成 0 会让所有人立刻掉线。
type settingSpec struct {
	Key      string
	Group    string // 分区，前端据此分组渲染 + 分区级恢复默认
	Kind     string // int | int64 | bool | bytes(MB 输入)
	Min, Max int64  // Kind 为数值时的收敛范围；bool 忽略
	Label    string
	get      func(*RuntimeSettings) string
	set      func(*RuntimeSettings, int64, bool)
}

const (
	groupAlbum     = "album"
	groupRetention = "retention"
	groupSecurity  = "security"
	groupInteract  = "interaction"
)

// runtimeSettingSpecs 是「配置项 → 字段」的全量映射表。
// 新增配置项只需在这里加一行，读写/校验/默认值/后台下发全部自动生效。
var runtimeSettingSpecs = []settingSpec{
	// 相册与上传
	{Key: "album.photo_max_mb", Group: groupAlbum, Kind: "bytes", Min: 1, Max: 100, Label: "单张照片大小上限(MB)",
		get: func(s *RuntimeSettings) string { return strconv.FormatInt(s.PhotoMaxBytes/(1024*1024), 10) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.PhotoMaxBytes = v * 1024 * 1024 }},
	{Key: "album.photos_per_day", Group: groupAlbum, Kind: "int", Min: 1, Max: 100000, Label: "每人每日上传张数",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.PhotosPerDay) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.PhotosPerDay = int(v) }},
	{Key: "album.upload_mb_per_day", Group: groupAlbum, Kind: "bytes", Min: 1, Max: 1024000, Label: "每人每日上传总量(MB)",
		get: func(s *RuntimeSettings) string { return strconv.FormatInt(s.UploadBytesPerDay/(1024*1024), 10) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.UploadBytesPerDay = v * 1024 * 1024 }},
	{Key: "album.enabled", Group: groupAlbum, Kind: "bool", Label: "相册功能总开关",
		get: func(s *RuntimeSettings) string { return boolStr(s.AlbumEnabled) },
		set: func(s *RuntimeSettings, _ int64, b bool) { s.AlbumEnabled = b }},
	{Key: "album.social_enabled", Group: groupAlbum, Kind: "bool", Label: "照片评论与点赞",
		get: func(s *RuntimeSettings) string { return boolStr(s.PhotoSocialEnabled) },
		set: func(s *RuntimeSettings, _ int64, b bool) { s.PhotoSocialEnabled = b }},
	{Key: "album.on_this_day_enabled", Group: groupAlbum, Kind: "bool", Label: "「这一天」功能",
		get: func(s *RuntimeSettings) string { return boolStr(s.OnThisDayEnabled) },
		set: func(s *RuntimeSettings, _ int64, b bool) { s.OnThisDayEnabled = b }},

	// 数据保留
	{Key: "retention.network_log_days", Group: groupRetention, Kind: "int", Min: 0, Max: 3650, Label: "网络日志保留(天，0=永久)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.NetworkLogDays) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.NetworkLogDays = int(v) }},
	{Key: "retention.status_history_days", Group: groupRetention, Kind: "int", Min: 0, Max: 3650, Label: "状态历史保留(天，0=永久)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.StatusHistoryDays) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.StatusHistoryDays = int(v) }},
	{Key: "retention.recycle_bin_days", Group: groupRetention, Kind: "int", Min: 0, Max: 3650, Label: "回收站保留(天，0=永久)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.RecycleBinDays) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.RecycleBinDays = int(v) }},

	// 安全与限流
	{Key: "security.email_code_ttl_min", Group: groupSecurity, Kind: "int", Min: 1, Max: 1440, Label: "邮箱验证码有效期(分钟)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.EmailCodeTTLMinutes) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.EmailCodeTTLMinutes = int(v) }},
	{Key: "security.email_code_cooldown_sec", Group: groupSecurity, Kind: "int", Min: 10, Max: 3600, Label: "验证码发送冷却(秒)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.EmailCodeCooldownSec) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.EmailCodeCooldownSec = int(v) }},
	{Key: "security.email_code_max_attempts", Group: groupSecurity, Kind: "int", Min: 1, Max: 100, Label: "验证码最大尝试次数",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.EmailCodeMaxAttempts) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.EmailCodeMaxAttempts = int(v) }},
	{Key: "security.login_rate_window_min", Group: groupSecurity, Kind: "int", Min: 1, Max: 1440, Label: "登录限流窗口(分钟)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.LoginRateWindowMin) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.LoginRateWindowMin = int(v) }},
	{Key: "security.admin_login_max_fails", Group: groupSecurity, Kind: "int", Min: 1, Max: 100, Label: "后台登录失败上限",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.AdminLoginMaxFails) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.AdminLoginMaxFails = int(v) }},
	{Key: "security.admin_token_ttl_hours", Group: groupSecurity, Kind: "int", Min: 1, Max: 720, Label: "后台登录有效期(小时)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.AdminTokenTTLHours) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.AdminTokenTTLHours = int(v) }},
	{Key: "security.user_token_ttl_hours", Group: groupSecurity, Kind: "int", Min: 1, Max: 8760, Label: "APP 登录有效期(小时)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.UserTokenTTLHours) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.UserTokenTTLHours = int(v) }},
	{Key: "security.invite_ttl_min", Group: groupSecurity, Kind: "int", Min: 1, Max: 10080, Label: "邀请码有效期(分钟)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.InviteTTLMinutes) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.InviteTTLMinutes = int(v) }},
	{Key: "security.bind_attempt_limit", Group: groupSecurity, Kind: "int", Min: 1, Max: 100, Label: "绑定尝试次数上限",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.BindAttemptLimit) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.BindAttemptLimit = int(v) }},

	// 互动冷却
	{Key: "interaction.ring_cooldown_sec", Group: groupInteract, Kind: "int", Min: 0, Max: 86400, Label: "响铃冷却窗口(秒)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.RingCooldownSec) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.RingCooldownSec = int(v) }},
	{Key: "interaction.ring_cooldown_limit", Group: groupInteract, Kind: "int", Min: 1, Max: 100, Label: "响铃窗口内次数上限",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.RingCooldownLimit) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.RingCooldownLimit = int(v) }},
	{Key: "interaction.light_cooldown_sec", Group: groupInteract, Kind: "int", Min: 0, Max: 3600, Label: "安抚/冷静冷却(秒)",
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.InteractionCooldownSec) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.InteractionCooldownSec = int(v) }},
}

func boolStr(b bool) string {
	if b {
		return "1"
	}
	return "0"
}

func parseBoolSetting(raw string) bool {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}

// runtimeCache 保存当前生效的快照。零值时读侧回退默认值，故启动早期也安全。
var runtimeCache atomic.Pointer[RuntimeSettings]

// settingsNow 返回当前生效配置。**热路径唯一入口**，不查库。
func settingsNow() RuntimeSettings {
	if p := runtimeCache.Load(); p != nil {
		return *p
	}
	return defaultRuntimeSettings()
}

// applySettingValue 把一个 k/v 写进快照，带范围收敛。
// 返回是否被采纳（键不认识或值非法则跳过，保留默认）。
func applySettingValue(s *RuntimeSettings, key, raw string) bool {
	for _, spec := range runtimeSettingSpecs {
		if spec.Key != key {
			continue
		}
		if spec.Kind == "bool" {
			spec.set(s, 0, parseBoolSetting(raw))
			return true
		}
		v, err := strconv.ParseInt(strings.TrimSpace(raw), 10, 64)
		if err != nil {
			return false
		}
		// 收敛而非拒绝：管理员填了越界值也要有个确定的结果，不能让服务处于半坏状态。
		if v < spec.Min {
			v = spec.Min
		}
		if spec.Max > 0 && v > spec.Max {
			v = spec.Max
		}
		spec.set(s, v, false)
		return true
	}
	return false
}

// reloadRuntimeSettings 从库里整体重建快照并 atomic 替换。
// 保存配置后、以及进程启动时各调一次。
func reloadRuntimeSettings() {
	if st == nil {
		return
	}
	next := defaultRuntimeSettings()
	for _, spec := range runtimeSettingSpecs {
		raw, err := st.GetSetting(spec.Key)
		if err != nil || strings.TrimSpace(raw) == "" {
			continue // 未配置 → 保留默认值
		}
		if !applySettingValue(&next, spec.Key, raw) {
			slog.Warn("ignore invalid setting value", "key", spec.Key, "raw", raw)
		}
	}
	runtimeCache.Store(&next)
	slog.Info("runtime settings reloaded",
		"photo_max_mb", next.PhotoMaxBytes/(1024*1024),
		"photos_per_day", next.PhotosPerDay,
		"status_history_days", next.StatusHistoryDays,
		"recycle_bin_days", next.RecycleBinDays,
		"album_enabled", next.AlbumEnabled)
}

// runtimeSettingsPayload 供后台 GET /settings 下发：当前值 + 默认值 + 元信息。
// 默认值一并下发是「一键恢复默认」的基础（Q26=C），前端不必自己抄一份常量。
func runtimeSettingsPayload() (values map[string]string, defaults map[string]string, meta []map[string]interface{}) {
	cur := settingsNow()
	def := defaultRuntimeSettings()
	values = map[string]string{}
	defaults = map[string]string{}
	meta = make([]map[string]interface{}, 0, len(runtimeSettingSpecs))
	for _, spec := range runtimeSettingSpecs {
		values[spec.Key] = spec.get(&cur)
		defaults[spec.Key] = spec.get(&def)
		meta = append(meta, map[string]interface{}{
			"key": spec.Key, "group": spec.Group, "kind": spec.Kind,
			"min": spec.Min, "max": spec.Max, "label": spec.Label,
		})
	}
	return values, defaults, meta
}

// isRuntimeSettingKey 判断某个键是否属于运行参数（用于权限分组：这些键不含密钥）。
func isRuntimeSettingKey(key string) bool {
	for _, spec := range runtimeSettingSpecs {
		if spec.Key == key {
			return true
		}
	}
	return false
}

// runtimeSettingKeys 全部运行参数键名。
func runtimeSettingKeys() []string {
	out := make([]string, 0, len(runtimeSettingSpecs))
	for _, spec := range runtimeSettingSpecs {
		out = append(out, spec.Key)
	}
	return out
}
