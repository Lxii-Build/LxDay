package main

import (
	"path/filepath"
	"testing"
	"time"
)

// newTestStore 建一个真实的内嵌 SQLite 库（modernc 纯 Go，无需 CGO），跑一遍内嵌建表脚本。
// 相册的归属校验、「这一天」日期匹配都依赖真实 SQL 行为，纯单测覆盖不到，故走真库。
func newTestStore(t *testing.T) *Store {
	t.Helper()
	dsn := "file:" + filepath.Join(t.TempDir(), "test.db") +
		"?_pragma=busy_timeout(5000)&_pragma=foreign_keys(on)"
	db, err := sqlOpen(dsn)
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}
	db.SetMaxOpenConns(1)
	if err := runMigrations(db); err != nil {
		db.Close()
		t.Fatalf("migrate: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return &Store{DB: db, mem: newMemStore()}
}

// withTestStore 把全局 st 换成测试库并在收尾还原（handler/URL 生成读的是全局 st）。
func withTestStore(t *testing.T) *Store {
	t.Helper()
	s := newTestStore(t)
	prev := st
	st = s
	t.Cleanup(func() { st = prev })
	return s
}

// seedPair 造两个用户 + 一个已绑定的 pair。
func seedPair(t *testing.T, s *Store, nickA, nickB, code string) (*Pair, int64, int64) {
	t.Helper()
	a, err := s.CreateUser(nickA+"_u", nickA+"@t.local", nickA, hashPassword("Abcdefghij12"))
	if err != nil {
		t.Fatalf("create user a: %v", err)
	}
	b, err := s.CreateUser(nickB+"_u", nickB+"@t.local", nickB, hashPassword("Abcdefghij12"))
	if err != nil {
		t.Fatalf("create user b: %v", err)
	}
	res, err := s.DB.Exec(
		`INSERT INTO pair(user_a_id,user_b_id,invite_code,status) VALUES(?,?,?,1)`, a, b, code)
	if err != nil {
		t.Fatalf("create pair: %v", err)
	}
	pid, _ := res.LastInsertId()
	return &Pair{ID: pid, UserAID: a, UserBID: b, InviteCode: code}, a, b
}

// addPhoto 插一张照片，takenAt 传 nil 表示无 EXIF。
func addPhoto(t *testing.T, s *Store, pairID, uid, albumID int64, name string, takenAt *time.Time) *Photo {
	t.Helper()
	rel := "upload/2026/08/20/" + name + ".jpg"
	p, err := s.CreatePhoto(pairID, uid, albumID, rel, "upload/2026/08/20/"+name+"_thumb.jpg",
		1600, 1200, 2048, "image/jpeg", takenAt)
	if err != nil {
		t.Fatalf("create photo %s: %v", name, err)
	}
	return p
}

// 归属校验：另一对情侣的照片/相册一律取不到，且软删只影响自己 pair 的行。
func Test相册与照片的Pair归属校验(t *testing.T) {
	s := withTestStore(t)
	pairA, uidA1, _ := seedPair(t, s, "amy", "ben", "CODEAAAA")
	pairB, uidB1, _ := seedPair(t, s, "cat", "dan", "CODEBBBB")

	albumA, err := s.CreateAlbum(pairA.ID, uidA1, "我们的旅行")
	if err != nil {
		t.Fatalf("create album: %v", err)
	}
	albumB, err := s.CreateAlbum(pairB.ID, uidB1, "别人的相册")
	if err != nil {
		t.Fatalf("create album b: %v", err)
	}

	photoA := addPhoto(t, s, pairA.ID, uidA1, albumA.ID, "a1", nil)
	photoB := addPhoto(t, s, pairB.ID, uidB1, albumB.ID, "b1", nil)

	// getOwnedAlbum：跨 pair 必须判定为无权。
	if _, owned := getOwnedAlbum(pairA, albumB.ID); owned {
		t.Fatal("pairA 不该拥有 pairB 的相册")
	}
	if _, owned := getOwnedAlbum(pairA, albumA.ID); !owned {
		t.Fatal("pairA 应拥有自己的相册")
	}
	if _, owned := getOwnedAlbum(pairA, 999999); owned {
		t.Fatal("不存在的相册不该判定为拥有")
	}

	// getOwnedPhoto：同理。
	if _, owned := getOwnedPhoto(pairA, photoB.ID); owned {
		t.Fatal("pairA 不该拥有 pairB 的照片")
	}
	if _, owned := getOwnedPhoto(pairA, photoA.ID); !owned {
		t.Fatal("pairA 应拥有自己的照片")
	}

	// 列表只出自己 pair 的相册。
	albums, err := s.ListAlbums(pairA.ID)
	if err != nil {
		t.Fatalf("list albums: %v", err)
	}
	if len(albums) != 1 || albums[0].ID != albumA.ID {
		t.Fatalf("pairA 相册列表越界：%#v", albums)
	}

	// 越权软删：SetPhotoStatus 带 pair_id，改不动别人的照片。
	if err := s.SetPhotoStatus(photoB.ID, pairA.ID, 2); err != nil {
		t.Fatalf("set status: %v", err)
	}
	again, err := s.GetPhoto(photoB.ID)
	if err != nil {
		t.Fatalf("get photo b: %v", err)
	}
	if again.Status != 1 {
		t.Fatal("越权软删竟然生效了")
	}

	// 越权挂入相册：MovePhotosToAlbum 也带 pair_id。
	moved, err := s.MovePhotosToAlbum(pairA.ID, albumA.ID, []int64{photoB.ID})
	if err != nil {
		t.Fatalf("move: %v", err)
	}
	if moved != 0 {
		t.Fatalf("越权挂入影响了 %d 行", moved)
	}

	// 越权改描述同样无效。
	if err := s.UpdatePhotoCaption(photoB.ID, pairA.ID, "偷改"); err != nil {
		t.Fatalf("caption: %v", err)
	}
	pb, _ := s.GetPhoto(photoB.ID)
	if pb.Caption != "" {
		t.Fatalf("越权改描述生效了：%q", pb.Caption)
	}
}

// 相册列表：张数统计与封面回退（未指定封面时用最新一张）。
func Test相册列表张数与封面回退(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "eve", "fox", "CODECCCC")
	album, _ := s.CreateAlbum(pair.ID, uid, "日常")

	old := time.Date(2024, 1, 2, 10, 0, 0, 0, time.Local)
	newer := time.Date(2026, 3, 4, 10, 0, 0, 0, time.Local)
	p1 := addPhoto(t, s, pair.ID, uid, album.ID, "old", &old)
	p2 := addPhoto(t, s, pair.ID, uid, album.ID, "new", &newer)
	// 未归类照片不该被算进相册张数。
	addPhoto(t, s, pair.ID, uid, 0, "loose", nil)

	albums, err := s.ListAlbums(pair.ID)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(albums) != 1 {
		t.Fatalf("相册数 %d", len(albums))
	}
	if albums[0].PhotoCount != 2 {
		t.Fatalf("张数=%d want 2（未归类不计入）", albums[0].PhotoCount)
	}
	// 未指定封面 → 回退最新一张（p2）。
	if albums[0].CoverThumb != mediaThumbURL(p2.ID) {
		t.Fatalf("封面=%q want %q", albums[0].CoverThumb, mediaThumbURL(p2.ID))
	}

	// 显式指定封面为较旧那张。
	if err := s.UpdateAlbum(album.ID, pair.ID, nil, &p1.ID); err != nil {
		t.Fatalf("update album: %v", err)
	}
	albums, _ = s.ListAlbums(pair.ID)
	if albums[0].CoverThumb != mediaThumbURL(p1.ID) {
		t.Fatalf("显式封面未生效：%q", albums[0].CoverThumb)
	}

	// 封面照片被软删 → 回退最新可用那张，而不是留空白块。
	if err := s.SetPhotoStatus(p1.ID, pair.ID, 2); err != nil {
		t.Fatalf("soft delete: %v", err)
	}
	albums, _ = s.ListAlbums(pair.ID)
	if albums[0].CoverThumb != mediaThumbURL(p2.ID) {
		t.Fatalf("封面删除后应回退最新一张，得到 %q", albums[0].CoverThumb)
	}
	if albums[0].PhotoCount != 1 {
		t.Fatalf("软删后张数=%d want 1", albums[0].PhotoCount)
	}

	// 改名。
	name := "新名字"
	if err := s.UpdateAlbum(album.ID, pair.ID, &name, nil); err != nil {
		t.Fatalf("rename: %v", err)
	}
	got, _ := s.GetAlbum(album.ID)
	if got.Name != name {
		t.Fatalf("改名失败：%q", got.Name)
	}
}

