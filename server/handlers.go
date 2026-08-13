package main

import (
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

// uploadDir 为本地上传根目录（Go 自托管 /uploads/ 静态映射）
var uploadDir = "uploads"

func initUploadDir() {
	if cfg.Storage.UploadDir != "" {
		uploadDir = cfg.Storage.UploadDir
	}
}

// ================= 工具 =================

func sqlOpen(dsn string) (*sql.DB, error) {
	return sql.Open("sqlite", dsn)
}

func ok(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, gin.H{"code": 0, "message": "ok", "data": data})
}

func fail(c *gin.Context, httpCode, bizCode int, msg string) {
	c.JSON(httpCode, gin.H{"code": bizCode, "message": msg})
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
		v, _ := rand.Int(rand.Reader, big.NewInt(int64(len(digits))))
		out[i] = digits[v.Int64()]
	}
	return string(out)
}

// randomPassword 生成随机强口令（去除易混字符），用于超级管理员初始密码，避免公开的默认口令被抢注接管。
func randomPassword(n int) string {
	const cs = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
	out := make([]byte, n)
	for i := range out {
		v, _ := rand.Int(rand.Reader, big.NewInt(int64(len(cs))))
		out[i] = cs[v.Int64()]
	}
	return string(out)
}

// ================= JWT =================

func signToken(uid int64) (string, error) {
	claims := jwt.MapClaims{
		"uid": uid,
		"exp": time.Now().Add(time.Duration(cfg.App.TokenTTLHours) * time.Hour).Unix(),
	}
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return t.SignedString([]byte(cfg.App.JWTSecret))
}

func ParseToken(token string) (int64, error) {
	t, err := jwt.Parse(token, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method")
		}
		return []byte(cfg.App.JWTSecret), nil
	})
	if err != nil || !t.Valid {
		return 0, err
	}
	claims, ok := t.Claims.(jwt.MapClaims)
	if !ok {
		return 0, fmt.Errorf("bad claims")
	}
	return int64(claims["uid"].(float64)), nil
}

func JWTAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		h := c.GetHeader("Authorization")
		token := strings.TrimPrefix(h, "Bearer ")
		uid, err := ParseToken(token)
		if err != nil {
			fail(c, http.StatusUnauthorized, 1003, "登录已失效，请重新登录")
			c.Abort()
			return
		}
		// token 有效但用户不存在（如数据库重建 / 账号被删）→ 视为登录失效，
		// 让客户端据 401 自动清会话并回登录页，而非报 500/403。
		if _, err := st.GetUserByID(uid); err != nil {
			fail(c, http.StatusUnauthorized, 1003, "登录已失效，请重新登录")
			c.Abort()
			return
		}
		c.Set("uid", uid)
		c.Next()
	}
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

const inviteTTL = time.Hour // 邀请码 1 小时有效

