package main

import (
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// ================= 相册存储层 =================

// photoCols 统一列序，GetPhoto / ListAlbumPhotos / on-this-day 共用同一个 scanPhoto。
const photoCols = `id,album_id,pair_id,uploader_id,url,thumb_url,preview_path,width,height,size_bytes,mime,taken_at,caption,status,created_at,deleted_at`

// photoColumns 是 photoCols 的别名，供 album_purge.go 使用（语义更直白）。
const photoColumns = photoCols

// scanPhotoRows 供 *sql.Rows 直接复用 scanPhoto（两者都满足 rowScanner）。
func scanPhotoRows(rs rowScanner) (*Photo, error) { return scanPhoto(rs) }

// negDaysModifier 生成 SQLite datetime() 的「-N days」修饰串。
// datetime modifier 不支持占位符，故 days 必须是受信整数，调用方先做范围收敛。
func negDaysModifier(days int) string {
	if days < 1 {
		days = 1
	}
	if days > 3650 {
		days = 3650
	}
	return fmt.Sprintf("-%d days", days)
}

type rowScanner interface {
	Scan(dest ...interface{}) error
}

// scanPhoto 读一行照片并把库里的真实磁盘路径换成鉴权代理 URL。
// 真实路径只留在 diskPath/diskThumb（不出 JSON），见 Photo 注释。
//
// 时间列扫成字符串再自行解析，而不是直接扫进 time.Time / sql.NullTime：
// SQLite 是动态类型，DATETIME 列里既可能是 CURRENT_TIMESTAMP 写的 "2026-08-20 07:43:02"，
// 也可能是驱动序列化 time.Time 得到的 RFC3339（带 T 与时区偏移）。
// 依赖驱动帮我们认出所有形态，等于把「整个相册能否读出来」押在一个实现细节上——
// 一旦不认，scanPhoto 直接报错，列表/详情/这一天全部 500。自己解析则形态无关。
func scanPhoto(rs rowScanner) (*Photo, error) {
	p := &Photo{}
	var thumb, preview, mime, caption, taken, created, deleted sql.NullString
	err := rs.Scan(&p.ID, &p.AlbumID, &p.PairID, &p.UploaderID, &p.diskPath, &thumb, &preview,
		&p.Width, &p.Height, &p.SizeBytes, &mime, &taken, &caption, &p.Status, &created, &deleted)
	if err != nil {
		return nil, err
	}
	p.diskThumb = thumb.String
	p.diskPreview = preview.String
	p.Mime = mime.String
	p.Caption = caption.String
	if taken.Valid {
		// taken_at 是本地墙钟（EXIF 无时区）。
		if t, okT := parseSQLiteLocalTime(taken.String); okT {
			p.TakenAt = &t
		}
	}
	if created.Valid {
		// created_at 由 CURRENT_TIMESTAMP 写入，是 UTC。
		if t, okT := parseSQLiteUTCTime(created.String); okT {
			p.CreatedAt = t
		}
	}
	if deleted.Valid {
		if t, okT := parseSQLiteUTCTime(deleted.String); okT {
			p.deletedAt = sql.NullTime{Time: t, Valid: true}
		}
	}
	p.URL = mediaURL(p.ID)
	p.ThumbURL = mediaThumbURL(p.ID)
	// 回收站只能展示缩略图；预览/原图地址即使出现在响应里，服务端也会拒绝，
	// 这里直接把预览字段收敛到缩略图，避免客户端先发起一条注定失败的私密请求。
	if p.Status == 2 {
		p.PreviewURL = p.ThumbURL
	} else if p.diskPreview != "" {
		p.PreviewURL = mediaPreviewURL(p.ID)
	} else {
		// 预览图缺失（0821 之前上传的历史照片）时回退原图，客户端无需分支处理。
		p.PreviewURL = p.URL
	}
	return p, nil
}

// sqliteTimeLayouts 覆盖本库 DATETIME 列可能出现的全部文本形态。
// 前两个是 CURRENT_TIMESTAMP 与我们自己写入的格式；其余是驱动序列化 time.Time 的常见形态。
var sqliteTimeLayouts = []string{
	"2006-01-02 15:04:05",
	"2006-01-02 15:04:05.999999999",
	"2006-01-02T15:04:05Z07:00",
	"2006-01-02T15:04:05.999999999Z07:00",
	"2006-01-02 15:04:05-07:00",
	"2006-01-02 15:04:05.999999999-07:00",
	"2006-01-02 15:04:05 -0700 MST",
	"2006-01-02",
}

// 两个入口的区别是「无时区信息时按哪个时区解释」，这不是风格问题而是正确性问题：
//
//   - created_at / updated_at 由 SQLite 的 CURRENT_TIMESTAMP 写入，**其值是 UTC**
//     （已用本机 sqlite3 核实：存 08:04:51 而本地时间为 16:04:51，差 8 小时）。
//     按本地解释会让每个返回给客户端的时间整体偏移一个时区差。
//   - taken_at 由 takenAtValue 按**本地墙钟**格式化写入（EXIF 无时区，语义是拍摄地当天），
//     按 UTC 解释同样会偏移。
//
// 故两者必须分开，不能共用一个函数。
func parseSQLiteLocalTime(s string) (time.Time, bool) {
	return parseSQLiteTimeIn(s, time.Local)
}

func parseSQLiteUTCTime(s string) (time.Time, bool) {
	t, okT := parseSQLiteTimeIn(s, time.UTC)
	if !okT {
		return time.Time{}, false
	}
	// 统一转本地再返回：客户端按本地时区展示，JSON 里也带上正确的偏移。
	return t.In(time.Local), true
}

// parseSQLiteTimeIn 按候选布局逐个尝试解析；布局自带时区的形态以其自身时区为准（loc 被忽略）。
func parseSQLiteTimeIn(s string, loc *time.Location) (time.Time, bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}, false
	}
	for _, layout := range sqliteTimeLayouts {
		if t, err := time.ParseInLocation(layout, s, loc); err == nil {
			return t, true
		}
	}
	return time.Time{}, false
}