// 删相册：相册软删，其中照片归为未归类而不被删除。
func Test删相册不连带删照片(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "gil", "hal", "CODEDDDD")
	album, _ := s.CreateAlbum(pair.ID, uid, "临时相册")
	p := addPhoto(t, s, pair.ID, uid, album.ID, "keep", nil)

	if err := s.DeleteAlbum(album.ID, pair.ID); err != nil {
		t.Fatalf("delete album: %v", err)
	}
	gotAlbum, err := s.GetAlbum(album.ID)
	if err != nil {
		t.Fatalf("get album: %v", err)
	}
	if gotAlbum.Status != 2 {
		t.Fatalf("相册应软删，status=%d", gotAlbum.Status)
	}
	if albums, _ := s.ListAlbums(pair.ID); len(albums) != 0 {
		t.Fatalf("已删相册不该出现在列表：%#v", albums)
	}
	// 照片仍在，且已归为未归类。
	gotPhoto, err := s.GetPhoto(p.ID)
	if err != nil {
		t.Fatalf("get photo: %v", err)
	}
	if gotPhoto.Status != 1 {
		t.Fatalf("照片不该被删，status=%d", gotPhoto.Status)
	}
	if gotPhoto.AlbumID != 0 {
		t.Fatalf("照片应归为未归类，album_id=%d", gotPhoto.AlbumID)
	}
	if list, total, _ := s.ListAlbumPhotos(pair.ID, 0, 30, 0); total != 1 || len(list) != 1 {
		t.Fatalf("未归类列表 total=%d len=%d want 1", total, len(list))
	}
}

