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
	PhotoMaxPixels     int   // 单张照片像素数上限（解码前校验，防解压炸弹）
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
	UserLoginMaxFails    int
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
		PhotoMaxBytes: 20 * 1024 * 1024,
		// 12M 像素（约 4000×3000）。
		//
		// 原值是 64M（约 8000×8000），那是「解码后单张最坏 256MB」的来源。
		// 下调到 12M 的依据：
		//   - 客户端上传前一律把长边压到 2048（4:3 约 3.1M 像素），
		//     所以正常路径离 12M 还差 4 倍，完全碰不到；
		//   - 12M 恰好是主流手机主摄的默认直出尺寸（4000×3000），
		//     所以「不走客户端、直接调接口传原图」这种用法也仍然可用；
		//   - GIF/动图原样上传（不重编码）同样受这条约束，而 12M 像素的动图
		//     在 20MB 文件上限下几乎不可能出现。
		// 需要更高时超管可在后台调到 64M（见 album.photo_max_megapixels）。
		PhotoMaxPixels:     12 << 20,
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
		UserLoginMaxFails:    5,
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
	// Super 表示该项只有超管可写（读仍对所有管理员开放——这些值不含密钥，
	// 且后台页面要显示当前状态）。
	//
	// 为什么需要它：运行参数整体是"不含密钥所以放给普通 admin"（Q42=C），
	// 但这批参数里混着两类**权限等级完全不同**的东西：
	//   - 「保留天数」是不可逆销毁开关。把 retention.recycle_bin_days 改成 1，
	//     几小时后那次定时清理就会真删库行 + 真删磁盘文件，
	//     全站用户回收站里的照片一起没了，且没有任何撤销途径；
	//   - 「安全限流」是防护强度开关，往上调等于削弱爆破防护。
	// 这两类必须跟 SMTP 一样限超管，否则"分角色"这件事在最要紧的地方是空的。
	Super bool
	get   func(*RuntimeSettings) string
	set   func(*RuntimeSettings, int64, bool)
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
	// 像素上限限超管：它是**内存安全**闸门（解码内存与像素数成正比），
	// 调高等于放大单张最坏内存占用，与 security.* 同性质。
	// 范围 4M~64M：低于 4M 会拒掉客户端正常压缩后的图（长边 2048 约 4M），
	// 高于 64M 则回到本次要修掉的那个状态。
	{Key: "album.photo_max_megapixels", Group: groupAlbum, Kind: "int", Min: 4, Max: 64, Label: "单张照片像素上限(百万像素)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.PhotoMaxPixels >> 20) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.PhotoMaxPixels = int(v) << 20 }},
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

	// 数据保留。三项全部限超管：调小保留期 = 让下一次定时清理去真删数据，
	// 而清理是不可逆的（回收站那条连磁盘文件一起删）。
	{Key: "retention.network_log_days", Group: groupRetention, Kind: "int", Min: 0, Max: 3650, Label: "网络日志保留(天，0=永久)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.NetworkLogDays) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.NetworkLogDays = int(v) }},
	{Key: "retention.status_history_days", Group: groupRetention, Kind: "int", Min: 0, Max: 3650, Label: "状态历史保留(天，0=永久)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.StatusHistoryDays) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.StatusHistoryDays = int(v) }},
	// 这一条是全部配置里破坏力最大的：改小 → runRetentionCleanup → PurgeExpiredRecycleBin
	// → 真删 photo 行 + 真删磁盘上的原图与缩略图。用户以为"还在回收站里能恢复"的照片
	// 会在几小时内消失，且无任何撤销途径。
	{Key: "retention.recycle_bin_days", Group: groupRetention, Kind: "int", Min: 0, Max: 3650, Label: "回收站保留(天，0=永久)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.RecycleBinDays) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.RecycleBinDays = int(v) }},

	// 安全与限流。整组限超管：这些项都是"防护强度"旋钮，
	// 往放松的方向调等于削弱爆破防护，而普通 admin 账号本身就是可能被爆破的目标之一。
	{Key: "security.email_code_ttl_min", Group: groupSecurity, Kind: "int", Min: 1, Max: 1440, Label: "邮箱验证码有效期(分钟)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.EmailCodeTTLMinutes) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.EmailCodeTTLMinutes = int(v) }},
	{Key: "security.email_code_cooldown_sec", Group: groupSecurity, Kind: "int", Min: 10, Max: 3600, Label: "验证码发送冷却(秒)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.EmailCodeCooldownSec) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.EmailCodeCooldownSec = int(v) }},
	// 上限从 100 收到 20：验证码只有 6 位（100 万组合），
	// 允许 100 次尝试是在明显放大爆破成功率，给不出正当场景。
	{Key: "security.email_code_max_attempts", Group: groupSecurity, Kind: "int", Min: 1, Max: 20, Label: "验证码最大尝试次数", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.EmailCodeMaxAttempts) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.EmailCodeMaxAttempts = int(v) }},
	{Key: "security.login_rate_window_min", Group: groupSecurity, Kind: "int", Min: 1, Max: 1440, Label: "登录限流窗口(分钟)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.LoginRateWindowMin) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.LoginRateWindowMin = int(v) }},
	// 上限 20 而非 100：这两项是**削弱**爆破防护的方向，给不出需要 100 次失败的正当场景，
	// 而配得越大越接近"关掉防护"。范围收敛在这里不只是防手滑，也是防越权改动的最后一道闸。
	{Key: "security.admin_login_max_fails", Group: groupSecurity, Kind: "int", Min: 1, Max: 20, Label: "后台登录失败上限", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.AdminLoginMaxFails) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.AdminLoginMaxFails = int(v) }},
	// APP 端登录失败上限必须是独立的一项。此前 handleLogin 误读了上面那条
	// `AdminLoginMaxFails`：标着"后台登录失败上限"的开关实际只作用于 APP 端，
	// 而真正的后台登录写死 5 次、完全不受配置影响 —— 两边的语义整个错位。
	{Key: "security.user_login_max_fails", Group: groupSecurity, Kind: "int", Min: 1, Max: 20, Label: "APP 登录失败上限", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.UserLoginMaxFails) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.UserLoginMaxFails = int(v) }},
	{Key: "security.admin_token_ttl_hours", Group: groupSecurity, Kind: "int", Min: 1, Max: 720, Label: "后台登录有效期(小时)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.AdminTokenTTLHours) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.AdminTokenTTLHours = int(v) }},
	{Key: "security.user_token_ttl_hours", Group: groupSecurity, Kind: "int", Min: 1, Max: 8760, Label: "APP 登录有效期(小时)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.UserTokenTTLHours) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.UserTokenTTLHours = int(v) }},
	{Key: "security.invite_ttl_min", Group: groupSecurity, Kind: "int", Min: 1, Max: 10080, Label: "邀请码有效期(分钟)", Super: true,
		get: func(s *RuntimeSettings) string { return strconv.Itoa(s.InviteTTLMinutes) },
		set: func(s *RuntimeSettings, v int64, _ bool) { s.InviteTTLMinutes = int(v) }},
	{Key: "security.bind_attempt_limit", Group: groupSecurity, Kind: "int", Min: 1, Max: 20, Label: "绑定尝试次数上限", Super: true,
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
			// 前端据此把非超管的对应输入框置灰。
			// 注意这只是**提示**，真正的拦截在服务端 handleAdminUpdateSettings：
			// 置灰只能防误操作，防不住直接调接口。
			"super": spec.Super,
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

// isSuperOnlySettingKey 该运行参数是否只允许超管写入。
// 未知键返回 false，由调用方按"不认识的键"处理（白名单语义）。
func isSuperOnlySettingKey(key string) bool {
	for _, spec := range runtimeSettingSpecs {
		if spec.Key == key {
			return spec.Super
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