// sqliteDateTimeLayout 与 SQLite 的 CURRENT_TIMESTAMP 文本格式一致。
//
// taken_at 显式按此格式写字符串，而不是直接绑 time.Time：驱动把 time.Time 序列化成
// 什么文本（带不带 T、带不带时区偏移）属实现细节，而「这一天」要靠 substr 取字面 MM-DD。
// 固定成与 created_at 同一格式，日期比较才与存储层实现无关。
const sqliteDateTimeLayout = "2006-01-02 15:04:05"

// takenAtValue 把拍摄时间转成入库值（nil 保持 NULL）。
func takenAtValue(t *time.Time) interface{} {
	if t == nil {
		return nil
	}
	return t.Format(sqliteDateTimeLayout)
}

// CreatePhoto 落库一张已处理完的照片，不带幂等键。
func (s *Store) CreatePhoto(pairID, uploaderID, albumID int64, url, thumbURL, previewURL string,
	width, height int, sizeBytes int64, mime string, takenAt *time.Time) (*Photo, error) {
	return s.CreatePhotoWithIdempotency(pairID, uploaderID, albumID, url, thumbURL, previewURL,
		width, height, sizeBytes, mime, takenAt, "")
}

// CreatePhotoWithIdempotency 落库一张已处理完的照片，并把客户端上传幂等键一起保存。
// url/thumbURL/previewURL 传的是私密媒体根目录下的相对路径（如 media/2026/08/21/x.jpg），
// 对外 URL 由 scanPhoto 换成 /media/<id> 形态，真实路径不出服务端。
func (s *Store) CreatePhotoWithIdempotency(pairID, uploaderID, albumID int64, url, thumbURL, previewURL string,
	width, height int, sizeBytes int64, mime string, takenAt *time.Time, idempotencyKey string) (*Photo, error) {
	res, err := s.DB.Exec(
		`INSERT INTO photo(album_id,pair_id,uploader_id,url,thumb_url,preview_path,width,height,size_bytes,mime,taken_at,status,upload_idempotency_key)
		 VALUES(?,?,?,?,?,?,?,?,?,?,?,1,?)`,
		albumID, pairID, uploaderID, url, thumbURL, nullIfEmpty(previewURL),
		width, height, sizeBytes, mime, takenAtValue(takenAt), nullIfEmpty(idempotencyKey))
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return s.GetPhoto(id)
}

