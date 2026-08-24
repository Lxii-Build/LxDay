package main

import (
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
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

// BindPair 用邀请码把 uid 填入 pair 空位。
// code 改为字符串：邀请码已从 6 位纯数字升级为 8 位混合字符，不再能用 int64 承载。
func (s *Store) BindPair(code string, uid int64) (int64, error) {
	var pairID int64
	err := s.DB.QueryRow(
		`SELECT id FROM pair WHERE invite_code=? AND status=1 LIMIT 1`, code).Scan(&pairID)
	if err != nil {
		return 0, errors.New("邀请码无效或已失效")
	}
	// 判断是 A 还是 B。
	//
	// **这里的 Scan 错误绝不能忽略**：忽略了 userA/userB 就保持零值，于是既跳过
	// 下面「不能和自己绑定」的校验，又会走进 `userA == 0` 那条分支，
	// 用 UPDATE 把已存在的 user_a_id **覆盖掉** —— 直接冲掉别人的情侣关系。
	// 这不是展示不一致，是不可逆的数据破坏。
	var userA, userB int64
	if err := s.DB.QueryRow(
		`SELECT user_a_id,user_b_id FROM pair WHERE id=?`, pairID,
	).Scan(&userA, &userB); err != nil {
		return 0, fmt.Errorf("读取绑定关系失败: %w", err)
	}
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

// ---------- token 撤销（token_ver） ----------

// UserAuthState 读用户当前 status 与 token_ver，供每次鉴权实时比对。
func (s *Store) UserAuthState(id int64) (status int, tokenVer int64, err error) {
	err = s.DB.QueryRow("SELECT status,token_ver FROM `user` WHERE id=?", id).Scan(&status, &tokenVer)
	return
}

// UserTokenVer 读用户当前 token_ver，签发新 token 时写进 claims。
func (s *Store) UserTokenVer(id int64) (int64, error) {
	var v int64
	err := s.DB.QueryRow("SELECT token_ver FROM `user` WHERE id=?", id).Scan(&v)
	return v, err
}

// BumpUserTokenVer 令该用户所有已签发 token 立即失效（改密、封禁、删号时调用）。
func (s *Store) BumpUserTokenVer(id int64) error {
	_, err := s.DB.Exec("UPDATE `user` SET token_ver=token_ver+1 WHERE id=?", id)
	return err
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
		if err := rows.Scan(&t.ID, &t.PairID, &t.CreatorID, &t.AssigneeID, &t.Title, &t.Note, &t.RemindAt, &t.RemindType, &t.RepeatType, &t.Weekdays, &t.RemindEnabled, &t.Status, &t.CompletedAt); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan todo failed", "err", err)
			continue
		}
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
		if err := rows.Scan(&t.ID, &t.PairID, &t.CreatorID, &t.AssigneeID, &t.Title, &t.Note, &t.RemindAt, &t.RemindType, &t.RepeatType, &t.Weekdays, &t.RemindEnabled, &t.Status, &t.CompletedAt); err != nil {
			// 坏行跳过并留痕：忽略 Scan 错误会让 NULL 列静默变成零值。
			slog.Error("scan todo failed", "err", err)
			continue
		}
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

// parseDayRange 把 YYYY-MM-DD 解析为「本地时区」的当日 [00:00, 次日00:00)。
//
// 必须用 ParseInLocation 而不是 Parse：Parse 得到的是 **UTC 零点**，
// 而写入侧用的是 time.Now().Truncate(5*time.Minute)（服务器本地时区）。
// 容器 TZ=Asia/Shanghai(+8) 时，"今天"的查询窗口实际是本地 08:00~次日 08:00，
// 于是凌晨 0~8 点的记录被算进"昨天"，用户看到当天早上一片空白。
func parseDayRange(date string) (time.Time, time.Time, bool) {
	start, err := time.ParseInLocation("2006-01-02", date, time.Local)
	if err != nil {
		return time.Time{}, time.Time{}, false
	}
	return start, start.AddDate(0, 0, 1), true
}

// HistoryTimeline 分页查询某用户历史时间线（按 ts 倒序）
//
// 三个可空列用 sql.NullString 接，并且**必须检查 rows.Scan 的返回值**：
// 忽略它会让出错的行以全零值进入结果集（Ts 变零时间 → ts=-62135596800000），
// 客户端按 ts 做 LazyColumn key 时就撞重复 key 而崩溃。这是 0821 的崩溃根因。
func (s *Store) HistoryTimeline(pairID, uid int64, date string, limit, offset int) ([]StatusHistory, error) {
	q := `SELECT pair_id,user_id,battery,charging,screen_on,locked,foreground_pkg,foreground_name,ssid,network,ts
		  FROM status_history WHERE pair_id=? AND user_id=?`
	args := []interface{}{pairID, uid}
	if date != "" {
		start, end, okD := parseDayRange(date)
		if !okD {
			return nil, fmt.Errorf("invalid date %q", date)
		}
		q += " AND ts>=? AND ts<?"
		args = append(args, start, end)
	}
	q += " ORDER BY ts DESC LIMIT ? OFFSET ?"
	args = append(args, limit, offset)
	rows, err := s.DB.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []StatusHistory{}
	for rows.Next() {
		var h StatusHistory
		if err := rows.Scan(&h.PairID, &h.UserID, &h.BatteryLevel, &h.IsCharging, &h.ScreenOn, &h.IsLocked,
			&h.ForegroundPkg, &h.ForegroundApp, &h.SSID, &h.NetworkType, &h.Ts); err != nil {
			// 单行坏数据不该让整页失败，但必须记下来——静默跳过等于又埋一颗雷。
			slog.Error("scan status_history row failed", "err", err, "pair_id", pairID, "user_id", uid)
			continue
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// BatteryCurve 某日 24h 电量序列（按 ts 升序）
func (s *Store) BatteryCurve(pairID, uid int64, date string) ([]StatusHistory, error) {
	start, end, okD := parseDayRange(date)
	if !okD {
		return nil, fmt.Errorf("invalid date %q", date)
	}
	rows, err := s.DB.Query(
		`SELECT pair_id,user_id,battery,charging,ts FROM status_history
		 WHERE pair_id=? AND user_id=? AND ts>=? AND ts<? ORDER BY ts ASC`,
		pairID, uid, start, end)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []StatusHistory{}
	for rows.Next() {
		var h StatusHistory
		if err := rows.Scan(&h.PairID, &h.UserID, &h.BatteryLevel, &h.IsCharging, &h.Ts); err != nil {
			slog.Error("scan battery_curve row failed", "err", err, "pair_id", pairID, "user_id", uid)
			continue
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// CleanupStatusHistory 删除 N 天前的状态历史。days<=0 表示永久保留（不清理）。
//
// 必须用 SQLite 的 datetime() 而非 MySQL 的 `NOW() - INTERVAL ? DAY`——
// 0820 那轮 netlog 就是写成 MySQL 语法导致清理永久静默失败、磁盘只涨不跌。
func (s *Store) CleanupStatusHistory(days int) (int64, error) {
	if days <= 0 {
		return 0, nil // 永久保留
	}
	res, err := s.DB.Exec(
		`DELETE FROM status_history WHERE ts < datetime('now', ?)`, negDaysModifier(days))
	if err != nil {
		slog.Error("cleanup status_history failed", "err", err, "days", days)
		return 0, err
	}
	n, _ := res.RowsAffected()
	return n, nil
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
