package main

import (
	"database/sql"
	"log/slog"
	"os"
	"time"
)

// ================= 回收站彻底删除（真删磁盘文件） =================
//
// 此前全链路只有软删（status=2），`/upload` 下的原图与缩略图**永久保留**，
// 磁盘只涨不跌。管理员 Q21=C：要「彻底删除 + 清空回收站 + 按天自动清理 + 显示剩余天数」。
//
// 安全约束（不可逆操作，宁可少删也不能误删）：
//   - 只删 `status=2`（已在回收站）且 `pair_id` 相符的行；
//   - 磁盘路径一律过 `safeUploadPath` 防穿越，库值被写坏也不能删到 uploadDir 之外；
//   - 先删库、再删盘：反过来若删库失败，库里会留下指向已消失文件的行（列表全是碎图）。
//     顺序反了的代价是"删库成功但删盘失败"会留孤儿文件——那只是占盘，可由后续清理兜底。

// photoDiskPaths 汇总一张照片在盘上的全部产物（原图 / 缩略图 / 预览图）。
func photoDiskPaths(p *Photo) []string {
	out := make([]string, 0, 3)
	for _, rel := range []string{p.diskPath, p.diskThumb, p.diskPreview} {
		if rel == "" {
			continue
		}
		if full, ok := safeUploadPath(rel); ok {
			out = append(out, full)
		}
	}
	return out
}

// removePhotoFiles 删盘并返回释放的字节数。单个文件删不掉不算失败（可能早已不存在）。
func removePhotoFiles(p *Photo) int64 {
	var freed int64
	for _, full := range photoDiskPaths(p) {
		if fi, err := os.Stat(full); err == nil && !fi.IsDir() {
			freed += fi.Size()
		}
		if err := os.Remove(full); err != nil && !os.IsNotExist(err) {
			slog.Warn("remove photo file failed", "path", full, "err", err)
		}
	}
	return freed
}

// PurgePhoto 彻底删除一张已在回收站的照片：删库行 + 关联数据 + 磁盘文件。
// 返回释放的字节数。照片不在回收站或不属于该 pair 时返回 sql.ErrNoRows。
func (s *Store) PurgePhoto(id, pairID int64) (int64, error) {
	photo, err := s.GetPhoto(id)
	if err != nil {
		return 0, err
	}
	if photo == nil || photo.PairID != pairID {
		return 0, sql.ErrNoRows
	}
	if photo.Status != 2 {
		// 只允许删回收站里的：直接彻底删正常照片等于绕过了"可恢复"这层保护。
		return 0, sql.ErrNoRows
	}
	if err := s.purgePhotoRows(id, pairID); err != nil {
		return 0, err
	}
	return removePhotoFiles(photo), nil
}

// purgePhotoRows 在一个事务里删掉照片本体与其关联数据。
// 相册封面若指向被删照片必须一并清空，否则相册列表会拿着一个不存在的 id 去取封面。
func (s *Store) purgePhotoRows(id, pairID int64) error {
	tx, err := s.DB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.Exec(`DELETE FROM photo_comment WHERE photo_id=?`, id); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM photo_like WHERE photo_id=?`, id); err != nil {
		return err
	}
	if _, err := tx.Exec(
		`UPDATE album SET cover_photo_id=NULL WHERE cover_photo_id=? AND pair_id=?`,
		id, pairID); err != nil {
		return err
	}
	res, err := tx.Exec(`DELETE FROM photo WHERE id=? AND pair_id=? AND status=2`, id, pairID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return tx.Commit()
}

// PurgeRecycleBin 清空某 pair 的回收站。返回删除张数与释放字节数。
func (s *Store) PurgeRecycleBin(pairID int64) (int, int64, error) {
	photos, err := s.listRecycledForPurge(pairID, 0)
	if err != nil {
		return 0, 0, err
	}
	var count int
	var freed int64
	for _, p := range photos {
		if err := s.purgePhotoRows(p.ID, p.PairID); err != nil {
			slog.Error("purge photo rows failed", "photo_id", p.ID, "err", err)
			continue
		}
		freed += removePhotoFiles(p)
		count++
	}
	return count, freed, nil
}

// listRecycledForPurge 列出待彻底删除的照片。
//
// pairID>0 表示只看某一对；olderThanDays>0 表示只取"进回收站超过 N 天"的（定时清理用）。
// 判定基准是 deleted_at（进回收站的时刻）——用 created_at 会把"刚删掉的老照片"
// 当成过期立刻清掉，用户根本没有反悔的机会。
func (s *Store) listRecycledForPurge(pairID int64, olderThanDays int) ([]*Photo, error) {
	q := `SELECT ` + photoColumns + ` FROM photo WHERE status=2`
	args := []interface{}{}
	if pairID > 0 {
		q += ` AND pair_id=?`
		args = append(args, pairID)
	}
	if olderThanDays > 0 {
		// deleted_at 可能为 NULL（0821 之前进回收站的历史数据），
		// 这类回退到 created_at，否则它们永远清不掉。
		q += ` AND COALESCE(deleted_at, created_at) < datetime('now', ?)`
		args = append(args, negDaysModifier(olderThanDays))
	}
	q += ` ORDER BY id ASC`
	rows, err := s.DB.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Photo{}
	for rows.Next() {
		p, err := scanPhotoRows(rows)
		if err != nil {
			slog.Error("scan recycled photo failed", "err", err)
			continue
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// PurgeExpiredRecycleBin 全站清理超过保留期的回收站照片（定时任务用）。
func (s *Store) PurgeExpiredRecycleBin(days int) (int, int64, error) {
	if days <= 0 {
		return 0, 0, nil // 永久保留
	}
	photos, err := s.listRecycledForPurge(0, days)
	if err != nil {
		return 0, 0, err
	}
	var count int
	var freed int64
	for _, p := range photos {
		if err := s.purgePhotoRows(p.ID, p.PairID); err != nil {
			slog.Error("purge expired photo failed", "photo_id", p.ID, "err", err)
			continue
		}
		freed += removePhotoFiles(p)
		count++
	}
	return count, freed, nil
}

// recycleRemainingDays 回收站照片还剩几天被自动删除。
// -1 = 永久保留（未配置保留天数）；0 = 已到期，下次清理就删。
func recycleRemainingDays(deletedAt sql.NullTime, createdAt time.Time, keepDays int) int {
	if keepDays <= 0 {
		return -1
	}
	base := createdAt
	if deletedAt.Valid {
		base = deletedAt.Time
	}
	remain := keepDays - int(time.Since(base).Hours()/24)
	if remain < 0 {
		return 0
	}
	return remain
}
