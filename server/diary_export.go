package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// ================= 日记导出（功能下线前的留档） =================
//
// 管理员 Q33=B：删日记功能之前给一次导出机会。
// Q56 补充「写过，删掉了」——库里可能还有他和伴侣写过的日记行。
//
// 日记是**不可再生的个人内容**（两个人写下的文字），
// 删功能之前不给导出就等于永久销毁。这个接口只在本轮存在，
// 导完即可随下一轮清理掉；留着也无害（只读、限超管、走审计）。
//
// 输出 Markdown 而非 JSON：管理员是要"留个纪念"，
// Markdown 能直接读、能存进笔记软件，JSON 得再转一手。

// handleAdminExportDiaries 把全站日记导出为 Markdown 文本。
//
// 限超管：日记是情侣私密内容，与相册同级。
// 每次导出写审计——谁在什么时候导出了全站日记，必须留痕。
func handleAdminExportDiaries(c *gin.Context) {
	rows, err := st.DB.Query(`
		SELECT d.id, d.pair_id, d.author_id, COALESCE(u.nickname,''),
		       d.title, d.content, d.diary_date, d.created_at
		FROM diary d
		LEFT JOIN "user" u ON u.id = d.author_id
		ORDER BY d.pair_id ASC, d.diary_date ASC, d.id ASC`)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	// 刻意不在这里 `defer rows.Close()`：rows 必须在**取图片地址之前**就关掉
	// （否则死锁，见下），关闭责任在下面那个立即执行的闭包里。
	// 放在这里会让人误以为连接一直被占到函数返回。

	var sb strings.Builder
	sb.WriteString("# 林曦日记 · 日记导出\n\n")
	sb.WriteString(fmt.Sprintf("导出时间：%s\n\n", time.Now().Format("2006-01-02 15:04:05")))
	sb.WriteString("> 本文件是「日记」功能下线前的留档。\n")
	sb.WriteString("> 日记功能已于 2026-08-21 移除，此后不再产生新内容。\n\n---\n\n")

	// **先把行全部读进内存并关闭 rows，再去取图片地址。**
	//
	// 不能在 `for rows.Next()` 里调 diaryImageURLs：它内部会再发一次 Query，
	// 而 SQLite 连接池是 MaxOpenConns(1)（见 main.go）——外层 rows 正占着那条
	// 唯一连接、且要等遍历结束才释放，内层查询于是永久等待。**自己等自己。**
	// 结果不只是这个请求挂住：那条连接再也不回池，**全站所有 DB 操作随之瘫痪**，
	// 只能重启容器。等于后台多了个"点一下就把生产打死"的按钮。
	//
	// 新库撞不到（schema 里已无 diary 表，外层 Query 直接报错返回），
	// 只有从老库升级上来、diary 表还在的实例才会炸 —— 而那正是需要导出的实例。
	// 同类修法见 album_store.go 的 ListAlbums。
	type diaryRow struct {
		id                                             int64
		pairID                                         int64
		nickname, title, content, diaryDate, createdAt string
	}
	var items []diaryRow
	func() {
		defer rows.Close()
		for rows.Next() {
			var r diaryRow
			var authorID int64
			if err := rows.Scan(&r.id, &r.pairID, &authorID, &r.nickname,
				&r.title, &r.content, &r.diaryDate, &r.createdAt); err != nil {
				slog.Error("scan diary for export failed", "err", err)
				continue
			}
			items = append(items, r)
		}
		if err := rows.Err(); err != nil {
			slog.Error("iterate diaries for export failed", "err", err)
		}
	}()

	count := 0
	lastPair := int64(-1)
	for _, r := range items {
		if r.pairID != lastPair {
			sb.WriteString(fmt.Sprintf("\n## 情侣 #%d\n\n", r.pairID))
			lastPair = r.pairID
		}
		sb.WriteString(fmt.Sprintf("### %s　%s\n\n", r.diaryDate, r.title))
		sb.WriteString(fmt.Sprintf("*%s ·  写于 %s*\n\n", r.nickname, r.createdAt))
		sb.WriteString(r.content)
		sb.WriteString("\n\n")

		// rows 已关闭，此时再查图片地址是安全的。
		// 日记图片走 /upload 静态路径，导出后这些 URL 仍可访问，
		// 直到管理员清理 uploads 目录。
		if imgs := diaryImageURLs(r.id); len(imgs) > 0 {
			sb.WriteString("图片：\n")
			for _, u := range imgs {
				sb.WriteString(fmt.Sprintf("- %s\n", u))
			}
			sb.WriteString("\n")
		}
		sb.WriteString("---\n\n")
		count++
	}

	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "export_diaries",
		fmt.Sprintf("count=%d", count), c.ClientIP())

	if count == 0 {
		sb.WriteString("_（库里没有日记记录）_\n")
	}

	filename := fmt.Sprintf("linxi-diaries-%s.md", time.Now().Format("20060102-150405"))
	c.Header("Content-Disposition", `attachment; filename="`+filename+`"`)
	c.Header("Content-Type", "text/markdown; charset=utf-8")
	// 私密内容：禁止任何缓存留副本。
	c.Header("Cache-Control", "no-store, no-cache, must-revalidate, private")
	c.String(http.StatusOK, sb.String())
}