// 「这一天」：只出历年同月同日；无 EXIF 时退化用入库时间；跨 pair 不串。
func Test这一天日期匹配(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "ivy", "jay", "CODEEEEE")
	other, otherUID, _ := seedPair(t, s, "kim", "leo", "CODEFFFF")

	mk := func(y, m, d, hh int) *time.Time {
		tt := time.Date(y, time.Month(m), d, hh, 30, 0, 0, time.Local)
		return &tt
	}
	hit2024 := addPhoto(t, s, pair.ID, uid, 0, "hit2024", mk(2024, 8, 20, 9))
	hit2025 := addPhoto(t, s, pair.ID, uid, 0, "hit2025", mk(2025, 8, 20, 21))
	addPhoto(t, s, pair.ID, uid, 0, "missDay", mk(2025, 8, 21, 9))
	addPhoto(t, s, pair.ID, uid, 0, "missMonth", mk(2025, 9, 20, 9))
	// 边界：当天 00:05 与 23:55 都必须命中（时区归一化会把这两个挤到隔天）。
	edgeEarly := addPhoto(t, s, pair.ID, uid, 0, "edgeEarly", mk(2023, 8, 20, 0))
	edgeLate := addPhoto(t, s, pair.ID, uid, 0, "edgeLate", mk(2022, 8, 20, 23))
	// 已软删的不该出现。
	deleted := addPhoto(t, s, pair.ID, uid, 0, "deleted", mk(2021, 8, 20, 12))
	if err := s.SetPhotoStatus(deleted.ID, pair.ID, 2); err != nil {
		t.Fatalf("soft delete: %v", err)
	}
	// 另一对情侣同一天的照片不得串进来。
	addPhoto(t, s, other.ID, otherUID, 0, "otherPair", mk(2024, 8, 20, 9))

	got, err := s.PhotosOnThisDay(pair.ID, 8, 20)
	if err != nil {
		t.Fatalf("on this day: %v", err)
	}
	wantIDs := map[int64]bool{
		hit2024.ID: true, hit2025.ID: true, edgeEarly.ID: true, edgeLate.ID: true,
	}
	if len(got) != len(wantIDs) {
		for _, p := range got {
			t.Logf("命中 id=%d taken=%v", p.ID, p.TakenAt)
		}
		t.Fatalf("命中 %d 张，期望 %d 张", len(got), len(wantIDs))
	}
	for _, p := range got {
		if !wantIDs[p.ID] {
			t.Fatalf("不该命中 id=%d taken=%v", p.ID, p.TakenAt)
		}
		if p.TakenAt == nil {
			t.Fatalf("id=%d 拍摄时间丢失", p.ID)
		}
		if int(p.TakenAt.Month()) != 8 || p.TakenAt.Day() != 20 {
			t.Fatalf("id=%d 月/日不符：%v", p.ID, p.TakenAt)
		}
	}
	// 倒序：最新的年份在最前。
	if got[0].ID != hit2025.ID {
		t.Fatalf("应按时间倒序，首个 id=%d want %d", got[0].ID, hit2025.ID)
	}

	// 无 EXIF 的照片退化用 created_at（= 今天），故查今天必然能命中它。
	noExif := addPhoto(t, s, pair.ID, uid, 0, "noexif", nil)
	now := time.Now()
	todays, err := s.PhotosOnThisDay(pair.ID, int(now.Month()), now.Day())
	if err != nil {
		t.Fatalf("on this day today: %v", err)
	}
	found := false
	for _, p := range todays {
		if p.ID == noExif.ID {
			found = true
		}
	}
	if !found {
		t.Fatal("无 EXIF 的照片应按入库时间落在今天")
	}
}

