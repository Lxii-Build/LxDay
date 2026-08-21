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
	defer rows.Close()

	var sb strings.Builder
	sb.WriteString("# 林曦日记 · 日记导出\n\n")
	sb.WriteString(fmt.Sprintf("导出时间：%s\n\n", time.Now().Format("2006-01-02 15:04:05")))
	sb.WriteString("> 本文件是「日记」功能下线前的留档。\n")
	sb.WriteString("> 日记功能已于 2026-08-21 移除，此后不再产生新内容。\n\n---\n\n")

	count := 0
	lastPair := int64(-1)
	for rows.Next() {
		var id, pairID, authorID int64
		var nickname, title, content, diaryDate, createdAt string
		if err := rows.Scan(&id, &pairID, &authorID, &nickname,
			&title, &content, &diaryDate, &createdAt); err != nil {
			slog.Error("scan diary for export failed", "err", err)
			continue
		}
		if pairID != lastPair {
			sb.WriteString(fmt.Sprintf("\n## 情侣 #%d\n\n", pairID))
			lastPair = pairID
		}
		sb.WriteString(fmt.Sprintf("### %s　%s\n\n", diaryDate, title))
		sb.WriteString(fmt.Sprintf("*%s ·  写于 %s*\n\n", nickname, createdAt))
		sb.WriteString(content)
		sb.WriteString("\n\n")

		// 附带图片地址（若有）。日记图片走 /upload 静态路径，
		// 导出后这些 URL 仍可访问，直到管理员清理 uploads 目录。
		// 直接查表而不用 store 方法：store 层的日记方法已随功能一并删除，
		// 这里是唯一还需要读它的地方（导完即可移除本文件）。
		if imgs := diaryImageURLs(id); len(imgs) > 0 {
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
	st.DB.QueryRow(`SELECT COUNT(*) FROM diary_image`).Scan(&imgs)
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
