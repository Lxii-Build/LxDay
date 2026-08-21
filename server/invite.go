package main

import (
	"crypto/rand"
	"math/big"
	"strconv"
	"strings"
	"time"
)

// ================= 绑定码（生成 / 校验 / 爆破防护） =================
// 原实现是 6 位纯数字（仅 100 万空间）、TTL 1 小时、且对 /pair/bind 无任何尝试次数限制。
// 后果：攻击者注册一个账号后循环提交邀请码即可在几分钟内撞上任意在线邀请，
// 绑成陌生人的 pair —— 而 pair 一旦成立就能读到对方全部日记、待办、设备状态历史（含 SSID、前台应用）。
// 现改为 8 位混合字符（约 32^8 ≈ 1.1 万亿）+ 每账号 10 分钟 5 次上限。

// inviteAlphabet 大写字母 + 数字，剔除易混字符 0/O/1/I/L，便于口头/截图转达。
const inviteAlphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

// inviteCodeLen 新码长度。旧库里可能仍存在 6 位纯数字的历史码，校验侧兼容（见 normalizeInviteCode）。
const inviteCodeLen = 8

// 绑定尝试限流：窗口与次数均后台可配（默认 10 分钟内最多 5 次失败），超限直接拒绝。
func bindAttemptWindowNow() time.Duration {
	return time.Duration(settingsNow().LoginRateWindowMin) * time.Minute
}

func bindAttemptLimitNow() int64 { return int64(settingsNow().BindAttemptLimit) }

func bindFailKey(uid int64) string { return "bind:fail:" + strconv.FormatInt(uid, 10) }

// generateInviteCode 生成 8 位邀请码。
// 必须用 crypto/rand：math/rand 的默认源可由时间种子预测，攻击者能直接算出对方刚生成的码。
func generateInviteCode() string {
	n := big.NewInt(int64(len(inviteAlphabet)))
	out := make([]byte, inviteCodeLen)
	for i := range out {
		v, err := rand.Int(rand.Reader, n)
		if err != nil {
			// crypto/rand 失败属系统级异常；返回空串让调用方走「生成失败」分支，
			// 绝不退化到可预测的随机源。
			return ""
		}
		out[i] = inviteAlphabet[v.Int64()]
	}
	return string(out)
}

// normalizeInviteCode 规整用户输入：去空白、转大写。
// 返回值为空表示格式不合法。兼容两种长度：
//   - 8 位混合字符（当前生成的新码）
//   - 6 位纯数字（历史遗留码，老客户端仍可能持有；库里存量码不作废，避免升级窗口内绑定全挂）
func normalizeInviteCode(in string) string {
	s := strings.ToUpper(strings.TrimSpace(in))
	s = strings.ReplaceAll(s, " ", "")
	s = strings.ReplaceAll(s, "-", "")
	switch len(s) {
	case inviteCodeLen:
		for i := 0; i < len(s); i++ {
			if !strings.ContainsRune(inviteAlphabet, rune(s[i])) {
				return ""
			}
		}
		return s
	case 6:
		// 历史 6 位纯数字码。
		for i := 0; i < len(s); i++ {
			if s[i] < '0' || s[i] > '9' {
				return ""
			}
		}
		return s
	default:
		return ""
	}
}

// bindAttemptAllowed 记一次绑定尝试并判断是否放行（在校验绑定码之前调用）。
// 计数只在「尝试」时累加、在「绑定成功」时清零，故失败次数才是实际消耗的额度。
func bindAttemptAllowed(uid int64) bool {
	return st.mem.incr(bindFailKey(uid), bindAttemptWindowNow()) <= bindAttemptLimitNow()
}

// bindAttemptReset 绑定成功后清零计数，避免正常用户被自己此前的手误拖累。
func bindAttemptReset(uid int64) { st.mem.del(bindFailKey(uid)) }