// 回归测试：created_at 由 CURRENT_TIMESTAMP 写入、存的是 **UTC**。
//
// 曾经的实现把它按本地时区解释，于是（UTC+8 下）本地 00:00~08:00 上传的无 EXIF 照片
// 其字面日期是「前一天」，「这一天」会把它们整段漏掉，且返回的 created_at 整体偏 8 小时。
// 这里直接写入 UTC 文本、按本地日期去查，与宿主时区无关。
func Test无EXIF照片按本地日期归属(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "cyn", "dex", "CODEOOOO")

	// 取本地当天 00:30 这个时刻——它的 UTC 字面日期在东八区就是前一天。
	localInstant := time.Date(2026, 8, 20, 0, 30, 0, 0, time.Local)
	createdUTC := localInstant.UTC().Format("2006-01-02 15:04:05")
	res, err := s.DB.Exec(
		`INSERT INTO photo(album_id,pair_id,uploader_id,url,thumb_url,width,height,size_bytes,mime,taken_at,status,created_at)
		 VALUES(0,?,?,?,?,100,100,1024,'image/jpeg',NULL,1,?)`,
		pair.ID, uid, "upload/skew.jpg", "upload/skew_thumb.jpg", createdUTC)
	if err != nil {
		t.Fatalf("insert: %v", err)
	}
	id, _ := res.LastInsertId()

	// 读回来的 created_at 必须等于原始时刻（而非偏一个时区差）。
	got, err := s.GetPhoto(id)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if !got.CreatedAt.Equal(localInstant) {
		t.Fatalf("created_at=%v want %v（UTC 被当成本地时间了）", got.CreatedAt, localInstant)
	}

	// 按本地月/日查得到；按 UTC 字面日期查则查不到（除非宿主本就是 UTC）。
	hits, err := s.PhotosOnThisDay(pair.ID, int(localInstant.Month()), localInstant.Day())
	if err != nil {
		t.Fatalf("on this day: %v", err)
	}
	seen := false
	for _, p := range hits {
		if p.ID == id {
			seen = true
		}
	}
	if !seen {
		t.Fatalf("按本地日期 %02d-%02d 应命中该照片（created_at 是 UTC）",
			int(localInstant.Month()), localInstant.Day())
	}
}

