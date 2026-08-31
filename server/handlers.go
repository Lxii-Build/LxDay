package main

import (
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log"
	"log/slog"
	"math/big"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

// uploadDir 为公开静态资源根目录（Go 自托管 /uploads/ 静态映射）。
// 私密相册文件使用同级的 privateMediaDir()，绝不能落在这个目录下面。
var uploadDir = "uploads"

func initUploadDir() {
	if cfg.Storage.UploadDir != "" {
		uploadDir = cfg.Storage.UploadDir
	}
}

// privateMediaDir 是私密相册文件的物理根目录。
//
// 它必须是 uploadDir 的同级目录，而不能只是 uploadDir 下的另一个子目录：
// /uploads 静态兼容路由映射整个 uploadDir，放在其子目录里仍然等于公开。
// 不新增配置项，避免管理员还要同步改 compose；目录名由公开根目录稳定推导。
func privateMediaDir() string {
	clean := filepath.Clean(uploadDir)
	return filepath.Join(filepath.Dir(clean), filepath.Base(clean)+"-private")
}

func privateMediaDatePath(t time.Time) string {
	return fmt.Sprintf("media/%04d/%02d/%02d", t.Year(), int(t.Month()), t.Day())
}

// ================= 上传路径 / 公开 URL（日期分区，本地磁盘） =================
// 头像与日记图片统一存本地 uploadDir/upload/年/月/日/<随机名><扩展名>，对外走 /upload 静态路由。
// 站点地址(site.url)已配置 → 绝对 URL(https://域名/upload/...)；未配置 → 相对 /upload/...（客户端用 BASE_URL 兜底）。

// uploadDatePath 返回当日日期分区相对路径 upload/YYYY/MM/DD（正斜杠，URL 与磁盘子路径共用）。
func uploadDatePath(t time.Time) string {
	return fmt.Sprintf("upload/%04d/%02d/%02d", t.Year(), int(t.Month()), t.Day())
}

// relFromUploadDir 将 uploadDir 下的本地文件路径转为相对 uploadDir 的正斜杠路径（供 URL 生成/删除映射）。
func relFromUploadDir(fullPath string) string {
	rel, err := filepath.Rel(uploadDir, fullPath)
	if err != nil {
		return filepath.ToSlash(filepath.Base(fullPath))
	}
	return filepath.ToSlash(rel)
}

// siteBaseCache 缓存 site.url。
//
// **这不是性能优化，是正确性要求。** SQLite 连接池是 MaxOpenConns(1)（见 main.go），
// 而 siteBaseURL 会被 scanPhoto → mediaURL 在 `for rows.Next()` 遍历中调用；
// 若此刻再发一次查询，它就要排队等那条正被 rows 占用、且要等遍历结束才释放的连接
// —— 自己等自己，直接死锁（照片列表接口永久挂起，测试里表现为整包 600s 超时）。
var siteBaseCache struct {
	sync.RWMutex
	val    string
	loaded bool
}

// invalidateSiteBaseCache 在后台保存设置后调用：**立即重载，而不是留成冷态**。
//
// 为什么不能只把 loaded 置 false：那样下一次读取就要查库，而 siteBaseURL 会被
// scanPhoto 在 `for rows.Next()` 遍历中调用 —— MaxOpenConns(1) 下那次查询会
// 等一条永不释放的连接，直接死锁（详见 siteBaseCache 的注释与 warmSiteBaseCache）。
//
// 本函数只在后台保存设置时被调用（admin.go），那是普通 HTTP handler，
// 此刻没有任何 rows 在遍历，可以安全查库。**冷态窗口必须在这里就关掉。**
func invalidateSiteBaseCache() {
	warmSiteBaseCache()
}

// warmSiteBaseCache 主动把 site.url 读进缓存，消除「冷缓存 + rows 遍历」的死锁窗口。
//
// 必须在启动时（服务开始接请求、且在任何遍历照片的定时任务之前）调用一次，
// 见 main.go。否则第一次走到 scanPhoto 的那条路径就会挂死整个连接池 ——
// 最确定的触发点是 startRequestLogWorker 里同步先跑的那次 runRetentionCleanup。
func warmSiteBaseCache() {
	if st == nil {
		return
	}
	raw, err := st.GetSetting("site.url")
	if err != nil {
		// 取不到就别写缓存：留着未加载状态，下次再试。
		// 这里刻意不 fatal —— 站点地址只影响 URL 是绝对还是相对，不该阻断启动。
		slog.Warn("预热站点地址失败，图片 URL 暂按相对路径下发", "err", err)
		return
	}
	v := normalizeSiteBase(raw)
	siteBaseCache.Lock()
	siteBaseCache.val = v
	siteBaseCache.loaded = true
	siteBaseCache.Unlock()
}

// siteBaseURL 读后台站点地址(site.url)，规整为无尾斜杠的 scheme://host 前缀；
// 未配置或缓存未就绪时返回空 —— 调用方回退相对路径，客户端侧会补全成绝对 URL。
//
// **本函数绝不查库。** 这是硬性约束，不是优化取舍：
// 它会被 scanPhoto 在 `for rows.Next()` 遍历中调用，而 MaxOpenConns(1) 下
// 那次查询要等一条正被 rows 占用、且要等遍历结束才释放的连接 —— 自己等自己，
// 永久死锁，那条连接再也不回池，全站所有 DB 操作随之挂死。
//
// 早先的实现是「冷缓存时惰性查库」，靠"第一次调用之后就有缓存了"来规避，
// 但**第一次调用本身仍要查库**。生产上最确定的触发点是启动时同步跑的那次
// runRetentionCleanup → 清理回收站 → 遍历 photo 行 → scanPhoto，
// 于是「库里有过期回收站照片」的容器一起来就整个服务不可用
// （见 retention_deadlock_test.go，已实测复现）。
//
// 现在改成：**冷态直接返回空，绝不自己去查**。加载的责任交给
// warmSiteBaseCache（启动时一次 + 后台保存设置后一次），那两处都不在 rows 遍历中。
// 代价是万一预热失败，图片地址会退成相对路径 —— 客户端有
// MediaUrlPolicy.absolutize 兜着，功能不受影响。
// 收益是死锁在结构上不可能再发生，而不是靠"记得先预热"的顺序约定。
func siteBaseURL() string {
	siteBaseCache.RLock()
	defer siteBaseCache.RUnlock()
	if !siteBaseCache.loaded {
		return "" // 冷态：回退相对路径，绝不在这里查库（会死锁，见上）
	}
	return siteBaseCache.val
}

func normalizeSiteBase(raw string) string {
	v := strings.TrimSpace(raw)
	if v == "" {
		return ""
	}
	v = strings.TrimRight(v, "/")
	if !strings.HasPrefix(v, "http://") && !strings.HasPrefix(v, "https://") {
		v = "https://" + v // 仅填域名时默认 https
	}
	return v
}

// publicUploadURL 由 uploadDir 相对路径(如 upload/2026/08/13/x.png)生成对外 URL。
func publicUploadURL(rel string) string {
	rel = strings.TrimPrefix(filepath.ToSlash(rel), "/")
	path := "/" + rel // 相对 URL 与 /upload 静态挂载对齐（rel 以 upload/ 打头）
	if base := siteBaseURL(); base != "" {
		return base + path
	}
	return path
}

// settingFrom 用给定 queryer（可为事务）读单条设置，复用同一连接，避免单连接池(MaxOpenConns=1)在事务内二次取连接死锁。
func settingFrom(q profileQueryer, key string) string {
	var v sql.NullString
	if err := q.QueryRow("SELECT v FROM app_setting WHERE k=?", key).Scan(&v); err != nil {
		return ""
	}
	return v.String
}

// avatarOrLogo 头像为空时回退全局 LOGO(site.logo)；仅用于对客户端展示，不改库、不影响旧头像清理（清理读原始库值）。
func avatarOrLogo(cur *string, logo string) *string {
	if cur != nil && strings.TrimSpace(*cur) != "" {
		return cur
	}
	if logo != "" {
		l := logo
		return &l
	}
	return cur // nil 或空 → 交客户端兜底
}

// ================= 工具 =================

func sqlOpen(dsn string) (*sql.DB, error) {
	return sql.Open("sqlite", dsn)
}

func ok(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, gin.H{"code": 0, "message": "ok", "data": data})
}