func (s *Store) GetPhotoByUploadIdempotencyKey(uploaderID int64, key string) (*Photo, error) {
	var id int64
	if err := s.DB.QueryRow(
		`SELECT id FROM photo WHERE uploader_id=? AND upload_idempotency_key=? LIMIT 1`,
		uploaderID, key,
	).Scan(&id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return s.GetPhoto(id)
}

// nullIfEmpty 空串写 NULL，避免 preview_path 出现 "" 这种既非空也非有效路径的中间态。
func nullIfEmpty(s string) interface{} {
	if s == "" {
		return nil
	}
	return s
}

func (s *Store) GetPhoto(id int64) (*Photo, error) {
	return scanPhoto(s.DB.QueryRow(`SELECT `+photoCols+` FROM photo WHERE id=?`, id))
}

// ListAlbumPhotos 相册内分页，按拍摄时间倒序（无 EXIF 时退化用入库时间，避免 NULL 全部沉底）。
func (s *Store) ListAlbumPhotos(pairID, albumID int64, limit, offset int) ([]Photo, int, error) {
	var total int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM photo WHERE pair_id=? AND album_id=? AND status=1`,
		pairID, albumID).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		`SELECT `+photoCols+` FROM photo WHERE pair_id=? AND album_id=? AND status=1
		 ORDER BY COALESCE(taken_at, created_at) DESC, id DESC LIMIT ? OFFSET ?`,
		pairID, albumID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []Photo{}
	for rows.Next() {
		p, err := scanPhoto(rows)
		if err != nil {
			// 坏行跳过并留痕：某个 NULL/坏时间列不应让整页相册直接 500。
			slog.Error("scan album photo failed", "pair_id", pairID, "album_id", albumID, "err", err)
			continue
		}
		out = append(out, *p)
	}
	return out, total, rows.Err()
}

// ListRecycledPhotos 回收站（status=2），供客户端做「最近删除」恢复。
func (s *Store) ListRecycledPhotos(pairID int64, limit, offset int) ([]Photo, int, error) {
	var total int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM photo WHERE pair_id=? AND status=2`, pairID).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		`SELECT `+photoCols+` FROM photo WHERE pair_id=? AND status=2
		 ORDER BY id DESC LIMIT ? OFFSET ?`, pairID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []Photo{}
	for rows.Next() {
		p, err := scanPhoto(rows)
		if err != nil {
			// 坏行跳过并留痕：回收站仍应允许用户恢复其它照片。
			slog.Error("scan recycled photo failed", "pair_id", pairID, "err", err)
			continue
		}
		out = append(out, *p)
	}
	return out, total, rows.Err()
}

// SetPhotoStatus 软删/恢复。带 pair_id 条件：越权改他人照片状态必须在 SQL 层也拦一道。
//
// 进回收站时记 deleted_at，供「N 天后自动彻底删除」与剩余天数展示使用；
// 恢复时必须清空它，否则刚恢复的照片会被清理任务按旧时间立刻再删一次。
func (s *Store) SetPhotoStatus(id, pairID int64, status int) error {
	if status != 1 && status != 2 {
		return fmt.Errorf("invalid photo status %d", status)
	}
	var res sql.Result
	var err error
	if status == 2 {
		res, err = s.DB.Exec(
			`UPDATE photo SET status=?, deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP)
			 WHERE id=? AND pair_id=? AND status=1`,
			status, id, pairID)
	} else {
		res, err = s.DB.Exec(
			`UPDATE photo SET status=?, deleted_at=NULL WHERE id=? AND pair_id=? AND status=2`,
			status, id, pairID)
	}
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

// SetPhotosStatus 批量改状态（网格多选删除用）。返回实际影响行数。
func (s *Store) SetPhotosStatus(pairID int64, photoIDs []int64, status int) (int64, error) {
	if len(photoIDs) == 0 {
		return 0, nil
	}
	if status != 1 && status != 2 {
		return 0, fmt.Errorf("invalid photo status %d", status)
	}
	holders := make([]string, len(photoIDs))
	args := make([]interface{}, 0, len(photoIDs)+2)
	args = append(args, status)
	for i, id := range photoIDs {
		holders[i] = "?"
		args = append(args, id)
	}
	args = append(args, pairID)
	stamp := "deleted_at=NULL"
	fromStatus := 2
	if status == 2 {
		stamp = "deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP)"
		fromStatus = 1
	}
	res, err := s.DB.Exec(
		`UPDATE photo SET status=?, `+stamp+
			` WHERE id IN (`+strings.Join(holders, ",")+`) AND pair_id=? AND status=?`, append(args, fromStatus)...)
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, err
	}
	return n, nil
}

func (s *Store) UpdatePhotoCaption(id, pairID int64, caption string) error {
	// 保持幂等的无权/不存在语义：handler 已在 SQL 前做归属校验，
	// 这里即使遇到竞态也只是不更新，不把别人的照片状态泄露给调用方。
	_, err := s.DB.Exec(`UPDATE photo SET caption=? WHERE id=? AND pair_id=? AND status=1`, caption, id, pairID)
	return err
}

