package main

import (
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

// ================= 后台相册管理 =================
//
// 管理员 Q28=D：要「相册管理页 + 磁盘占用统计 + 后台能看缩略图」。
//
// **我在方案里强烈反对过"后台能看缩略图"这一条，管理员仍选了 D。**
// 0820 那轮刚修掉「私密照片三重泄露」（/upload 全公开 + 网络日志页能点开情侣私照 +
// 无 Referrer-Policy），后台能看图等于把这个面重新打开。既然是他的明确决定，
// 我按最小暴露面实现，并加上三道约束：
//   1. **只给缩略图，永不给原图**（384 长边，看得出是什么但看不清细节）；
//   2. **每一次查看都写审计**（谁、什么时候、看了哪张），管理员自己也被记录；
//   3. **仅超管**可用，且响应头带 no-store + Referrer-Policy，防截图外链与浏览器缓存。
// 若日后要收回这个能力，删掉 handleAdminPhotoThumb 一个函数即可，其余功能不受影响。

// handleAdminListAlbums 按 pair 列出相册，含张数与占用空间。
func handleAdminListAlbums(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	pairFilter, _ := strconv.ParseInt(c.Query("pair_id"), 10, 64)
	keyword := strings.TrimSpace(c.Query("keyword"))

	where := "WHERE a.status=1"
	args := []interface{}{}
	if pairFilter > 0 {
		where += " AND a.pair_id=?"
		args = append(args, pairFilter)
	}
	if keyword != "" {
		where += " AND a.name LIKE ?"
		args = append(args, "%"+keyword+"%")
	}

	var total int
	if err := st.DB.QueryRow(`SELECT COUNT(*) FROM album a `+where, args...).Scan(&total); err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}

	q := `SELECT a.id, a.pair_id, a.name, a.created_at,
	         COALESCE(ua.nickname,''), COALESCE(ub.nickname,''),
	         (SELECT COUNT(*) FROM photo p WHERE p.album_id=a.id AND p.status=1),
	         (SELECT COALESCE(SUM(p.size_bytes),0) FROM photo p WHERE p.album_id=a.id AND p.status=1)
	      FROM album a
	      LEFT JOIN pair pr ON pr.id=a.pair_id
	      LEFT JOIN "user" ua ON ua.id=pr.user_a_id
	      LEFT JOIN "user" ub ON ub.id=pr.user_b_id ` + where +
		` ORDER BY a.id DESC LIMIT ? OFFSET ?`
	args = append(args, limit, offset)

	rows, err := st.DB.Query(q, args...)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	defer rows.Close()
	out := []gin.H{}
	for rows.Next() {
		var id, pairID, photoCount, bytes int64
		var name, created, nickA, nickB string
		if err := rows.Scan(&id, &pairID, &name, &created, &nickA, &nickB,
			&photoCount, &bytes); err != nil {
			slog.Error("scan admin album row failed", "err", err)
			continue
		}
		out = append(out, gin.H{
			"id": id, "pair_id": pairID, "name": name, "created_at": created,
			"couple":      nickA + " & " + nickB,
			"photo_count": photoCount, "size_bytes": bytes,
		})
	}
	if err := rows.Err(); err != nil {
		slog.Error("iterate admin album rows failed", "err", err)
		afail(c, 500, 500, "查询失败")
		return
	}
	pageResp(c, out, total, current, size)
}