func handleCreateInvite(c *gin.Context) {
	uid := currentUID(c)
	if _, err := st.GetPairByUserID(uid); err == nil {
		fail(c, 400, 1001, "已绑定，无法重复创建")
		return
	}
	// 若已有 1 小时内未过期的邀请，直接复用
	var existCode string
	var existCreated time.Time
	if err := st.DB.QueryRow(
		`SELECT invite_code, created_at FROM pair WHERE user_a_id=? AND user_b_id=0 AND status=1 LIMIT 1`,
		uid).Scan(&existCode, &existCreated); err == nil && existCode != "" {
		if time.Since(existCreated) < inviteTTL {
			ok(c, gin.H{"invite_code": existCode, "expires_in": int(inviteTTL.Seconds() - time.Since(existCreated).Seconds())})
			return
		}
		// 过期：作废旧码重新生成
		st.DB.Exec(`UPDATE pair SET status=0 WHERE invite_code=?`, existCode)
	}
	// 生成唯一 6 位邀请码
	var code string
	for i := 0; i < 5; i++ {
		code = randomCode(6)
		_, err := st.DB.Exec(
			`INSERT INTO pair(user_a_id,user_b_id,invite_code,status) VALUES(?,0,?,1)`,
			uid, code)
		if err == nil {
			ok(c, gin.H{"invite_code": code, "expires_in": int(inviteTTL.Seconds())})
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
	}
	num, err := strconv.ParseInt(req.InviteCode, 10, 64)
	if err != nil || len(req.InviteCode) != 6 {
		fail(c, 400, 1002, "邀请码为 6 位数字")
		return
	}
	// 校验有效期：邀请记录创建时间必须在 1 小时内
	var created time.Time
	if err := st.DB.QueryRow(
		`SELECT created_at FROM pair WHERE invite_code=? AND status=1`, req.InviteCode).
		Scan(&created); err != nil {
		fail(c, 400, 1009, "邀请码无效或已失效")
		return
	}
	if time.Since(created) > inviteTTL {
		fail(c, 400, 1009, "邀请码已过期，请让对方重新生成")
		return
	}
	pairID, err := st.BindPair(num, uid)
	if err != nil {
		fail(c, 400, 1009, err.Error())
		return
	}
	_ = pairID
	// 返回伴侣信息
	pair, _ := st.GetPairByUserID(uid)
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
		ok(c, gin.H{"unbound": true}) // 本就未绑定
		return
	}
	partner := st.PartnerID(pair, uid)
	if _, e := st.DB.Exec(`UPDATE pair SET status=0, unbind_time=datetime('now') WHERE id=?`, pair.ID); e != nil {
		fail(c, 500, 1010, "解绑失败")
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
	st.DB.Exec(`UPDATE pair SET status=0 WHERE user_a_id=? AND user_b_id=0 AND status=1`, uid)
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
	if err != nil {
		fail(c, 200, 1001, "未绑定")
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
		tx.Rollback()
		fail(c, 200, 1001, "未绑定")
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
		tx.Rollback()
		fail(c, 200, 1001, "未绑定")
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
	if affected, _ := result.RowsAffected(); affected != 1 {
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
		 WHERE status=1 AND (user_a_id=? OR user_b_id=?) LIMIT 1`, uid, uid).Scan(
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
	// 对方槽位为空 = 仅有挂起邀请、尚未真正双人绑定，视为未绑定（/pair/status 返回 bound:false）。
	if partnerID == 0 {
		return nil, errPairUnbound
	}
	partner, err := userFrom(queryer, partnerID)
	if err != nil {
		return nil, err
	}
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
		fail(c, 200, 1001, "未绑定")
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
		fail(c, 200, 1001, "未绑定")
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
	if err != nil || t == nil || t.PairID != pair.ID {
		return nil, false
	}
	return t, true
}

func handleUpdateTodo(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
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
	todo, _ := st.GetTodo(id)
	hub.Notify(pair, currentUID(c), WsMessage{Type: MsgTodoNew, Data: todo})
	ok(c, todo)
}

func handleCompleteTodo(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
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
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	if _, owned := getOwnedTodo(pair, id); !owned {
		fail(c, 403, 1017, "无权操作该待办")
		return
	}
	st.DeleteTodo(id)
	ok(c, gin.H{"deleted": id})
}

// ================= 日记 =================

func handleCreateDiary(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	var req struct {
		Title   string   `json:"title" binding:"required"`
		Content string   `json:"content" binding:"required"`
		Date    string   `json:"date" binding:"required"`
		Images  []string `json:"images"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	d, err := st.CreateDiary(pair.ID, uid, req.Title, req.Content, req.Date)
	if err != nil {
		fail(c, 500, 1010, "创建失败")
		return
	}
	if len(req.Images) > 0 {
		st.AddDiaryImages(d.ID, req.Images)
		d.Images = req.Images
	}
	u, _ := st.GetUserByID(uid)
	d.AuthorName = u.Nickname
	hub.Notify(pair, uid, WsMessage{Type: MsgDiaryNew, Data: d})
	ok(c, d)
}

func handleListDiaries(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	date := c.Query("date")
	diaries, err := st.ListDiaries(pair.ID, date)
	if err != nil {
		fail(c, 500, 1010, "查询失败")
		return
	}
	ok(c, diaries)
}

func handleUpdateDiary(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	if pid, err := st.DiaryPairID(id); err != nil || pid != pair.ID {
		fail(c, 403, 1017, "无权操作该日记")
		return
	}
	var req struct {
		Title   *string `json:"title"`
		Content *string `json:"content"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, 400, 1002, "参数错误")
		return
	}
	if err := st.UpdateDiary(id, req.Title, req.Content); err != nil {
		fail(c, 500, 1010, "更新失败")
		return
	}
	ok(c, gin.H{"updated": id})
}

func handleDeleteDiary(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	if pid, err := st.DiaryPairID(id); err != nil || pid != pair.ID {
		fail(c, 403, 1017, "无权操作该日记")
		return
	}
	st.DeleteDiary(id)
	ok(c, gin.H{"deleted": id})
}

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
	st.DB.Exec(`DELETE FROM push_token WHERE user_id=?`, uid)
	ok(c, gin.H{"unregistered": true})
}

// ================= 状态历史 =================

func handleHistoryTimeline(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	date := c.Query("date")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	if limit < 1 || limit > 200 {
		limit = 50
	}
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	list, err := st.HistoryTimeline(pair.ID, uid, date, limit, offset)
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
			Locked: h.IsLocked, ForegroundApp: h.ForegroundApp, SSID: h.SSID,
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
	uid := currentUID(c)
	date := c.Query("date")
	if date == "" {
		date = time.Now().Format("2006-01-02")
	}
	list, err := st.BatteryCurve(pair.ID, uid, date)
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
// 存储：uploads/diary/{pairId}/{uuid}.jpg
// Go 自托管静态服务 /uploads/；URL 带不可猜测 uuid，纯自用场景免鉴权。

func handleUploadDiaryImage(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	file, err := c.FormFile("file")
	if err != nil {
		fail(c, 400, 1002, "缺少文件字段 file")
		return
	}
	// 限 10MB，仅图片
	if file.Size > 10*1024*1024 {
		fail(c, 400, 1002, "图片不能超过 10MB")
		return
	}
	ext := strings.ToLower(filepath.Ext(file.Filename))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".webp", ".gif":
	default:
		fail(c, 400, 1002, "仅支持 jpg/png/webp/gif")
		return
	}
	dir := filepath.Join(uploadDir, "diary", fmt.Sprintf("%d", pair.ID))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fail(c, 500, 1010, "存储目录创建失败")
		return
	}
	filename := fmt.Sprintf("%s%s", randomCode(24), ext)
	dst := filepath.Join(dir, filename)
	if err := c.SaveUploadedFile(file, dst); err != nil {
		fail(c, 500, 1010, "保存失败")
		return
	}
	url := newStorage().PublicURL(fmt.Sprintf("diary/%d/%s", pair.ID, filename))
	ok(c, gin.H{"url": url})
}

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
