package main

import (
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 造一张带真实磁盘文件的照片，返回照片与其三个产物的绝对路径。
func seedPhotoWithFiles(t *testing.T, s *Store, pairID, uid int64, name string) (*Photo, []string) {
	t.Helper()
	datePath := "upload/2026/08/21"
	dir := filepath.Join(uploadDir, filepath.FromSlash(datePath))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	rels := []string{
		datePath + "/" + name + ".jpg",
		datePath + "/" + name + "_thumb.jpg",
		datePath + "/" + name + "_preview.jpg",
	}
	var fulls []string
	for _, rel := range rels {
		full := filepath.Join(uploadDir, filepath.FromSlash(rel))
		if err := os.WriteFile(full, []byte("fake-image-bytes"), 0o600); err != nil {
			t.Fatal(err)
		}
		fulls = append(fulls, full)
	}
	p, err := s.CreatePhoto(pairID, uid, 0, rels[0], rels[1], rels[2],
		800, 600, 16, "image/jpeg", nil)
	if err != nil {
		t.Fatalf("create photo: %v", err)
	}
	return p, fulls
}

func withTempUploadDir(t *testing.T) {
	t.Helper()
	prev := uploadDir
	uploadDir = filepath.Join(t.TempDir(), "uploads")
	t.Cleanup(func() { uploadDir = prev })
}

// 彻底删除必须真删磁盘文件——此前全链路只软删，/upload 下的原图永久占盘，磁盘只涨不跌。
func TestPurgePhotoRemovesDiskFiles(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "pga", "pgb", "PURGE001")

	photo, fulls := seedPhotoWithFiles(t, s, pair.ID, uidA, "p1")
	for _, f := range fulls {
		if _, err := os.Stat(f); err != nil {
			t.Fatalf("前置文件应存在: %v", err)
		}
	}

	// 正常状态的照片不允许直接彻底删（必须先进回收站，否则绕过了"可恢复"这层保护）。
	if _, err := s.PurgePhoto(photo.ID, pair.ID); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("未进回收站就彻底删应被拒，实际 %v", err)
	}
	if _, err := os.Stat(fulls[0]); err != nil {
		t.Fatal("被拒后文件不该被删")
	}

	// 进回收站后才能彻底删。
	if err := s.SetPhotoStatus(photo.ID, pair.ID, 2); err != nil {
		t.Fatalf("软删失败: %v", err)
	}
	freed, err := s.PurgePhoto(photo.ID, pair.ID)
	if err != nil {
		t.Fatalf("彻底删除失败: %v", err)
	}
	if freed != 48 { // 3 个文件 × 16 字节
		t.Fatalf("释放字节数 %d，期望 48", freed)
	}
	for _, f := range fulls {
		if _, err := os.Stat(f); !os.IsNotExist(err) {
			t.Fatalf("磁盘文件仍存在: %s", f)
		}
	}
	// 库行也要没
	if got, _ := s.GetPhoto(photo.ID); got != nil {
		t.Fatal("库行应已删除")
	}
}

// 越权彻底删：别人 pair 的照片一行都不能动。
func TestPurgePhotoRejectsCrossPair(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)
	pairA, uidA, _ := seedPair(t, s, "xa", "xb", "XPURGE01")
	pairB, uidB, _ := seedPair(t, s, "xc", "xd", "XPURGE02")

	photoB, fulls := seedPhotoWithFiles(t, s, pairB.ID, uidB, "pb")
	if err := s.SetPhotoStatus(photoB.ID, pairB.ID, 2); err != nil {
		t.Fatal(err)
	}
	_ = uidA

	if _, err := s.PurgePhoto(photoB.ID, pairA.ID); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("越权彻底删应返回 sql.ErrNoRows，实际 %v", err)
	}
	if _, err := os.Stat(fulls[0]); err != nil {
		t.Fatal("越权操作不该删掉别人的文件")
	}
	if got, _ := s.GetPhoto(photoB.ID); got == nil {
		t.Fatal("越权操作不该删掉别人的库行")
	}
}

