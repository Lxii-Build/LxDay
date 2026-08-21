package main

import (
	"strconv"
	"testing"
)

// 默认值必须与各调用点原本写死的常量一致——这是「改造不改变现有行为」的底线。
// 若哪天有人手滑把默认配额从 200 改成 20，这个测试会立刻拦住。
func TestDefaultRuntimeSettingsMatchLegacyConstants(t *testing.T) {
	d := defaultRuntimeSettings()
	checks := []struct {
		name string
		got  int64
		want int64
	}{
		{"单张照片上限", d.PhotoMaxBytes, 20 * 1024 * 1024},
		{"每日张数", int64(d.PhotosPerDay), 200},
		{"每日字节", d.UploadBytesPerDay, 500 * 1024 * 1024},
		{"网络日志保留", int64(d.NetworkLogDays), 7},
		{"验证码有效期", int64(d.EmailCodeTTLMinutes), 10},
		{"验证码冷却", int64(d.EmailCodeCooldownSec), 60},
		{"验证码尝试上限", int64(d.EmailCodeMaxAttempts), 5},
		{"登录限流窗口", int64(d.LoginRateWindowMin), 10},
		{"后台登录失败上限", int64(d.AdminLoginMaxFails), 5},
		{"后台token有效期", int64(d.AdminTokenTTLHours), 2},
		{"用户token有效期", int64(d.UserTokenTTLHours), 720},
		{"邀请码有效期", int64(d.InviteTTLMinutes), 60},
		{"绑定尝试上限", int64(d.BindAttemptLimit), 5},
		{"响铃冷却", int64(d.RingCooldownSec), 600},
		{"响铃次数", int64(d.RingCooldownLimit), 3},
		{"轻互动冷却", int64(d.InteractionCooldownSec), 7},
	}
	for _, c := range checks {
		if c.got != c.want {
			t.Errorf("%s 默认值 %d，期望 %d", c.name, c.got, c.want)
		}
	}
	// 功能开关默认全开：升级不该悄悄关掉用户正在用的功能。
	if !d.AlbumEnabled || !d.PhotoSocialEnabled || !d.OnThisDayEnabled {
		t.Error("功能开关默认应全部开启")
	}
}

// 越界值要收敛而非拒绝：管理员填了非法值也必须落到一个确定的可用状态，
// 不能让服务处于"配置一半"的坏状态（比如配额 0 = 相册完全不可用）。
func TestApplySettingValueClampsRange(t *testing.T) {
	cases := []struct {
		key      string
		raw      string
		wantFunc func(RuntimeSettings) int64
		want     int64
		desc     string
	}{
		{"album.photos_per_day", "0",
			func(s RuntimeSettings) int64 { return int64(s.PhotosPerDay) }, 1, "0 收敛到下限 1"},
		{"album.photos_per_day", "-5",
			func(s RuntimeSettings) int64 { return int64(s.PhotosPerDay) }, 1, "负数收敛到下限"},
		{"album.photos_per_day", "999999999",
			func(s RuntimeSettings) int64 { return int64(s.PhotosPerDay) }, 100000, "超大值收敛到上限"},
		{"album.photo_max_mb", "1000",
			func(s RuntimeSettings) int64 { return s.PhotoMaxBytes }, 100 * 1024 * 1024, "MB 上限 100"},
		{"security.admin_token_ttl_hours", "0",
			func(s RuntimeSettings) int64 { return int64(s.AdminTokenTTLHours) }, 1, "token 有效期不能为 0"},
		{"retention.status_history_days", "0",
			func(s RuntimeSettings) int64 { return int64(s.StatusHistoryDays) }, 0, "保留天数 0 合法（=永久）"},
		{"retention.status_history_days", "99999",
			func(s RuntimeSettings) int64 { return int64(s.StatusHistoryDays) }, 3650, "保留天数上限 3650"},
	}
	for _, c := range cases {
		s := defaultRuntimeSettings()
		if !applySettingValue(&s, c.key, c.raw) {
			t.Fatalf("%s: applySettingValue 未采纳 %q", c.desc, c.raw)
		}
		if got := c.wantFunc(s); got != c.want {
			t.Errorf("%s: %s=%q → %d，期望 %d", c.desc, c.key, c.raw, got, c.want)
		}
	}
}

// 非法输入（空串/非数字）必须保留默认值，不能变成 0。
func TestApplySettingValueRejectsGarbage(t *testing.T) {
	for _, raw := range []string{"abc", "", "12.5", "1e3", "０"} {
		s := defaultRuntimeSettings()
		before := s.PhotosPerDay
		accepted := applySettingValue(&s, "album.photos_per_day", raw)
		if accepted {
			t.Errorf("垃圾值 %q 竟被采纳，结果 %d", raw, s.PhotosPerDay)
		}
		if s.PhotosPerDay != before {
			t.Errorf("垃圾值 %q 改动了配置：%d → %d", raw, before, s.PhotosPerDay)
		}
	}
	// 不认识的键也不能生效
	s := defaultRuntimeSettings()
	if applySettingValue(&s, "album.no_such_key", "1") {
		t.Error("未知键竟被采纳")
	}
}