// MovePhotosToAlbum 把已上传的照片挂进相册（批量）。
// pair_id 进 WHERE：只允许移动自己 pair 名下的照片，传别人的 id 会静默无效而非改到别人数据。
func (s *Store) MovePhotosToAlbum(pairID, albumID int64, photoIDs []int64) (int64, error) {
	if len(photoIDs) == 0 {
		return 0, nil
	}
	args := []interface{}{albumID}
	holders := make([]string, 0, len(photoIDs))
	for _, id := range photoIDs {
		holders = append(holders, "?")
		args = append(args, id)
	}
	args = append(args, pairID)
	res, err := s.DB.Exec(
		`UPDATE photo SET album_id=? WHERE id IN (`+strings.Join(holders, ",")+`) AND pair_id=? AND status=1`,
		args...)
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, err
	}
	return n, nil
}

// ---------- 相册 ----------

func (s *Store) CreateAlbum(pairID, createdBy int64, name string) (*Album, error) {
	res, err := s.DB.Exec(
		`INSERT INTO album(pair_id,name,created_by,status) VALUES(?,?,?,1)`, pairID, name, createdBy)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return s.GetAlbum(id)
}

// albumCols 与 scanAlbum 的列序一致。
const albumCols = `id,pair_id,name,cover_photo_id,created_by,status,created_at,updated_at`

// scanAlbum 读一行相册。时间列同样先扫字符串再解析，理由见 scanPhoto。
// extra 用于承接列表查询多出来的聚合列（张数、封面 id），单行查询传 nil。
func scanAlbum(rs rowScanner, extra ...interface{}) (*Album, error) {
	a := &Album{}
	var created, updated sql.NullString
	dest := []interface{}{&a.ID, &a.PairID, &a.Name, &a.CoverPhotoID, &a.CreatedBy, &a.Status,
		&created, &updated}
	dest = append(dest, extra...)
	if err := rs.Scan(dest...); err != nil {
		return nil, err
	}
	if created.Valid {
		if t, okT := parseSQLiteUTCTime(created.String); okT {
			a.CreatedAt = t
		}
	}
	if updated.Valid {
		if t, okT := parseSQLiteUTCTime(updated.String); okT {
			a.UpdatedAt = t
		}
	}
	return a, nil
}

func (s *Store) GetAlbum(id int64) (*Album, error) {
	return scanAlbum(s.DB.QueryRow(`SELECT `+albumCols+` FROM album WHERE id=?`, id))
}

// ListAlbums 相册列表：带张数与封面缩略图。
// 封面优先用显式指定的 cover_photo_id，未指定/已删则回退该相册最新一张——
// 否则新建相册在客户端永远是灰色空白块。
func (s *Store) ListAlbums(pairID int64) ([]Album, error) {
	rows, err := s.DB.Query(
		`SELECT a.id,a.pair_id,a.name,a.cover_photo_id,a.created_by,a.status,a.created_at,a.updated_at,
		        (SELECT COUNT(*) FROM photo p WHERE p.album_id=a.id AND p.pair_id=a.pair_id AND p.status=1),
		        COALESCE(
		          (SELECT c.id FROM photo c WHERE c.id=a.cover_photo_id AND c.pair_id=a.pair_id AND c.status=1),
		          (SELECT l.id FROM photo l WHERE l.album_id=a.id AND l.pair_id=a.pair_id AND l.status=1
		            ORDER BY COALESCE(l.taken_at, l.created_at) DESC, l.id DESC LIMIT 1),
		          0)
		 FROM album a WHERE a.pair_id=? AND a.status=1 ORDER BY a.id DESC`, pairID)
	if err != nil {
		return nil, err
	}
	out := []Album{}
	coverIDs := []int64{}
	// 先把行读完并立刻关闭 rows，**不要在遍历中做任何会触发新查询的事**。
	//
	// 死锁原因：SQLite 连接池是 MaxOpenConns(1)（见 main.go），遍历 rows 期间那条唯一连接
	// 被占用；而 mediaThumbURL → siteBaseURL → GetSetting 会再发一次查询，
	// 于是它排队等一条永远不会被释放的连接——测试里表现为整包 600s 超时。
	func() {
		defer rows.Close()
		for rows.Next() {
			var photoCount int
			var coverID int64
			a, scanErr := scanAlbum(rows, &photoCount, &coverID)
			if scanErr != nil {
				slog.Error("scan album row failed", "pair_id", pairID, "err", scanErr)
				continue
			}
			a.PhotoCount = photoCount
			out = append(out, *a)
			coverIDs = append(coverIDs, coverID)
		}
		err = rows.Err()
	}()
	if err != nil {
		return nil, err
	}
	// rows 已关闭，此时再取站点地址（可能查库）是安全的。
	for i := range out {
		if coverIDs[i] > 0 {
			out[i].CoverThumb = mediaThumbURL(coverIDs[i])
		}
	}
	return out, nil
}

