package main

import (
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// ================= 存储层 =================

type Store struct {
	DB  *sql.DB
	mem *memStore
}

// ---------- Redis Key 常量 ----------
func keyPair(pairID int64) string     { return fmt.Sprintf("pair:%d", pairID) }
func keyUserID(code string) string    { return fmt.Sprintf("invite:%s", code) }
func keyOnline(uid int64) string      { return fmt.Sprintf("online:user:%d", uid) }
func keyStatus(uid int64) string      { return fmt.Sprintf("status:user:%d", uid) }
func keyEventQ(uid int64) string      { return fmt.Sprintf("event:queue:user:%d", uid) }
func keyRingCool(pairID int64) string { return fmt.Sprintf("pair:ring:cooldown:%d", pairID) }

// keyInteractionCool 按互动类型独立的冷却键（comfort/calm 各自计数，不与响铃或彼此共用）。
func keyInteractionCool(pairID int64, kind string) string {
	return fmt.Sprintf("pair:interact:cooldown:%s:%d", kind, pairID)
}

// ---------- 用户 ----------

func (s *Store) CreateUser(username, email, nickname, hash string) (int64, error) {
	res, err := s.DB.Exec(
		"INSERT INTO `user`(username,email,nickname,password_hash) VALUES(?,?,?,?)",
		username, email, nickname, hash)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// GetUserByLogin 按用户名或邮箱查用户（登录用），带 password_hash。
func (s *Store) GetUserByLogin(account string) (*User, error) {
	u := &User{}
	err := s.DB.QueryRow(
		"SELECT id,nickname,avatar_url,avatar_thumbnail_url,password_hash FROM `user` WHERE username=? OR email=? LIMIT 1",
		account, account).
		Scan(&u.ID, &u.Nickname, &u.AvatarURL, &u.AvatarThumbnailURL, &u.PasswordHash)
	return u, err
}

// GetUserProfile 读扩展个人资料（本人）。
func (s *Store) GetUserProfile(id int64) (*UserProfile, error) {
	p := &UserProfile{}
	var birthday sql.NullTime
	err := s.DB.QueryRow(
		"SELECT id,username,email,nickname,avatar_url,avatar_thumbnail_url,gender,signature,birthday FROM `user` WHERE id=?", id).
		Scan(&p.ID, &p.Username, &p.Email, &p.Nickname, &p.AvatarURL, &p.AvatarThumbnailURL, &p.Gender, &p.Signature, &birthday)
	if err != nil {
		return nil, err
	}
	if birthday.Valid {
		formatted := birthday.Time.Format("2006-01-02")
		p.Birthday = &formatted
	}
	return p, nil
}

// UpdateUserProfile 更新昵称/性别/简介/生日；signature、birthday 传 nil 即置空。
func (s *Store) UpdateUserProfile(id int64, nickname string, gender int, signature, birthday *string) error {
	_, err := s.DB.Exec(
		"UPDATE `user` SET nickname=?, gender=?, signature=?, birthday=? WHERE id=?",
		nickname, gender, signature, birthday, id)
	return err
}

// ---------- 后台/系统设置（键值） ----------

func (s *Store) GetSetting(key string) (string, error) {
	var v sql.NullString
	err := s.DB.QueryRow("SELECT v FROM app_setting WHERE k=?", key).Scan(&v)
	if err != nil {
		return "", err
	}
	return v.String, nil
}

func (s *Store) SetSetting(key, val string) error {
	_, err := s.DB.Exec(
		"INSERT INTO app_setting(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
		key, val)
	return err
}

func (s *Store) GetUserByNickname(nickname string) (*User, error) {
	u := &User{}
	err := s.DB.QueryRow("SELECT id,nickname,avatar_url,avatar_thumbnail_url,password_hash FROM `user` WHERE nickname=?", nickname).
		Scan(&u.ID, &u.Nickname, &u.AvatarURL, &u.AvatarThumbnailURL, &u.PasswordHash)
	return u, err
}

func (s *Store) GetUserByID(id int64) (*User, error) {
	u := &User{}
	err := s.DB.QueryRow("SELECT id,nickname,avatar_url,avatar_thumbnail_url FROM `user` WHERE id=?", id).
		Scan(&u.ID, &u.Nickname, &u.AvatarURL, &u.AvatarThumbnailURL)
	return u, err
}

func (s *Store) UpdateNickname(id int64, nickname string) error {
	_, err := s.DB.Exec("UPDATE `user` SET nickname=? WHERE id=?", nickname, id)
	return err
}

func (s *Store) UpdateAvatar(id int64, avatarURL, thumbnailURL string) error {
	_, err := s.DB.Exec(
		"UPDATE `user` SET avatar_url=?, avatar_thumbnail_url=? WHERE id=?",
		avatarURL, thumbnailURL, id)
	return err
}

// ---------- 绑定 ----------

func (s *Store) GetPairByUserID(uid int64) (*Pair, error) {
	p := &Pair{}
	err := s.DB.QueryRow(
		`SELECT id,user_a_id,user_b_id,invite_code,anniversary_date FROM pair
		 WHERE status=1 AND (user_a_id=? OR user_b_id=?)
		 ORDER BY (user_a_id>0 AND user_b_id>0) DESC, id DESC LIMIT 1`, uid, uid).Scan(
		&p.ID, &p.UserAID, &p.UserBID, &p.InviteCode, &p.AnniversaryDate)
	return p, err
}

func (s *Store) UpdateAnniversary(pairID int64, anniversary time.Time) error {
	_, err := s.DB.Exec("UPDATE pair SET anniversary_date=? WHERE id=?", anniversary, pairID)
	return err
}

func (s *Store) PartnerID(p *Pair, me int64) int64 {
	if p.UserAID == me {
		return p.UserBID
	}
	return p.UserAID
}

func (s *Store) BindPair(code, uid int64) (int64, error) {
	var pairID int64
	err := s.DB.QueryRow(
		`SELECT id FROM pair WHERE invite_code=? AND status=1 LIMIT 1`,
		formatInviteCode(code)).Scan(&pairID)
	if err != nil {
		return 0, errors.New("邀请码无效或已失效")
	}
	// 判断是 A 还是 B
	var userA, userB int64
	s.DB.QueryRow(`SELECT user_a_id,user_b_id FROM pair WHERE id=?`, pairID).Scan(&userA, &userB)
	if userA == uid || userB == uid {
		return 0, errors.New("不能和自己绑定")
	}
	if userB != 0 && userA != 0 {
		return 0, errors.New("该绑定关系已满，仅支持双人")
	}
	// 将当前用户填到空位（创建者为 A，绑定者为 B）
	if userA == 0 {
		_, err = s.DB.Exec(`UPDATE pair SET user_a_id=? WHERE id=?`, uid, pairID)
	} else {
		_, err = s.DB.Exec(`UPDATE pair SET user_b_id=? WHERE id=?`, uid, pairID)
	}
	if err != nil {
		return 0, err
	}
	// 作废绑定方此前自己创建的挂起邀请：双方都生成过码时，一旦一方用了对方的码绑定，
	// 自己那张挂起的码即失效（另一张码仍可被再次使用/或已被消费）。
	s.DB.Exec(
		`UPDATE pair SET status=0 WHERE id!=? AND status=1 AND ((user_a_id=? AND user_b_id=0) OR (user_b_id=? AND user_a_id=0))`,
		pairID, uid, uid)
	return pairID, nil
}

func formatInviteCode(v int64) string {
	// 存库用 6 位固定长度，补零
	return fmt.Sprintf("%06d", v)
}

// ---------- 待办 ----------

func (s *Store) CreateTodo(pairID, creatorID, assigneeID int64, title, note string, remindAt *time.Time, remindType, repeatType, weekdays int, remindEnabled bool) (*Todo, error) {
	t := &Todo{PairID: pairID, CreatorID: creatorID, AssigneeID: assigneeID, Title: title, Note: note, RemindAt: remindAt, RemindType: remindType, RepeatType: repeatType, Weekdays: weekdays, RemindEnabled: remindEnabled}
	res, err := s.DB.Exec(
		`INSERT INTO todo(pair_id,creator_id,assignee_id,title,note,remind_at,remind_type,repeat_type,weekdays,remind_enabled,status)
		 VALUES(?,?,?,?,?,?,?,?,?,?,0)`, pairID, creatorID, assigneeID, title, note, remindAt, remindType, repeatType, weekdays, remindEnabled)
	if err != nil {
		return nil, err
	}
	t.ID, _ = res.LastInsertId()
	return t, nil
}

func (s *Store) ListTodos(pairID int64, status int) ([]Todo, error) {
	rows, err := s.DB.Query(
		`SELECT id,pair_id,creator_id,assignee_id,title,note,remind_at,remind_type,repeat_type,weekdays,remind_enabled,status,completed_at
		 FROM todo WHERE pair_id=? AND status=? ORDER BY remind_at IS NULL, remind_at ASC, id DESC`,
		pairID, status)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Todo
	for rows.Next() {
		var t Todo
		rows.Scan(&t.ID, &t.PairID, &t.CreatorID, &t.AssigneeID, &t.Title, &t.Note, &t.RemindAt, &t.RemindType, &t.RepeatType, &t.Weekdays, &t.RemindEnabled, &t.Status, &t.CompletedAt)
		out = append(out, t)
	}
	return out, nil
}

func (s *Store) UpdateTodo(id int64, title, note *string, remindAt *time.Time, assigneeID *int64, remindType, repeatType, weekdays *int, remindEnabled *bool) error {
	sets, args := []string{}, []interface{}{}
	if title != nil {
		sets = append(sets, "title=?")
		args = append(args, *title)
	}
	if note != nil {
		sets = append(sets, "note=?")
		args = append(args, *note)
	}
	if remindAt != nil {
		sets = append(sets, "remind_at=?")
		args = append(args, *remindAt)
	}
	if assigneeID != nil {
		sets = append(sets, "assignee_id=?")
		args = append(args, *assigneeID)
	}
	if remindType != nil {
		sets = append(sets, "remind_type=?")
		args = append(args, *remindType)
	}
	if repeatType != nil {
		sets = append(sets, "repeat_type=?")
		args = append(args, *repeatType)
	}
	if weekdays != nil {
		sets = append(sets, "weekdays=?")
		args = append(args, *weekdays)
	}
	if remindEnabled != nil {
		sets = append(sets, "remind_enabled=?")
		args = append(args, *remindEnabled)
	}
	if len(sets) == 0 {
		return nil
	}
	args = append(args, id)
	_, err := s.DB.Exec("UPDATE todo SET "+strings.Join(sets, ",")+" WHERE id=?", args...)
	return err
}

func (s *Store) CompleteTodo(id, uid int64) (*Todo, error) {
	now := time.Now()
	_, err := s.DB.Exec(
		`UPDATE todo SET status=1, completed_at=? WHERE id=? AND status=0`, now, id)
	if err != nil {
		return nil, err
	}
	return s.GetTodo(id)
}

func (s *Store) DeleteTodo(id int64) error {
	_, err := s.DB.Exec(`UPDATE todo SET status=2 WHERE id=?`, id)
	return err
}

func (s *Store) GetTodo(id int64) (*Todo, error) {
	t := &Todo{}
	err := s.DB.QueryRow(
		`SELECT id,pair_id,creator_id,assignee_id,title,note,remind_at,remind_type,repeat_type,weekdays,remind_enabled,status,completed_at
		 FROM todo WHERE id=?`, id).
		Scan(&t.ID, &t.PairID, &t.CreatorID, &t.AssigneeID, &t.Title, &t.Note, &t.RemindAt, &t.RemindType, &t.RepeatType, &t.Weekdays, &t.RemindEnabled, &t.Status, &t.CompletedAt)
	return t, err
}

// ---------- 待办到点提醒扫描 ----------

// DueTodos 返回「到点且未完成」的待办（remind_at <= now 且 status=0 且 remind_enabled=1），用于服务端定时扫描推送
func (s *Store) DueTodos(now time.Time) ([]Todo, error) {
	rows, err := s.DB.Query(
		`SELECT id,pair_id,creator_id,assignee_id,title,note,remind_at,remind_type,repeat_type,weekdays,remind_enabled,status,completed_at
		 FROM todo WHERE status=0 AND remind_enabled=1 AND remind_at IS NOT NULL AND remind_at<=?`, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Todo
	for rows.Next() {
		var t Todo
		rows.Scan(&t.ID, &t.PairID, &t.CreatorID, &t.AssigneeID, &t.Title, &t.Note, &t.RemindAt, &t.RemindType, &t.RepeatType, &t.Weekdays, &t.RemindEnabled, &t.Status, &t.CompletedAt)
		out = append(out, t)
	}
	return out, nil
}

// AdvanceTodoRemind 更新待办的下次提醒时间（循环提醒推进；next 为 nil 表示置空不再提醒）
func (s *Store) AdvanceTodoRemind(id int64, next *time.Time) error {
	_, err := s.DB.Exec(`UPDATE todo SET remind_at=? WHERE id=?`, next, id)
	return err
}

// ---------- 日记 ----------

func (s *Store) CreateDiary(pairID, authorID int64, title, content, date string) (*Diary, error) {
	d := &Diary{PairID: pairID, AuthorID: authorID, Title: title, Content: content, DiaryDate: date}
	res, err := s.DB.Exec(
		`INSERT INTO diary(pair_id,author_id,title,content,diary_date) VALUES(?,?,?,?,?)`,
		pairID, authorID, title, content, date)
	if err != nil {
		return nil, err
	}
	d.ID, _ = res.LastInsertId()
	return d, nil
}

func (s *Store) ListDiaries(pairID int64, date string) ([]Diary, error) {
	q := `SELECT d.id,d.pair_id,d.author_id,u.nickname,d.title,d.content,d.diary_date,d.created_at,d.updated_at
		  FROM diary d JOIN user u ON u.id=d.author_id WHERE d.pair_id=?`
	var args []interface{} = []interface{}{pairID}
	if date != "" {
		q += " AND d.diary_date=?"
		args = append(args, date)
	}
	q += " ORDER BY d.diary_date DESC, d.id DESC"
	rows, err := s.DB.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Diary
	for rows.Next() {
		var d Diary
		rows.Scan(&d.ID, &d.PairID, &d.AuthorID, &d.AuthorName, &d.Title, &d.Content,
			&d.DiaryDate, &d.CreatedAt, &d.UpdatedAt)
		d.Images, _ = s.DiaryImages(d.ID)
		out = append(out, d)
	}
	return out, nil
}

func (s *Store) DiaryImages(id int64) ([]string, error) {
	rows, err := s.DB.Query(`SELECT url FROM diary_image WHERE diary_id=? ORDER BY sort_no`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var u string
		rows.Scan(&u)
		out = append(out, u)
	}
	return out, nil
}

func (s *Store) AddDiaryImages(id int64, urls []string) error {
	for i, u := range urls {
		_, err := s.DB.Exec(`INSERT INTO diary_image(diary_id,url,sort_no) VALUES(?,?,?)`, id, u, i)
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) UpdateDiary(id int64, title, content *string) error {
	sets, args := []string{}, []interface{}{}
	if title != nil {
		sets = append(sets, "title=?")
		args = append(args, *title)
	}
	if content != nil {
		sets = append(sets, "content=?")
		args = append(args, *content)
	}
	if len(sets) == 0 {
		return nil
	}
	args = append(args, id)
	_, err := s.DB.Exec("UPDATE diary SET "+strings.Join(sets, ",")+" WHERE id=?", args...)
	return err
}

func (s *Store) DeleteDiary(id int64) error {
	_, err := s.DB.Exec(`DELETE FROM diary WHERE id=?`, id)
	if err != nil {
		return err
	}
	_, err = s.DB.Exec(`DELETE FROM diary_image WHERE diary_id=?`, id)
	return err
}

// DiaryPairID 返回日记所属 pair_id，用于越权校验。
func (s *Store) DiaryPairID(id int64) (int64, error) {
	var pid int64
	err := s.DB.QueryRow(`SELECT pair_id FROM diary WHERE id=?`, id).Scan(&pid)
	return pid, err
}

// ---------- 状态（Redis 为主，落库兜底） ----------

// SaveStatus 缓存伴侣最新状态（内存态，单实例）。
func (s *Store) SaveStatus(u *DeviceStatus) error {
	s.mem.saveStatus(u.UserID, u)
	return nil
}

func (s *Store) GetStatus(uid int64) (*DeviceStatus, error) {
	return s.mem.getStatus(uid), nil
}

// ---------- 状态历史（5 分钟聚合，永久保留） ----------

// InsertStatusHistory 写入一条历史记录（幂等：同 pair+user+ts 去重，供 5min 上报与 cron 兜底共用）
func (s *Store) InsertStatusHistory(p *Pair, uid int64, st *DeviceStatus, ts time.Time) error {
	_, err := s.DB.Exec(
		`INSERT OR IGNORE INTO status_history
		 (pair_id,user_id,battery,charging,screen_on,locked,foreground_pkg,foreground_name,ssid,network,ts)
		 VALUES(?,?,?,?,?,?,?,?,?,?,?)`,
		p.ID, uid, st.BatteryLevel, st.IsCharging, st.ScreenOn, st.IsLocked,
		pkgOf(st), nameOf(st), st.SSID, st.NetworkType, ts)
	return err
}

func pkgOf(st *DeviceStatus) interface{} {
	if st.ForegroundApp != nil {
		return st.ForegroundApp.Pkg
	}
	return nil
}
func nameOf(st *DeviceStatus) interface{} {
	if st.ForegroundApp != nil {
		return st.ForegroundApp.Name
	}
	return nil
}

// HistoryTimeline 分页查询某用户历史时间线（按 ts 倒序）
func (s *Store) HistoryTimeline(pairID, uid int64, date string, limit, offset int) ([]StatusHistory, error) {
	q := `SELECT pair_id,user_id,battery,charging,screen_on,locked,foreground_pkg,foreground_name,ssid,network,ts
		  FROM status_history WHERE pair_id=? AND user_id=?`
	args := []interface{}{pairID, uid}
	if date != "" {
		q += " AND ts>=? AND ts<?"
		start, _ := time.Parse("2006-01-02", date)
		args = append(args, start, start.AddDate(0, 0, 1))
	}
	q += " ORDER BY ts DESC LIMIT ? OFFSET ?"
	args = append(args, limit, offset)
	rows, err := s.DB.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []StatusHistory
	for rows.Next() {
		var h StatusHistory
		rows.Scan(&h.PairID, &h.UserID, &h.BatteryLevel, &h.IsCharging, &h.ScreenOn, &h.IsLocked,
			&h.ForegroundPkg, &h.ForegroundApp, &h.SSID, &h.NetworkType, &h.Ts)
		out = append(out, h)
	}
	return out, nil
}

// BatteryCurve 某日 24h 电量序列（按 ts 升序）
func (s *Store) BatteryCurve(pairID, uid int64, date string) ([]StatusHistory, error) {
	start, _ := time.Parse("2006-01-02", date)
	rows, err := s.DB.Query(
		`SELECT pair_id,user_id,battery,charging,ts FROM status_history
		 WHERE pair_id=? AND user_id=? AND ts>=? AND ts<? ORDER BY ts ASC`,
		pairID, uid, start, start.AddDate(0, 0, 1))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []StatusHistory
	for rows.Next() {
		var h StatusHistory
		rows.Scan(&h.PairID, &h.UserID, &h.BatteryLevel, &h.IsCharging, &h.Ts)
		out = append(out, h)
	}
	return out, nil
}

// ---------- 离线补偿 ----------

func (s *Store) PushEventQ(uid int64, msg string) error {
	s.mem.pushEvent(uid, msg)
	return nil
}

func (s *Store) PopEventQ(uid int64) []string {
	return s.mem.popEvents(uid)
}

func (s *Store) SetOnline(uid int64, online bool) {
	s.mem.setOnline(uid, online, 60*time.Second)
}

func (s *Store) IsOnline(uid int64) bool {
	return s.mem.isOnline(uid)
}

// ---------- 响铃冷却 ----------

func (s *Store) RingCooldown(pairID int64) bool {
	// 限频窗口与次数读配置（ring_cooldown_seconds / ring_cooldown_limit），留空回退 600s / 3 次。
	window := 600
	limit := 3
	if cfg != nil {
		if cfg.App.RingCooldownSeconds > 0 {
			window = cfg.App.RingCooldownSeconds
		}
		if cfg.App.RingCooldownLimit > 0 {
			limit = cfg.App.RingCooldownLimit
		}
	}
	cnt := s.mem.incr(keyRingCool(pairID), time.Duration(window)*time.Second)
	return cnt <= int64(limit)
}

// interactionCooldownWindow/Limit 为安抚(comfort)/冷静(calm)等轻量互动的默认限频：
// 7 秒内至多 1 次，与客户端「7 秒进行中态」呼应；相比响铃(默认 600s/3 次)更短，避免误伤正常连点，
// 又能挡住刷屏骚扰。独立于响铃计数，且 comfort、calm 各自分桶。
const (
	interactionCooldownWindow = 7 // 秒
	interactionCooldownLimit  = 1
)

// InteractionCooldown 按类型独立限频：窗口内计数 <= 上限返回 true(放行)，超频返回 false(丢弃)。
// kind 用互动消息类型（如 comfort_request / calm_request），保证各类型独立分桶。
func (s *Store) InteractionCooldown(pairID int64, kind string) bool {
	cnt := s.mem.incr(keyInteractionCool(pairID, kind), interactionCooldownWindow*time.Second)
	return cnt <= int64(interactionCooldownLimit)
}