func fail(c *gin.Context, httpCode, bizCode int, msg string) {
	drainRequestBody(c)
	c.JSON(httpCode, gin.H{"code": bizCode, "message": msg})
}

// drainRequestBody 在返回错误前把未读完的请求体丢弃掉。
//
// **这是生产 502 的根因修复。** Go 的 http server 在 handler 返回时只会自动排空
// 最多 256KB 的未读 body，超过就直接关闭连接。于是任何「大 body + 提前拒绝」的组合
// （AppKeyGuard 403 / JWTAuth 401 / 配额 429 / 超过单张上限 400）都会让 Nginx
// 在还没写完 body 时撞上 RST，对外表现为 **502 Bad Gateway**，
// 而不是我们精心写的中文错误——客户端只能显示"服务器开小差了"，
// 用户完全不知道真实原因是格式还是配额。
//
// 实测（生产 love.lxii.cc）：body ≤1MB 正常回 403；≥2MB 一律 502；60MB 才是 Nginx 的 413。
//
// 上限 320MB：必须覆盖后台 APK 的 300MB 上传上限，否则大上传在鉴权/校验
// 阶段被提前拒绝时，未读完的请求体仍会让 Go 关闭连接，Nginx 可能把业务错误
// 放大成 502。真正更大的请求仍由 Nginx/client_max_body_size 拦截。
func drainRequestBody(c *gin.Context) {
	if c.Request == nil || c.Request.Body == nil {
		return
	}
	// GET/DELETE 之类通常无 body，ContentLength==0 时直接跳过，省掉一次系统调用。
	if c.Request.ContentLength == 0 {
		return
	}
	const maxDrain = 320 << 20
	_, _ = io.CopyN(io.Discard, c.Request.Body, maxDrain)
}

// hashPassword 使用 bcrypt 生成加盐哈希（每次不同）；校验用 checkPassword。
func hashPassword(pw string) string {
	b, err := bcrypt.GenerateFromPassword([]byte(pw), bcrypt.DefaultCost)
	if err != nil {
		return ""
	}
	return string(b)
}

// checkPassword 校验明文与 bcrypt 哈希是否匹配。
func checkPassword(hash, pw string) bool {
	return hash != "" && bcrypt.CompareHashAndPassword([]byte(hash), []byte(pw)) == nil
}

func randomCode(n int) string {
	const digits = "0123456789"
	out := make([]byte, n)
	for i := range out {
		v, err := rand.Int(rand.Reader, big.NewInt(int64(len(digits))))
		if err != nil {
			return ""
		}
		out[i] = digits[v.Int64()]
	}
	return string(out)
}

// randomPassword 生成随机强口令（去除易混字符），用于超级管理员初始密码，避免公开的默认口令被抢注接管。
func randomPassword(n int) string {
	const cs = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
	out := make([]byte, n)
	for i := range out {
		v, err := rand.Int(rand.Reader, big.NewInt(int64(len(cs))))
		if err != nil {
			return ""
		}
		out[i] = cs[v.Int64()]
	}
	return string(out)
}

// ================= JWT =================

