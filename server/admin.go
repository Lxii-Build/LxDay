package main

import (
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// ================= 后台管理 API（独立 {code,msg,data} 信封） =================

const adminCodeOK = 200

func aok(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, gin.H{"code": adminCodeOK, "msg": "success", "data": data})
}

func afail(c *gin.Context, httpCode, bizCode int, msg string) {
	c.JSON(httpCode, gin.H{"code": bizCode, "msg": msg})
}

// ---------- 管理员 JWT ----------

// adminTokenTTL 后台 token 有效期。
//
// 与 App 端（720h）不同，后台是高价值目标：一旦 token 泄露，攻击者可读取全站用户资料、
// 群发通知、删除内容。故有效期压到 2 小时，配合 token_ver 做即时撤销。
const adminTokenTTL = 2 * time.Hour

func signAdminToken(aid int64, role string, mustChange bool, tokenVer int64) (string, error) {
	claims := jwt.MapClaims{
		"aid":   aid,
		"role":  role,
		"scope": "admin",
		"mc":    mustChange,
		"tv":    tokenVer,
		"exp":   time.Now().Add(adminTokenTTL).Unix(),
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
		token := strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
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
	var n int
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM admin_user").Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	// 安全：不使用公开可知的默认口令（admin/123456 会被抢注接管），改为随机初始口令。
	pw := randomPassword(16)
	_, err := s.DB.Exec(
		"INSERT INTO admin_user(username,password_hash,role,must_change) VALUES(?,?,?,1)",
		"admin", hashPassword(pw), "super")
	if err != nil {
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
		// 写文件失败时只能退回日志，否则管理员将永远无法登录。
		slog.Warn("初始口令文件写入失败，仅此一次打印到日志，请立即登录改密并清理日志",
			"path", pwPath, "err", werr, "username", "admin", "password", pw)
		return nil
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
	s.DB.Exec("UPDATE admin_user SET last_login_at=datetime('now') WHERE id=?", id)
}

func (s *Store) UpdateAdminCredentials(id int64, username, hash string, email *string) error {
	// 改凭据即令旧 token 全部失效（token_ver+1）：否则改完密码，
	// 泄露的旧 token 仍能用满整个有效期。
	_, err := s.DB.Exec(
		"UPDATE admin_user SET username=?, password_hash=?, email=?, must_change=0, token_ver=token_ver+1 WHERE id=?",
		username, hash, email, id)
	return err
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
	_, err := s.DB.Exec("UPDATE admin_user SET token_ver=token_ver+1 WHERE id=?", id)
	return err
}

// ---------- 管理员管理（角色/状态/密码/删除） ----------

func (s *Store) UpdateAdminRole(id int64, role string) error {
	// 角色变更必须撤销旧 token，否则降级后的管理员仍持有 super 权限的 token。
	_, err := s.DB.Exec(
		"UPDATE admin_user SET role=?, token_ver=token_ver+1 WHERE id=?", role, id)
	return err
}

func (s *Store) UpdateAdminStatus(id int64, status int) error {
	_, err := s.DB.Exec(
		"UPDATE admin_user SET status=?, token_ver=token_ver+1 WHERE id=?", status, id)
	return err
}

func (s *Store) ResetAdminPassword(id int64, hash string) error {
	// 重置密码后旧 token 必须失效，否则「重置密码」形同虚设。
	_, err := s.DB.Exec(
		"UPDATE admin_user SET password_hash=?, token_ver=token_ver+1 WHERE id=?", hash, id)
	return err
}

func (s *Store) DeleteAdmin(id int64) error {
	_, err := s.DB.Exec("DELETE FROM admin_user WHERE id=?", id)
	return err
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
			return nil, 0, err
		}
		out = append(out, a)
	}
	return out, total, rows.Err()
}