// 粗筛候选集：目标日 + 前后各一天，且跨月/跨年/闰日边界都要正确。
func Test这一天粗筛候选集(t *testing.T) {
	cases := []struct {
		month, day int
		want       []string
	}{
		{8, 20, []string{"08-20", "08-19", "08-21"}},
		{1, 1, []string{"01-01", "12-31", "01-02"}},   // 跨年回到上一年末
		{12, 31, []string{"12-31", "12-30", "01-01"}}, // 跨年到下一年初
		{2, 29, []string{"02-29", "02-28", "03-01"}},
		{2, 28, []string{"02-28", "02-27", "02-29"}},
		{3, 1, []string{"03-01", "02-29", "03-02", "02-28"}}, // 平年库里是 02-28，也要纳入
	}
	for _, tc := range cases {
		got := monthDayCandidates(tc.month, tc.day)
		if len(got) != len(tc.want) {
			t.Fatalf("%02d-%02d 候选=%v want %v", tc.month, tc.day, got, tc.want)
		}
		for i := range got {
			if got[i] != tc.want[i] {
				t.Fatalf("%02d-%02d 候选=%v want %v", tc.month, tc.day, got, tc.want)
			}
		}
	}
	// 非法日期不 panic；由 Go 侧复核保证最终结果为空。
	if got := monthDayCandidates(4, 31); len(got) == 0 {
		t.Fatal("非法日期不应返回空切片（应有候选键，靠复核兜底）")
	}
	if got := monthDayCandidates(13, 1); len(got) == 0 {
		t.Fatal("越界月份不应 panic")
	}
}

// 时间列的两种解释必须分开：taken_at 是本地墙钟，created_at 是 UTC。
func Test时间文本解析区分本地与UTC(t *testing.T) {
	const text = "2024-07-15 18:30:45"
	local, okL := parseSQLiteLocalTime(text)
	if !okL {
		t.Fatal("本地解析失败")
	}
	if local.Hour() != 18 || local.Day() != 15 {
		t.Fatalf("本地解析应保持墙钟 18:30，得到 %v", local)
	}
	utc, okU := parseSQLiteUTCTime(text)
	if !okU {
		t.Fatal("UTC 解析失败")
	}
	// 同一串文本，两种解释相差正好一个本地时区偏移。
	_, offset := local.Zone()
	if diff := local.Sub(utc); diff != time.Duration(offset)*time.Second {
		t.Fatalf("两种解释差值=%v want %v", diff, time.Duration(offset)*time.Second)
	}
	// 驱动可能给出的其他形态也要认。
	for _, s := range []string{
		"2024-07-15T18:30:45Z",
		"2024-07-15T18:30:45+08:00",
		"2024-07-15 18:30:45.123",
		"2024-07-15",
	} {
		if _, okAny := parseSQLiteUTCTime(s); !okAny {
			t.Fatalf("应能解析 %q", s)
		}
	}
	for _, s := range []string{"", "   ", "not a time", "15/07/2024"} {
		if _, okBad := parseSQLiteUTCTime(s); okBad {
			t.Fatalf("%q 不该解析成功", s)
		}
	}
}

func Test月日比较键零填充(t *testing.T) {
	cases := map[string]string{
		monthDayKey(1, 1):   "01-01",
		monthDayKey(8, 20):  "08-20",
		monthDayKey(12, 31): "12-31",
		monthDayKey(2, 29):  "02-29",
	}
	for got, want := range cases {
		if got != want {
			t.Fatalf("monthDayKey=%q want %q", got, want)
		}
	}
	// 与 SQLite 日期文本 "YYYY-MM-DD HH:MM:SS" 的 substr(...,6,5) 位置对齐。
	const sample = "2026-08-20 12:34:56"
	if sample[5:10] != monthDayKey(8, 20) {
		t.Fatalf("substr 位置不对齐：%q vs %q", sample[5:10], monthDayKey(8, 20))
	}
}