// 清空回收站：只清回收站里的，正常照片不受影响。
func TestPurgeRecycleBinOnlyTouchesRecycled(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "rba", "rbb", "RECYCLE1")

	keep, keepFiles := seedPhotoWithFiles(t, s, pair.ID, uidA, "keep")
	del1, del1Files := seedPhotoWithFiles(t, s, pair.ID, uidA, "del1")
	del2, del2Files := seedPhotoWithFiles(t, s, pair.ID, uidA, "del2")

	for _, p := range []*Photo{del1, del2} {
		if err := s.SetPhotoStatus(p.ID, pair.ID, 2); err != nil {
			t.Fatal(err)
		}
	}

	count, freed, err := s.PurgeRecycleBin(pair.ID)
	if err != nil {
		t.Fatalf("清空回收站失败: %v", err)
	}
	if count != 2 {
		t.Fatalf("应清 2 张，实际 %d", count)
	}
	if freed != 96 { // 2 张 × 3 文件 × 16 字节
		t.Fatalf("释放 %d 字节，期望 96", freed)
	}
	// 正常照片必须还在（文件与库行都在）
	if _, err := os.Stat(keepFiles[0]); err != nil {
		t.Fatal("正常照片的文件被误删了")
	}
	if got, _ := s.GetPhoto(keep.ID); got == nil {
		t.Fatal("正常照片的库行被误删了")
	}
	for _, f := range append(del1Files, del2Files...) {
		if _, err := os.Stat(f); !os.IsNotExist(err) {
			t.Fatalf("回收站文件仍存在: %s", f)
		}
	}
}

// 到期自动清理：只删「进回收站超过 N 天」的，且 days<=0 表示永久保留。
// 判定基准必须是 deleted_at 而非 created_at——否则"刚删掉的老照片"会被当成过期立刻清掉。
func TestPurgeExpiredRecycleBinUsesDeletedAt(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "exa", "exb", "EXPIRE01")

	// 一张"很久以前拍的、刚刚删掉的"照片：created_at 老，deleted_at 新。
	oldPhoto, oldFiles := seedPhotoWithFiles(t, s, pair.ID, uidA, "old")
	if _, err := s.DB.Exec(`UPDATE photo SET created_at=? WHERE id=?`,
		time.Now().AddDate(0, 0, -400), oldPhoto.ID); err != nil {
		t.Fatal(err)
	}
	if err := s.SetPhotoStatus(oldPhoto.ID, pair.ID, 2); err != nil {
		t.Fatal(err)
	}

	// 一张"30 天前就删掉的"照片。
	expired, expiredFiles := seedPhotoWithFiles(t, s, pair.ID, uidA, "expired")
	if err := s.SetPhotoStatus(expired.ID, pair.ID, 2); err != nil {
		t.Fatal(err)
	}
	if _, err := s.DB.Exec(`UPDATE photo SET deleted_at=? WHERE id=?`,
		time.Now().AddDate(0, 0, -30), expired.ID); err != nil {
		t.Fatal(err)
	}

	// days=0 → 永久保留，一张都不删。
	if cnt, _, err := s.PurgeExpiredRecycleBin(0); err != nil || cnt != 0 {
		t.Fatalf("days=0 应不删任何照片，实际删 %d err=%v", cnt, err)
	}

	// days=7 → 只删 30 天前删掉的那张；刚删的老照片必须留着。
	cnt, _, err := s.PurgeExpiredRecycleBin(7)
	if err != nil {
		t.Fatalf("清理失败: %v", err)
	}
	if cnt != 1 {
		t.Fatalf("应清 1 张，实际 %d（可能误用了 created_at 作基准）", cnt)
	}
	if _, err := os.Stat(oldFiles[0]); err != nil {
		t.Fatal("刚删掉的照片被误清了 —— 基准应是 deleted_at 而非 created_at")
	}
	if _, err := os.Stat(expiredFiles[0]); !os.IsNotExist(err) {
		t.Fatal("到期照片未被清理")
	}
}