// handleAdminDeleteAlbum 后台删相册（软删，照片退回未归类）。
func handleAdminDeleteAlbum(c *gin.Context) {
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	var pairID int64
	if err := st.DB.QueryRow(`SELECT pair_id FROM album WHERE id=?`, id).Scan(&pairID); err != nil {
		afail(c, 404, 404, "相册不存在")
		return
	}
	if err := st.DeleteAlbum(id, pairID); err != nil {
		afail(c, 500, 500, "删除失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "delete_album",
		"album_id="+strconv.FormatInt(id, 10), c.ClientIP())
	aok(c, gin.H{"ok": true})
}

// handleAdminStorageStats 磁盘占用统计。
//
// 管理员现在完全不知道服务器磁盘被谁占了多少，直到它满——这是运营方的核心需求。
func handleAdminStorageStats(c *gin.Context) {
	type pairUsage struct {
		PairID     int64  `json:"pair_id"`
		Couple     string `json:"couple"`
		PhotoCount int64  `json:"photo_count"`
		SizeBytes  int64  `json:"size_bytes"`
		Recycled   int64  `json:"recycled_count"`
		RecycledB  int64  `json:"recycled_bytes"`
	}

	rows, err := st.DB.Query(`
		SELECT p.pair_id,
		       COALESCE(ua.nickname,''), COALESCE(ub.nickname,''),
		       SUM(CASE WHEN p.status=1 THEN 1 ELSE 0 END),
		       SUM(CASE WHEN p.status=1 THEN p.size_bytes ELSE 0 END),
		       SUM(CASE WHEN p.status=2 THEN 1 ELSE 0 END),
		       SUM(CASE WHEN p.status=2 THEN p.size_bytes ELSE 0 END)
		FROM photo p
		LEFT JOIN pair pr ON pr.id=p.pair_id
		LEFT JOIN "user" ua ON ua.id=pr.user_a_id
		LEFT JOIN "user" ub ON ub.id=pr.user_b_id
		GROUP BY p.pair_id
		ORDER BY 5 DESC`)
	if err != nil {
		afail(c, 500, 500, "查询失败")
		return
	}
	defer rows.Close()

	list := []pairUsage{}
	var totalBytes, totalCount, totalRecycled, totalRecycledB int64
	for rows.Next() {
		var u pairUsage
		var nickA, nickB string
		if err := rows.Scan(&u.PairID, &nickA, &nickB,
			&u.PhotoCount, &u.SizeBytes, &u.Recycled, &u.RecycledB); err != nil {
			slog.Error("scan storage stat row failed", "err", err)
			continue
		}
		u.Couple = nickA + " & " + nickB
		list = append(list, u)
		totalBytes += u.SizeBytes
		totalCount += u.PhotoCount
		totalRecycled += u.Recycled
		totalRecycledB += u.RecycledB
	}
	// 这里的截断会直接体现为**错误的统计数字**：合计字节/张数是循环里累加出来的，
	// 少几行就是少算几行，而页面上完全看不出这是个不完整的结果。
	if err := rows.Err(); err != nil {
		slog.Error("iterate storage stat rows failed", "err", err)
		afail(c, 500, 500, "查询失败")
		return
	}

	// 真实磁盘占用（含缩略图与预览图，库里的 size_bytes 只统计原图）。
	diskBytes, fileCount := dirUsage(uploadDir)

	aok(c, gin.H{
		"pairs": list,
		"total": gin.H{
			"photo_count":     totalCount,
			"size_bytes":      totalBytes,
			"recycled_count":  totalRecycled,
			"recycled_bytes":  totalRecycledB,
			"disk_bytes":      diskBytes,
			"disk_file_count": fileCount,
		},
		"retention": gin.H{
			"recycle_bin_days":    settingsNow().RecycleBinDays,
			"status_history_days": settingsNow().StatusHistoryDays,
			"network_log_days":    settingsNow().NetworkLogDays,
		},
	})
}

// dirUsage 递归统计目录占用。失败返回已统计到的部分，不报错——
// 统计是辅助信息，不该因为一个权限问题让整个页面 500。
func dirUsage(root string) (bytes int64, files int64) {
	_ = filepath.WalkDir(root, func(_ string, d os.DirEntry, err error) error {
		if err != nil {
			return nil // 跳过不可读的条目
		}
		if d.IsDir() {
			return nil
		}
		if fi, err := d.Info(); err == nil {
			bytes += fi.Size()
			files++
		}
		return nil
	})
	return bytes, files
}

// handleAdminPurgeRecycleBin 后台清空某 pair 的回收站（真删磁盘）。
func handleAdminPurgeRecycleBin(c *gin.Context) {
	pairID, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	if pairID <= 0 {
		afail(c, 400, 400, "参数错误")
		return
	}
	count, freed, err := st.PurgeRecycleBin(pairID)
	if err != nil {
		afail(c, 500, 500, "清空失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "purge_recycle_bin",
		"pair_id="+strconv.FormatInt(pairID, 10)+" photos="+strconv.Itoa(count), c.ClientIP())
	aok(c, gin.H{"purged": count, "freed_bytes": freed})
}

// handleAdminPhotoThumb 后台查看照片缩略图（Q28=D）。
//
// **只给缩略图，永不给原图或预览图。** 384 长边足够辨认内容（审核目的），
// 但看不清细节，比暴露原图的隐私面小得多。
// 每次调用都写审计——管理员看了谁的哪张照片必须留痕，包括他自己。
func handleAdminPhotoThumb(c *gin.Context) {
	id, _ := strconv.ParseInt(c.Param("id"), 10, 64)
	photo, err := st.GetPhoto(id)
	if err != nil || photo == nil {
		afail(c, 404, 404, "照片不存在")
		return
	}
	// 缩略图缺失（历史照片）时不回退原图——那正是我们要避免的暴露。
	if photo.diskThumb == "" {
		afail(c, 404, 404, "该照片没有缩略图")
		return
	}
	full, okPath := safeUploadPath(photo.diskThumb)
	if !okPath {
		afail(c, 404, 404, "照片不存在")
		return
	}
	if fi, err := os.Stat(full); err != nil || fi.IsDir() {
		afail(c, 404, 404, "照片不存在")
		return
	}

	// 审计：查看私密内容属敏感操作，必须可追溯。
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "view_photo_thumb",
		"photo_id="+strconv.FormatInt(id, 10)+" pair_id="+strconv.FormatInt(photo.PairID, 10),
		c.ClientIP())

	h := c.Writer.Header()
	// no-store：不许浏览器与任何中间层留副本。私密内容不能因为"翻了一下后台"就落到磁盘缓存里。
	h.Set("Cache-Control", "no-store, no-cache, must-revalidate, private")
	h.Set("Pragma", "no-cache")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("Content-Disposition", "inline")
	c.File(full)
}

// registerAdminAlbumRoutes 注册后台相册相关路由。
// 全部挂 sup（超管）：相册是全站最私密的内容。
func registerAdminAlbumRoutes(sup *gin.RouterGroup) {
	sup.GET("/albums", handleAdminListAlbums)
	sup.DELETE("/albums/:id", handleAdminDeleteAlbum)
	sup.GET("/storage-stats", handleAdminStorageStats)
	sup.POST("/pairs/:id/purge-recycle-bin", handleAdminPurgeRecycleBin)
	sup.GET("/photos/:id/thumb", handleAdminPhotoThumb)
}