// UpdateAlbum 改名/换封面（两者都可选）。cover 必须是本 pair 名下的正常照片，调用方先校验。
func (s *Store) UpdateAlbum(id, pairID int64, name *string, coverPhotoID *int64) error {
	sets, args := []string{}, []interface{}{}
	if name != nil {
		sets = append(sets, "name=?")
		args = append(args, *name)
	}
	if coverPhotoID != nil {
		sets = append(sets, "cover_photo_id=?")
		args = append(args, *coverPhotoID)
	}
	if len(sets) == 0 {
		return nil
	}
	sets = append(sets, "updated_at=datetime('now')")
	args = append(args, id, pairID)
	res, err := s.DB.Exec(
		"UPDATE album SET "+strings.Join(sets, ",")+" WHERE id=? AND pair_id=? AND status=1", args...)
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

// DeleteAlbum 软删相册，并把其中照片归为未归类（album_id=0）。
// 照片不跟着删：相册只是分组，删分组不该连带销毁照片本体——
// 误删一个相册就永久丢掉几百张合照是不可接受的后果。
func (s *Store) DeleteAlbum(id, pairID int64) error {
	tx, err := s.DB.Begin()
	if err != nil {
		return err
	}
	res, err := tx.Exec(`UPDATE album SET status=2, updated_at=datetime('now') WHERE id=? AND pair_id=? AND status=1`,
		id, pairID)
	if err != nil {
		_ = tx.Rollback()
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		_ = tx.Rollback()
		return err
	}
	if n != 1 {
		_ = tx.Rollback()
		return sql.ErrNoRows
	}
	if _, err := tx.Exec(`UPDATE photo SET album_id=0 WHERE album_id=? AND pair_id=?`, id, pairID); err != nil {
		tx.Rollback()
		return err
	}
	return tx.Commit()
}

// AlbumSummary 发现页卡片用的概要：总张数、相册数、最新一张的缩略图。
func (s *Store) AlbumSummary(pairID int64) (gin.H, error) {
	var photoCount, albumCount int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM photo WHERE pair_id=? AND status=1`, pairID).Scan(&photoCount); err != nil {
		return nil, err
	}
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM album WHERE pair_id=? AND status=1`, pairID).Scan(&albumCount); err != nil {
		return nil, err
	}
	var latestID int64
	// 无照片时 QueryRow 返回 ErrNoRows，此处忽略：概要接口不该因为「还没有照片」而报错。
	if err := s.DB.QueryRow(
		`SELECT id FROM photo WHERE pair_id=? AND status=1
		 ORDER BY COALESCE(taken_at, created_at) DESC, id DESC LIMIT 1`, pairID).Scan(&latestID); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	latestThumb := ""
	if latestID > 0 {
		latestThumb = mediaThumbURL(latestID)
	}

	// 未归类张数由服务端直接算（album_id=0 且未删）。
	//
	// 此前是客户端做减法：`总数 − 各相册张数之和`（AlbumListScreen.kt:77）。
	// 两边统计口径只要有一点不一致（软删照片算不算、相册软删后照片是否退回未归类）
	// 就会算出负数或错数，客户端只能用 coerceAtLeast(0) 兜住，显示仍然不准。
	var unclassified int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM photo WHERE pair_id=? AND status=1 AND album_id=0`,
		pairID).Scan(&unclassified); err != nil {
		return nil, err
	}

	// 回收站张数：客户端据此决定「回收站」入口要不要显示角标。
	var recycled int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM photo WHERE pair_id=? AND status=2`, pairID).Scan(&recycled); err != nil {
		return nil, err
	}

	return gin.H{
		"photo_count":        photoCount,
		"album_count":        albumCount,
		"latest_thumb_url":   latestThumb,
		"unclassified_count": unclassified,
		"recycled_count":     recycled,
		// 「未归类」的显示名可被改（管理员 Q22 附言：未归类也要能更改名字）。
		"unclassified_name": s.UnclassifiedName(pairID),
	}, nil
}

// defaultUnclassifiedName 「未归类」虚拟相册的缺省显示名。
const defaultUnclassifiedName = "未归类"

