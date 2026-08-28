package main

import (
	"crypto/tls"
	"errors"
	"fmt"
	"mime"
	"net"
	"net/smtp"
	"regexp"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// ================= 账号体系（注册/登录/邮箱验证码/扩展资料） =================

var (
	reUsername = regexp.MustCompile(`^[A-Za-z]{3,20}$`)           // 用户名：3-20 位大小写英文
	reEmail    = regexp.MustCompile(`^[^@\s]+@[^@\s]+\.[^@\s]+$`) // 邮箱基础校验
)

// emailCodeTTLNow 验证码有效期，读后台配置（默认 10 分钟）。
func emailCodeTTLNow() time.Duration {
	return time.Duration(settingsNow().EmailCodeTTLMinutes) * time.Minute
}

func emailCodeKey(email string) string   { return "emailcode:" + strings.ToLower(email) }
func emailCodeCDKey(email string) string { return "emailcode:cd:" + strings.ToLower(email) }

// ---------- 验证码爆破防护 ----------

// 单个验证码允许的校验失败次数已改为后台可配（settings.go 的 EmailCodeMaxAttempts，默认 5）。
// 6 位数字仅 100 万组合，无上限时可在有效期内穷举 → 冒用他人邮箱注册。

func emailCodeAttemptKey(email string) string {
	return "emailcode:try:" + strings.ToLower(email)
}

// emailCodeAttemptAllowed 是否还允许尝试。
func emailCodeAttemptAllowed(email string) bool {
	return st.mem.count(emailCodeAttemptKey(email)) < int64(settingsNow().EmailCodeMaxAttempts)
}

// emailCodeAttemptFailed 记一次失败，返回是否已达上限（达到则调用方应作废该验证码）。
func emailCodeAttemptFailed(email string) bool {
	// TTL 与验证码有效期同量级即可：验证码过期后计数自然失效。
	return st.mem.incr(emailCodeAttemptKey(email), 15*time.Minute) >= int64(settingsNow().EmailCodeMaxAttempts)
}

func emailCodeAttemptReset(email string) { st.mem.del(emailCodeAttemptKey(email)) }

// ---------- SMTP（配置来自后台 app_setting，可随时修改） ----------

type smtpConfig struct {
	Host, Port, Username, Password, From string
	SSL                                  bool
}

func loadSMTP() (smtpConfig, error) {
	get := func(k string) string { v, _ := st.GetSetting(k); return strings.TrimSpace(v) }
	sc := smtpConfig{
		Host:     get("smtp.host"),
		Port:     get("smtp.port"),
		Username: get("smtp.username"),
		Password: get("smtp.password"),
		From:     get("smtp.from"),
		SSL:      get("smtp.ssl") == "true",
	}
	if sc.From == "" {
		sc.From = sc.Username
	}
	if sc.Host == "" || sc.Username == "" || sc.Password == "" {
		return sc, errors.New("smtp not configured")
	}
	if sc.Port == "" {
		if sc.SSL {
			sc.Port = "465"
		} else {
			sc.Port = "587"
		}
	}
	return sc, nil
}

// APPEND-ACCOUNT-1

func buildMessage(from, to, subject, body string) []byte {
	enc := mime.QEncoding.Encode("utf-8", subject)
	headers := map[string]string{
		"From":         from,
		"To":           to,
		"Subject":      enc,
		"MIME-Version": "1.0",
		"Content-Type": "text/html; charset=utf-8",
	}
	var b strings.Builder
	for k, v := range headers {
		fmt.Fprintf(&b, "%s: %s\r\n", k, v)
	}
	b.WriteString("\r\n")
	b.WriteString(body)
	return []byte(b.String())
}

// verifyCodeEmailHTML 生成品牌化的验证码邮件（内联样式，兼容主流邮箱客户端；单一强调色=品牌蓝）。
func verifyCodeEmailHTML(code string, minutes int) string {
	return fmt.Sprintf(`<!DOCTYPE html><html><body style="margin:0;padding:0;background:#f5f6f8;">
<div style="max-width:480px;margin:0 auto;padding:32px 20px;font-family:-apple-system,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:#1f2329;">
  <div style="background:#ffffff;border-radius:16px;padding:32px 28px;border:1px solid #eceef1;">
    <div style="font-size:20px;font-weight:600;color:#277AF7;">林曦日记</div>
    <div style="margin-top:20px;font-size:15px;line-height:1.6;color:#4e5969;">你正在注册 / 验证林曦日记账号，验证码如下：</div>
    <div style="margin:24px 0;text-align:center;">
      <span style="display:inline-block;font-size:32px;letter-spacing:8px;font-weight:700;color:#277AF7;background:#f0f6ff;border-radius:12px;padding:14px 24px;">%s</span>
    </div>
    <div style="font-size:13px;line-height:1.6;color:#86909c;">验证码 %d 分钟内有效，请勿泄露给他人。若非本人操作，请忽略本邮件。</div>
  </div>
  <div style="text-align:center;margin-top:16px;font-size:12px;color:#a9aeb8;">此邮件由系统自动发送，请勿回复</div>
</div>
</body></html>`, code, minutes)
}

// smtpTimeout 发信的整体超时。
//
// 原先 `tls.Dial` 与 `smtp.SendMail` 都不带超时，而它们是在**请求 goroutine 里同步跑的**：
// SMTP 主机被防火墙丢包（不是拒绝，是黑洞）时 TCP 会一直重传，那条 HTTP 请求
// 就永久悬挂、连带占住内存。发送冷却是按邮箱分桶的，换个邮箱即可再挂一条。
const smtpTimeout = 20 * time.Second

func sendMail(sc smtpConfig, to, subject, body string) error {
	addr := net.JoinHostPort(sc.Host, sc.Port)
	auth := smtp.PlainAuth("", sc.Username, sc.Password, sc.Host)
	msg := buildMessage(sc.From, to, subject, body)
	dialer := &net.Dialer{Timeout: smtpTimeout}
	if !sc.SSL {
		// 587/25 明文起步 + STARTTLS。不能再用 smtp.SendMail：它内部自己 Dial，
		// 没有任何注入超时的入口，只能改为手动建连后复用 negotiate 流程。
		conn, err := dialer.Dial("tcp", addr)
		if err != nil {
			return err
		}
		// 连上之后的每一步也要有期限：握手完成但对端不再回话时，
		// 上面的 Dial 超时已经用掉了，后续 Auth/Data 会无限等。
		_ = conn.SetDeadline(time.Now().Add(smtpTimeout))
		client, err := smtp.NewClient(conn, sc.Host)
		if err != nil {
			conn.Close()
			return err
		}
		defer client.Close()
		if ok, _ := client.Extension("STARTTLS"); ok {
			if err := client.StartTLS(&tls.Config{ServerName: sc.Host}); err != nil {
				return err
			}
		}
		return sendMailBody(client, auth, sc.From, to, msg)
	}
	// 465 隐式 TLS：手动建立连接
	conn, err := tls.DialWithDialer(dialer, "tcp", addr, &tls.Config{ServerName: sc.Host})
	if err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Now().Add(smtpTimeout))
	client, err := smtp.NewClient(conn, sc.Host)
	if err != nil {
		conn.Close()
		return err
	}
	defer client.Close()
	return sendMailBody(client, auth, sc.From, to, msg)
}