// signToken 签发用户 token。App 端不接受频繁重登，故仍保留 720h 长有效期，
// 改密/封禁后的即时失效改由 claims 里的 tv(token_ver) 承担（见 JWTAuth）。
func signToken(uid int64) (string, error) {
	tv, err := st.UserTokenVer(uid)
	if err != nil {
		return "", err
	}
	return signTokenWithVer(uid, tv)
}

// userTokenTTL APP 端 token 有效期，读后台配置（security.user_token_ttl_hours，默认 720 小时）。
//
// 原先读的是 `cfg.App.TokenTTLHours`（配置文件 / 环境变量），
// 于是后台那一项「APP 登录有效期(小时)」改了完全无效。
// 配置文件里的值现在作为「后台从未配置过时的兜底」：settingsNow() 在未配置时
// 返回默认 720，若管理员在 config.yaml 里显式写过别的值，以他写的为准。
func userTokenTTL() time.Duration {
	if p := runtimeCache.Load(); p != nil && p.UserTokenTTLHours > 0 {
		return time.Duration(p.UserTokenTTLHours) * time.Hour
	}
	if cfg != nil && cfg.App.TokenTTLHours > 0 {
		return time.Duration(cfg.App.TokenTTLHours) * time.Hour
	}
	return time.Duration(defaultRuntimeSettings().UserTokenTTLHours) * time.Hour
}

func signTokenWithVer(uid int64, tokenVer int64) (string, error) {
	claims := jwt.MapClaims{
		"uid": uid,
		"tv":  tokenVer,
		"exp": time.Now().Add(userTokenTTL()).Unix(),
	}
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return t.SignedString([]byte(cfg.App.JWTSecret))
}

// ParseToken 解析用户 token，返回 uid 与 claims 中的 token_ver。
// 老 token 无 tv 字段 → 视为 0，与新库默认值一致，升级后不会把所有人踢下线。
func ParseToken(token string) (int64, int64, error) {
	t, err := jwt.Parse(token, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method")
		}
		return []byte(cfg.App.JWTSecret), nil
	})
	if err != nil || !t.Valid {
		if err == nil {
			err = fmt.Errorf("invalid token")
		}
		return 0, 0, err
	}
	claims, ok := t.Claims.(jwt.MapClaims)
	if !ok {
		return 0, 0, fmt.Errorf("bad claims")
	}
	uidF, ok := claims["uid"].(float64)
	if !ok {
		return 0, 0, fmt.Errorf("bad claims")
	}
	tv, _ := claims["tv"].(float64)
	return int64(uidF), int64(tv), nil
}

// authUserByToken 用户鉴权的公共校验：token 合法性 + 账号存在 + 未被封禁 + token_ver 未被撤销。
// HTTP 与 /ws 两处入口共用，避免 WS 侧漏校验成为绕过口。
func authUserByToken(token string) (int64, error) {
	uid, tv, err := ParseToken(token)
	if err != nil {
		return 0, err
	}
	// 实时读库：status 与 token_ver 都必须查当前值。
	// 不查 status 的后果：后台「封禁用户」形同虚设，被封用户拿旧 token 照样读写全部接口；
	// 不查 token_ver 的后果：改密/封禁后旧 token 仍能用满 720h（整整一个月）。
	status, dbVer, err := st.UserAuthState(uid)
	if err != nil {
		return 0, fmt.Errorf("user not found")
	}
	if status != 1 {
		return 0, errUserDisabled
	}
	if dbVer != tv {
		return 0, fmt.Errorf("token revoked")
	}
	return uid, nil
}

// errUserDisabled 账号被封禁，前端据独立业务码提示「账号已被禁用」而非「登录已失效」。
var errUserDisabled = errors.New("user disabled")

func JWTAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		token := bearerToken(c.GetHeader("Authorization"))
		uid, err := authUserByToken(token)
		if err != nil {
			// token 有效但用户不存在/被撤销（如数据库重建、账号被删、改密）→ 视为登录失效，
			// 让客户端据 401 自动清会话并回登录页，而非报 500/403。
			if errors.Is(err, errUserDisabled) {
				fail(c, http.StatusForbidden, 1018, "账号已被禁用，请联系管理员")
			} else {
				fail(c, http.StatusUnauthorized, 1003, "登录已失效，请重新登录")
			}
			c.Abort()
			return
		}
		c.Set("uid", uid)
		c.Next()
	}
}

func bearerToken(header string) string {
	header = strings.TrimSpace(header)
	if len(header) < len("Bearer ") || !strings.EqualFold(header[:len("Bearer ")], "Bearer ") {
		return ""
	}
	return strings.TrimSpace(header[len("Bearer "):])
}

func currentUID(c *gin.Context) int64 {
	return c.GetInt64("uid")
}

// AppKeyGuard 通讯密钥中间件：校验请求头 X-App-Key 是否与配置 app_key 一致。
// 仅挂在 /api/v1/* 分组；app_key 为空时禁用（放行）。错误沿用 /api/v1 错误信封。
func AppKeyGuard() gin.HandlerFunc {
	return func(c *gin.Context) {
		expected := cfg.App.AppKey
		if expected == "" {
			c.Next()
			return
		}
		got := c.GetHeader("X-App-Key")
		if subtle.ConstantTimeCompare([]byte(got), []byte(expected)) != 1 {
			fail(c, http.StatusForbidden, 1016, "客户端校验失败")
			c.Abort()
			return
		}
		c.Next()
	}
}

// ================= 认证 =================
// 账号体系（注册/登录/邮箱验证码/扩展资料）实现见 account.go。

// ================= 绑定 =================

// inviteTTLNow 邀请码有效期，读后台配置（默认 60 分钟）。
func inviteTTLNow() time.Duration {
	return time.Duration(settingsNow().InviteTTLMinutes) * time.Minute
}