// 恢复照片时必须清空 deleted_at，否则刚恢复的照片会被清理任务按旧时间立刻再删一次。
func TestRestoreClearsDeletedAt(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "rsa", "rsb", "RESTORE1")

	photo, files := seedPhotoWithFiles(t, s, pair.ID, uidA, "r1")
	if err := s.SetPhotoStatus(photo.ID, pair.ID, 2); err != nil {
		t.Fatal(err)
	}
	// 伪造成 100 天前删的
	if _, err := s.DB.Exec(`UPDATE photo SET deleted_at=? WHERE id=?`,
		time.Now().AddDate(0, 0, -100), photo.ID); err != nil {
		t.Fatal(err)
	}
	// 恢复
	if err := s.SetPhotoStatus(photo.ID, pair.ID, 1); err != nil {
		t.Fatal(err)
	}
	// 再跑清理：已恢复的照片不该被碰
	if cnt, _, err := s.PurgeExpiredRecycleBin(7); err != nil || cnt != 0 {
		t.Fatalf("恢复后不应被清理，实际删 %d err=%v", cnt, err)
	}
	if _, err := os.Stat(files[0]); err != nil {
		t.Fatal("已恢复的照片文件被清掉了")
	}
	got, _ := s.GetPhoto(photo.ID)
	if got == nil || got.Status != 1 {
		t.Fatal("照片应处于正常状态")
	}
	if got.deletedAt.Valid {
		t.Fatal("恢复后 deleted_at 应被清空")
	}
}

// 剩余天数计算：keepDays<=0 → -1（永久）；已超期 → 0。
func TestRecycleRemainingDays(t *testing.T) {
	now := time.Now()
	del := sql.NullTime{Time: now.AddDate(0, 0, -10), Valid: true}

	if got := recycleRemainingDays(del, now, 0); got != -1 {
		t.Fatalf("keepDays=0 应返回 -1（永久），实际 %d", got)
	}
	if got := recycleRemainingDays(del, now, 30); got != 20 {
		t.Fatalf("30-10 应剩 20 天，实际 %d", got)
	}
	if got := recycleRemainingDays(del, now, 7); got != 0 {
		t.Fatalf("已超期应返回 0，实际 %d", got)
	}
	// deleted_at 为 NULL（0821 之前的历史数据）时回退 created_at
	noDel := sql.NullTime{}
	if got := recycleRemainingDays(noDel, now.AddDate(0, 0, -5), 30); got != 25 {
		t.Fatalf("回退 created_at 应剩 25 天，实际 %d", got)
	}
}

// 批量软删与批量移动都必须带 pair 归属，别人的照片一张都不能动。
func TestBatchOperationsRespectPairOwnership(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)
	pairA, uidA, _ := seedPair(t, s, "ba1", "ba2", "BATCH001")
	pairB, uidB, _ := seedPair(t, s, "bb1", "bb2", "BATCH002")

	mine, _ := seedPhotoWithFiles(t, s, pairA.ID, uidA, "mine")
	theirs, _ := seedPhotoWithFiles(t, s, pairB.ID, uidB, "theirs")

	// 批量软删：混入别人的 id，只应删掉自己那张。
	n, err := s.SetPhotosStatus(pairA.ID, []int64{mine.ID, theirs.ID}, 2)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("应只影响 1 行，实际 %d", n)
	}
	if got, _ := s.GetPhoto(theirs.ID); got.Status != 1 {
		t.Fatal("别人的照片被越权软删了")
	}

	// 批量移动同理。注意要另起一张**未被软删**的照片：
	// MovePhotosToAlbum 带 status=1 条件（回收站里的照片不该能被移动），
	// 上面那张 mine 已经进回收站了。
	mine2, _ := seedPhotoWithFiles(t, s, pairA.ID, uidA, "mine2")
	albumA, err := s.CreateAlbum(pairA.ID, uidA, "目标")
	if err != nil {
		t.Fatal(err)
	}
	moved, err := s.MovePhotosToAlbum(pairA.ID, albumA.ID, []int64{mine2.ID, theirs.ID})
	if err != nil {
		t.Fatal(err)
	}
	if moved != 1 {
		t.Fatalf("应只移动 1 张，实际 %d", moved)
	}
	if got, _ := s.GetPhoto(theirs.ID); got.AlbumID == albumA.ID {
		t.Fatal("别人的照片被越权移动了")
	}

	// 回收站里的照片不能被移动（否则等于绕过删除状态把它捞回相册）。
	movedDeleted, err := s.MovePhotosToAlbum(pairA.ID, albumA.ID, []int64{mine.ID})
	if err != nil {
		t.Fatal(err)
	}
	if movedDeleted != 0 {
		t.Fatal("回收站里的照片不应能被移动")
	}
}