// diaryImageURLs 读某条日记的配图 URL。
func diaryImageURLs(diaryID int64) []string {
	rows, err := st.DB.Query(
		`SELECT url FROM diary_image WHERE diary_id=? ORDER BY sort_no`, diaryID)
	if err != nil {
		return nil
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var u string
		if err := rows.Scan(&u); err != nil {
			slog.Error("scan diary_image for export failed", "err", err)
			continue
		}
		out = append(out, u)
	}
	// 导出是一次性留档，遍历出错时少列几张图就永久少了 —— 至少要留痕。
	// 这里不返回 error：调用方把图片当锦上添花处理（拿不到就不列），
	// 为它中断整个导出反而更糟。
	if err := rows.Err(); err != nil {
		slog.Error("iterate diary_image for export failed", "err", err, "diary_id", diaryID)
	}
	return out
}

// handleAdminDiaryCount 导出前先让前端知道有多少条（决定要不要提示"库里是空的"）。
func handleAdminDiaryCount(c *gin.Context) {
	var n int
	if err := st.DB.QueryRow(`SELECT COUNT(*) FROM diary`).Scan(&n); err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	var imgs int
	// diary_image 可能已被 purge 删表，取不到就按 0 报，不阻断 ——
	// 上面那条 diary 的计数才是这个接口的主要用途。
	if err := st.DB.QueryRow(`SELECT COUNT(*) FROM diary_image`).Scan(&imgs); err != nil {
		slog.Warn("count diary_image failed", "err", err)
	}
	aok(c, gin.H{"diaries": n, "images": imgs})
}

// handleAdminPurgeDiaries 显式删除日记表（Q31=D：彻底断根）。
//
// **必须由管理员显式触发，不放在自动迁移里。**
// 若在 runMigrations 里自动 DROP，新镜像一上线日记就没了——
// 而管理员还没来得及导出留档，那个导出接口就成了废物。
//
// 需要带 `?confirm=DROP` 才真执行：这是不可逆操作，多一道显式确认。
func handleAdminPurgeDiaries(c *gin.Context) {
	if c.Query("confirm") != "DROP" {
		afail(c, http.StatusBadRequest, 400,
			"这是不可逆操作。请先导出留档，再带 confirm=DROP 重试")
		return
	}
	dropped := DropRetiredTables(st.DB)
	total := 0
	for _, n := range dropped {
		total += n
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "purge_diary_tables",
		fmt.Sprintf("tables=%v rows=%d", dropped, total), c.ClientIP())
	aok(c, gin.H{"dropped": dropped, "rows": total})
}

// registerDiaryExportRoutes 挂日记留档与清理接口，全部限超管。
// 导完并确认删除后，本文件与这三条路由即可一并移除。
func registerDiaryExportRoutes(sup *gin.RouterGroup) {
	sup.GET("/diaries/export", handleAdminExportDiaries)
	sup.GET("/diaries/count", handleAdminDiaryCount)
	sup.POST("/diaries/purge", handleAdminPurgeDiaries)
}