// UnclassifiedName 取「未归类」的显示名，未自定义时返回缺省值。
//
// 它存在 pair 表上而不是 album 表：未归类不是一条真实的相册行（album_id=0 是个哨兵值），
// 为它插一行真实相册会让"删相册时照片退回未归类"这条逻辑变成自我引用。
func (s *Store) UnclassifiedName(pairID int64) string {
	var name sql.NullString
	if err := s.DB.QueryRow(
		`SELECT unclassified_name FROM pair WHERE id=?`, pairID).Scan(&name); err != nil {
		return defaultUnclassifiedName
	}
	if !name.Valid || strings.TrimSpace(name.String) == "" {
		return defaultUnclassifiedName
	}
	return name.String
}

// SetUnclassifiedName 改「未归类」的显示名。传空串恢复缺省。
func (s *Store) SetUnclassifiedName(pairID int64, name string) error {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" || trimmed == defaultUnclassifiedName {
		_, err := s.DB.Exec(`UPDATE pair SET unclassified_name=NULL WHERE id=?`, pairID)
		return err
	}
	_, err := s.DB.Exec(`UPDATE pair SET unclassified_name=? WHERE id=?`, trimmed, pairID)
	return err
}

// presetAlbumNames 绑定成功时自动建的默认分组（管理员 Q22=A+B）。
//
// 目的是让用户一进相册就有地方放照片，而不是必须先想个名字建相册。
// 都是普通相册行，可改名可删除——不喜欢就删掉，不会再自动长回来。
var presetAlbumNames = []string{"我们", "日常", "旅行"}