// createInviteMu 串行化同一进程内的邀请码创建。
//
// 先查“是否已有挂起邀请”再 INSERT 的组合不是单条 SQL；同一账号的两个并发
// 请求如果同时通过查询，就会生成两张都有效的邀请码。项目是单容器单进程部署，
// 在这里加窄范围互斥锁即可把该竞态在入口处消掉，绑定流程仍由 BindPair 的事务兜底。
var createInviteMu sync.Mutex

func handleCreateInvite(c *gin.Context) {
	createInviteMu.Lock()
	defer createInviteMu.Unlock()
	uid := currentUID(c)
	if _, err := st.GetPairByUserID(uid); err == nil {
		fail(c, 400, 1001, "已绑定，无法重复创建")
		return
	} else if !errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusInternalServerError, 1010, "读取关系失败")
		return
	}
	// 若已有 1 小时内未过期的邀请，直接复用
	var existCode string
	var existCreated time.Time
	err := st.DB.QueryRow(
		`SELECT invite_code, created_at FROM pair WHERE user_a_id=? AND user_b_id=0 AND status=1 LIMIT 1`,
		uid).Scan(&existCode, &existCreated)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusInternalServerError, 1010, "读取邀请状态失败")
		return
	}
	if err == nil && existCode != "" {
		if time.Since(existCreated) < inviteTTLNow() {
			ok(c, gin.H{"invite_code": existCode, "expires_in": int(inviteTTLNow().Seconds() - time.Since(existCreated).Seconds())})
			return
		}
		// 过期：作废旧码重新生成
		if _, err := st.DB.Exec(`UPDATE pair SET status=0 WHERE invite_code=?`, existCode); err != nil {
			fail(c, http.StatusInternalServerError, 1010, "更新邀请状态失败")
			return
		}
	}
	// 生成唯一 8 位混合字符邀请码（crypto/rand，见 invite.go）。
	// 唯一索引 uk_invite_code 冲突时重试，重试 5 次仍冲突才报失败。
	for i := 0; i < 5; i++ {
		code := generateInviteCode()
		if code == "" {
			break // 系统随机源异常，绝不退化到可预测随机
		}
		_, err := st.DB.Exec(
			`INSERT INTO pair(user_a_id,user_b_id,invite_code,status) VALUES(?,0,?,1)`,
			uid, code)
		if err == nil {
			ok(c, gin.H{"invite_code": code, "expires_in": int(inviteTTLNow().Seconds())})
			return
		}
	}
	fail(c, 500, 1008, "邀请码生成失败")
}