// bool 型开关的各种写法都要认。
func TestApplyBoolSetting(t *testing.T) {
	truthy := []string{"1", "true", "TRUE", "yes", "on"}
	falsy := []string{"0", "false", "no", "off", "", "随便"}
	for _, raw := range truthy {
		s := defaultRuntimeSettings()
		s.AlbumEnabled = false
		applySettingValue(&s, "album.enabled", raw)
		if !s.AlbumEnabled {
			t.Errorf("%q 应解析为 true", raw)
		}
	}
	for _, raw := range falsy {
		s := defaultRuntimeSettings()
		s.AlbumEnabled = true
		applySettingValue(&s, "album.enabled", raw)
		if s.AlbumEnabled {
			t.Errorf("%q 应解析为 false", raw)
		}
	}
}

// 「一键恢复默认」依赖 GET 下发的 defaults 与代码常量一致（Q26=C）。
// 若二者脱节，管理员点了恢复默认反而会填进一组错误的值。
func TestRuntimeSettingsPayloadDefaultsMatchCode(t *testing.T) {
	values, defaults, meta := runtimeSettingsPayload()
	if len(meta) != len(runtimeSettingSpecs) {
		t.Fatalf("meta 条数 %d != spec 条数 %d", len(meta), len(runtimeSettingSpecs))
	}
	// 未配置任何东西时，当前值应当等于默认值。
	runtimeCache.Store(nil)
	values, defaults, _ = runtimeSettingsPayload()
	for k, def := range defaults {
		if values[k] != def {
			t.Errorf("键 %s：当前值 %q 与默认值 %q 不一致（未配置时应相同）", k, values[k], def)
		}
	}
	// 关键键的默认值必须能解析成预期数字。
	if got := defaults["album.photos_per_day"]; got != "200" {
		t.Errorf("album.photos_per_day 默认值 %q，期望 \"200\"", got)
	}
	if got := defaults["album.photo_max_mb"]; got != "20" {
		t.Errorf("album.photo_max_mb 默认值 %q，期望 \"20\"", got)
	}
	// 每个 meta 项都要有 group/kind/label，否则前端渲染不出来。
	for _, m := range meta {
		for _, field := range []string{"key", "group", "kind", "label"} {
			if v, okv := m[field].(string); !okv || v == "" {
				t.Errorf("meta %v 缺少 %s", m["key"], field)
			}
		}
	}
}

// 配置键与运行参数字段必须双向对齐：spec 里每个键都能读能写。
func TestEveryRuntimeSpecIsReadWritable(t *testing.T) {
	for _, spec := range runtimeSettingSpecs {
		s := defaultRuntimeSettings()
		before := spec.get(&s)
		// 写一个与默认不同的合法值，再读回来验证真的变了。
		var probe string
		if spec.Kind == "bool" {
			probe = "0"
			if before == "0" {
				probe = "1"
			}
		} else {
			n, err := strconv.ParseInt(before, 10, 64)
			if err != nil {
				t.Fatalf("%s: 默认值 %q 不是数字", spec.Key, before)
			}
			probe = strconv.FormatInt(clampProbe(n, spec), 10)
		}
		if !applySettingValue(&s, spec.Key, probe) {
			t.Fatalf("%s: 写入 %q 失败", spec.Key, probe)
		}
		if got := spec.get(&s); got != probe {
			t.Errorf("%s: 写入 %q 后读回 %q（get/set 不对称）", spec.Key, probe, got)
		}
		if !isRuntimeSettingKey(spec.Key) {
			t.Errorf("%s: isRuntimeSettingKey 返回 false", spec.Key)
		}
	}
	if len(runtimeSettingKeys()) != len(runtimeSettingSpecs) {
		t.Error("runtimeSettingKeys 数量与 spec 不符")
	}
}

// clampProbe 取一个在 [Min,Max] 内、且与 n 不同的探测值。
func clampProbe(n int64, spec settingSpec) int64 {
	if n+1 <= spec.Max || spec.Max == 0 {
		return n + 1
	}
	if n-1 >= spec.Min {
		return n - 1
	}
	return n
}

// 敏感键（含密钥）不能混进运行参数——那些对普通 admin 开放。
func TestRuntimeKeysContainNoSecrets(t *testing.T) {
	for _, k := range runtimeSettingKeys() {
		for _, bad := range []string{"password", "secret", "access_key", "smtp", "token_secret"} {
			if containsFold(k, bad) {
				t.Errorf("运行参数键 %q 含敏感字样 %q，不应对普通 admin 开放", k, bad)
			}
		}
	}
}

func containsFold(s, sub string) bool {
	return len(sub) > 0 && len(s) >= len(sub) &&
		stringsContainsFold(s, sub)
}

func stringsContainsFold(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		match := true
		for j := 0; j < len(sub); j++ {
			a, b := s[i+j], sub[j]
			if a >= 'A' && a <= 'Z' {
				a += 32
			}
			if b >= 'A' && b <= 'Z' {
				b += 32
			}
			if a != b {
				match = false
				break
			}
		}
		if match {
			return true
		}
	}
	return false
}