// CreatePresetAlbums 为新绑定的 pair 建默认分组。
// 已经有任何相册的 pair 跳过（避免解绑重绑后又冒出来一堆重复相册）。
func (s *Store) CreatePresetAlbums(pairID, creatorID int64) error {
	var n int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM album WHERE pair_id=?`, pairID).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	for _, name := range presetAlbumNames {
		if _, err := s.DB.Exec(
			`INSERT INTO album(pair_id,created_by,name,status) VALUES(?,?,?,1)`,
			pairID, creatorID, name); err != nil {
			// 单个失败不阻断其余：默认分组是便利功能，不该让绑定流程失败。
			slog.Warn("create preset album failed", "pair_id", pairID, "name", name, "err", err)
		}
	}
	return nil
}

// ---------- 「这一天」 ----------

// onThisDayLimit 单次「这一天」最多返回多少张：这是个回忆入口而非全量浏览，
// 不设上限会在照片攒多后一次吐出几千行。
const onThisDayLimit = 200

// PhotosOnThisDay 返回历年同月同日的照片（按时间倒序）。
//
// 两段式过滤：SQL 先用 substr 取存储文本的 "MM-DD" 粗筛，Go 再用解析后的时间精确复核。
//
// 为什么 SQL 侧要放宽到「前后各一天」：
// taken_at 存的是本地墙钟，而 created_at 由 CURRENT_TIMESTAMP 写入、**存的是 UTC**。
// 对没有 EXIF 的照片（退化用 created_at），字面 MM-DD 就是 UTC 的日期——
// 在 UTC+8 下，本地 00:00~08:00 上传的照片其 created_at 字面日期是「前一天」，
// 只比对当天会把它们整段漏掉。放宽一天后由 photoMatchesMonthDay 用本地时间定夺，
// 既不漏也不会多返回（复核是最终权威）。
//
// 不用 strftime(...,'localtime') 归一：那依赖容器的 TZ 环境变量（alpine 镜像通常是 UTC），
// 等于把结果正确性押在部署环境的时区配置上，同一份代码在不同机器上行为不同。
func (s *Store) PhotosOnThisDay(pairID int64, month, day int) ([]Photo, error) {
	keys := monthDayCandidates(month, day)
	holders := make([]string, 0, len(keys))
	args := []interface{}{pairID}
	for _, k := range keys {
		holders = append(holders, "?")
		args = append(args, k)
	}
	// 粗筛上限按候选天数放大，避免精确复核前就被 LIMIT 截断。
	args = append(args, onThisDayLimit*len(keys))
	rows, err := s.DB.Query(
		`SELECT `+photoCols+` FROM photo
		 WHERE pair_id=? AND status=1
		   AND substr(COALESCE(taken_at, created_at),6,5) IN (`+strings.Join(holders, ",")+`)
		 ORDER BY COALESCE(taken_at, created_at) DESC, id DESC LIMIT ?`,
		args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Photo{}
	for rows.Next() {
		p, err := scanPhoto(rows)
		if err != nil {
			// 坏行跳过并留痕：历史数据的一行异常不应让整页回忆不可用。
			slog.Error("scan on-this-day photo failed", "pair_id", pairID, "month", month, "day", day, "err", err)
			continue
		}
		// 再用扫出来的时间复核一次月/日：万一底层驱动换了时间存储格式（如改存 unix 秒），
		// substr 会静默匹配不到或匹配错，这里能把错数据拦在返回前。
		if !photoMatchesMonthDay(p, month, day) {
			continue
		}
		out = append(out, *p)
		if len(out) >= onThisDayLimit {
			break // 粗筛上限已放大，这里把最终条数收回约定值
		}
	}
	return out, rows.Err()
}

// monthDayKey 生成 "MM-DD" 比较键（零填充，与 SQLite 日期文本前缀 YYYY-MM-DD 对齐）。
func monthDayKey(month, day int) string {
	return fmt.Sprintf("%02d-%02d", month, day)
}

// monthDayCandidates 返回粗筛用的 "MM-DD" 候选集：目标日 + 前一天 + 后一天。
//
// 用闰年 2000 做基准做日期加减，好处是 02-29 也在合法域内，且跨月/跨年（12-31↔01-01）
// 由 time.Date 的自动归一化处理，不必手写每月天数表。
// 非法输入（如 4 月 31 日）不特殊处理：候选键在库里匹配不到，且 Go 侧复核要求月日严格相等，
// 故最终结果为空，不会误返回邻近日期的照片。
func monthDayCandidates(month, day int) []string {
	base := time.Date(2000, time.Month(month), day, 12, 0, 0, 0, time.UTC)
	out := make([]string, 0, 3)
	seen := map[string]bool{}
	for _, d := range []time.Time{base, base.AddDate(0, 0, -1), base.AddDate(0, 0, 1)} {
		k := monthDayKey(int(d.Month()), d.Day())
		if !seen[k] {
			seen[k] = true
			out = append(out, k)
		}
	}
	// 目标日为 3-01 时，前一天在闰年是 02-29；平年库里存的是 02-28，也要一并纳入粗筛。
	if month == 3 && day == 1 && !seen["02-28"] {
		out = append(out, "02-28")
	}
	return out
}

// photoMatchesMonthDay 用照片的有效时间（拍摄时间优先，退化入库时间）复核月/日。
func photoMatchesMonthDay(p *Photo, month, day int) bool {
	t := p.CreatedAt
	if p.TakenAt != nil {
		t = *p.TakenAt
	}
	return int(t.Month()) == month && t.Day() == day
}

// ---------- 评论 / 点赞 ----------

func (s *Store) CreatePhotoComment(photoID, pairID, userID int64, content string) (*PhotoComment, error) {
	res, err := s.DB.Exec(
		`INSERT INTO photo_comment(photo_id,pair_id,user_id,content,status) VALUES(?,?,?,?,1)`,
		photoID, pairID, userID, content)
	if err != nil {
		return nil, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return nil, err
	}
	return scanComment(s.DB.QueryRow(commentSelectSQL+` WHERE c.id=?`, id))
}

// commentSelectSQL 评论查询的公共前缀（列序与 scanComment 一致）。
const commentSelectSQL = `SELECT c.id,c.photo_id,c.pair_id,c.user_id,COALESCE(u.nickname,''),c.content,c.status,c.created_at
	 FROM photo_comment c LEFT JOIN "user" u ON u.id=c.user_id`

// scanComment 读一行评论；时间列先扫字符串再解析，理由见 scanPhoto。
func scanComment(rs rowScanner) (*PhotoComment, error) {
	cm := &PhotoComment{}
	var created sql.NullString
	if err := rs.Scan(&cm.ID, &cm.PhotoID, &cm.PairID, &cm.UserID, &cm.UserName,
		&cm.Content, &cm.Status, &created); err != nil {
		return nil, err
	}
	if created.Valid {
		if t, okT := parseSQLiteUTCTime(created.String); okT {
			cm.CreatedAt = t
		}
	}
	return cm, nil
}

func (s *Store) ListPhotoComments(photoID int64) ([]PhotoComment, error) {
	rows, err := s.DB.Query(
		commentSelectSQL+` WHERE c.photo_id=? AND c.status=1 ORDER BY c.id ASC`, photoID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []PhotoComment{}
	for rows.Next() {
		cm, err := scanComment(rows)
		if err != nil {
			// 坏行跳过并留痕：单条评论异常不应隐藏其它评论。
			slog.Error("scan photo comment failed", "photo_id", photoID, "err", err)
			continue
		}
		out = append(out, *cm)
	}
	return out, rows.Err()
}

// DeletePhotoComment 只能删自己的：user_id 进 WHERE，越权删除会 0 行受影响。
func (s *Store) DeletePhotoComment(commentID, photoID, userID int64) (int64, error) {
	res, err := s.DB.Exec(
		`UPDATE photo_comment SET status=2 WHERE id=? AND photo_id=? AND user_id=? AND status=1`,
		commentID, photoID, userID)
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, err
	}
	return n, nil
}

// LikePhoto 幂等点赞：主键 (photo_id,user_id) + INSERT OR IGNORE，重复点不报错也不重复计数。
func (s *Store) LikePhoto(photoID, userID int64) error {
	_, err := s.DB.Exec(
		`INSERT OR IGNORE INTO photo_like(photo_id,user_id) VALUES(?,?)`, photoID, userID)
	return err
}

func (s *Store) UnlikePhoto(photoID, userID int64) error {
	_, err := s.DB.Exec(`DELETE FROM photo_like WHERE photo_id=? AND user_id=?`, photoID, userID)
	return err
}

// PhotoLikeState 返回点赞总数与「我是否已赞」。
func (s *Store) PhotoLikeState(photoID, userID int64) (int, bool, error) {
	var total, mine int
	if err := s.DB.QueryRow(`SELECT COUNT(*) FROM photo_like WHERE photo_id=?`, photoID).Scan(&total); err != nil {
		return 0, false, err
	}
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM photo_like WHERE photo_id=? AND user_id=?`, photoID, userID).Scan(&mine); err != nil {
		return 0, false, err
	}
	return total, mine > 0, nil
}