func handleBind(c *gin.Context) {
	uid := currentUID(c)
	var req struct {
		InviteCode string `json:"invite_code" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	if _, err := st.GetPairByUserID(uid); err == nil {
		fail(c, 400, 1001, "已绑定")
		return
	} else if !errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusInternalServerError, 1010, "读取关系失败")
		return
	}
	// 爆破防护：先记一次尝试并判额度，再校验码本身。
	// 顺序很关键——若先校验码后计数，则「码不存在」这条最常见的失败路径不消耗额度，限流等于没做。
	if !bindAttemptAllowed(uid) {
		fail(c, 429, 1019, "绑定尝试过于频繁，请 10 分钟后再试")
		return
	}
	code := normalizeInviteCode(req.InviteCode)
	if code == "" {
		fail(c, 400, 1002, "邀请码格式不正确")
		return
	}
	// 校验有效期：邀请记录创建时间必须在 1 小时内
	var created time.Time
	if err := st.DB.QueryRow(
		`SELECT created_at FROM pair WHERE invite_code=? AND status=1`, code).
		Scan(&created); err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			fail(c, http.StatusInternalServerError, 1010, "读取邀请状态失败")
			return
		}
		fail(c, 400, 1009, "邀请码无效或已失效")
		return
	}
	if time.Since(created) > inviteTTLNow() {
		fail(c, 400, 1009, "邀请码已过期，请让对方重新生成")
		return
	}
	if _, err := st.BindPair(code, uid); err != nil {
		fail(c, 400, 1009, err.Error())
		return
	}
	bindAttemptReset(uid)
	// 返回伴侣信息
	pair, err := st.GetPairByUserID(uid)
	if err != nil || pair == nil || pair.UserAID <= 0 || pair.UserBID <= 0 {
		fail(c, 500, 1010, "绑定已完成但读取关系失败")
		return
	}
	// 建默认分组（Q22=A+B）：让用户一进相册就有地方放照片，不必先想名字建相册。
	// 失败不阻断绑定——这只是便利功能。
	if err := st.CreatePresetAlbums(pair.ID, uid); err != nil {
		slog.Warn("create preset albums failed", "pair_id", pair.ID, "err", err)
	}
	partner := st.PartnerID(pair, uid)
	pu, _ := st.GetUserByID(partner)
	// 通知邀请方（另一方）：绑定成功，据此从"等待绑定"进入主界面。
	// 邀请方在线→WS 即时；离线→入补偿队列，其重连或轮询 /pair/status 时兜底。
	binder, _ := st.GetUserByID(uid)
	pairedData := gin.H{"pair_id": pair.ID, "bound": true}
	if binder != nil {
		pairedData["partner"] = binder
	}
	hub.route(partner, WsMessage{Type: MsgPaired, Data: pairedData})
	ok(c, gin.H{"pair_id": pair.ID, "partner": pu})
}

// handleUnbind 用户主动解除绑定：双方同时解绑（pair 置 status=0），并通知对方回到绑定页。
// 退出登录 ≠ 解绑；解绑后双方均可重新生成/输入邀请码正常绑定。
func handleUnbind(c *gin.Context) {
	uid := currentUID(c)
	pair, err := st.GetPairByUserID(uid)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			ok(c, gin.H{"unbound": true}) // 本就未绑定
			return
		}
		fail(c, http.StatusInternalServerError, 1010, "读取关系失败")
		return
	}
	partner := st.PartnerID(pair, uid)
	res, e := st.DB.Exec(`UPDATE pair SET status=0, unbind_time=datetime('now') WHERE id=? AND status=1`, pair.ID)
	if e != nil {
		fail(c, 500, 1010, "解绑失败")
		return
	}
	if affected, e := res.RowsAffected(); e != nil {
		fail(c, 500, 1010, "解绑失败")
		return
	} else if affected != 1 {
		// 另一条并发解绑请求已经完成，幂等返回成功，不重复通知。
		ok(c, gin.H{"unbound": true})
		return
	}
	if partner > 0 {
		hub.route(partner, WsMessage{Type: MsgUnbound, Data: gin.H{"pair_id": pair.ID}})
	}
	ok(c, gin.H{"unbound": true})
}

// handleCancelInvite 邀请方主动取消自己尚未被使用的邀请码（挂起邀请作废）。
func handleCancelInvite(c *gin.Context) {
	uid := currentUID(c)
	if _, err := st.DB.Exec(`UPDATE pair SET status=0 WHERE user_a_id=? AND user_b_id=0 AND status=1`, uid); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "取消邀请失败")
		return
	}
	ok(c, gin.H{"canceled": true})
}

func handlePairStatus(c *gin.Context) {
	profile, err := pairProfile(currentUID(c))
	if errors.Is(err, errPairUnbound) {
		ok(c, gin.H{"bound": false})
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "读取资料失败")
		return
	}
	ok(c, profile)
}

func handleGetProfile(c *gin.Context) {
	profile, err := pairProfile(currentUID(c))
	if errors.Is(err, errPairUnbound) {
		fail(c, 200, 1001, "未绑定")
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "读取资料失败")
		return
	}
	ok(c, profile)
}

func handleUpdateProfile(c *gin.Context) {
	uid := currentUID(c)
	var req struct {
		Nickname string `json:"nickname" binding:"required"`
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

	tx, err := st.DB.Begin()
	if err != nil {
		fail(c, 500, 1010, "更新失败")
		return
	}
	if _, err = pairFrom(tx, uid); err != nil {
		_ = tx.Rollback()
		if errors.Is(err, sql.ErrNoRows) {
			fail(c, 200, 1001, "未绑定")
		} else {
			fail(c, 500, 1010, "读取关系失败")
		}
		return
	}
	if _, err := tx.Exec("UPDATE `user` SET nickname=? WHERE id=?", nickname, uid); err != nil {
		tx.Rollback()
		fail(c, 400, 1006, "昵称已被占用")
		return
	}
	profile, err := pairProfileFrom(tx, uid)
	if err != nil {
		tx.Rollback()
		fail(c, 500, 1010, "读取资料失败")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(c, 500, 1010, "更新失败")
		return
	}
	notifyProfileUpdated(uid, profile)
	ok(c, profile)
}

func handleUpdateAnniversary(c *gin.Context) {
	uid := currentUID(c)
	var req struct {
		AnniversaryDate string `json:"anniversary_date" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	anniversary, err := parseAnniversary(req.AnniversaryDate, time.Now())
	if err != nil {
		fail(c, 400, 1002, "纪念日无效")
		return
	}

	tx, err := st.DB.Begin()
	if err != nil {
		fail(c, 500, 1010, "更新失败")
		return
	}
	pair, err := pairFrom(tx, uid)
	if err != nil {
		_ = tx.Rollback()
		if errors.Is(err, sql.ErrNoRows) {
			fail(c, 200, 1001, "未绑定")
		} else {
			fail(c, 500, 1010, "读取关系失败")
		}
		return
	}
	result, err := tx.Exec(
		"UPDATE pair SET anniversary_date=? WHERE id=? AND status=1 AND (user_a_id=? OR user_b_id=?)",
		anniversary, pair.ID, uid, uid,
	)
	if err != nil {
		tx.Rollback()
		fail(c, 500, 1010, "更新失败")
		return
	}
	affected, err := result.RowsAffected()
	if err != nil {
		_ = tx.Rollback()
		fail(c, 500, 1010, "更新失败")
		return
	}
	if affected != 1 {
		tx.Rollback()
		fail(c, 200, 1001, "未绑定")
		return
	}
	profile, err := pairProfileFrom(tx, uid)
	if err != nil {
		tx.Rollback()
		fail(c, 500, 1010, "读取资料失败")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(c, 500, 1010, "更新失败")
		return
	}
	notifyProfileUpdated(uid, profile)
	ok(c, profile)
}

type profileQueryer interface {
	QueryRow(query string, args ...interface{}) *sql.Row
}

func pairFrom(queryer profileQueryer, uid int64) (*Pair, error) {
	pair := &Pair{}
	err := queryer.QueryRow(
		`SELECT id,user_a_id,user_b_id,invite_code,anniversary_date FROM pair
		 WHERE status=1 AND user_a_id>0 AND user_b_id>0
		   AND (user_a_id=? OR user_b_id=?) LIMIT 1`, uid, uid).Scan(
		&pair.ID, &pair.UserAID, &pair.UserBID, &pair.InviteCode, &pair.AnniversaryDate)
	return pair, err
}

func userFrom(queryer profileQueryer, id int64) (*User, error) {
	user := &User{}
	err := queryer.QueryRow(
		"SELECT id,nickname,avatar_url,avatar_thumbnail_url FROM `user` WHERE id=?", id).
		Scan(&user.ID, &user.Nickname, &user.AvatarURL, &user.AvatarThumbnailURL)
	return user, err
}

var errPairUnbound = errors.New("pair is not bound")

func reportPairLookupError(c *gin.Context, err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, sql.ErrNoRows) {
		fail(c, http.StatusOK, 1001, "未绑定")
		return true
	}
	fail(c, http.StatusInternalServerError, 1010, "读取关系失败")
	return true
}