// 分页 + 排序：按拍摄时间倒序，无拍摄时间的退化用入库时间（不因 NULL 全部沉底）。
func Test相册内分页与排序(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "mia", "ned", "CODEGGGG")
	album, _ := s.CreateAlbum(pair.ID, uid, "分页")

	var ids []int64
	for i := 1; i <= 5; i++ {
		tt := time.Date(2026, 8, i, 10, 0, 0, 0, time.Local)
		p := addPhoto(t, s, pair.ID, uid, album.ID, "p"+string(rune('0'+i)), &tt)
		ids = append(ids, p.ID)
	}
	page1, total, err := s.ListAlbumPhotos(pair.ID, album.ID, 2, 0)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 5 {
		t.Fatalf("total=%d want 5", total)
	}
	if len(page1) != 2 {
		t.Fatalf("page1 len=%d want 2", len(page1))
	}
	// 倒序：8月5日最新。
	if page1[0].ID != ids[4] || page1[1].ID != ids[3] {
		t.Fatalf("排序错误：%d,%d", page1[0].ID, page1[1].ID)
	}
	page3, _, _ := s.ListAlbumPhotos(pair.ID, album.ID, 2, 4)
	if len(page3) != 1 || page3[0].ID != ids[0] {
		t.Fatalf("末页错误：%#v", page3)
	}
	// 无拍摄时间的照片仍应出现在列表里（COALESCE 退化），不被 NULL 排序吃掉。
	noExif := addPhoto(t, s, pair.ID, uid, album.ID, "noexif", nil)
	all, total, _ := s.ListAlbumPhotos(pair.ID, album.ID, 100, 0)
	if total != 6 {
		t.Fatalf("total=%d want 6", total)
	}
	seen := false
	for _, p := range all {
		if p.ID == noExif.ID {
			seen = true
		}
	}
	if !seen {
		t.Fatal("无 EXIF 的照片在列表里消失了")
	}
}

// 从库里读出来的照片，URL 必须已被换成 /media/<id>，真实路径不出 JSON。
func Test读出的照片URL不含真实路径(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "olu", "pat", "CODEHHHH")
	p := addPhoto(t, s, pair.ID, uid, 0, "secret", nil)

	if p.URL != mediaURL(p.ID) {
		t.Fatalf("url=%q want %q", p.URL, mediaURL(p.ID))
	}
	if p.ThumbURL != mediaThumbURL(p.ID) {
		t.Fatalf("thumb=%q want %q", p.ThumbURL, mediaThumbURL(p.ID))
	}
	if containsAny(p.URL, "/upload", "secret") || containsAny(p.ThumbURL, "/upload", "secret") {
		t.Fatalf("URL 泄露真实路径：%q %q", p.URL, p.ThumbURL)
	}
	// 真实路径确实还在库里（供鉴权代理读盘用）。
	if p.diskPath == "" || !containsAny(p.diskPath, "upload/") {
		t.Fatalf("diskPath 应保留真实相对路径，得到 %q", p.diskPath)
	}
	if p.diskThumb == "" {
		t.Fatal("diskThumb 应保留真实缩略图路径")
	}
	// 列表与「这一天」也必须是同一形态。
	list, _, _ := s.ListAlbumPhotos(pair.ID, 0, 10, 0)
	if len(list) != 1 || list[0].URL != mediaURL(p.ID) {
		t.Fatalf("列表 URL 形态不符：%#v", list)
	}
	summary, err := s.AlbumSummary(pair.ID)
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if summary["latest_thumb_url"] != mediaThumbURL(p.ID) {
		t.Fatalf("概要缩略图=%v want %v", summary["latest_thumb_url"], mediaThumbURL(p.ID))
	}
	if summary["photo_count"] != 1 || summary["album_count"] != 0 {
		t.Fatalf("概要计数错误：%#v", summary)
	}
}