func (s *Store) AddAudit(adminID int64, name, action, detail, ip string) {
	s.DB.Exec(
		"INSERT INTO admin_audit_log(admin_id,admin_name,action,detail,ip) VALUES(?,?,?,?,?)",
		adminID, name, action, detail, ip)
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
	// 登录失败限流：同一账号 10 分钟内最多 5 次失败。
	uname := strings.TrimSpace(req.Username)
	failKey := "adminlogin:fail:" + uname
	if st.mem.count(failKey) >= 5 {
		afail(c, 429, 429, "登录尝试过于频繁，请 10 分钟后再试")
		return
	}
	a, hash, err := st.GetAdminForLogin(uname)
	if err != nil || !checkPassword(hash, req.Password) {
		st.mem.incr(failKey, 10*time.Minute)
		afail(c, 400, 400, "账号或密码错误")
		return
	}
	st.mem.del(failKey)
	if a.Status != 1 {
		afail(c, 403, 403, "账号已被禁用")
		return
	}
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
		if len(u) < 3 || len(u) > 64 {
			afail(c, 400, 400, "用户名长度 3-64")
			return
		}
		username = u
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
	var email *string
	if e := strings.TrimSpace(req.Email); e != "" {
		email = &e
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
	newToken, _ := signAdminToken(aid, a.Role, false, tv)
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
	q := func(query string) int {
		var n int
		s.DB.QueryRow(query).Scan(&n)
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
			rows.Scan(&d, &c)
			daily = append(daily, gin.H{"date": d, "count": c})
		}
	}
	return gin.H{
		"users":        q("SELECT COUNT(*) FROM `user`"),
		"pairs":        q("SELECT COUNT(*) FROM pair WHERE status=1 AND user_a_id>0 AND user_b_id>0"),
		"todos":        q("SELECT COUNT(*) FROM todo WHERE status<2"),
		"diaries":      q("SELECT COUNT(*) FROM diary"),
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

func (s *Store) ListUsers(keyword string, limit, offset int) ([]AdminUserRow, int, error) {
	where := ""
	args := []interface{}{}
	if keyword != "" {
		where = " WHERE u.username LIKE ? OR u.email LIKE ? OR u.nickname LIKE ?"
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
		rows.Scan(&u.ID, &u.Username, &u.Email, &u.Nickname, &u.AvatarURL, &u.Gender, &u.Signature, &birthday, &u.Status, &u.CreatedAt, &anniversary)
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
	return out, total, nil
}

func (s *Store) SetUserStatus(id int64, status int) error {
	_, err := s.DB.Exec("UPDATE `user` SET status=? WHERE id=?", status, id)
	return err
}

func handleAdminListUsers(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	list, total, err := st.ListUsers(strings.TrimSpace(c.Query("keyword")), limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

func handleAdminSetUserStatus(c *gin.Context) {
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	var req struct {
		Status int `json:"status"`
	}
	c.ShouldBindJSON(&req)
	if err := st.SetUserStatus(id, req.Status); err != nil {
		afail(c, 500, 500, "操作失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), "", "set_user_status", "user="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// APPEND-ADMIN-4

// ---------- 绑定关系管理 ----------

func (s *Store) ListPairs(limit, offset int) ([]gin.H, int, error) {
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM pair").Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		"SELECT p.id,p.user_a_id,p.user_b_id,COALESCE(ua.nickname,''),COALESCE(ub.nickname,''),p.status,p.invite_code,p.created_at "+
			"FROM pair p LEFT JOIN `user` ua ON ua.id=p.user_a_id LEFT JOIN `user` ub ON ub.id=p.user_b_id "+
			"ORDER BY p.id DESC LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id, ua, ub int64
		var na, nb, code string
		var status int
		var created time.Time
		rows.Scan(&id, &ua, &ub, &na, &nb, &status, &code, &created)
		out = append(out, gin.H{"id": id, "user_a_id": ua, "user_b_id": ub, "name_a": na, "name_b": nb,
			"status": status, "invite_code": code, "created_at": created})
	}
	return out, total, nil
}

func (s *Store) UnbindPair(id int64) error {
	_, err := s.DB.Exec("UPDATE pair SET status=0, unbind_time=datetime('now') WHERE id=?", id)
	return err
}

func handleAdminListPairs(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	list, total, err := st.ListPairs(limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

func handleAdminUnbindPair(c *gin.Context) {
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	if err := st.UnbindPair(id); err != nil {
		afail(c, 500, 500, "操作失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), "", "unbind_pair", "pair="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// APPEND-ADMIN-5

// ---------- 内容审核（待办 / 日记） ----------

// ListTodosAll 后台待办列表（分页 + keyword，creator/assignee 名字从 user 解析）。
// 契约字段：id,title,note,creator_id,creator_name,assignee_id,assignee_name,remind_enabled,remind_type,repeat_type,remind_at,status,pair_id
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
			"t.title,COALESCE(t.note,''),t.remind_at,t.remind_type,t.repeat_type,t.remind_enabled,t.status "+
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
		var remindType, repeatType, status int
		var remindEnabled bool
		rows.Scan(&id, &pid, &cid, &creatorName, &aid, &assigneeName,
			&title, &note, &remindAt, &remindType, &repeatType, &remindEnabled, &status)
		var ra interface{}
		if remindAt.Valid {
			ra = remindAt.Time
		}
		out = append(out, gin.H{
			"id": id, "title": title, "note": note,
			"creator_id": cid, "creator_name": creatorName,
			"assignee_id": aid, "assignee_name": assigneeName,
			"remind_enabled": remindEnabled, "remind_type": remindType,
			"repeat_type": repeatType, "remind_at": ra,
			"status": status, "pair_id": pid,
		})
	}
	return out, total, nil
}

func (s *Store) ListDiariesAll(limit, offset int) ([]gin.H, int, error) {
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM diary").Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		"SELECT d.id,d.pair_id,d.author_id,COALESCE(u.nickname,''),d.title,d.diary_date,d.created_at "+
			"FROM diary d LEFT JOIN `user` u ON u.id=d.author_id ORDER BY d.id DESC LIMIT ? OFFSET ?",
		limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id, pid, aid int64
		var name, title, date string
		var created time.Time
		rows.Scan(&id, &pid, &aid, &name, &title, &date, &created)
		out = append(out, gin.H{"id": id, "pair_id": pid, "author_id": aid, "author_name": name,
			"title": title, "diary_date": date, "created_at": created})
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

func handleAdminDeleteTodo(c *gin.Context) {
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	st.DeleteTodo(id)
	st.AddAudit(c.GetInt64("aid"), "", "delete_todo", "todo="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func handleAdminListDiaries(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	list, total, err := st.ListDiariesAll(limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

func handleAdminDeleteDiary(c *gin.Context) {
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	st.DeleteDiary(id)
	st.AddAudit(c.GetInt64("aid"), "", "delete_diary", "diary="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// ---------- 内容审核（相册照片） ----------

// handleAdminListPhotos 照片列表：分页 + keyword 搜 caption + 按 pair 筛选。
// 只回元数据，不回图片 URL——见 Store.ListPhotosAll 的说明。
func handleAdminListPhotos(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	pairID, _ := strconv.ParseInt(c.DefaultQuery("pair_id", "0"), 10, 64)
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
		rows.Scan(&id, &aid, &name, &action, &detail, &ip, &created)
		out = append(out, gin.H{"id": id, "admin_id": aid, "admin_name": name, "action": action,
			"detail": detail, "ip": ip, "created_at": created})
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
		rows.Scan(&a.ID, &a.Username, &a.Email, &a.Role, &a.MustChange, &a.Status)
		out = append(out, a)
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
	var email *string
	if e := strings.TrimSpace(req.Email); e != "" {
		email = &e
	}
	id, err := st.CreateAdmin(strings.TrimSpace(req.Username), hashPassword(req.Password), role, email)
	if err != nil {
		afail(c, 400, 400, "用户名已被占用")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "create_admin",
		fmt.Sprintf("username=%s role=%s", req.Username, role), c.ClientIP())
	aok(c, gin.H{"id": id})
}

// APPEND-ADMIN-7

// ---------- APP 版本发布 ----------

func handleAdminListVersions(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	list, total, err := st.ListAppVersions(strings.TrimSpace(c.Query("platform")), limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, list, total, current, size)
}

func handleAdminCreateVersion(c *gin.Context) {
	var v AppVersion
	if err := c.ShouldBindJSON(&v); err != nil || v.VersionName == "" {
		afail(c, 400, 400, "参数错误（缺少版本号）")
		return
	}
	if v.Platform == "" {
		v.Platform = "android"
	}
	v.Status = 1
	id, err := st.CreateAppVersion(&v)
	if err != nil {
		afail(c, 500, 500, "创建失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), "", "create_version", v.VersionName, c.ClientIP())
	aok(c, gin.H{"id": id})
}

func handleAdminSetVersionStatus(c *gin.Context) {
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	var req struct {
		Status int `json:"status"`
	}
	c.ShouldBindJSON(&req)
	if err := st.SetAppVersionStatus(id, req.Status); err != nil {
		afail(c, 500, 500, "操作失败")
		return
	}
	aok(c, gin.H{"ok": true})
}

func handleAdminDeleteVersion(c *gin.Context) {
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	st.DeleteAppVersion(id)
	aok(c, gin.H{"ok": true})
}

// ---------- 系统设置（站点/存储/推送/SMTP） ----------

var settingKeys = []string{
	"site.name", "site.url", "site.logo", "site.description",
	"storage.driver", "storage.local_dir",
	"storage.oss_endpoint", "storage.oss_bucket", "storage.oss_access_key", "storage.oss_secret", "storage.oss_base_url",
	"smtp.host", "smtp.port", "smtp.username", "smtp.password", "smtp.from", "smtp.ssl",
	"push.provider",
}

// handleAdminSiteInfo 只回站点展示信息（名称/LOGO/描述），任何已登录管理员可读。
//
// 为什么要单独开一个：站点名与 LOGO 是后台每次加载都要用的展示数据，
// 而 GET /settings 里含 SMTP 主机账号与存储密钥，已被收敛到超管
// —— 若前端继续用它取站点名，普通 admin 会吃 403、后台顶栏连站点名都显示不出来。
// 同时它也在首登强制改密的白名单里：改密页自己也要显示站点名与 LOGO。
func handleAdminSiteInfo(c *gin.Context) {
	get := func(k string) string { v, _ := st.GetSetting(k); return v }
	aok(c, gin.H{
		"site.name":        get("site.name"),
		"site.logo":        get("site.logo"),
		"site.description": get("site.description"),
	})
}

func handleAdminGetSettings(c *gin.Context) {
	m := map[string]string{}
	for _, k := range settingKeys {
		v, _ := st.GetSetting(k)
		if k == "smtp.password" && v != "" {
			v = "__set__" // 不回传明文
		}
		m[k] = v
	}
	aok(c, m)
}

func handleAdminUpdateSettings(c *gin.Context) {
	var in map[string]string
	if err := c.ShouldBindJSON(&in); err != nil {
		afail(c, 400, 400, "参数错误")
		return
	}
	allowed := map[string]bool{}
	for _, k := range settingKeys {
		allowed[k] = true
	}
	for k, v := range in {
		if !allowed[k] {
			continue
		}
		if k == "smtp.password" && v == "__set__" {
			continue // 占位符表示不修改
		}
		st.SetSetting(k, v)
	}
	// site.url 被 siteBaseURL() 缓存（缓存是为了避开 MaxOpenConns(1) 下的自锁），
	// 改完必须失效，否则新配的站点地址要等进程重启才生效。
	invalidateSiteBaseCache()
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "update_settings",
		fmt.Sprintf("keys=%d", len(in)), c.ClientIP())
	aok(c, gin.H{"ok": true})
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
		rows.Scan(&id, &code, &title, &body, &enabled, &updated)
		out = append(out, gin.H{"id": id, "code": code, "title": title, "body": body, "enabled": enabled, "updated_at": updated})
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
	s.DB.Exec("INSERT INTO notify_record(template_code,title,body,target,sent_count) VALUES(?,?,?,?,?)",
		code, title, body, target, sent)
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
		rows.Scan(&id, &code, &title, &body, &target, &sent, &created)
		out = append(out, gin.H{"id": id, "template_code": code, "title": title, "body": body,
			"target": target, "sent_count": sent, "created_at": created})
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
		rows.Scan(&id)
		out = append(out, id)
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
	rel := "upload/" + randomCode(20) + ext
	dst := filepath.Join(uploadDir, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		afail(c, 500, 500, "存储目录创建失败")
		return
	}
	if err := c.SaveUploadedFile(file, dst); err != nil {
		afail(c, 500, 500, "保存失败")
		return
	}
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
		if n, _ := st.CountActiveSuperAdmins(id); n == 0 {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
	}
	if err := st.UpdateAdminRole(id, req.Role); err != nil {
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
		if n, _ := st.CountActiveSuperAdmins(id); n == 0 {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
	}
	if err := st.UpdateAdminStatus(id, req.Status); err != nil {
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
		if n, _ := st.CountActiveSuperAdmins(id); n == 0 {
			afail(c, 400, 400, "至少需要保留一个启用状态的超级管理员")
			return
		}
	}
	if err := st.DeleteAdmin(id); err != nil {
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
	auth.GET("/diaries", handleAdminListDiaries)
	auth.GET("/app-versions", handleAdminListVersions)
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
	sup.PUT("/users/:id/status", handleAdminSetUserStatus)
	sup.POST("/pairs/:id/unbind", handleAdminUnbindPair)
	sup.DELETE("/todos/:id", handleAdminDeleteTodo)
	sup.DELETE("/diaries/:id", handleAdminDeleteDiary)
	// 相册照片是全站最私密的内容，列表与删除都收敛到超管（普通 admin 连元数据都不给看）。
	sup.GET("/photos", handleAdminListPhotos)
	sup.DELETE("/photos/:id", handleAdminDeletePhoto)

	sup.POST("/app-versions", handleAdminCreateVersion)
	sup.PUT("/app-versions/:id/status", handleAdminSetVersionStatus)
	sup.DELETE("/app-versions/:id", handleAdminDeleteVersion)

	sup.GET("/admins", handleAdminListAdmins)
	sup.POST("/admins", handleAdminCreateAdmin)
	sup.PUT("/admins/:id", handleAdminUpdateAdminRole)
	sup.PUT("/admins/:id/status", handleAdminSetAdminStatus)
	sup.POST("/admins/:id/reset-password", handleAdminResetAdminPassword)
	sup.DELETE("/admins/:id", handleAdminDeleteAdmin)

	// GET /settings 会回吐 SMTP 主机/账号等配置，同样限超管。
	sup.GET("/settings", handleAdminGetSettings)
	sup.PUT("/settings", handleAdminUpdateSettings)
	sup.POST("/settings/smtp-test", handleAdminSmtpTest)

	sup.PUT("/notify-templates", handleAdminUpsertTemplate)
	sup.DELETE("/notify-templates/:id", handleAdminDeleteTemplate)
	sup.POST("/notify", handleAdminSendNotify)
}
