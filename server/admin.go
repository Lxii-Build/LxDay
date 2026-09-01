package main

import (
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// ================= 后台管理 API（独立 {code,msg,data} 信封） =================

const adminCodeOK = 200

var errLastSuperAdmin = errors.New("cannot remove last active super admin")

func aok(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, gin.H{"code": adminCodeOK, "msg": "success", "data": data})
}

func afail(c *gin.Context, httpCode, bizCode int, msg string) {
	// 与 fail 同理：不排空 body 的话，「大 body + 提前拒绝」会让 Nginx 收到 RST 变成 502。
	// 后台的 /upload（APK 上传，可达数百 MB）尤其容易踩到。
	drainRequestBody(c)
	c.JSON(httpCode, gin.H{"code": bizCode, "msg": msg})
}

// ---------- 管理员 JWT ----------

// adminTokenTTL 后台 token 有效期，读后台配置（security.admin_token_ttl_hours，默认 2 小时）。
//
// 与 App 端（默认 720h）不同，后台是高价值目标：一旦 token 泄露，攻击者可读取全站用户资料、
// 群发通知、删除内容。故默认压到 2 小时，配合 token_ver 做即时撤销。
//
// 此前这里是 `const adminTokenTTL = 2 * time.Hour`，而后台页面上摆着「后台登录有效期(小时)」
// 这一项 —— 管理员改它没有任何效果，属于比"没有这个开关"更糟的状态。
func adminTokenTTL() time.Duration {
	return time.Duration(settingsNow().AdminTokenTTLHours) * time.Hour
}

func signAdminToken(aid int64, role string, mustChange bool, tokenVer int64) (string, error) {
	claims := jwt.MapClaims{
		"aid":   aid,
		"role":  role,
		"scope": "admin",
		"mc":    mustChange,
		"tv":    tokenVer,
		"exp":   time.Now().Add(adminTokenTTL()).Unix(),
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(cfg.App.JWTSecret))
}

// signAdminTokenFor 读取该管理员当前的 token_ver 后签发，避免调用方各自查库漏掉。
func signAdminTokenFor(a *AdminUser) (string, error) {
	tv, err := st.AdminTokenVer(a.ID)
	if err != nil {
		return "", err
	}
	return signAdminToken(a.ID, a.Role, a.MustChange, tv)
}

// parseAdminToken 只做签名与结构校验，返回 aid 与 claims 中的 token_ver。
// **role / must_change 不再从 token 返回**——它们必须实时读库，见 AdminAuth。
func parseAdminToken(token string) (int64, int64, error) {
	t, err := jwt.Parse(token, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return []byte(cfg.App.JWTSecret), nil
	})
	if err != nil || !t.Valid {
		return 0, 0, errors.New("invalid token")
	}
	claims, ok := t.Claims.(jwt.MapClaims)
	if !ok || claims["scope"] != "admin" {
		return 0, 0, errors.New("bad claims")
	}
	aidF, ok := claims["aid"].(float64)
	if !ok {
		return 0, 0, errors.New("bad claims")
	}
	tvF, _ := claims["tv"].(float64)
	return int64(aidF), int64(tvF), nil
}

func AdminAuth() gin.HandlerFunc {
	// 首登强制改凭据前允许访问的白名单（否则一律拦截，防止用初始 admin 直接操作）。
	// 注意：键必须是 gin 的完整路由路径（含 /api/admin 组前缀），否则会误拦合法首登。
	allowWhileMustChange := map[string]bool{
		"/api/admin/user/info":          true,
		"/api/admin/change-credentials": true,
		// 改密页也要显示站点名与 LOGO；不放行会让改密页顶栏空白并在控制台刷 403。
		"/api/admin/site-info": true,
	}
	return func(c *gin.Context) {
		token := bearerToken(c.GetHeader("Authorization"))
		aid, tv, err := parseAdminToken(token)
		if err != nil {
			afail(c, http.StatusUnauthorized, 401, "登录已失效，请重新登录")
			c.Abort()
			return
		}
		// token 有效但管理员不存在（数据库重建 / 账号被删）→ 返回 401，
		// 让后台前端据 401 自动登出并回登录页，而非停在页面报 404/500/禁止访问。
		//
		// 关键：role / must_change / status 全部**实时读库**，不信 token 里的副本。
		// 不这么做的后果：① 管理员被降级或被禁用后，旧 token 仍按老权限畅通无阻整个有效期；
		// ② 首登拿到 mc=false 的 token 后，即便库里 must_change 被重置也不再被拦。
		a, err := st.GetAdminByID(aid)
		if err != nil || a == nil {
			afail(c, http.StatusUnauthorized, 401, "登录已失效，请重新登录")
			c.Abort()
			return
		}
		if a.Status != 1 {
			afail(c, http.StatusForbidden, 4033, "账号已被禁用")
			c.Abort()
			return
		}
		// token_ver 不匹配 = 该 token 已被撤销（改密 / 重置密码 / 禁用 / 角色变更）。
		curTV, err := st.AdminTokenVer(aid)
		if err != nil || curTV != tv {
			afail(c, http.StatusUnauthorized, 401, "登录状态已过期，请重新登录")
			c.Abort()
			return
		}
		if a.MustChange && !allowWhileMustChange[c.FullPath()] {
			afail(c, http.StatusForbidden, 4281, "请先修改初始账号与密码")
			c.Abort()
			return
		}
		c.Set("aid", aid)
		c.Set("role", a.Role)
		c.Set("mc", a.MustChange)
		c.Set("admin_name", a.Username)
		c.Next()
	}
}

// requireSuper 仅超级管理员可访问（管理员管理、系统设置等敏感操作）。
func requireSuper() gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.GetString("role") != "super" {
			afail(c, http.StatusForbidden, 403, "需要超级管理员权限")
			c.Abort()
			return
		}
		c.Next()
	}
}

// APPEND-ADMIN-1

type AdminUser struct {
	ID         int64   `json:"id"`
	Username   string  `json:"username"`
	Email      *string `json:"email"`
	Role       string  `json:"role"`
	MustChange bool    `json:"must_change"`
	Status     int     `json:"status"`
}

func (s *Store) EnsureSuperAdmin() error {
	tx, err := s.DB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var n int
	if err := tx.QueryRow("SELECT COUNT(*) FROM admin_user").Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	// 安全：不使用公开可知的默认口令（admin/123456 会被抢注接管），改为随机初始口令。
	pw := randomPassword(16)
	if pw == "" {
		return errors.New("secure random source unavailable")
	}
	// 先在事务中写入数据库，文件写失败时回滚，避免留下一个没有可取回
	// 初始口令的管理员账号，导致后续启动永远跳过初始化。
	if _, err = tx.Exec(
		"INSERT INTO admin_user(username,password_hash,role,must_change) VALUES(?,?,?,1)",
		"admin", hashPassword(pw), "super"); err != nil {
		return err
	}
	// 口令**不进日志**：此前用 slog.Warn 连同 password 字段打印，
	// 而 docker logs / 宝塔面板的日志长期留存且常被随手查看，
	// 等于「看一眼日志就能接管后台」。改为写入仅 owner 可读的文件。
	pwPath := filepath.Join(filepath.Dir(cfg.DB.Path), "initial-admin-password.txt")
	content := "林曦日记 · 初始超级管理员\r\n" +
		"用户名: admin\r\n" +
		"初始口令: " + pw + "\r\n\r\n" +
		"请立即登录后台完成首登强制改密，改密后请删除本文件。\r\n"
	if werr := os.WriteFile(pwPath, []byte(content), 0o600); werr != nil {
		// 绝不能把口令退回日志：日志通常会被持久化或转发到第三方。
		// 文件写失败时让启动失败，避免留下一个没人能安全取回口令的管理员。
		return fmt.Errorf("write initial admin password file %s: %w", pwPath, werr)
	}
	if err := tx.Commit(); err != nil {
		_ = os.Remove(pwPath)
		return err
	}
	slog.Warn("已创建初始超级管理员，口令写入文件（未打印到日志）；登录并改密后请删除该文件",
		"username", "admin", "password_file", pwPath)
	return nil
}

func (s *Store) getAdmin(where string, arg interface{}) (*AdminUser, string, error) {
	a := &AdminUser{}
	var hash string
	err := s.DB.QueryRow(
		"SELECT id,username,email,role,must_change,status,password_hash FROM admin_user WHERE "+where+" LIMIT 1", arg).
		Scan(&a.ID, &a.Username, &a.Email, &a.Role, &a.MustChange, &a.Status, &hash)
	return a, hash, err
}

func (s *Store) GetAdminForLogin(username string) (*AdminUser, string, error) {
	return s.getAdmin("username=?", username)
}

func (s *Store) GetAdminByID(id int64) (*AdminUser, error) {
	a, _, err := s.getAdmin("id=?", id)
	return a, err
}

func (s *Store) TouchAdminLogin(id int64) {
	if _, err := s.DB.Exec("UPDATE admin_user SET last_login_at=datetime('now') WHERE id=?", id); err != nil {
		// 登录已经成功，时间戳更新失败不应把用户踢出，但必须留痕便于发现数据库异常。
		slog.Error("touch admin login failed", "admin_id", id, "err", err)
	}
}