// 点赞幂等（主键去重）与评论只能删自己的。
func Test点赞幂等与评论权限(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, uidB := seedPair(t, s, "qin", "rex", "CODEIIII")
	p := addPhoto(t, s, pair.ID, uidA, 0, "liked", nil)

	// 同一人连点三次只算一次。
	for i := 0; i < 3; i++ {
		if err := s.LikePhoto(p.ID, uidA); err != nil {
			t.Fatalf("like: %v", err)
		}
	}
	count, liked, err := s.PhotoLikeState(p.ID, uidA)
	if err != nil {
		t.Fatalf("like state: %v", err)
	}
	if count != 1 || !liked {
		t.Fatalf("count=%d liked=%v want 1,true", count, liked)
	}
	// 伴侣也点赞 → 2 个赞；对 B 而言 liked=true，取消后回到 false。
	if err := s.LikePhoto(p.ID, uidB); err != nil {
		t.Fatalf("like b: %v", err)
	}
	count, _, _ = s.PhotoLikeState(p.ID, uidA)
	if count != 2 {
		t.Fatalf("count=%d want 2", count)
	}
	if err := s.UnlikePhoto(p.ID, uidB); err != nil {
		t.Fatalf("unlike: %v", err)
	}
	count, likedB, _ := s.PhotoLikeState(p.ID, uidB)
	if count != 1 || likedB {
		t.Fatalf("count=%d likedB=%v want 1,false", count, likedB)
	}
	// 取消不存在的赞不报错（幂等）。
	if err := s.UnlikePhoto(p.ID, uidB); err != nil {
		t.Fatalf("重复取消应幂等：%v", err)
	}

	// 评论：A 发一条，B 删不掉，A 能删。
	cm, err := s.CreatePhotoComment(p.ID, pair.ID, uidA, "好看")
	if err != nil {
		t.Fatalf("comment: %v", err)
	}
	if cm.UserName != "qin" {
		t.Fatalf("评论应带昵称，得到 %q", cm.UserName)
	}
	comments, _ := s.ListPhotoComments(p.ID)
	if len(comments) != 1 {
		t.Fatalf("评论数 %d", len(comments))
	}
	if n, _ := s.DeletePhotoComment(cm.ID, p.ID, uidB); n != 0 {
		t.Fatal("不该能删别人的评论")
	}
	n, err := s.DeletePhotoComment(cm.ID, p.ID, uidA)
	if err != nil {
		t.Fatalf("delete comment: %v", err)
	}
	if n != 1 {
		t.Fatalf("删除自己的评论受影响行=%d want 1", n)
	}
	comments, _ = s.ListPhotoComments(p.ID)
	if len(comments) != 0 {
		t.Fatalf("软删后不该再列出：%#v", comments)
	}
	// 重复删除不再受影响（status 已为 2）。
	if n, _ := s.DeletePhotoComment(cm.ID, p.ID, uidA); n != 0 {
		t.Fatal("重复删除应 0 行")
	}
}

// 回收站：软删进回收站、恢复回正常列表。
func Test回收站软删与恢复(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "sam", "tia", "CODEJJJJ")
	album, _ := s.CreateAlbum(pair.ID, uid, "回收")
	p := addPhoto(t, s, pair.ID, uid, album.ID, "trash", nil)

	if err := s.SetPhotoStatus(p.ID, pair.ID, 2); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, total, _ := s.ListAlbumPhotos(pair.ID, album.ID, 10, 0); total != 0 {
		t.Fatalf("软删后相册仍有 %d 张", total)
	}
	recycled, total, err := s.ListRecycledPhotos(pair.ID, 10, 0)
	if err != nil {
		t.Fatalf("recycled: %v", err)
	}
	if total != 1 || len(recycled) != 1 || recycled[0].ID != p.ID {
		t.Fatalf("回收站内容不符：total=%d %#v", total, recycled)
	}
	if err := s.SetPhotoStatus(p.ID, pair.ID, 1); err != nil {
		t.Fatalf("restore: %v", err)
	}
	if _, total, _ := s.ListAlbumPhotos(pair.ID, album.ID, 10, 0); total != 1 {
		t.Fatalf("恢复后相册张数=%d want 1", total)
	}
	if _, total, _ := s.ListRecycledPhotos(pair.ID, 10, 0); total != 0 {
		t.Fatalf("恢复后回收站应为空，得到 %d", total)
	}
}