// ---------- 后台 ----------

// ListPhotosAll 后台照片列表（分页 + caption 关键词 + pair 筛选）。
//
// **不返回任何图片 URL**：后台管理员不该能直接看到情侣的私密照片内容，
// 只给元数据用于违规内容处置（真要看图需走用户侧鉴权代理，管理员拿不到用户 token）。
func (s *Store) ListPhotosAll(keyword string, pairID int64, limit, offset int) ([]gin.H, int, error) {
	base := `FROM photo p LEFT JOIN "user" u ON u.id=p.uploader_id WHERE 1=1`
	args := []interface{}{}
	if keyword != "" {
		// COALESCE 不可省：caption 为 NULL 时 `NULL LIKE ?` 结果是 NULL 而非 false，
		// 虽同样不入结果集，但显式空串更能表达「无描述即不匹配关键词」。
		base += " AND COALESCE(p.caption,'') LIKE ?"
		args = append(args, "%"+keyword+"%")
	}
	if pairID > 0 {
		base += " AND p.pair_id=?"
		args = append(args, pairID)
	}
	var total int
	if err := s.DB.QueryRow("SELECT COUNT(*) "+base, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.Query(
		`SELECT p.id,p.pair_id,p.album_id,p.uploader_id,COALESCE(u.nickname,''),COALESCE(p.caption,''),
		        p.width,p.height,p.size_bytes,COALESCE(p.mime,''),p.taken_at,p.status,p.created_at `+
			base+" ORDER BY p.id DESC LIMIT ? OFFSET ?", append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id, pid, aid, uid int64
		var uploader, caption, mime string
		var width, height, status int
		var size int64
		// 时间列同样扫成字符串再解析，理由见 scanPhoto：不把可读性押在驱动的时间反序列化上。
		var taken, created sql.NullString
		if err := rows.Scan(&id, &pid, &aid, &uid, &uploader, &caption,
			&width, &height, &size, &mime, &taken, &status, &created); err != nil {
			slog.Error("scan admin photo row failed", "err", err)
			continue
		}
		var takenAt interface{}
		if taken.Valid {
			if t, okT := parseSQLiteLocalTime(taken.String); okT {
				takenAt = t
			}
		}
		var createdAt interface{}
		if created.Valid {
			if t, okT := parseSQLiteUTCTime(created.String); okT {
				createdAt = t
			}
		}
		out = append(out, gin.H{
			"id": id, "pair_id": pid, "album_id": aid,
			"uploader_id": uid, "uploader_name": uploader,
			"caption": caption, "width": width, "height": height,
			"size_bytes": size, "mime": mime, "taken_at": takenAt,
			"status": status, "created_at": createdAt,
		})
	}
	return out, total, rows.Err()
}

// AdminDeletePhoto 后台软删（进回收站，不删盘上文件，误删可由用户恢复）。
func (s *Store) AdminDeletePhoto(id int64) error {
	res, err := s.DB.Exec(`UPDATE photo SET status=2,
		deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP)
		WHERE id=? AND status=1`, id)
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