func (s *Store) UpdateAdminCredentials(id int64, username, hash string, email *string) error {
	// 改凭据即令旧 token 全部失效（token_ver+1）：否则改完密码，
	// 泄露的旧 token 仍能用满整个有效期。
	res, err := s.DB.Exec(
		"UPDATE admin_user SET username=?, password_hash=?, email=?, must_change=0, token_ver=token_ver+1 WHERE id=?",
		username, hash, email, id)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return sql.ErrNoRows
	}
	return nil
}

// ---------- 管理员 token 撤销 ----------

// AdminTokenVer 读当前 token_ver，签发时写进 claims、鉴权时实时比对。
func (s *Store) AdminTokenVer(id int64) (int64, error) {
	var v int64
	err := s.DB.QueryRow("SELECT token_ver FROM admin_user WHERE id=?", id).Scan(&v)
	return v, err
}

// BumpAdminTokenVer 令该管理员所有已签发 token 立即失效
// （重置密码、禁用、改角色、删除时调用）。
func (s *Store) BumpAdminTokenVer(id int64) error {
	res, err := s.DB.Exec("UPDATE admin_user SET token_ver=token_ver+1 WHERE id=?", id)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return sql.ErrNoRows
	}
	return nil
}

// ---------- 管理员管理（角色/状态/密码/删除） ----------

func (s *Store) UpdateAdminRole(id int64, role string) error {
	// 角色变更必须撤销旧 token，否则降级后的管理员仍持有 super 权限的 token。
	res, err := s.DB.Exec(
		`UPDATE admin_user
		 SET role=?, token_ver=token_ver+1
		 WHERE id=?
		   AND (role<>'super' OR status<>1 OR ?='super' OR
		        (SELECT COUNT(*) FROM admin_user other
		          WHERE other.role='super' AND other.status=1 AND other.id<>?)>0)`,
		role, id, role, id)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return errLastSuperAdmin
	}
	return nil
}

func (s *Store) UpdateAdminStatus(id int64, status int) error {
	res, err := s.DB.Exec(
		`UPDATE admin_user
		 SET status=?, token_ver=token_ver+1
		 WHERE id=?
		   AND (role<>'super' OR status<>1 OR ?<>2 OR
		        (SELECT COUNT(*) FROM admin_user other
		          WHERE other.role='super' AND other.status=1 AND other.id<>?)>0)`,
		status, id, status, id)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return errLastSuperAdmin
	}
	return nil
}

func (s *Store) ResetAdminPassword(id int64, hash string) error {
	// 重置密码后旧 token 必须失效，否则「重置密码」形同虚设。
	res, err := s.DB.Exec(
		"UPDATE admin_user SET password_hash=?, token_ver=token_ver+1 WHERE id=?", hash, id)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return sql.ErrNoRows
	}
	return nil
}

func (s *Store) DeleteAdmin(id int64) error {
	res, err := s.DB.Exec(
		`DELETE FROM admin_user
		 WHERE id=?
		   AND (role<>'super' OR status<>1 OR
		        (SELECT COUNT(*) FROM admin_user other
		          WHERE other.role='super' AND other.status=1 AND other.id<>?)>0)`,
		id, id)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return errLastSuperAdmin
	}
	return nil
}

// CountActiveSuperAdmins 统计仍启用的超管数量，用于阻止把最后一个超管删掉/降级/禁用
// （否则系统再也没有人能管理系统设置与管理员）。
func (s *Store) CountActiveSuperAdmins(excludeID int64) (int, error) {
	var n int
	err := s.DB.QueryRow(
		"SELECT COUNT(*) FROM admin_user WHERE role='super' AND status=1 AND id<>?",
		excludeID).Scan(&n)
	return n, err
}

// ListAdminsPaged 真分页版管理员列表（旧 ListAdmins 返回裸数组且不分页）。
func (s *Store) ListAdminsPaged(keyword string, limit, offset int) ([]AdminUser, int, error) {
	where := ""
	args := []interface{}{}
	if keyword != "" {
		where = "WHERE username LIKE ? OR IFNULL(email,'') LIKE ?"
		like := "%" + keyword + "%"
		args = append(args, like, like)
	}
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM admin_user "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		"SELECT id,username,email,role,must_change,status FROM admin_user "+where+
			" ORDER BY id ASC LIMIT ? OFFSET ?",
		append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []AdminUser{}
	for rows.Next() {
		var a AdminUser
		if err := rows.Scan(&a.ID, &a.Username, &a.Email, &a.Role, &a.MustChange, &a.Status); err != nil {
			slog.Error("scan admin_user row failed", "err", err)
			continue
		}
		out = append(out, a)
	}
	return out, total, rows.Err()
}

func (s *Store) AddAudit(adminID int64, name, action, detail, ip string) {
	if _, err := s.DB.Exec(
		"INSERT INTO admin_audit_log(admin_id,admin_name,action,detail,ip) VALUES(?,?,?,?,?)",
		adminID, name, action, detail, ip); err != nil {
		slog.Error("write admin audit failed", "admin_id", adminID, "action", action, "err", err)
	}
}

func validateAdminUsername(raw string) (string, error) {
	username := strings.TrimSpace(raw)
	if len([]byte(username)) < 3 || len([]byte(username)) > 64 {
		return "", errors.New("用户名长度 3-64")
	}
	return username, nil
}

func normalizeAdminEmail(raw string) (*string, error) {
	email := strings.ToLower(strings.TrimSpace(raw))
	if email == "" {
		return nil, nil
	}
	if len([]byte(email)) > maxEmailBytes || !reEmail.MatchString(email) {
		return nil, errors.New("邮箱格式不正确")
	}
	return &email, nil
}

// adminSettingValue 把「尚未配置」视为空值，但不吞掉真正的数据库故障。
func adminSettingValue(key string) (string, error) {
	v, err := st.GetSetting(key)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return v, err
}

// APPEND-ADMIN-2

// ---------- 登录 / 当前管理员 / 首登改凭据 ----------