func pairProfileFrom(queryer profileQueryer, uid int64) (gin.H, error) {
	pair, err := pairFrom(queryer, uid)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, errPairUnbound
	}
	if err != nil {
		return nil, err
	}
	me, err := userFrom(queryer, uid)
	if err != nil {
		return nil, err
	}
	partnerID := pair.UserAID
	if pair.UserAID == uid {
		partnerID = pair.UserBID
	}
	partner, err := userFrom(queryer, partnerID)
	if err != nil {
		return nil, err
	}
	// 头像默认全局 LOGO：未上传时对客户端返回 site.logo（用同一 queryer 读设置，避免事务内二次取连接死锁）。
	logo := settingFrom(queryer, "site.logo")
	me.AvatarURL = avatarOrLogo(me.AvatarURL, logo)
	me.AvatarThumbnailURL = avatarOrLogo(me.AvatarThumbnailURL, logo)
	partner.AvatarURL = avatarOrLogo(partner.AvatarURL, logo)
	partner.AvatarThumbnailURL = avatarOrLogo(partner.AvatarThumbnailURL, logo)
	var anniversary interface{}
	if pair.AnniversaryDate != nil {
		anniversary = pair.AnniversaryDate.Format("2006-01-02")
	}
	return gin.H{
		"bound":            true,
		"pair_id":          pair.ID,
		"me":               me,
		"partner":          partner,
		"anniversary_date": anniversary,
	}, nil
}

func pairProfile(uid int64) (gin.H, error) {
	return pairProfileFrom(st.DB, uid)
}

func notifyProfileUpdated(uid int64, profile gin.H) {
	if hub == nil {
		return
	}
	partner, ok := profile["partner"].(*User)
	if !ok {
		return
	}
	hub.route(partner.ID, WsMessage{
		Type: MsgProfileUpdated,
		Data: map[string]interface{}{"user_id": uid},
	})
}

// ================= 对方状态 =================

func handlePartnerStatus(c *gin.Context) {
	uid := currentUID(c)
	pair, err := st.GetPairByUserID(uid)
	if err != nil {
		reportPairLookupError(c, err)
		return
	}
	partner := st.PartnerID(pair, uid)
	stObj, err := st.GetStatus(partner)
	if err != nil {
		ok(c, gin.H{"online": st.IsOnline(partner), "status": nil})
		return
	}
	ok(c, gin.H{"online": st.IsOnline(partner), "status": stObj})
}

// ================= 待办 =================

func mustPair(c *gin.Context) (*Pair, bool) {
	pair, err := st.GetPairByUserID(currentUID(c))
	if err != nil {
		reportPairLookupError(c, err)
		return nil, false
	}
	return pair, true
}

