package main

import (
	"testing"
	"time"
)

// coldSiteBaseCache 把站点地址缓存打回未加载，模拟进程刚启动。
//
// 刻意不复用 invalidateSiteBaseCache —— 它（作为本轮修法的一部分）现在会立即重载，
// 那正是我们要验证的行为，用它就没法造出冷态了。
func coldSiteBaseCache() {
	siteBaseCache.Lock()
	siteBaseCache.val = ""
	siteBaseCache.loaded = false
	siteBaseCache.Unlock()
}

// Test回收站清理在冷缓存下不死锁 复现「容器启动即全站瘫痪」。
//
// ## 根因
//
// scanPhoto 结尾会调 mediaURL/mediaThumbURL → mediaPathURL → siteBaseURL()。
// siteBaseURL 是**惰性缓存**：loaded==false 时会真发一次 GetSetting("site.url")。
//
// 而 scanPhoto 被 4 处 `for rows.Next()` 循环调用（album_store.go:200/225/599、
// album_purge.go:144）。MaxOpenConns(1) 下，遍历 rows 期间那条唯一连接被占着，
// 此刻再发查询就要排队等它释放 —— 而它要等遍历结束才释放。**自己等自己。**
// QueryRow 用的是 context.Background()，没有超时，所以是永久阻塞，
// 那条连接再也不回池，全站所有 DB 操作随之挂死。
//
// siteBaseCache 的注释（handlers.go:53-58）正确描述了这个死锁，
// 但缓存只在**第一次调用之后**才生效 —— 第一次调用本身仍要查库。
//
// ## 为什么之前没被发现
//
// 启动路径上无人预热该缓存：reloadRuntimeSettings() 只遍历 runtimeSettingSpecs，
// 而 site.url 不在其中（它属于 settingKeys，只有后台 GET /settings 才读）。
// 最确定的触发点是 startRequestLogWorker 里那句**同步先跑一次**的
// runRetentionCleanup() → PurgeExpiredRecycleBin → listRecycledForPurge。
//
//   - 新库没有回收站数据 → 循环体一次都不执行 → 撞不到；
//   - 已有测试也撞不到 —— newTestStore 不设全局 st，
//     而 siteBaseURL 在 st==nil 时直接返回空串（handlers.go:74），走不到查库分支。
//
// 所以这条路径只在「生产库跑过保留期、有过期回收站照片」时炸，
// 且一炸就是容器起来后整个服务不可用。
//
// ## 这个测试怎么保证测到
//
//  1. withTestStore 设置全局 st（否则 siteBaseURL 短路成空串）
//  2. resetSiteBaseCache 把缓存打回未加载（模拟进程刚启动的冷缓存）
//  3. 造一条已过期的回收站照片，让循环体真的执行到 scanPhoto
//  4. 整个调用放在带超时的 goroutine 里 —— 死锁时表现为超时失败而非测试挂住。
//     不加超时，这个测试自己会卡到 go test 整包超时，看起来像"测试环境有问题"
//     而不是"发现了 bug"。
func Test回收站清理在冷缓存下不死锁(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "dla", "dlb", "DLCODE01")

	p, _ := seedPhotoWithFiles(t, s, pair.ID, uidA, "expired")
	// 软删并把 deleted_at 推到 40 天前（默认保留 30 天，必然过期）。
	if _, err := s.DB.Exec(
		`UPDATE photo SET status=2, deleted_at=datetime('now','-40 days') WHERE id=?`, p.ID,
	); err != nil {
		t.Fatalf("造过期回收站照片失败: %v", err)
	}

	// **关键前置**：打回冷缓存。不做这一步，同包里跑过的其它测试
	// 可能已经把它烘热，死锁就复现不出来（这个测试也就变成了摆设）。
	coldSiteBaseCache()

	done := make(chan error, 1)
	go func() {
		_, _, err := s.PurgeExpiredRecycleBin(30)
		done <- err
	}()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("清理回收站失败: %v", err)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("PurgeExpiredRecycleBin 在冷缓存下死锁了：" +
			"scanPhoto → mediaURL → siteBaseURL 在 rows 遍历中发起查询，" +
			"MaxOpenConns(1) 下自己等自己。修法见 main.go 的 warmCaches。")
	}
}

// Test照片列表在冷缓存下不死锁 覆盖 HTTP 路径上的同一条死锁。
//
// 生产上通常有别的请求先把缓存烘热，所以不如清理任务那么必然；
// 但「容器刚起来、第一个请求就是打开相册」时同样成立。
func Test照片列表在冷缓存下不死锁(t *testing.T) {
	s := withTestStore(t)
	pair, uidA, _ := seedPair(t, s, "dlc", "dld", "DLCODE02")
	album, err := s.CreateAlbum(pair.ID, uidA, "测试相册")
	if err != nil {
		t.Fatalf("建相册失败: %v", err)
	}
	addPhoto(t, s, pair.ID, uidA, album.ID, "cold", nil)

	coldSiteBaseCache()

	done := make(chan error, 1)
	go func() {
		_, _, err := s.ListAlbumPhotos(pair.ID, album.ID, 20, 0)
		done <- err
	}()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("列相册照片失败: %v", err)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("ListAlbumPhotos 在冷缓存下死锁了（同 scanPhoto → siteBaseURL 链路）")
	}
}

// Test预热后站点地址缓存立即可用 确认修法本身有效：
// 预热过一次之后，siteBaseURL 不再需要查库，rows 遍历中调用它是安全的。
func Test预热后站点地址缓存立即可用(t *testing.T) {
	s := withTestStore(t)
	if err := s.SetSetting("site.url", "https://love.example.com/"); err != nil {
		t.Fatalf("写 site.url 失败: %v", err)
	}
	coldSiteBaseCache()
	warmSiteBaseCache()

	if got := siteBaseURL(); got != "https://love.example.com" {
		t.Fatalf("预热后 siteBaseURL=%q，期望已去尾斜杠的 https://love.example.com", got)
	}
	// 预热后 mediaThumbURL 应给出绝对地址，且不再触发查库。
	if got := mediaThumbURL(7); got != "https://love.example.com/media/7/thumb" {
		t.Fatalf("mediaThumbURL=%q", got)
	}
}