func handleAdminLogin(c *gin.Context) {
	var req struct {
		Username string `json:"username" binding:"required"`
		Password string `json:"password" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	// 登录失败限流。窗口与次数读后台配置——此前这两个数字写死为 5 / 10 分钟，
	// 而后台上明明摆着「后台登录失败上限」这一项，改了却毫无作用。
	uname := strings.TrimSpace(req.Username)
	set := settingsNow()
	failKey := "adminlogin:fail:" + strings.ToLower(uname)
	failWindow := time.Duration(set.LoginRateWindowMin) * time.Minute
	if st.mem.count(failKey) >= int64(set.AdminLoginMaxFails) {
		afail(c, 429, 429, fmt.Sprintf("登录尝试过于频繁，请 %d 分钟后再试", set.LoginRateWindowMin))
		return
	}
	if len([]byte(uname)) > 64 || len([]byte(req.Password)) > maxPasswordBytes {
		st.mem.incr(failKey, failWindow)
		afail(c, 400, 400, "账号或密码错误")
		return
	}
	a, hash, err := st.GetAdminForLogin(uname)
	// 封禁校验必须与密码校验合并成同一个分支。
	//
	// 原先是"先验密码，验过了再看 status"，于是两种失败的响应不同（400 vs 403）：
	// 攻击者拿一个已被禁用的账号爆破，403 就等于"这个口令是对的"。
	// 账号被禁用往往正是因为它已经不可信（离职/疑似泄露），
	// 而这里恰好把它的口令校验结果免费告诉了外面。
	if err != nil || !checkPassword(hash, req.Password) || a.Status != 1 {
		st.mem.incr(failKey, failWindow)
		afail(c, 400, 400, "账号或密码错误")
		return
	}
	st.mem.del(failKey)
	st.TouchAdminLogin(a.ID)
	st.AddAudit(a.ID, a.Username, "login", "", c.ClientIP())
	token, err := signAdminTokenFor(a)
	if err != nil {
		afail(c, 500, 500, "签发登录凭据失败")
		return
	}
	aok(c, gin.H{"token": token, "refreshToken": token, "must_change": a.MustChange})
}

func handleAdminInfo(c *gin.Context) {
	a, err := st.GetAdminByID(c.GetInt64("aid"))
	if err != nil {
		afail(c, 404, 404, "管理员不存在")
		return
	}
	aok(c, gin.H{
		"userId": a.ID, "userName": a.Username, "roles": []string{a.Role},
		"buttons": []string{}, "avatar": "", "email": a.Email, "must_change": a.MustChange,
	})
}

func handleAdminChangeCredentials(c *gin.Context) {
	aid := c.GetInt64("aid")
	var req struct {
		OldPassword string `json:"old_password" binding:"required"`
		Username    string `json:"username"`
		Password    string `json:"password"`
		Email       string `json:"email"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	a, hash, err := st.getAdmin("id=?", aid)
	if err != nil {
		afail(c, 404, 404, "管理员不存在")
		return
	}
	if !checkPassword(hash, req.OldPassword) {
		afail(c, 400, 400, "原密码错误")
		return
	}
	username := a.Username
	if u := strings.TrimSpace(req.Username); u != "" {
		var err error
		if username, err = validateAdminUsername(u); err != nil {
			afail(c, 400, 400, err.Error())
			return
		}
	}
	newHash := hash
	// 首登强制改密不可绕过：此前 Password 允许为空，攻击者只要带着 old_password
	// 调一次本接口，must_change 就被无条件清零，而初始随机口令继续有效。
	if a.MustChange && strings.TrimSpace(req.Password) == "" {
		afail(c, 400, 400, "首次登录必须设置新密码")
		return
	}
	if req.Password != "" {
		if err := validateStrongPassword(req.Password); err != nil {
			afail(c, 400, 400, err.Error())
			return
		}
		// 新密码不得与旧密码相同，否则「强制改密」等于没改。
		if checkPassword(hash, req.Password) {
			afail(c, 400, 400, "新密码不能与原密码相同")
			return
		}
		newHash = hashPassword(req.Password)
	}
	email := a.Email
	if req.Email != "" {
		var err error
		if email, err = normalizeAdminEmail(req.Email); err != nil {
			afail(c, 400, 400, err.Error())
			return
		}
	} else {
		email = a.Email
	}
	if err := st.UpdateAdminCredentials(aid, username, newHash, email); err != nil {
		afail(c, 400, 400, "更新失败，用户名可能已被占用")
		return
	}
	st.AddAudit(aid, username, "change_credentials", "", c.ClientIP())
	// 首登改凭据后 must_change 已清零，且 UpdateAdminCredentials 已把 token_ver+1
	// （旧 token 全部失效）→ 必须重新读 token_ver 再签发，否则新 token 也会立刻被判失效。
	tv, err := st.AdminTokenVer(aid)
	if err != nil {
		afail(c, 500, 500, "签发登录凭据失败")
		return
	}
	newToken, err := signAdminToken(aid, a.Role, false, tv)
	if err != nil {
		afail(c, 500, 500, "签发登录凭据失败")
		return
	}
	aok(c, gin.H{"ok": true, "token": newToken, "refreshToken": newToken})
}

// APPEND-ADMIN-3

func pageParams(c *gin.Context) (limit, offset, current, size int) {
	current, _ = strconv.Atoi(c.DefaultQuery("current", "1"))
	size, _ = strconv.Atoi(c.DefaultQuery("size", "10"))
	if current < 1 {
		current = 1
	}
	if size < 1 || size > 200 {
		size = 10
	}
	return size, (current - 1) * size, current, size
}

func pageResp(c *gin.Context, records interface{}, total, current, size int) {
	aok(c, gin.H{"records": records, "total": total, "current": current, "size": size})
}

// ---------- 数据看板 ----------

func (s *Store) DashboardStats() gin.H {
	// COUNT 出错时返回 0 是可接受的降级（看板少一个数字，不影响功能），
	// 但必须留痕 —— 否则「看板显示 0 而库里明明有数据」这种问题无从排查。
	q := func(query string) int {
		var n int
		if err := s.DB.QueryRow(query).Scan(&n); err != nil {
			slog.Error("dashboard count failed", "query", query, "err", err)
			return 0
		}
		return n
	}
	// 近 7 天每日新增
	daily := []gin.H{}
	rows, err := s.DB.Query(
		`SELECT DATE(created_at) d, COUNT(*) c FROM ` + "`user`" +
			` WHERE created_at>=date('now','-6 days') GROUP BY d ORDER BY d`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var d string
			var c int
			if err := rows.Scan(&d, &c); err != nil {
				// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
				slog.Error("scan dashboard_daily failed", "err", err)
				continue
			}
			daily = append(daily, gin.H{"date": d, "count": c})
		}
		if err := rows.Err(); err != nil {
			// 仪表盘折线图少一天不影响可用性，但要留痕：
			// 否则"某天新增为 0"分不清是真没人注册还是遍历断了。
			slog.Error("iterate dashboard_daily failed", "err", err)
		}
	}
	return gin.H{
		"users":        q("SELECT COUNT(*) FROM `user`"),
		"pairs":        q("SELECT COUNT(*) FROM pair WHERE status=1 AND user_a_id>0 AND user_b_id>0"),
		"todos":        q("SELECT COUNT(*) FROM todo WHERE status<2"),
		"new_users_7d": q("SELECT COUNT(*) FROM `user` WHERE created_at>=datetime('now','-7 days')"),
		"daily_new":    daily,
	}
}

func handleAdminStats(c *gin.Context) { aok(c, st.DashboardStats()) }

// ---------- 用户管理 ----------

type AdminUserRow struct {
	ID          int64     `json:"id"`
	Username    *string   `json:"username"`
	Email       *string   `json:"email"`
	Nickname    string    `json:"nickname"`
	AvatarURL   *string   `json:"avatar_url"`
	Gender      int       `json:"gender"`
	Signature   *string   `json:"signature"`
	Birthday    *string   `json:"birthday"`
	Anniversary *string   `json:"anniversary"`
	Status      int       `json:"status"`
	CreatedAt   time.Time `json:"created_at"`
}