// sendMailBody 走完 AUTH → MAIL → RCPT → DATA → QUIT。
// 抽出来是因为 STARTTLS 与隐式 TLS 两条路径在这一步之后完全相同，
// 各写一遍必然分叉（此前明文路径靠 smtp.SendMail、TLS 路径手写，两边行为就已经不一致）。
func sendMailBody(client *smtp.Client, auth smtp.Auth, from, to string, msg []byte) error {
	if err := client.Auth(auth); err != nil {
		return err
	}
	if err := client.Mail(from); err != nil {
		return err
	}
	if err := client.Rcpt(to); err != nil {
		return err
	}
	w, err := client.Data()
	if err != nil {
		return err
	}
	if _, err := w.Write(msg); err != nil {
		return err
	}
	if err := w.Close(); err != nil {
		return err
	}
	return client.Quit()
}

// ---------- 发送邮箱验证码 ----------

func handleSendEmailCode(c *gin.Context) {
	var req struct {
		Email string `json:"email" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if !reEmail.MatchString(email) {
		fail(c, 400, 1002, "邮箱格式不正确")
		return
	}
	// 发送冷却（后台可配，默认 60s）。文案带上真实秒数，避免用户干等不知道要多久。
	cdSec := settingsNow().EmailCodeCooldownSec
	if !st.mem.kvSetNX(emailCodeCDKey(email), time.Duration(cdSec)*time.Second) {
		fail(c, 429, 1012, fmt.Sprintf("验证码发送过于频繁，请 %d 秒后再试", cdSec))
		return
	}
	code := randomCode(6)
	st.mem.kvSet(emailCodeKey(email), code, emailCodeTTLNow())
	sc, err := loadSMTP()
	if err != nil {
		fail(c, 500, 1013, "邮件服务未配置，请联系管理员")
		return
	}
	body := verifyCodeEmailHTML(code, int(emailCodeTTLNow().Minutes()))
	if err := sendMail(sc, email, "林曦日记 · 邮箱验证码", body); err != nil {
		fail(c, 500, 1014, "验证码发送失败，请稍后再试")
		return
	}
	ok(c, gin.H{"sent": true, "expires_in": int(emailCodeTTLNow().Seconds())})
}

// APPEND-ACCOUNT-2

// ---------- 注册 ----------

func handleRegister(c *gin.Context) {
	var req struct {
		Username string `json:"username" binding:"required"`
		Email    string `json:"email" binding:"required"`
		Code     string `json:"code" binding:"required"`
		Password string `json:"password" binding:"required"`
		Nickname string `json:"nickname"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	username := strings.TrimSpace(req.Username)
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if !reUsername.MatchString(username) {
		fail(c, 400, 1002, "用户名需为 3-20 位大小写英文字母")
		return
	}
	if !reEmail.MatchString(email) {
		fail(c, 400, 1002, "邮箱格式不正确")
		return
	}
	if len(req.Password) < 6 {
		fail(c, 400, 1002, "密码至少 6 位")
		return
	}
	// 校验验证码。
	//
	// 必须有尝试次数上限：6 位数字只有 100 万种组合，此前校验失败**完全不计数**，
	// 攻击者可在验证码 10 分钟有效期内穷举，从而用他人邮箱完成注册（抢占身份）。
	if !emailCodeAttemptAllowed(email) {
		fail(c, 429, 1016, "验证码尝试次数过多，请重新获取")
		return
	}
	saved, found := st.mem.kvGet(emailCodeKey(email))
	if !found || saved != strings.TrimSpace(req.Code) {
		// 失败即计数；达到上限后连同验证码一起作废，迫使对方重新获取（重新获取有 60s 冷却）。
		if emailCodeAttemptFailed(email) {
			st.mem.kvDel(emailCodeKey(email))
		}
		fail(c, 400, 1015, "验证码错误或已过期")
		return
	}
	nickname := username
	if strings.TrimSpace(req.Nickname) != "" {
		n, err := normalizeNickname(req.Nickname)
		if err != nil {
			fail(c, 400, 1002, "昵称长度 2-32")
			return
		}
		nickname = n
	}
	id, err := st.CreateUser(username, email, nickname, hashPassword(req.Password))
	if err != nil {
		fail(c, 400, 1006, "用户名或邮箱已被占用")
		return
	}
	st.mem.kvDel(emailCodeKey(email))
	emailCodeAttemptReset(email)
	token, _ := signToken(id)
	ok(c, gin.H{"user_id": id, "token": token})
}

// ---------- 登录 ----------

func handleLogin(c *gin.Context) {
	var req struct {
		Account  string `json:"account"`
		Username string `json:"username"`
		Email    string `json:"email"`
		Password string `json:"password" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	account := strings.TrimSpace(req.Account)
	if account == "" {
		account = strings.TrimSpace(req.Username)
	}
	if account == "" {
		account = strings.ToLower(strings.TrimSpace(req.Email))
	}
	if account == "" {
		fail(c, 400, 1002, "请输入账号")
		return
	}
	// 登录失败限流：同一账号在窗口内最多 N 次失败，防暴力破解。
	// 读 UserLoginMaxFails —— 此前误读了 AdminLoginMaxFails（那是后台的项）。
	set := settingsNow()
	failKey := "login:fail:" + strings.ToLower(account)
	loginWindow := time.Duration(set.LoginRateWindowMin) * time.Minute
	if st.mem.count(failKey) >= int64(set.UserLoginMaxFails) {
		fail(c, 429, 1012, fmt.Sprintf("登录尝试过于频繁，请 %d 分钟后再试", set.LoginRateWindowMin))
		return
	}
	u, err := st.GetUserByLogin(account)
	if err != nil || !checkPassword(u.PasswordHash, req.Password) {
		st.mem.incr(failKey, loginWindow)
		fail(c, 400, 1007, "账号或密码错误")
		return
	}
	st.mem.del(failKey)
	token, _ := signToken(u.ID)
	ok(c, gin.H{"user_id": u.ID, "token": token})
}

// ---------- 扩展个人资料（本人） ----------

// applyProfileAvatarDefault 未上传头像时回退全局 LOGO(site.logo)，用于「我的资料」展示。
func applyProfileAvatarDefault(p *UserProfile) {
	if p == nil {
		return
	}
	logo, _ := st.GetSetting("site.logo")
	logo = strings.TrimSpace(logo)
	p.AvatarURL = avatarOrLogo(p.AvatarURL, logo)
	p.AvatarThumbnailURL = avatarOrLogo(p.AvatarThumbnailURL, logo)
}

func handleGetMyProfile(c *gin.Context) {
	p, err := st.GetUserProfile(currentUID(c))
	if err != nil {
		fail(c, 500, 1010, "读取资料失败")
		return
	}
	applyProfileAvatarDefault(p)
	ok(c, p)
}

func handleUpdateMyProfile(c *gin.Context) {
	uid := currentUID(c)
	var req struct {
		Nickname  string  `json:"nickname" binding:"required"`
		Gender    int     `json:"gender"`
		Signature *string `json:"signature"`
		Birthday  *string `json:"birthday"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	nickname, err := normalizeNickname(req.Nickname)
	if err != nil {
		fail(c, 400, 1002, "昵称长度 2-32")
		return
	}
	gender := req.Gender
	if gender < 0 || gender > 2 {
		gender = 0
	}
	var sig *string
	if req.Signature != nil {
		s := strings.TrimSpace(*req.Signature)
		if len([]rune(s)) > 200 {
			fail(c, 400, 1002, "简介不能超过 200 字")
			return
		}
		if s != "" {
			sig = &s
		}
	}
	var birthday *string
	if req.Birthday != nil && strings.TrimSpace(*req.Birthday) != "" {
		b := strings.TrimSpace(*req.Birthday)
		if _, err := parseAnniversary(b, time.Now()); err != nil {
			fail(c, 400, 1002, "生日日期无效")
			return
		}
		birthday = &b
	}
	if err := st.UpdateUserProfile(uid, nickname, gender, sig, birthday); err != nil {
		fail(c, 500, 1010, "更新失败")
		return
	}
	if profile, err := pairProfile(uid); err == nil {
		notifyProfileUpdated(uid, profile)
	}
	p, err := st.GetUserProfile(uid)
	if err != nil {
		fail(c, 500, 1010, "读取资料失败")
		return
	}
	applyProfileAvatarDefault(p)
	ok(c, p)
}