// 批量挂入相册：只影响本 pair 名下、且状态正常的照片。
func Test批量挂入相册(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "uma", "vic", "CODEKKKK")
	other, otherUID, _ := seedPair(t, s, "wes", "xia", "CODELLLL")
	album, _ := s.CreateAlbum(pair.ID, uid, "批量")

	p1 := addPhoto(t, s, pair.ID, uid, 0, "m1", nil)
	p2 := addPhoto(t, s, pair.ID, uid, 0, "m2", nil)
	trashed := addPhoto(t, s, pair.ID, uid, 0, "m3", nil)
	if err := s.SetPhotoStatus(trashed.ID, pair.ID, 2); err != nil {
		t.Fatalf("trash: %v", err)
	}
	foreign := addPhoto(t, s, other.ID, otherUID, 0, "m4", nil)

	moved, err := s.MovePhotosToAlbum(pair.ID, album.ID,
		[]int64{p1.ID, p2.ID, trashed.ID, foreign.ID, 999999})
	if err != nil {
		t.Fatalf("move: %v", err)
	}
	if moved != 2 {
		t.Fatalf("moved=%d want 2（回收站/他人/不存在的都不该动）", moved)
	}
	if _, total, _ := s.ListAlbumPhotos(pair.ID, album.ID, 10, 0); total != 2 {
		t.Fatalf("相册张数=%d want 2", total)
	}
	// 空列表不报错也不影响任何行。
	if n, err := s.MovePhotosToAlbum(pair.ID, album.ID, nil); err != nil || n != 0 {
		t.Fatalf("空列表应返回 0,nil：%d %v", n, err)
	}
}

// 后台照片列表：分页 + caption 关键词 + pair 筛选，且不返回任何图片 URL。
func Test后台照片列表不泄露图片地址(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "yan", "zoe", "CODEMMMM")
	other, otherUID, _ := seedPair(t, s, "abe", "bea", "CODENNNN")

	p1 := addPhoto(t, s, pair.ID, uid, 0, "n1", nil)
	if err := s.UpdatePhotoCaption(p1.ID, pair.ID, "海边的日落"); err != nil {
		t.Fatalf("caption: %v", err)
	}
	addPhoto(t, s, pair.ID, uid, 0, "n2", nil)
	addPhoto(t, s, other.ID, otherUID, 0, "n3", nil)

	all, total, err := s.ListPhotosAll("", 0, 10, 0)
	if err != nil {
		t.Fatalf("list all: %v", err)
	}
	if total != 3 || len(all) != 3 {
		t.Fatalf("total=%d len=%d want 3", total, len(all))
	}
	// 绝不能带 url / thumb_url 字段。
	for _, row := range all {
		if _, bad := row["url"]; bad {
			t.Fatal("后台列表不得返回 url")
		}
		if _, bad := row["thumb_url"]; bad {
			t.Fatal("后台列表不得返回 thumb_url")
		}
		if row["uploader_name"] == "" {
			t.Fatal("应带上传者昵称")
		}
	}
	// keyword 搜 caption。
	hit, total, _ := s.ListPhotosAll("日落", 0, 10, 0)
	if total != 1 || len(hit) != 1 || hit[0]["id"] != p1.ID {
		t.Fatalf("keyword 搜索结果不符：total=%d %#v", total, hit)
	}
	if _, total, _ := s.ListPhotosAll("不存在的词", 0, 10, 0); total != 0 {
		t.Fatalf("无匹配应为 0，得到 %d", total)
	}
	// 按 pair 筛选。
	byPair, total, _ := s.ListPhotosAll("", pair.ID, 10, 0)
	if total != 2 || len(byPair) != 2 {
		t.Fatalf("按 pair 筛选 total=%d want 2", total)
	}
	// 后台软删 → 用户回收站可见（可恢复）。
	if err := s.AdminDeletePhoto(p1.ID); err != nil {
		t.Fatalf("admin delete: %v", err)
	}
	got, _ := s.GetPhoto(p1.ID)
	if got.Status != 2 {
		t.Fatalf("后台删除应软删，status=%d", got.Status)
	}
	if _, total, _ := s.ListRecycledPhotos(pair.ID, 10, 0); total != 1 {
		t.Fatalf("后台删除后用户回收站应有 1 张，得到 %d", total)
	}
}