func (s *Store) ListUsers(keyword string, status, limit, offset int) ([]AdminUserRow, int, error) {
	where := ""
	args := []interface{}{}
	if status == 1 || status == 2 {
		where = " WHERE u.status=?"
		args = append(args, status)
	}
	if keyword != "" {
		if where == "" {
			where = " WHERE"
		} else {
			where += " AND"
		}
		where += " (u.username LIKE ? OR u.email LIKE ? OR u.nickname LIKE ?)"
		kw := "%" + keyword + "%"
		args = append(args, kw, kw, kw)
	}
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM `user` u"+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		"SELECT u.id,u.username,u.email,u.nickname,u.avatar_url,u.gender,u.signature,u.birthday,u.status,u.created_at,"+
			"(SELECT p.anniversary_date FROM pair p WHERE p.status=1 AND (p.user_a_id=u.id OR p.user_b_id=u.id) LIMIT 1) "+
			"FROM `user` u"+where+
			" ORDER BY u.id DESC LIMIT ? OFFSET ?", append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []AdminUserRow{}
	for rows.Next() {
		var u AdminUserRow
		var birthday, anniversary sql.NullTime
		if err := rows.Scan(&u.ID, &u.Username, &u.Email, &u.Nickname, &u.AvatarURL, &u.Gender, &u.Signature, &birthday, &u.Status, &u.CreatedAt, &anniversary); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan admin_user_row failed", "err", err)
			continue
		}
		if birthday.Valid {
			b := birthday.Time.Format("2006-01-02")
			u.Birthday = &b
		}
		if anniversary.Valid {
			a := anniversary.Time.Format("2006-01-02")
			u.Anniversary = &a
		}
		out = append(out, u)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

func (s *Store) SetUserStatus(id int64, status int) error {
	if status != 1 && status != 2 {
		return fmt.Errorf("invalid user status %d", status)
	}
	res, err := s.DB.Exec(
		"UPDATE `user` SET status=?, token_ver=token_ver+1 WHERE id=?",
		status, id)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func handleAdminListUsers(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	status := 0
	if raw := strings.TrimSpace(c.Query("status")); raw != "" && raw != "0" {
		status, _ = strconv.Atoi(raw)
		if status != 1 && status != 2 {
			afail(c, 400, 400, "status 只能是 1(启用) 或 2(禁用)")
			return
		}
	}
	list, total, err := st.ListUsers(strings.TrimSpace(c.Query("keyword")), status, limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

// handleAdminUpdateUser 修改后台可编辑的用户资料，不触碰用户名、头像与登录状态。
func handleAdminUpdateUser(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "用户 ID 非法")
		return
	}
	var req struct {
		Email     *string `json:"email"`
		Nickname  string  `json:"nickname"`
		Gender    int     `json:"gender"`
		Signature *string `json:"signature"`
		Birthday  *string `json:"birthday"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	nickname, err := normalizeNickname(req.Nickname)
	if err != nil {
		afail(c, 400, 400, "昵称长度 2-32")
		return
	}
	if req.Gender < 0 || req.Gender > 2 {
		afail(c, 400, 400, "性别只能是 0(保密)、1(男) 或 2(女)")
		return
	}
	var signature *string
	if req.Signature != nil {
		s := strings.TrimSpace(*req.Signature)
		if utf8.RuneCountInString(s) > 200 {
			afail(c, 400, 400, "简介不能超过 200 字")
			return
		}
		if s != "" {
			signature = &s
		}
	}
	var birthday *string
	if req.Birthday != nil && strings.TrimSpace(*req.Birthday) != "" {
		b := strings.TrimSpace(*req.Birthday)
		if _, err := parseAnniversary(b, time.Now()); err != nil {
			afail(c, 400, 400, "生日日期无效")
			return
		}
		birthday = &b
	}
	rawEmail := ""
	if req.Email != nil {
		rawEmail = *req.Email
	}
	email, err := normalizeAdminEmail(rawEmail)
	if err != nil {
		afail(c, 400, 400, err.Error())
		return
	}
	if err := ensureAdminUserExists(id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			afail(c, 404, 404, "用户不存在")
		} else {
			afail(c, 500, 500, "查询失败")
		}
		return
	}
	if conflict, err := adminUserFieldConflict("nickname", nickname, id); err != nil {
		afail(c, 500, 500, "查询失败")
		return
	} else if conflict {
		afail(c, 400, 400, "昵称已被占用")
		return
	}
	if email != nil {
		if conflict, err := adminUserFieldConflict("email", *email, id); err != nil {
			afail(c, 500, 500, "查询失败")
			return
		} else if conflict {
			afail(c, 400, 400, "邮箱已被占用")
			return
		}
	}
	if err := st.UpdateAdminUserProfile(id, email, nickname, req.Gender, signature, birthday); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			afail(c, 404, 404, "用户不存在")
			return
		}
		afail(c, 500, 500, "更新失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "update_user_profile",
		"user="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func ensureAdminUserExists(id int64) error {
	var exists int
	return st.DB.QueryRow("SELECT 1 FROM `user` WHERE id=?", id).Scan(&exists)
}

// adminUserFieldConflict 使用固定字段名调用，避免把请求内容拼进 SQL。
func adminUserFieldConflict(field, value string, id int64) (bool, error) {
	if field != "nickname" && field != "email" {
		return false, errors.New("invalid user field")
	}
	var otherID int64
	err := st.DB.QueryRow("SELECT id FROM `user` WHERE "+field+"=? AND id<>? LIMIT 1", value, id).Scan(&otherID)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

func handleAdminSetUserStatus(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "用户 ID 非法")
		return
	}
	var req struct {
		Status int `json:"status"`
	}
	// **必须检查 err 且限定取值**（与 handleAdminSetAdminStatus 保持一致）。
	// 原先是裸调 ShouldBindJSON 并把任意整数直落库，有两个真实后果：
	//   ① body 解析失败时 req.Status 是零值 0，而 authUserByToken 认为
	//      `status != 1` 即为禁用 → **一次格式错误的请求就把用户静默封禁**；
	//   ② 传 status:7 也能写进去，而后台前端只提供 1/2 两个选项，
	//      写进去之后没有任何界面能把他改回来。
	if err := c.ShouldBindJSON(&req); err != nil || (req.Status != 1 && req.Status != 2) {
		afail(c, 400, 400, "status 只能是 1(启用) 或 2(禁用)")
		return
	}
	if err := st.SetUserStatus(id, req.Status); err != nil {
		afail(c, 500, 500, "操作失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), "", "set_user_status", "user="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// APPEND-ADMIN-4

// ---------- 绑定关系管理 ----------

func (s *Store) ListPairs(keyword string, limit, offset int) ([]gin.H, int, error) {
	keyword = strings.TrimSpace(keyword)
	where := ""
	var filterArgs []any
	if keyword != "" {
		like := "%" + keyword + "%"
		where = " WHERE CAST(p.id AS TEXT) LIKE ? OR CAST(p.user_a_id AS TEXT) LIKE ? OR CAST(p.user_b_id AS TEXT) LIKE ?" +
			" OR COALESCE(ua.nickname,'') LIKE ? OR COALESCE(ub.nickname,'') LIKE ?" +
			" OR COALESCE(ua.username,'') LIKE ? OR COALESCE(ub.username,'') LIKE ?"
		filterArgs = []any{like, like, like, like, like, like, like}
	}
	var total int
	if err := s.DB.QueryRow(
		"SELECT COUNT(*) FROM pair p LEFT JOIN `user` ua ON ua.id=p.user_a_id LEFT JOIN `user` ub ON ub.id=p.user_b_id"+where,
		filterArgs...,
	).Scan(&total); err != nil {
		return nil, 0, err
	}
	// ★★ 绝对不要把 invite_code 下发给后台 ★★
	//
	// 挂起的邀请码就是"成为某个用户的伴侣"的凭据本身。它下发之后，
	// 任何一个普通 admin（这张列表对普通 admin 开放）都能：
	// 挑一条 user_b_id=0 且 status=1 的挂起邀请 → 抄走码 → 在 App 注册个账号 →
	// 调 /pair/bind 填上去。BindPair 只拦"两个槽都满"，空位会被顺利填上。
	// 绑定完成即成为对方的合法伴侣，从此相册、/media/<id>、待办、
	// 状态历史（含 WiFi SSID 与前台应用）全部合法可读——
	// 而相册与日记导出这些接口早就特意收敛到超管了，这条口子等于把那些收敛全部绕过。
	//
	// 后台真正需要的只是「这条邀请还挂着没人用」，那是一个布尔值，不是那串码。
	// 也不能下发前几位之类的"部分脱敏"：邀请码只有 8 位，泄露任何一段都在
	// 成倍缩小爆破空间（invite.go 把它从 6 位数字加长到 8 位混合字符正是为了这个）。
	listArgs := append([]any(nil), filterArgs...)
	listArgs = append(listArgs, limit, offset)
	rows, err := s.DB.Query(
		"SELECT p.id,p.user_a_id,p.user_b_id,COALESCE(ua.nickname,''),COALESCE(ub.nickname,''),p.status,"+
			"CASE WHEN p.status=1 AND p.user_a_id>0 AND p.user_b_id=0 AND p.invite_code IS NOT NULL AND p.invite_code<>'' THEN 1 ELSE 0 END,p.created_at,p.anniversary_date "+
			"FROM pair p LEFT JOIN `user` ua ON ua.id=p.user_a_id LEFT JOIN `user` ub ON ub.id=p.user_b_id"+where+
			" ORDER BY p.id DESC LIMIT ? OFFSET ?", listArgs...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id, ua, ub int64
		var na, nb string
		var status, hasCode int
		var created time.Time
		var anniversary sql.NullTime
		if err := rows.Scan(&id, &ua, &ub, &na, &nb, &status, &hasCode, &created, &anniversary); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan pair_row failed", "err", err)
			continue
		}
		var anniversaryValue interface{}
		if anniversary.Valid {
			anniversaryValue = anniversary.Time.Format("2006-01-02")
		}
		out = append(out, gin.H{"id": id, "user_a_id": ua, "user_b_id": ub, "name_a": na, "name_b": nb,
			"status": status, "has_invite": hasCode == 1, "created_at": created,
			"anniversary": anniversaryValue})
	}
	// 遍历中途出错时 rows.Next() 会返回 false，与"正常读完"无法区分。
	// 不检查就等于把"少了几行的结果"当成功返回，而分页页面上看不出任何异常。
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

func (s *Store) UnbindPair(id int64) error {
	_, err := s.DB.Exec("UPDATE pair SET status=0, unbind_time=datetime('now') WHERE id=?", id)
	return err
}

func handleAdminListPairs(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	list, total, err := st.ListPairs(c.Query("keyword"), limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

// handleAdminUpdatePair 修改关系级的纪念日；邀请码与成员关系不可通过后台编辑。
func handleAdminUpdatePair(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "关系 ID 非法")
		return
	}
	var req struct {
		AnniversaryDate *string `json:"anniversary_date"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	var anniversary *time.Time
	if req.AnniversaryDate != nil && strings.TrimSpace(*req.AnniversaryDate) != "" {
		value := strings.TrimSpace(*req.AnniversaryDate)
		parsed, err := parseAnniversary(value, time.Now())
		if err != nil {
			afail(c, 400, 400, "纪念日无效")
			return
		}
		anniversary = &parsed
	}
	if err := st.UpdateAdminPairAnniversary(id, anniversary); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			afail(c, 404, 404, "已绑定关系不存在")
			return
		}
		afail(c, 500, 500, "更新失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "update_pair_anniversary",
		"pair="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func handleAdminUnbindPair(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "参数错误")
		return
	}
	if err := st.UnbindPair(id); err != nil {
		afail(c, 500, 500, "操作失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), "", "unbind_pair", "pair="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// CancelPendingInviteAdmin 作废后台发现的挂起邀请码，但不把凭据返回给后台。
// 作废时写入墓碑值，避免旧邀请码在任何遗漏的查询路径中再次可用。
func (s *Store) CancelPendingInviteAdmin(id int64) error {
	res, err := s.DB.Exec(
		`UPDATE pair SET status=0, invite_code=('revoked:' || id)
		 WHERE id=? AND status=1 AND user_a_id>0 AND user_b_id=0`, id,
	)
	if err != nil {
		return err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func handleAdminCancelPendingInvite(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "关系 ID 非法")
		return
	}
	if err := st.CancelPendingInviteAdmin(id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			afail(c, 404, 404, "挂起邀请不存在或已经失效")
			return
		}
		afail(c, 500, 500, "取消邀请失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "cancel_pair_invite",
		"pair="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// APPEND-ADMIN-5

// ---------- 内容审核（待办 / 日记） ----------

// ListTodosAll 后台待办列表（分页 + keyword，creator/assignee 名字从 user 解析）。
// 契约字段：id,title,note,creator_id,creator_name,assignee_id,assignee_name,remind_enabled,remind_type,repeat_type,weekdays,remind_at,status,pair_id,created_at
func (s *Store) ListTodosAll(keyword string, limit, offset int) ([]gin.H, int, error) {
	base := "FROM todo t " +
		"LEFT JOIN `user` uc ON uc.id=t.creator_id " +
		"LEFT JOIN `user` ua ON ua.id=t.assignee_id"
	args := []interface{}{}
	if keyword != "" {
		base += " WHERE t.title LIKE ? OR t.note LIKE ? OR uc.nickname LIKE ? OR ua.nickname LIKE ?"
		kw := "%" + keyword + "%"
		args = append(args, kw, kw, kw, kw)
	}
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) "+base, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		"SELECT t.id,t.pair_id,t.creator_id,COALESCE(uc.nickname,''),t.assignee_id,COALESCE(ua.nickname,''),"+
			"t.title,COALESCE(t.note,''),t.remind_at,t.remind_type,t.repeat_type,t.weekdays,t.remind_enabled,t.status,t.created_at "+
			base+" ORDER BY t.id DESC LIMIT ? OFFSET ?", append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id, pid, cid, aid int64
		var creatorName, assigneeName, title, note string
		var remindAt sql.NullTime
		var remindType, repeatType, weekdays, status int
		var remindEnabled bool
		var created time.Time
		if err := rows.Scan(&id, &pid, &cid, &creatorName, &aid, &assigneeName,
			&title, &note, &remindAt, &remindType, &repeatType, &weekdays, &remindEnabled, &status, &created); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan admin_todo_row failed", "err", err)
			continue
		}
		var ra interface{}
		if remindAt.Valid {
			ra = remindAt.Time
		}
		out = append(out, gin.H{
			"id": id, "title": title, "note": note,
			"creator_id": cid, "creator_name": creatorName,
			"assignee_id": aid, "assignee_name": assigneeName,
			"remind_enabled": remindEnabled, "remind_type": remindType, "weekdays": weekdays,
			"repeat_type": repeatType, "remind_at": ra,
			"status": status, "pair_id": pid, "created_at": created,
		})
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

func handleAdminListTodos(c *gin.Context) {
	limit, offset, _, _ := pageParams(c)
	list, total, err := st.ListTodosAll(strings.TrimSpace(c.Query("keyword")), limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	// 契约：data:{list,total}
	aok(c, gin.H{"list": list, "total": total})
}

// handleAdminUpdateTodo 编辑待办内容、提醒规则与被提醒者，不允许把已删除记录复活。
func handleAdminUpdateTodo(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "待办 ID 非法")
		return
	}
	todo, err := st.GetTodo(id)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			afail(c, 404, 404, "待办不存在")
		} else {
			afail(c, 500, 500, "查询失败")
		}
		return
	}
	if todo.Status == 2 {
		afail(c, 409, 409, "已删除的待办不能编辑")
		return
	}
	var req struct {
		AssigneeID    int64   `json:"assignee_id"`
		Title         string  `json:"title"`
		Note          string  `json:"note"`
		RemindAt      *string `json:"remind_at"`
		RemindType    int     `json:"remind_type"`
		RepeatType    int     `json:"repeat_type"`
		Weekdays      int     `json:"weekdays"`
		RemindEnabled bool    `json:"remind_enabled"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	title := strings.TrimSpace(req.Title)
	if title == "" || utf8.RuneCountInString(title) > 200 {
		afail(c, 400, 400, "待办标题长度 1-200 字")
		return
	}
	note := strings.TrimSpace(req.Note)
	if utf8.RuneCountInString(note) > 5000 {
		afail(c, 400, 400, "待办详情不能超过 5000 字")
		return
	}
	if req.AssigneeID <= 0 {
		afail(c, 400, 400, "被提醒者非法")
		return
	}
	if req.RemindType != 0 && req.RemindType != 1 {
		afail(c, 400, 400, "提醒类型非法")
		return
	}
	if req.RepeatType < 0 || req.RepeatType > 2 || req.Weekdays < 0 || req.Weekdays > allWeekdaysMask {
		afail(c, 400, 400, "重复规则非法")
		return
	}
	var userA, userB int64
	if err := st.DB.QueryRow("SELECT user_a_id,user_b_id FROM pair WHERE id=?", todo.PairID).Scan(&userA, &userB); err != nil {
		afail(c, 400, 400, "所属关系不存在")
		return
	}
	if req.AssigneeID != userA && req.AssigneeID != userB {
		afail(c, 400, 400, "被提醒者不属于该关系")
		return
	}
	var remindAt *time.Time
	if req.RemindAt != nil && strings.TrimSpace(*req.RemindAt) != "" {
		parsed, ok := parseSQLiteLocalTime(strings.TrimSpace(*req.RemindAt))
		if !ok {
			afail(c, 400, 400, "提醒时间格式无效")
			return
		}
		remindAt = &parsed
	}
	repeatType, weekdays := normalizeRepeat(req.RepeatType, req.Weekdays)
	if err := st.UpdateAdminTodo(id, req.AssigneeID, title, note, remindAt,
		req.RemindType, repeatType, weekdays, req.RemindEnabled); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			afail(c, 409, 409, "待办已不存在或已删除")
			return
		}
		afail(c, 500, 500, "更新失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "update_todo",
		"todo="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func handleAdminDeleteTodo(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "参数错误")
		return
	}
	if err := st.DeleteTodo(id); err != nil {
		afail(c, 500, 500, "删除失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), "", "delete_todo", "todo="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// ---------- 内容审核（相册照片） ----------

// handleAdminListPhotos 照片列表：分页 + keyword 搜 caption + 按 pair 筛选。
// 只回元数据，不回图片 URL——见 Store.ListPhotosAll 的说明。
func handleAdminListPhotos(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	pairID := int64(0)
	if raw := strings.TrimSpace(c.DefaultQuery("pair_id", "0")); raw != "" && raw != "0" {
		var err error
		pairID, err = strconv.ParseInt(raw, 10, 64)
		if err != nil || pairID <= 0 {
			afail(c, 400, 400, "pair_id 参数错误")
			return
		}
	}
	list, total, err := st.ListPhotosAll(strings.TrimSpace(c.Query("keyword")), pairID, limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

// handleAdminDeletePhoto 软删（进用户回收站，可由用户自行恢复），不删盘上文件。
func handleAdminDeletePhoto(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "参数错误")
		return
	}
	if err := st.AdminDeletePhoto(id); err != nil {
		afail(c, 500, 500, "删除失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "delete_photo",
		"photo="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// handleAdminUpdatePhoto 修改照片描述，不回传照片地址，也不允许编辑回收站中的照片。
func handleAdminUpdatePhoto(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "参数错误")
		return
	}
	var req struct {
		Caption string `json:"caption"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	caption := strings.TrimSpace(req.Caption)
	if utf8.RuneCountInString(caption) > maxCaptionLen {
		afail(c, 400, 400, "照片描述不能超过 500 字")
		return
	}
	photo, err := st.GetPhoto(id)
	if err != nil || photo == nil {
		afail(c, 404, 404, "照片不存在")
		return
	}
	if photo.Status != 1 {
		afail(c, 409, 409, "回收站中的照片不能编辑")
		return
	}
	if err := st.UpdateAdminPhotoCaption(id, caption); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			afail(c, 409, 409, "照片已不存在或已移入回收站")
			return
		}
		afail(c, 500, 500, "更新失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "update_photo_caption",
		"photo="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// APPEND-ADMIN-6

// ---------- 系统日志 / 审计 ----------

func (s *Store) ListAudit(limit, offset int) ([]gin.H, int, error) {
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM admin_audit_log").Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		"SELECT id,admin_id,COALESCE(admin_name,''),action,COALESCE(detail,''),COALESCE(ip,''),created_at "+
			"FROM admin_audit_log ORDER BY id DESC LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id, aid int64
		var name, action, detail, ip string
		var created time.Time
		if err := rows.Scan(&id, &aid, &name, &action, &detail, &ip, &created); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan audit_row failed", "err", err)
			continue
		}
		out = append(out, gin.H{"id": id, "admin_id": aid, "admin_name": name, "action": action,
			"detail": detail, "ip": ip, "created_at": created})
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

func handleAdminListAudit(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	list, total, err := st.ListAudit(limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

// ---------- 管理员与角色 ----------

func (s *Store) ListAdmins() ([]AdminUser, error) {
	rows, err := s.DB.Query("SELECT id,username,email,role,must_change,status FROM admin_user ORDER BY id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []AdminUser{}
	for rows.Next() {
		var a AdminUser
		if err := rows.Scan(&a.ID, &a.Username, &a.Email, &a.Role, &a.MustChange, &a.Status); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan admin_account_row failed", "err", err)
			continue
		}
		out = append(out, a)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func (s *Store) CreateAdmin(username, hash, role string, email *string) (int64, error) {
	res, err := s.DB.Exec("INSERT INTO admin_user(username,password_hash,role,email) VALUES(?,?,?,?)",
		username, hash, role, email)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func handleAdminListAdmins(c *gin.Context) {
	// 真分页：此前返回裸数组不分页，管理员一多就整页返回。
	limit, offset, current, size := pageParams(c)
	keyword := strings.TrimSpace(c.Query("keyword"))
	list, total, err := st.ListAdminsPaged(keyword, limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

func handleAdminCreateAdmin(c *gin.Context) {
	var req struct {
		Username string `json:"username" binding:"required"`
		Password string `json:"password" binding:"required"`
		Role     string `json:"role"`
		Email    string `json:"email"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	// 密码强度：此前只要 6 位，`123456` 就能过——后台账号是全站数据的钥匙。
	if err := validateStrongPassword(req.Password); err != nil {
		afail(c, 400, 400, err.Error())
		return
	}
	role := req.Role
	if role == "" {
		role = "admin"
	}
	if !isValidAdminRole(role) {
		afail(c, 400, 400, "角色只能是 admin 或 super")
		return
	}
	username, err := validateAdminUsername(req.Username)
	if err != nil {
		afail(c, 400, 400, err.Error())
		return
	}
	email, err := normalizeAdminEmail(req.Email)
	if err != nil {
		afail(c, 400, 400, err.Error())
		return
	}
	id, err := st.CreateAdmin(username, hashPassword(req.Password), role, email)
	if err != nil {
		afail(c, 400, 400, "用户名已被占用")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "create_admin",
		fmt.Sprintf("username=%s role=%s", req.Username, role), c.ClientIP())
	aok(c, gin.H{"id": id})
}

// ---------- 系统设置（站点/存储/推送/SMTP） ----------

// settingKeys 敏感/展示类设置（含密钥），一律限超管读写。
//
// 已删掉 6 个废弃键（Q43=A）：storage.local_dir 与 5 个 OSS 键。
// 0813 就定了「只留 local 存储、隐藏 OSS/COS/Kodo」，前端早已不显示，
// 后端却还留着——留一个"配了没用"的键比没有这个键更糟：
// 它会让人（包括未来的我）以为改它有效，然后浪费时间排查。
// push.provider 同理：推送网关目前是日志占位实现，配它不产生任何行为。
//
// 运行参数（相册配额/保留期/限流/互动冷却）不在这里，见 settings.go 的 runtimeSettingSpecs，
// 那些键不含密钥，故对普通 admin 开放。
var settingKeys = []string{
	"site.name", "site.url", "site.logo", "site.description",
	"storage.driver",
	"smtp.host", "smtp.port", "smtp.username", "smtp.password", "smtp.from", "smtp.ssl",
}

// auditPublicSettingKeys 是敏感设置里**可以把具体值写进审计日志**的白名单。
//
// 审计日志页对普通 admin 开放，而 GET /settings 已收敛到超管；
// 若审计里明文记下 smtp.host / smtp.username，那次收敛就被绕过了
// （超管每改一次配置，普通 admin 就能从审计表里读到）。
//
// 这里只放"本来就公开可见"的站点展示项：站点名、LOGO、描述在后台顶栏
// 与登录页都直接显示，记进审计不增加任何暴露面。
// site.url 也在内——它就是用户访问的地址本身。
//
// 白名单之外的一切（含将来新增的键）默认脱敏。
var auditPublicSettingKeys = map[string]bool{
	"site.name":        true,
	"site.url":         true,
	"site.logo":        true,
	"site.description": true,
	"storage.driver":   true,
	"smtp.ssl":         true,
	"smtp.port":        true,
}

// handleAdminSiteInfo 只回站点展示信息（名称/LOGO/描述），任何已登录管理员可读。
//
// 为什么要单独开一个：站点名与 LOGO 是后台每次加载都要用的展示数据，
// 而 GET /settings 里含 SMTP 主机账号与存储密钥，已被收敛到超管
// —— 若前端继续用它取站点名，普通 admin 会吃 403、后台顶栏连站点名都显示不出来。
// 同时它也在首登强制改密的白名单里：改密页自己也要显示站点名与 LOGO。
func handleAdminSiteInfo(c *gin.Context) {
	get := func(k string) (string, bool) {
		v, err := adminSettingValue(k)
		return v, err == nil
	}
	name, okName := get("site.name")
	logo, okLogo := get("site.logo")
	description, okDescription := get("site.description")
	if !okName || !okLogo || !okDescription {
		afail(c, 500, 500, "读取站点配置失败")
		return
	}
	aok(c, gin.H{
		"site.name":        name,
		"site.logo":        logo,
		"site.description": description,
	})
}

// handleAdminGetSettings 下发全部设置。
//
// 除当前值外还回 `defaults` 与 `meta`（Q26=C）：
// 「一键恢复默认」由前端填回 defaults 再提交，这样默认值永远与代码里的常量一致，
// 不会出现"后台写死的默认值和代码不一样"这种分裂。meta 带分区/类型/取值范围，
// 前端据此分组渲染并做输入校验，新增配置项无需改前端结构。
func handleAdminGetSettings(c *gin.Context) {
	m := map[string]string{}
	for _, k := range settingKeys {
		v, err := adminSettingValue(k)
		if err != nil {
			afail(c, 500, 500, "读取配置失败")
			return
		}
		if k == "smtp.password" && v != "" {
			v = "__set__" // 不回传明文
		}
		m[k] = v
	}
	// 运行参数（相册配额/保留期/限流/互动冷却）走 settings.go 的快照，不含任何密钥。
	values, defaults, meta := runtimeSettingsPayload()
	for k, v := range values {
		m[k] = v
	}
	aok(c, gin.H{"values": m, "defaults": defaults, "meta": meta})
}

// handleAdminGetRuntimeSettings 只回运行参数（相册配额/保留期/限流/互动冷却）+ 默认值 + 元信息。
// **不含任何密钥**，故对普通 admin 开放；SMTP 与存储配置在 handleAdminGetSettings（限超管）。
func handleAdminGetRuntimeSettings(c *gin.Context) {
	values, defaults, meta := runtimeSettingsPayload()
	aok(c, gin.H{"values": values, "defaults": defaults, "meta": meta})
}

func handleAdminUpdateSettings(c *gin.Context) {
	var in map[string]string
	if err := c.ShouldBindJSON(&in); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	// 敏感键（SMTP/存储/站点）仍限超管；运行参数放开给普通 admin（Q42=C）。
	// 0820 那轮刚把敏感路由收敛到超管，就是因为之前"普通 admin 事实等于超管"。
	isSuper := c.GetString("role") == "super"
	sensitive := map[string]bool{}
	for _, k := range settingKeys {
		sensitive[k] = true
	}

	// 先收集并校验全部变更，最后一次事务提交。不能边遍历边写：map 遍历顺序不稳定，
	// 如果请求里混入了无权限键或中途数据库故障，旧实现会留下半套配置。
	type settingChange struct {
		key, value    string
		audit         string
		runtime, site bool
	}
	pending := make([]settingChange, 0, len(in))
	runtimeTouched := false
	siteTouched := false

	for k, v := range in {
		switch {
		case sensitive[k]:
			if !isSuper {
				afail(c, 403, 403, "修改「"+k+"」需要超级管理员权限")
				return
			}
			if k == "smtp.password" && v == "__set__" {
				continue // 占位符表示不修改
			}
			old, err := adminSettingValue(k)
			if err != nil {
				afail(c, 500, 500, "读取配置失败")
				return
			}
			if old == v {
				continue
			}
			// 敏感键一律只记「改过了」，不记值。
			//
			// 原先的脱敏只匹配 password/secret/access_key 三个词，于是
			// `smtp.host`、`smtp.username`、`smtp.from` 走 else 分支被**明文写进审计**。
			// 而审计日志页对普通 admin 开放，GET /settings 之所以收敛到超管，
			// 理由正是"SMTP 主机与账号本身就是攻击面"——超管每改一次 SMTP，
			// 那两个值就落进普通 admin 能翻的表里，收敛因此形同虚设。
			//
			// 判定改为白名单：只有明确可公开的 site.* 展示项才记具体值，
			// 其余敏感键全部脱敏。用白名单而不是继续往黑名单里补词，
			// 是因为将来新增的敏感键（比如某个第三方 token）默认会落进"要脱敏"那侧，
			// 而黑名单的默认行为是"明文记下来"，漏一个就泄露一个。
			audit := k + ": ***→***"
			if auditPublicSettingKeys[k] {
				audit = fmt.Sprintf("%s: %q→%q", k, old, v)
			}
			pending = append(pending, settingChange{key: k, value: v, audit: audit, site: strings.HasPrefix(k, "site.")})
		case isRuntimeSettingKey(k):
			// 运行参数整体"不含密钥所以放给普通 admin"，但其中两组不能放：
			//   - retention.*：调小即触发不可逆清理（回收站那条连磁盘文件一起真删）；
			//   - security.*：往松的方向调等于削弱爆破防护。
			// 这两组标了 Super，与 SMTP 同级限超管。
			if isSuperOnlySettingKey(k) && !isSuper {
				afail(c, 403, 403, "修改「"+k+"」需要超级管理员权限")
				return
			}
			old, err := adminSettingValue(k)
			if err != nil {
				afail(c, 500, 500, "读取配置失败")
				return
			}
			if old == v {
				continue
			}
			pending = append(pending, settingChange{key: k, value: v, audit: fmt.Sprintf("%s: %q→%q", k, old, v), runtime: true})
		default:
			// 不认识的键静默忽略（白名单语义），不因此让整个请求失败。
			continue
		}
	}
	if len(pending) > 0 {
		tx, err := st.DB.Begin()
		if err != nil {
			afail(c, 500, 500, "保存失败")
			return
		}
		defer tx.Rollback()
		for _, change := range pending {
			if _, err := tx.Exec(
				"INSERT INTO app_setting(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
				change.key, change.value); err != nil {
				_ = tx.Rollback()
				afail(c, 500, 500, "保存失败")
				return
			}
		}
		if err := tx.Commit(); err != nil {
			afail(c, 500, 500, "保存失败")
			return
		}
	}
	changes := make([]string, 0, len(pending))
	for _, change := range pending {
		changes = append(changes, change.audit)
		runtimeTouched = runtimeTouched || change.runtime
		siteTouched = siteTouched || change.site
	}

	if siteTouched {
		// site.url 被 siteBaseURL() 缓存（缓存是为了避开 MaxOpenConns(1) 下的自锁），
		// 改完必须失效，否则新配的站点地址要等进程重启才生效。
		invalidateSiteBaseCache()
	}
	if runtimeTouched {
		// 整体重建快照并 atomic 替换 → 改完立即生效，无需重启容器
		//（管理员 Q27=A 明确不想动服务器上的 compose）。
		reloadRuntimeSettings()
	}

	detail := "无改动"
	if len(changes) > 0 {
		sort.Strings(changes) // 顺序稳定，便于比对两次审计
		detail = strings.Join(changes, "; ")
		if len(detail) > 900 {
			detail = detail[:900] + fmt.Sprintf(" …(共 %d 项)", len(changes))
		}
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "update_settings",
		detail, c.ClientIP())
	aok(c, gin.H{"ok": true, "changed": len(changes)})
}

// APPEND-ADMIN-8

// ---------- 通知模板与下发记录 ----------

func (s *Store) ListNotifyTemplates() ([]gin.H, error) {
	rows, err := s.DB.Query("SELECT id,code,title,body,enabled,updated_at FROM notify_template ORDER BY id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id int64
		var code, title, body string
		var enabled int
		var updated time.Time
		if err := rows.Scan(&id, &code, &title, &body, &enabled, &updated); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan notify_template_row failed", "err", err)
			continue
		}
		out = append(out, gin.H{"id": id, "code": code, "title": title, "body": body, "enabled": enabled, "updated_at": updated})
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func (s *Store) UpsertNotifyTemplate(code, title, body string, enabled int) error {
	_, err := s.DB.Exec(
		"INSERT INTO notify_template(code,title,body,enabled) VALUES(?,?,?,?) "+
			"ON CONFLICT(code) DO UPDATE SET title=excluded.title,body=excluded.body,enabled=excluded.enabled",
		code, title, body, enabled)
	return err
}

// DeleteNotifyTemplate 删除通知模板。此前模板只能增改不能删，
// 写错一次就永久留在列表里。
func (s *Store) DeleteNotifyTemplate(id int64) error {
	_, err := s.DB.Exec("DELETE FROM notify_template WHERE id=?", id)
	return err
}

func (s *Store) AddNotifyRecord(code, title, body, target string, sent int) {
	if _, err := s.DB.Exec("INSERT INTO notify_record(template_code,title,body,target,sent_count) VALUES(?,?,?,?,?)",
		code, title, body, target, sent); err != nil {
		slog.Error("insert notify record failed", "template_code", code, "err", err)
	}
}

func (s *Store) ListNotifyRecords(limit, offset int) ([]gin.H, int, error) {
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM notify_record").Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		"SELECT id,COALESCE(template_code,''),title,body,target,sent_count,created_at FROM notify_record ORDER BY id DESC LIMIT ? OFFSET ?",
		limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id int64
		var code, title, body, target string
		var sent int
		var created time.Time
		if err := rows.Scan(&id, &code, &title, &body, &target, &sent, &created); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan notify_record_row failed", "err", err)
			continue
		}
		out = append(out, gin.H{"id": id, "template_code": code, "title": title, "body": body,
			"target": target, "sent_count": sent, "created_at": created})
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

func (s *Store) AllUserIDs() ([]int64, error) {
	rows, err := s.DB.Query("SELECT id FROM `user` WHERE status=1")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []int64{}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan user_id_row failed", "err", err)
			continue
		}
		out = append(out, id)
	}
	// ★ 这一处的静默截断后果最直接：群发通知用它取收件人列表。
	// 遍历中途出错 → 少了一批用户 → 那些人收不到通知，
	// 而后台仍然显示"已发送 N 条"（N 就是截断后的条数），成功回执与事实不符。
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func handleAdminListTemplates(c *gin.Context) {
	list, err := st.ListNotifyTemplates()
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	aok(c, list)
}

func handleAdminUpsertTemplate(c *gin.Context) {
	var req struct {
		Code    string `json:"code" binding:"required"`
		Title   string `json:"title" binding:"required"`
		Body    string `json:"body" binding:"required"`
		Enabled int    `json:"enabled"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	if err := st.UpsertNotifyTemplate(req.Code, req.Title, req.Body, req.Enabled); err != nil {
		afail(c, 500, 500, "保存失败")
		return
	}
	aok(c, gin.H{"ok": true})
}

func handleAdminListRecords(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	list, total, err := st.ListNotifyRecords(limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

func handleAdminSendNotify(c *gin.Context) {
	var req struct {
		Title        string `json:"title" binding:"required"`
		Body         string `json:"body" binding:"required"`
		Target       string `json:"target"`
		TemplateCode string `json:"template_code"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	target := strings.TrimSpace(req.Target)
	if target == "" {
		target = "all"
	}
	// 定向投递：此前 target 字段是假的——无论填什么都 AllUserIDs() 全站广播，
	// 返回的 sent 也是全站人数而非真实投递数，管理员完全被误导。
	ids, err := resolveNotifyTargets(target)
	if err != nil {
		afail(c, 400, 400, err.Error())
		return
	}
	if len(ids) == 0 {
		afail(c, 400, 400, "目标用户为空，请检查 target")
		return
	}
	notice := WsMessage{Type: MsgAdminNotice, Data: gin.H{"title": req.Title, "body": req.Body, "ts": time.Now().UnixMilli()}}
	// 异步扇出，避免在请求线程内串行遍历全量用户阻塞响应。
	go func() {
		for _, id := range ids {
			hub.route(id, notice)
		}
	}()
	sent := len(ids)
	st.AddNotifyRecord(req.TemplateCode, req.Title, req.Body, target, sent)
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "send_notify",
		fmt.Sprintf("target=%s sent=%d title=%s", target, sent, req.Title), c.ClientIP())
	aok(c, gin.H{"sent": sent})
}

// resolveNotifyTargets 解析投递目标。
// 约定：`all` = 全站广播；`uid:1,2,3` = 定向给这些用户 id（不存在的 id 会被剔除）。
func resolveNotifyTargets(target string) ([]int64, error) {
	if target == "all" {
		return st.AllUserIDs()
	}
	raw, ok := strings.CutPrefix(target, "uid:")
	if !ok {
		return nil, errors.New("target 格式无效，应为 all 或 uid:1,2,3")
	}
	seen := map[int64]bool{}
	out := []int64{}
	for _, part := range strings.Split(raw, ",") {
		p := strings.TrimSpace(part)
		if p == "" {
			continue
		}
		id, err := strconv.ParseInt(p, 10, 64)
		if err != nil || id <= 0 {
			return nil, fmt.Errorf("用户 id 无效：%s", p)
		}
		if seen[id] {
			continue
		}
		// 剔除不存在的用户，避免把「投递数」虚报成填进来的条数。
		if u, err := st.GetUserByID(id); err != nil || u == nil {
			continue
		}
		seen[id] = true
		out = append(out, id)
	}
	return out, nil
}

// APPEND-ADMIN-9

// ---------- 通用文件上传（APK / LOGO 等，落存储抽象层） ----------

func handleAdminUpload(c *gin.Context) {
	releaseMultipartSlot, parseOK := acquireMultipartParseSlot()
	if !parseOK {
		rejectMultipartBusy(c, true)
		return
	}
	defer releaseMultipartSlot()
	defer releaseParsedMultipartForm(c)

	// 先限制整个请求体，再让 FormFile 解析；否则 file.Size 检查之前就可能
	// 把任意大的请求写入内存或临时盘。300MB 是业务上限，额外空间只留给 multipart 头。
	const adminUploadMaxBytes = 300*1024*1024 + bytesHeaderSlack
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, adminUploadMaxBytes)

	file, err := c.FormFile("file")
	if err != nil {
		afail(c, 400, 400, "缺少文件字段 file")
		return
	}
	if file.Size > 300*1024*1024 {
		afail(c, 400, 400, "文件过大（不超过 300MB）")
		return
	}
	ext := strings.ToLower(filepath.Ext(file.Filename))
	// 白名单：仅允许 APK 与常见图片；拒绝 html/svg/可执行等可致同源 XSS 或滥用的类型。
	switch ext {
	case ".apk", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".ico":
	default:
		afail(c, 400, 400, "不支持的文件类型")
		return
	}
	base := randomCode(20)
	if base == "" {
		afail(c, 500, 500, "安全随机源不可用")
		return
	}
	rel := "upload/" + base + ext
	dst := filepath.Join(uploadDir, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		afail(c, 500, 500, "存储目录创建失败")
		return
	}
	if err := c.SaveUploadedFile(file, dst); err != nil {
		afail(c, 500, 500, "保存失败")
		return
	}
	releaseParsedMultipartForm(c)
	url := newStorage().PublicURL(rel)
	st.AddAudit(c.GetInt64("aid"), "", "upload", rel, c.ClientIP())
	aok(c, gin.H{"url": url, "apk_url": url})
}

// ---------- 管理员管理（角色/状态/重置密码/删除） ----------

// adminTargetID 取路径参数 :id，并阻止对自己动手。
// 自我降级/自我禁用/自我删除都可能把系统锁死（没人能管系统设置了）。
func adminTargetID(c *gin.Context, action string) (int64, bool) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "参数错误")
		return 0, false
	}
	if id == c.GetInt64("aid") {
		afail(c, 400, 400, "不能对自己执行"+action)
		return 0, false
	}
	return id, true
}

func handleAdminUpdateAdminRole(c *gin.Context) {
	id, ok := adminTargetID(c, "改角色")
	if !ok {
		return
	}
	var req struct {
		Role string `json:"role" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	// 白名单：此前 role 字段无任何校验，可写入任意字符串。
	// 拼错一个字母就会产生既非 admin 也非 super 的「幽灵角色」，
	// 该账号能登录却处处 403，排查成本极高。
	if !isValidAdminRole(req.Role) {
		afail(c, 400, 400, "角色只能是 admin 或 super")
		return
	}
	target, err := st.GetAdminByID(id)
	if err != nil || target == nil {
		afail(c, 404, 404, "管理员不存在")
		return
	}
	// 把最后一个启用的超管降级 = 系统再无人可管理设置与管理员。
	if target.Role == "super" && req.Role != "super" {
		n, err := st.CountActiveSuperAdmins(id)
		if err != nil {
			afail(c, 500, 500, "检查超级管理员失败")
			return
		}
		if n == 0 {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
	}
	if err := st.UpdateAdminRole(id, req.Role); err != nil {
		if errors.Is(err, errLastSuperAdmin) {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
		afail(c, 500, 500, "更新失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "update_admin_role",
		fmt.Sprintf("id=%d %s→%s", id, target.Role, req.Role), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func handleAdminSetAdminStatus(c *gin.Context) {
	id, ok := adminTargetID(c, "启用/禁用")
	if !ok {
		return
	}
	var req struct {
		Status int `json:"status"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || (req.Status != 1 && req.Status != 2) {
		afail(c, 400, 400, "status 只能是 1(启用) 或 2(禁用)")
		return
	}
	target, err := st.GetAdminByID(id)
	if err != nil || target == nil {
		afail(c, 404, 404, "管理员不存在")
		return
	}
	if req.Status == 2 && target.Role == "super" {
		n, err := st.CountActiveSuperAdmins(id)
		if err != nil {
			afail(c, 500, 500, "检查超级管理员失败")
			return
		}
		if n == 0 {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
	}
	if err := st.UpdateAdminStatus(id, req.Status); err != nil {
		if errors.Is(err, errLastSuperAdmin) {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
		afail(c, 500, 500, "更新失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "set_admin_status",
		fmt.Sprintf("id=%d status=%d", id, req.Status), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func handleAdminResetAdminPassword(c *gin.Context) {
	id, ok := adminTargetID(c, "重置密码")
	if !ok {
		return
	}
	var req struct {
		Password string `json:"password" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	if err := validateStrongPassword(req.Password); err != nil {
		afail(c, 400, 400, err.Error())
		return
	}
	if _, err := st.GetAdminByID(id); err != nil {
		afail(c, 404, 404, "管理员不存在")
		return
	}
	// ResetAdminPassword 内部会 token_ver+1，令该管理员所有旧 token 立即失效。
	if err := st.ResetAdminPassword(id, hashPassword(req.Password)); err != nil {
		afail(c, 500, 500, "重置失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "reset_admin_password",
		fmt.Sprintf("id=%d", id), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func handleAdminDeleteAdmin(c *gin.Context) {
	id, ok := adminTargetID(c, "删除")
	if !ok {
		return
	}
	target, err := st.GetAdminByID(id)
	if err != nil || target == nil {
		afail(c, 404, 404, "管理员不存在")
		return
	}
	if target.Role == "super" {
		n, err := st.CountActiveSuperAdmins(id)
		if err != nil {
			afail(c, 500, 500, "检查超级管理员失败")
			return
		}
		if n == 0 {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
	}
	if err := st.DeleteAdmin(id); err != nil {
		if errors.Is(err, errLastSuperAdmin) {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
		afail(c, 500, 500, "删除失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "delete_admin",
		fmt.Sprintf("id=%d username=%s", id, target.Username), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// ---------- 通知模板删除 ----------

func handleAdminDeleteTemplate(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		afail(c, 400, 400, "参数错误")
		return
	}
	if err := st.DeleteNotifyTemplate(id); err != nil {
		afail(c, 500, 500, "删除失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "delete_notify_template",
		fmt.Sprintf("id=%d", id), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// ---------- SMTP 测试发信 ----------

// handleAdminSmtpTest 用当前保存的 SMTP 配置真的发一封测试邮件。
// 此前配完 SMTP 没有任何验证手段，只能去 App 端走一遍注册流程试错；
// 失败原因必须原样返回给前端（认证失败/连接超时/发件人非法各不相同）。
func handleAdminSmtpTest(c *gin.Context) {
	var req struct {
		To string `json:"to" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		afail(c, 400, 400, "请填写收件邮箱")
		return
	}
	to := strings.TrimSpace(req.To)
	if !strings.Contains(to, "@") || strings.HasPrefix(to, "@") || strings.HasSuffix(to, "@") {
		afail(c, 400, 400, "收件邮箱格式不正确")
		return
	}
	sc, err := loadSMTP()
	if err != nil {
		afail(c, 400, 400, "SMTP 未配置完整：主机、用户名、密码均为必填")
		return
	}
	body := "这是一封来自「林曦日记」后台的 SMTP 测试邮件。\r\n\r\n" +
		"如果你收到了它，说明邮件服务配置正确，App 端的邮箱验证码可以正常发送。"
	if err := sendMail(sc, to, "林曦日记 · SMTP 测试", body); err != nil {
		// 把真实错误带出去：认证失败(535)、连接超时、TLS 不匹配等原因各不相同，
		// 统一成「发送失败」等于让管理员盲猜。
		afail(c, 400, 400, "发送失败："+err.Error())
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "smtp_test", "to="+to, c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func registerAdminRoutes(r *gin.Engine) {
	g := r.Group("/api/admin")
	g.POST("/login", handleAdminLogin)

	auth := g.Group("", AdminAuth())
	auth.GET("/user/info", handleAdminInfo)
	auth.POST("/change-credentials", handleAdminChangeCredentials)
	auth.GET("/stats", handleAdminStats)

	auth.GET("/users", handleAdminListUsers)
	auth.GET("/pairs", handleAdminListPairs)
	auth.GET("/todos", handleAdminListTodos)
	auth.GET("/app-releases", handleAdminListAppReleases)
	auth.GET("/server-info", handleAdminServerInfo)
	auth.GET("/audit-logs", handleAdminListAudit)
	auth.GET("/network-logs", handleAdminListNetworkLogs)
	auth.GET("/notify-templates", handleAdminListTemplates)
	auth.GET("/notify-records", handleAdminListRecords)
	// 站点展示信息：所有已登录管理员可读（含首登改密期间），不含任何密钥。
	auth.GET("/site-info", handleAdminSiteInfo)

	// ---- 以下为敏感操作，一律要求超级管理员 ----
	//
	// 此前只有 POST /admins 与 PUT /settings 挂了 requireSuper，其余全部裸奔，
	// 于是「普通 admin」事实上等于超管：能读取 GET /settings 里的存储密钥、
	// 能向全站用户群发通知、能删任意日记待办、能封禁用户、能解绑他人情侣关系、
	// 能上传 300MB 文件落盘。这与「分角色」的初衷完全背离。
	sup := g.Group("", AdminAuth(), requireSuper())
	sup.POST("/upload", handleAdminUpload)
	sup.PUT("/users/:id", handleAdminUpdateUser)
	sup.PUT("/users/:id/status", handleAdminSetUserStatus)
	sup.PUT("/pairs/:id", handleAdminUpdatePair)
	sup.POST("/pairs/:id/unbind", handleAdminUnbindPair)
	sup.POST("/pairs/:id/cancel-invite", handleAdminCancelPendingInvite)
	sup.PUT("/todos/:id", handleAdminUpdateTodo)
	sup.DELETE("/todos/:id", handleAdminDeleteTodo)
	// 相册照片是全站最私密的内容，列表与删除都收敛到超管（普通 admin 连元数据都不给看）。
	sup.GET("/photos", handleAdminListPhotos)
	sup.PUT("/photos/:id", handleAdminUpdatePhoto)
	sup.DELETE("/photos/:id", handleAdminDeletePhoto)

	sup.GET("/admins", handleAdminListAdmins)
	sup.POST("/admins", handleAdminCreateAdmin)
	sup.PUT("/admins/:id", handleAdminUpdateAdminRole)
	sup.PUT("/admins/:id/status", handleAdminSetAdminStatus)
	sup.POST("/admins/:id/reset-password", handleAdminResetAdminPassword)
	sup.DELETE("/admins/:id", handleAdminDeleteAdmin)

	// GET /settings 会回吐 SMTP 主机/账号等配置，同样限超管。
	// 运行参数（相册配额/保留期/限流/互动冷却）单独开一条只读接口给普通 admin（Q42=C）。
	//
	// **不能直接把 GET /settings 放给普通 admin**：那里面有 smtp.host 与 smtp.username，
	// 虽然 password 会脱敏成 __set__，但主机与账号本身就是攻击面
	//（0820 那轮正是为此把 /settings 收敛到超管）。故拆成两条：
	//   - GET /runtime-settings：只有运行参数 + 默认值 + 元信息，零密钥，普通 admin 可读
	//   - GET /settings：含 SMTP/存储，仍限超管
	auth.GET("/runtime-settings", handleAdminGetRuntimeSettings)
	// 写入统一走一条，敏感键的超管校验在 handler 内逐键做
	// （命中 settingKeys 且非超管 → 403，见 handleAdminUpdateSettings）。
	auth.PUT("/settings", handleAdminUpdateSettings)

	sup.GET("/settings", handleAdminGetSettings)
	// SMTP 测试会真的发信，仍限超管。
	sup.POST("/settings/smtp-test", handleAdminSmtpTest)

	// 相册管理 + 磁盘统计（Q28=D）。全部限超管：相册是全站最私密的内容。
	registerAdminAlbumRoutes(sup)

	// 日记导出：功能下线前的留档通道（Q33=B）。导完即可移除。
	registerDiaryExportRoutes(sup)

	sup.PUT("/notify-templates", handleAdminUpsertTemplate)
	sup.DELETE("/notify-templates/:id", handleAdminDeleteTemplate)
	sup.POST("/notify", handleAdminSendNotify)
}
