package main

import (
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

// ================= 按来源 IP 的限流 =================
//
// ★★ 为什么必须有这一层：客户端没有可保密的共享密钥 ★★
//
// 任何随客户端分发的密钥都不可能保密，故当前 APK 不再携带 APP_KEY。
// 正确的威胁模型是：攻击者可以直接调用公开接口并伪造客户端，
// 服务端仍然必须依靠 HTTPS、JWT、账号状态校验与限流保持安全。
//
// 而在这个前提下，此前所有限流的**键全部由攻击者自己控制**：
//
//	login:fail:<账号名>      ← 攻击者提供
//	emailcode:cd:<邮箱>      ← 攻击者提供
//	media:cnt:<日期>:<uid>   ← uid 来自攻击者注册的账号，可无限换
//
// 换个账号名、换个邮箱、换个新注册账号，每一道限流都从零开始。
// 唯一不受攻击者随意支配的维度就是**来源 IP**，而它此前只用于写审计日志、
// 从未参与任何限流判定。这一层就是补这个缺口。
//
// IP 可以伪造吗：不能。`SetTrustedProxies` 只信任本机与内网段（见 main.go），
// 所以 `c.ClientIP()` 要么是真实对端地址，要么是同机 Nginx 转发的 XFF ——
// 外部无法通过伪造 XFF 绕过（0820 那轮已经把默认"信任所有代理"改掉了）。
//
// 注意这不是万能的：攻击者换 IP（代理池/家宽拨号）仍能绕过。
// 但它把「一台机器批量注册几百个账号」的成本从 0 提到了「要有 IP 池」，
// 而真正兜住内存的是解码侧的硬上限（像素上限 + 帧数扫描 + 并发闸门），
// 那几道不依赖任何身份判定。分层防御，这一层负责抬高成本。

// ipRateSpec 描述一条 IP 限流规则。
type ipRateSpec struct {
	name   string        // 用于 key 前缀与日志
	limit  int64         // 窗口内允许的次数
	window time.Duration // 窗口长度
	msg    string        // 超限时给用户看的中文提示
}

var (
	// 注册：**最要紧的一条**。
	//
	// 攻击链的第一步就是调用注册接口拿 JWT，而注册此前
	// 除邮箱验证码外零限制 —— 一台机器可以无成本地造出任意多个账号，
	// 于是每个"每人每日配额"都等于没有。
	// 1 小时 5 个对真实用户绝对够（情侣应用，一个人一辈子注册一次）。
	ipRateRegister = ipRateSpec{
		name: "reg", limit: 5, window: time.Hour,
		msg: "注册过于频繁，请稍后再试",
	}
	// 发验证码：每封信都是真实的 SMTP 开销与配额消耗。
	// 按邮箱的冷却已有（默认 60s），但换个邮箱即可绕过，所以要再加按 IP 的。
	ipRateSendCode = ipRateSpec{
		name: "code", limit: 10, window: time.Hour,
		msg: "验证码请求过于频繁，请稍后再试",
	}
	// 登录：按账号的失败限流已有，但攻击者换账号名就能重置。
	// 30 次/10 分钟对真人足够宽松（记错密码试几次很正常），
	// 对撞库/枚举则是实质约束。
	ipRateLogin = ipRateSpec{
		name: "login", limit: 30, window: 10 * time.Minute,
		msg: "登录尝试过于频繁，请稍后再试",
	}
	// 上传：按账号的每日配额已有，但新注册账号的配额是满的。
	// 这条按 IP 卡住"注册一批账号轮着传"的放大路径。
	// 300 次/小时远高于正常使用（一次最多选 100 张），不影响真实批量上传。
	ipRateUpload = ipRateSpec{
		name: "upload", limit: 300, window: time.Hour,
		msg: "上传过于频繁，请稍后再试",
	}
)

// ipRateKey 生成限流键。
//
// 键里带 IP，而 IP 长度有界（IPv6 最长 45 字符），
// 所以不会像账号名那样构成"超长 key"的放大面；
// memStore 的 normalizeMemKey 仍会兜第二道。
func ipRateKey(name, ip string) string {
	return "iprate:" + name + ":" + ip
}

// allowByIP 判定并计数。返回 false 表示应当拒绝。
//
// 用 incr 而不是"先查后写"：并发请求下先查后写会被击穿
// （这正是 reserveUploadQuota 修过的那个坑）。
func allowByIP(spec ipRateSpec, ip string) bool {
	if ip == "" {
		// 取不到 IP 时放行而不是拒绝：宁可漏一次限流，
		// 也不能因为拿不到 IP 就把正常用户全拦住（fail-open 在这里是对的，
		// 因为兜底的内存安全闸门不依赖 IP）。
		return true
	}
	return st.mem.incr(ipRateKey(spec.name, ip), spec.window) <= spec.limit
}

// IPRateLimit 返回按 IP 限流的中间件。
//
// 放在 handler 之前而不是 handler 内部：注册与发码这类接口在 handler 里
// 会先做一堆校验（正则、查库、发信），限流必须在这些开销之前生效。
func IPRateLimit(spec ipRateSpec) gin.HandlerFunc {
	return func(c *gin.Context) {
		ip := c.ClientIP()
		if !allowByIP(spec, ip) {
			// 记日志留痕：这是唯一能发现"有人在批量注册/撞库"的地方。
			// IP 记进日志是可以的（审计日志本来就记 IP），但**不记请求体**
			// —— 那里面有邮箱与口令。
			slog.Warn("ip rate limit exceeded",
				"rule", spec.name, "ip", ip, "limit", spec.limit, "window", spec.window.String())
			fail(c, http.StatusTooManyRequests, 1012, spec.msg)
			c.Abort()
			return
		}
		c.Next()
	}
}

// ---------- 单账号在飞行上限 ----------

// maxInFlightPerUser 单个用户同时可占用的图片处理槽位数。
//
// 全局闸门是 maxConcurrentDecodes(3)。若不加这条，一个账号发 3 个并发上传
// 就能占满全部槽位，其余所有用户的上传全部排队到超时 ——
// 这是一种**不需要打爆内存**的拒绝服务：内存曲线平稳，但别人都传不上去。
//
// 取 2（而非 1）：客户端串行上传，正常单用户只占 1 个；
// 给 2 是为了留一点重试/并发余量，同时保证 3 个槽位里**至少有 1 个**
// 永远留给其他用户。
const maxInFlightPerUser = 2

func inFlightKey(uid int64) string {
	return "inflight:" + strconv.FormatInt(uid, 10)
}

// acquireUserSlot 占一个用户级槽位，返回释放函数。
// 第二个返回值为 false 表示该用户已占满，调用方应拒绝并让其重试。
//
// TTL 是兜底：正常路径一定会调 release，但若进程在处理中被 kill
// （或将来某条路径漏了 release），槽位不能永久泄漏。
// 5 分钟与 http.Server 的 WriteTimeout 同量级 —— 那时请求本身早已结束。
func acquireUserSlot(uid int64) (release func(), ok bool) {
	key := inFlightKey(uid)
	if st.mem.incr(key, 5*time.Minute) > maxInFlightPerUser {
		st.mem.incrBy(key, -1, 5*time.Minute)
		return nil, false
	}
	return func() { st.mem.incrBy(key, -1, 5*time.Minute) }, true
}
