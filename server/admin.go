package main

import (
	"database/sql"
	"errors"
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

func signAdminToken(aid int64, role string, mustChange bool) (string, error) {
	claims := jwt.MapClaims{
		"aid":   aid,
		"role":  role,
		"scope": "admin",
		"mc":    mustChange,
		"exp":   time.Now().Add(time.Duration(cfg.App.TokenTTLHours) * time.Hour).Unix(),
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(cfg.App.JWTSecret))
}

func parseAdminToken(token string) (int64, string, bool, error) {
	t, err := jwt.Parse(token, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return []byte(cfg.App.JWTSecret), nil
	})
	if err != nil || !t.Valid {
		return 0, "", false, errors.New("invalid token")
	}
	claims, ok := t.Claims.(jwt.MapClaims)
	if !ok || claims["scope"] != "admin" {
		return 0, "", false, errors.New("bad claims")
	}
	aidF, ok := claims["aid"].(float64)
	if !ok {
		return 0, "", false, errors.New("bad claims")
	}
	role, _ := claims["role"].(string)
	mc, _ := claims["mc"].(bool)
	return int64(aidF), role, mc, nil
}

func AdminAuth() gin.HandlerFunc {
	// 首登强制改凭据前允许访问的白名单（否则一律拦截，防止用初始 admin/123456 直接操作）。
	allowWhileMustChange := map[string]bool{
		"/api/admin/info":               true,
		"/api/admin/change-credentials": true,
		"/api/admin/logout":             true,
	}
	return func(c *gin.Context) {
		token := strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
		aid, role, mc, err := parseAdminToken(token)
		if err != nil {
			afail(c, http.StatusUnauthorized, 401, "未授权")
			c.Abort()
			return
		}
		if mc && !allowWhileMustChange[c.FullPath()] {
			afail(c, http.StatusForbidden, 4281, "请先修改初始账号与密码")
			c.Abort()
			return
		}
		c.Set("aid", aid)
		c.Set("role", role)
		c.Set("mc", mc)
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
	_, err := s.DB.Exec(
		"INSERT INTO admin_user(username,password_hash,role,must_change) VALUES(?,?,?,1)",
		"admin", hashPassword("123456"), "super")
	return err
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
	s.DB.Exec("UPDATE admin_user SET last_login_at=NOW() WHERE id=?", id)
}

func (s *Store) UpdateAdminCredentials(id int64, username, hash string, email *string) error {
	_, err := s.DB.Exec(
		"UPDATE admin_user SET username=?, password_hash=?, email=?, must_change=0 WHERE id=?",
		username, hash, email, id)
	return err
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
	a, hash, err := st.GetAdminForLogin(strings.TrimSpace(req.Username))
	if err != nil || !checkPassword(hash, req.Password) {
		afail(c, 400, 400, "账号或密码错误")
		return
	}
	if a.Status != 1 {
		afail(c, 403, 403, "账号已被禁用")
		return
	}
	st.TouchAdminLogin(a.ID)
	st.AddAudit(a.ID, a.Username, "login", "", c.ClientIP())
	token, _ := signAdminToken(a.ID, a.Role, a.MustChange)
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
	if req.Password != "" {
		if len(req.Password) < 6 {
			afail(c, 400, 400, "密码至少 6 位")
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
	// 首登改凭据后 must_change 已清零：签发新 token（mc=false）供前端替换，解除访问限制。
	newToken, _ := signAdminToken(aid, a.Role, false)
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
			` WHERE created_at>=DATE_SUB(CURDATE(), INTERVAL 6 DAY) GROUP BY d ORDER BY d`)
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
		"new_users_7d": q("SELECT COUNT(*) FROM `user` WHERE created_at>=DATE_SUB(NOW(), INTERVAL 7 DAY)"),
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
	_, err := s.DB.Exec("UPDATE pair SET status=0, unbind_time=NOW() WHERE id=?", id)
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
	list, err := st.ListAdmins()
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	aok(c, list)
}

func handleAdminCreateAdmin(c *gin.Context) {
	if c.GetString("role") != "super" {
		afail(c, 403, 403, "仅超级管理员可添加管理员")
		return
	}
	var req struct {
		Username string `json:"username" binding:"required"`
		Password string `json:"password" binding:"required"`
		Role     string `json:"role"`
		Email    string `json:"email"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.Password) < 6 {
		afail(c, 400, 400, "参数错误（密码至少 6 位）")
		return
	}
	role := req.Role
	if role == "" {
		role = "admin"
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
	st.AddAudit(c.GetInt64("aid"), "", "create_admin", req.Username, c.ClientIP())
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
	"site.name", "site.logo", "site.description",
	"storage.driver", "storage.local_dir",
	"storage.oss_endpoint", "storage.oss_bucket", "storage.oss_access_key", "storage.oss_secret", "storage.oss_base_url",
	"smtp.host", "smtp.port", "smtp.username", "smtp.password", "smtp.from", "smtp.ssl",
	"push.provider",
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
	st.AddAudit(c.GetInt64("aid"), "", "update_settings", "", c.ClientIP())
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
			"ON DUPLICATE KEY UPDATE title=VALUES(title),body=VALUES(body),enabled=VALUES(enabled)",
		code, title, body, enabled)
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
	ids, _ := st.AllUserIDs()
	notice := WsMessage{Type: MsgAdminNotice, Data: gin.H{"title": req.Title, "body": req.Body, "ts": time.Now().UnixMilli()}}
	// 异步扇出，避免在请求线程内串行遍历全量用户阻塞响应。
	go func() {
		for _, id := range ids {
			hub.route(id, notice)
		}
	}()
	sent := len(ids)
	target := req.Target
	if target == "" {
		target = "all"
	}
	st.AddNotifyRecord(req.TemplateCode, req.Title, req.Body, target, sent)
	st.AddAudit(c.GetInt64("aid"), "", "send_notify", req.Title, c.ClientIP())
	aok(c, gin.H{"sent": sent})
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

func registerAdminRoutes(r *gin.Engine) {
	g := r.Group("/api/admin")
	g.POST("/login", handleAdminLogin)

	auth := g.Group("", AdminAuth())
	auth.GET("/user/info", handleAdminInfo)
	auth.POST("/change-credentials", handleAdminChangeCredentials)
	auth.POST("/upload", handleAdminUpload)
	auth.GET("/stats", handleAdminStats)

	auth.GET("/users", handleAdminListUsers)
	auth.PUT("/users/:id/status", handleAdminSetUserStatus)

	auth.GET("/pairs", handleAdminListPairs)
	auth.POST("/pairs/:id/unbind", handleAdminUnbindPair)

	auth.GET("/todos", handleAdminListTodos)
	auth.DELETE("/todos/:id", handleAdminDeleteTodo)
	auth.GET("/diaries", handleAdminListDiaries)
	auth.DELETE("/diaries/:id", handleAdminDeleteDiary)

	auth.GET("/app-versions", handleAdminListVersions)
	auth.POST("/app-versions", handleAdminCreateVersion)
	auth.PUT("/app-versions/:id/status", handleAdminSetVersionStatus)
	auth.DELETE("/app-versions/:id", handleAdminDeleteVersion)

	auth.GET("/audit-logs", handleAdminListAudit)
	auth.GET("/network-logs", handleAdminListNetworkLogs)
	auth.GET("/admins", handleAdminListAdmins)
	auth.POST("/admins", requireSuper(), handleAdminCreateAdmin)

	auth.GET("/settings", handleAdminGetSettings)
	auth.PUT("/settings", requireSuper(), handleAdminUpdateSettings)

	auth.GET("/notify-templates", handleAdminListTemplates)
	auth.PUT("/notify-templates", handleAdminUpsertTemplate)
	auth.GET("/notify-records", handleAdminListRecords)
	auth.POST("/notify", handleAdminSendNotify)
}