func handleCreateTodo(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	var req struct {
		Title         string    `json:"title" binding:"required"`
		AssigneeID    int64     `json:"assignee_id"`
		Note          string    `json:"note"`
		RemindAt      time.Time `json:"remind_at"`
		RemindType    int       `json:"remind_type"`    // 0普通 1强提醒
		RepeatType    int       `json:"repeat_type"`    // 0仅一次 1每天 2每周
		Weekdays      int       `json:"weekdays"`       // bit0=周一..bit6=周日
		RemindEnabled *bool     `json:"remind_enabled"` // 缺省=true
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	// assignee 允许 pair 任一成员（本人或伴侣）：给定即用，缺省=伴侣
	partner := st.PartnerID(pair, uid)
	assignee := partner
	if req.AssigneeID == uid || req.AssigneeID == partner {
		assignee = req.AssigneeID
	}
	remindEnabled := true
	if req.RemindEnabled != nil {
		remindEnabled = *req.RemindEnabled
	}
	var rp *time.Time
	if !req.RemindAt.IsZero() {
		rp = &req.RemindAt
	}
	repeatType, weekdays := normalizeRepeat(req.RepeatType, req.Weekdays)
	todo, err := st.CreateTodo(pair.ID, uid, assignee, req.Title, req.Note, rp, req.RemindType, repeatType, weekdays, remindEnabled)
	if err != nil {
		fail(c, 500, 1010, "创建失败")
		return
	}
	hub.Notify(pair, uid, WsMessage{Type: MsgTodoNew, Data: todo})
	ok(c, todo)
}

const allWeekdaysMask = 0x7F // 周一~周日全选

// normalizeRepeat 规整循环规则：每周全选→每天；每周未选→仅一次；非每周清空掩码。
func normalizeRepeat(repeatType, weekdays int) (int, int) {
	switch repeatType {
	case 2:
		weekdays &= allWeekdaysMask
		if weekdays == 0 {
			return 0, 0
		}
		if weekdays == allWeekdaysMask {
			return 1, 0
		}
		return 2, weekdays
	case 1:
		return 1, 0
	default:
		return 0, 0
	}
}

func handleListTodos(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	status, _ := strconv.Atoi(c.DefaultQuery("status", "0"))
	todos, err := st.ListTodos(pair.ID, status)
	if err != nil {
		fail(c, 500, 1010, "查询失败")
		return
	}
	ok(c, todos)
}

// getOwnedTodo 取出待办并校验其归属当前 pair（防越权：任意已绑定用户遍历 id 改删他人待办）。
func getOwnedTodo(pair *Pair, id int64) (*Todo, bool) {
	t, err := st.GetTodo(id)
	if err != nil || t == nil || t.PairID != pair.ID || t.Status == 2 {
		return nil, false
	}
	return t, true
}

func handleUpdateTodo(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "待办 ID 非法")
		return
	}
	if _, owned := getOwnedTodo(pair, id); !owned {
		fail(c, 403, 1017, "无权操作该待办")
		return
	}
	var req struct {
		Title         *string    `json:"title"`
		Note          *string    `json:"note"`
		RemindAt      *time.Time `json:"remind_at"`
		RemindType    *int       `json:"remind_type"`
		RepeatType    *int       `json:"repeat_type"`
		Weekdays      *int       `json:"weekdays"`
		RemindEnabled *bool      `json:"remind_enabled"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	if req.RepeatType != nil {
		rt, wd := normalizeRepeat(*req.RepeatType, valueOrZero(req.Weekdays))
		req.RepeatType = &rt
		req.Weekdays = &wd
	}
	if err := st.UpdateTodo(id, req.Title, req.Note, req.RemindAt, nil, req.RemindType, req.RepeatType, req.Weekdays, req.RemindEnabled); err != nil {
		fail(c, 500, 1010, "更新失败")
		return
	}
	todo, err := st.GetTodo(id)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "读取失败")
		return
	}
	hub.Notify(pair, currentUID(c), WsMessage{Type: MsgTodoNew, Data: todo})
	ok(c, todo)
}

func handleCompleteTodo(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "待办 ID 非法")
		return
	}
	if _, owned := getOwnedTodo(pair, id); !owned {
		fail(c, 403, 1017, "无权操作该待办")
		return
	}
	todo, err := st.CompleteTodo(id, uid)
	if err != nil {
		fail(c, 500, 1010, "操作失败")
		return
	}
	hub.Notify(pair, uid, WsMessage{Type: MsgTodoCompleted, Data: todo})
	ok(c, todo)
}

func handleDeleteTodo(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "待办 ID 非法")
		return
	}
	if _, owned := getOwnedTodo(pair, id); !owned {
		fail(c, 403, 1017, "无权操作该待办")
		return
	}
	if err := st.DeleteTodo(id); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "删除失败")
		return
	}
	ok(c, gin.H{"deleted": id})
}

// ================= 日记 =================

// ================= 情绪交互 =================

func handleComfort(c *gin.Context) {
	interaction(c, MsgComfortRequest)
}
func handleCalm(c *gin.Context) {
	interaction(c, MsgCalmRequest)
}
func handleRing(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	if !st.RingCooldown(pair.ID) {
		fail(c, 429, 1011, "响铃请求过于频繁，请稍后再试")
		return
	}
	interaction(c, MsgRingRequest)
}

func interaction(c *gin.Context, msgType string) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	payload := map[string]interface{}{"ts": time.Now().UnixMilli()}
	if u, err := st.GetUserByID(uid); err == nil {
		payload["from_name"] = u.Nickname
	}
	hub.Notify(pair, uid, WsMessage{Type: msgType, Data: payload})
	ok(c, gin.H{"sent": msgType})
}

// ================= 推送 token =================

func handleRegisterPushToken(c *gin.Context) {
	uid := currentUID(c)
	var req PushToken
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	req.Platform = strings.ToLower(strings.TrimSpace(req.Platform))
	req.Channel = strings.ToLower(strings.TrimSpace(req.Channel))
	req.Token = strings.TrimSpace(req.Token)
	if req.Platform == "" || len([]byte(req.Platform)) > 32 ||
		req.Channel == "" || len([]byte(req.Channel)) > 64 ||
		req.Token == "" || len([]byte(req.Token)) > 4096 {
		fail(c, 400, 1002, "推送参数无效")
		return
	}
	_, err := st.DB.Exec(
		`INSERT INTO push_token(user_id,platform,channel,token) VALUES(?,?,?,?)
		 ON CONFLICT(user_id,channel) DO UPDATE SET token=excluded.token, updated_at=datetime('now')`,
		uid, req.Platform, req.Channel, req.Token)
	if err != nil {
		log.Printf("push token register error: %v", err)
		fail(c, 500, 1010, "注册失败")
		return
	}
	ok(c, gin.H{"registered": true})
}

func handleUnregisterPushToken(c *gin.Context) {
	uid := currentUID(c)
	if _, err := st.DB.Exec(`DELETE FROM push_token WHERE user_id=?`, uid); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "注销失败")
		return
	}
	ok(c, gin.H{"unregistered": true})
}

// handleReportStatus 是 WS 上报的 **REST 兜底**（Q36=B）。
//
// 此前服务端只能通过 WS 收状态（/status/* 全是读接口），
// 而客户端 pushNow() 在 WS 未连接时直接 return 把这次采集扔掉。
// 结果：地铁、电梯、切飞行模式期间状态完全停更，
// 对方看到的是一个"看起来很正常"的旧值（客户端当时也不显示时效）。
//
// 与 WS 路径共用 hub.applyStatusUpdate，避免两条链路行为分叉。
func handleReportStatus(c *gin.Context) {
	uid := currentUID(c)
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	var incoming DeviceStatus
	if err := c.ShouldBindJSON(&incoming); err != nil {
		fail(c, http.StatusBadRequest, 1002, "参数错误")
		return
	}
	// 限频与 WS 共用同一个闸门，否则换条路径就能绕过。
	if !hub.allowStatusUpdate(uid) {
		fail(c, http.StatusTooManyRequests, 1012, "上报过于频繁")
		return
	}
	hub.applyStatusUpdate(pair, uid, &incoming)
	ok(c, gin.H{"accepted": true})
}

// ================= 状态历史 =================

// historySubjectUID 解析 ?who=me|partner，返回要查谁的历史。
//
// 此前固定用 currentUID —— 而页面标题是「伴侣状态历史」，
// 于是用户看到的一直是自己的记录。默认 partner 以匹配页面语义，
// 同时允许显式查 me（"我昨晚几点睡的"也有价值）。
func historySubjectUID(c *gin.Context, pair *Pair) (int64, bool) {
	uid := currentUID(c)
	switch c.DefaultQuery("who", "partner") {
	case "me":
		return uid, true
	case "partner":
		partner := pair.PartnerOf(uid)
		if partner <= 0 {
			fail(c, 200, 1001, "未绑定伴侣")
			return 0, false
		}
		return partner, true
	default:
		fail(c, http.StatusBadRequest, 1002, "参数 who 只能是 me 或 partner")
		return 0, false
	}
}

func handleHistoryTimeline(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	subject, okW := historySubjectUID(c, pair)
	if !okW {
		return
	}
	date := c.Query("date")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	if limit < 1 || limit > 200 {
		limit = 50
	}
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	list, err := st.HistoryTimeline(pair.ID, subject, date, limit, offset)
	if err != nil {
		fail(c, 500, 1010, "查询失败")
		return
	}
	// 时间戳统一为 epoch 毫秒（客户端按 ms 解析）
	type entry struct {
		Battery       int    `json:"battery"`
		Charging      bool   `json:"charging"`
		ScreenOn      bool   `json:"screen_on"`
		Locked        bool   `json:"locked"`
		ForegroundApp string `json:"foreground_app"`
		SSID          string `json:"ssid"`
		Network       string `json:"network"`
		Ts            int64  `json:"ts"`
	}
	out := make([]entry, 0, len(list))
	for _, h := range list {
		out = append(out, entry{
			Battery: h.BatteryLevel, Charging: h.IsCharging, ScreenOn: h.ScreenOn,
			Locked: h.IsLocked, ForegroundApp: h.ForegroundAppName(), SSID: h.SSIDValue(),
			Network: h.NetworkType, Ts: h.Ts.UnixMilli(),
		})
	}
	ok(c, out)
}

func handleBatteryCurve(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	subject, okW := historySubjectUID(c, pair)
	if !okW {
		return
	}
	date := c.Query("date")
	if date == "" {
		date = time.Now().Format("2006-01-02")
	}
	list, err := st.BatteryCurve(pair.ID, subject, date)
	if err != nil {
		fail(c, 500, 1010, "查询失败")
		return
	}
	// 精简：只要电量+充电+ts
	type point struct {
		Battery  int   `json:"battery"`
		Charging bool  `json:"charging"`
		Ts       int64 `json:"ts"`
	}
	out := make([]point, 0, len(list))
	for _, h := range list {
		out = append(out, point{h.BatteryLevel, h.IsCharging, h.Ts.UnixMilli()})
	}
	ok(c, out)
}

// ================= 日记图片上传（本地磁盘） =================
// 存储：uploadDir/upload/年/月/日/<随机名><扩展名>；Go 自托管 /upload/ 静态服务。
// URL 带不可猜测随机名，纯自用场景免鉴权；公开 URL 形态见 publicUploadURL。

// ================= 待办到点提醒定时扫描 =================
// 每分钟检查一次「到点未完成」的待办，通知 assignee（在线 WS / 离线入队）。

func scanDueTodos() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		// 单次扫描包 recover：单条待办或一次 WS 写异常不应杀死整个提醒扫描 goroutine。
		func() {
			defer func() {
				if r := recover(); r != nil {
					log.Printf("scanDueTodos panic recovered: %v", r)
				}
			}()
			scanDueOnce(time.Now())
		}()
	}
}

func scanDueOnce(now time.Time) {
	todos, err := st.DueTodos(now)
	if err != nil {
		log.Printf("scanDueTodos error: %v", err)
		return
	}
	for _, t := range todos {
		payload := map[string]interface{}{
			"todo_id": t.ID, "title": t.Title, "remind_type": t.RemindType,
			"ts": now.UnixMilli(),
		}
		msg := WsMessage{Type: MsgTodoRemind, Data: payload}
		// 提醒被提醒者(assignee)；创建者若与其不同也提示一次。去重避免自指派待办被重复推送两次。
		sent := map[int64]bool{}
		for _, uid := range []int64{t.AssigneeID, t.CreatorID} {
			if uid == 0 || sent[uid] {
				continue
			}
			sent[uid] = true
			hub.route(uid, msg)
		}
		// 推进提醒时间：循环提醒滚动到下次；仅一次则置空，避免每分钟重复触发
		var next *time.Time
		if t.RemindAt != nil {
			next = nextRemind(*t.RemindAt, t.RepeatType, t.Weekdays, now)
		}
		if err := st.AdvanceTodoRemind(t.ID, next); err != nil {
			log.Printf("advance todo %d remind error: %v", t.ID, err)
		}
	}
}

func valueOrZero(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

// nextRemind 依据循环规则计算严格晚于 now 的下一次提醒时间（保留原提醒的时刻）。
// repeatType: 1每天 2每周(weekdays 位掩码 bit0=周一..bit6=周日)；其余返回 nil（仅一次）。
func nextRemind(cur time.Time, repeatType, weekdays int, now time.Time) *time.Time {
	switch repeatType {
	case 1:
		c := cur
		for i := 0; i < 400 && !c.After(now); i++ {
			c = c.AddDate(0, 0, 1)
		}
		if c.After(now) {
			return &c
		}
	case 2:
		if weekdays&allWeekdaysMask == 0 {
			return nil
		}
		c := cur
		for i := 0; i < 14; i++ {
			idx := (int(c.Weekday()) + 6) % 7 // 周一=0 .. 周日=6
			if c.After(now) && weekdays&(1<<uint(idx)) != 0 {
				return &c
			}
			c = c.AddDate(0, 0, 1)
		}
	}
	return nil
}