// 「未归类」改名与恢复缺省。
func TestUnclassifiedNameRename(t *testing.T) {
	s := withTestStore(t)
	pair, _, _ := seedPair(t, s, "uca", "ucb", "UNCLASS1")

	if got := s.UnclassifiedName(pair.ID); got != defaultUnclassifiedName {
		t.Fatalf("缺省名应为 %q，实际 %q", defaultUnclassifiedName, got)
	}
	if err := s.SetUnclassifiedName(pair.ID, "随手拍"); err != nil {
		t.Fatal(err)
	}
	if got := s.UnclassifiedName(pair.ID); got != "随手拍" {
		t.Fatalf("改名后应为 随手拍，实际 %q", got)
	}
	// 传空串恢复缺省
	if err := s.SetUnclassifiedName(pair.ID, "   "); err != nil {
		t.Fatal(err)
	}
	if got := s.UnclassifiedName(pair.ID); got != defaultUnclassifiedName {
		t.Fatalf("空串应恢复缺省，实际 %q", got)
	}
}

// 预置默认分组：绑定时建，已有相册的 pair 不重复建。
func TestCreatePresetAlbums(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "pra", "prb", "PRESET01")

	if err := s.CreatePresetAlbums(pair.ID, uidA); err != nil {
		t.Fatal(err)
	}
	albums, err := s.ListAlbums(pair.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(albums) != len(presetAlbumNames) {
		t.Fatalf("应建 %d 个预置相册，实际 %d", len(presetAlbumNames), len(albums))
	}

	// 再调一次不应重复建（解绑重绑后不该冒出一堆重复相册）。
	if err := s.CreatePresetAlbums(pair.ID, uidA); err != nil {
		t.Fatal(err)
	}
	again, _ := s.ListAlbums(pair.ID)
	if len(again) != len(presetAlbumNames) {
		t.Fatalf("重复调用后相册数变成 %d", len(again))
	}
}

// AlbumSummary 必须由服务端直接给出未归类张数（客户端不再做减法）。
func TestAlbumSummaryProvidesUnclassifiedCount(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "asa", "asb", "SUMMARY1")

	album, err := s.CreateAlbum(pair.ID, uidA, "有名字的")
	if err != nil {
		t.Fatal(err)
	}
	// 2 张未归类 + 1 张在相册里 + 1 张已删
	seedPhotoWithFiles(t, s, pair.ID, uidA, "u1")
	seedPhotoWithFiles(t, s, pair.ID, uidA, "u2")
	inAlbum, _ := seedPhotoWithFiles(t, s, pair.ID, uidA, "a1")
	deleted, _ := seedPhotoWithFiles(t, s, pair.ID, uidA, "d1")
	if _, err := s.MovePhotosToAlbum(pair.ID, album.ID, []int64{inAlbum.ID}); err != nil {
		t.Fatal(err)
	}
	if err := s.SetPhotoStatus(deleted.ID, pair.ID, 2); err != nil {
		t.Fatal(err)
	}

	summary, err := s.AlbumSummary(pair.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got := summary["unclassified_count"]; got != 2 {
		t.Fatalf("未归类应为 2，实际 %v", got)
	}
	if got := summary["photo_count"]; got != 3 {
		t.Fatalf("正常照片总数应为 3，实际 %v", got)
	}
	if got := summary["recycled_count"]; got != 1 {
		t.Fatalf("回收站应为 1，实际 %v", got)
	}
	if got := summary["unclassified_name"]; got != defaultUnclassifiedName {
		t.Fatalf("未归类名应为缺省，实际 %v", got)
	}
}
