package main

import (
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// ================= 相册接口 =================

const (
	maxAlbumNameLen = 32
	maxCaptionLen   = 500
	maxCommentLen   = 500
	// maxAttachPerCall 单次挂入相册的照片数上限，防一次请求塞进上万个 id。
	maxAttachPerCall = 200
)

// ---------- 相册 CRUD ----------

func handleListAlbums(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	albums, err := st.ListAlbums(pair.ID)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	ok(c, albums)
}

func handleCreateAlbum(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	var req struct {
		Name string `json:"name" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, 1002, "参数错误")
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" || len([]rune(name)) > maxAlbumNameLen {
		fail(c, http.StatusBadRequest, 1002, "相册名长度 1-32")
		return
	}
	album, err := st.CreateAlbum(pair.ID, currentUID(c), name)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "创建失败")
		return
	}
	ok(c, album)
}

// getOwnedAlbum 取相册并校验归属当前 pair（同 getOwnedTodo：防遍历 id 改删他人相册）。
func getOwnedAlbum(pair *Pair, id int64) (*Album, bool) {
	a, err := st.GetAlbum(id)
	if err != nil || a == nil || a.PairID != pair.ID || a.Status != 1 {
		return nil, false
	}
	return a, true
}

// handleAlbumByID 分派 GET /albums/:id。
//
// 为什么要手动分派：gin 的路由树不允许同一层级同时存在静态段与通配段，
// 注册 /albums/summary 与 /albums/:id 会在启动时 panic（整个服务起不来）。
// 故只注册通配路由，在 handler 内识别保留字 summary。
func handleAlbumByID(c *gin.Context) {
	if c.Param("id") == "summary" {
		handleAlbumSummary(c)
		return
	}
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "相册 ID 非法")
		return
	}
	album, owned := getOwnedAlbum(pair, id)
	if !owned {
		fail(c, http.StatusForbidden, 1017, "无权访问该相册")
		return
	}
	_, total, err := st.ListAlbumPhotos(pair.ID, album.ID, 1, 0)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	album.PhotoCount = total
	ok(c, album)
}

func handleAlbumSummary(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	summary, err := st.AlbumSummary(pair.ID)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	ok(c, summary)
}

func handleUpdateAlbum(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id < 0 {
		fail(c, http.StatusBadRequest, 1002, "相册 ID 非法")
		return
	}
	// id=0 是虚拟的「未归类」：它不是真实相册行，走单独分支只改显示名
	//（管理员 Q22 附言：「未归类也要能更改名字」）。封面/删除对它无意义。
	if id == 0 {
		handleRenameUnclassified(c, pair)
		return
	}
	if _, owned := getOwnedAlbum(pair, id); !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该相册")
		return
	}
	var req struct {
		Name         *string `json:"name"`
		CoverPhotoID *int64  `json:"cover_photo_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, 1002, "参数错误")
		return
	}
	if req.Name != nil {
		name := strings.TrimSpace(*req.Name)
		if name == "" || len([]rune(name)) > maxAlbumNameLen {
			fail(c, http.StatusBadRequest, 1002, "相册名长度 1-32")
			return
		}
		req.Name = &name
	}
	// 封面必须是本 pair 名下的正常照片：否则可把别人的照片 id 设成自己相册封面，
	// 再借相册列表接口把它的缩略图读出来（越权读图）。
	if req.CoverPhotoID != nil && *req.CoverPhotoID != 0 {
		p, err := st.GetPhoto(*req.CoverPhotoID)
		if err != nil || p == nil || p.PairID != pair.ID || p.Status != 1 {
			fail(c, http.StatusForbidden, 1017, "封面照片无效")
			return
		}
	}
	if err := st.UpdateAlbum(id, pair.ID, req.Name, req.CoverPhotoID); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "更新失败")
		return
	}
	album, err := st.GetAlbum(id)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "读取失败")
		return
	}
	ok(c, album)
}

func handleDeleteAlbum(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "相册 ID 非法")
		return
	}
	if _, owned := getOwnedAlbum(pair, id); !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该相册")
		return
	}
	if err := st.DeleteAlbum(id, pair.ID); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "删除失败")
		return
	}
	ok(c, gin.H{"deleted": id})
}

// ---------- 相册内照片 ----------

func handleListAlbumPhotos(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id < 0 {
		fail(c, http.StatusBadRequest, 1002, "相册 ID 非法")
		return
	}
	// album_id=0 是「未归类」虚拟相册，本身没有 album 行，不做归属校验（照片自带 pair_id）。
	if id != 0 {
		if _, owned := getOwnedAlbum(pair, id); !owned {
			fail(c, http.StatusForbidden, 1017, "无权访问该相册")
			return
		}
	}
	page, size := photoPageParams(c)
	list, total, err := st.ListAlbumPhotos(pair.ID, id, size, (page-1)*size)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	ok(c, gin.H{"list": list, "total": total, "page": page, "size": size})
}

// photoPageParams 解析 ?page=&size=，收敛到 1..100（默认 30，与网格三列布局匹配）。
func photoPageParams(c *gin.Context) (page, size int) {
	page, _ = strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ = strconv.Atoi(c.DefaultQuery("size", "30"))
	if page < 1 {
		page = 1
	}
	if size < 1 || size > 100 {
		size = 30
	}
	return page, size
}

// handleAttachPhotos 把已上传的照片挂入相册（批量）。
//
// 兼容两种入参：{photo_ids:[..]} 与 {photos:[{url:"/media/12"},..]}——
// 后者让客户端可以把 /media 的返回体原样回传，不必自己拆 id。
func handleAttachPhotos(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	albumID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || albumID <= 0 {
		fail(c, http.StatusBadRequest, 1002, "相册 ID 非法")
		return
	}
	album, owned := getOwnedAlbum(pair, albumID)
	if !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该相册")
		return
	}
	var req struct {
		PhotoIDs []int64 `json:"photo_ids"`
		Photos   []struct {
			ID  int64  `json:"id"`
			URL string `json:"url"`
		} `json:"photos"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, 1002, "参数错误")
		return
	}
	ids := append([]int64{}, req.PhotoIDs...)
	for _, p := range req.Photos {
		switch {
		case p.ID > 0:
			ids = append(ids, p.ID)
		case p.URL != "":
			if id, okURL := photoIDFromMediaURL(p.URL); okURL {
				ids = append(ids, id)
			}
		}
	}
	if len(ids) == 0 {
		fail(c, http.StatusBadRequest, 1002, "请提供要加入相册的照片")
		return
	}
	if len(ids) > maxAttachPerCall {
		fail(c, http.StatusBadRequest, 1002, "单次最多加入 200 张")
		return
	}
	moved, err := st.MovePhotosToAlbum(pair.ID, albumID, ids)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "操作失败")
		return
	}
	if moved > 0 {
		// 通知伴侣有新照片。走普通事件（默认 route）：离线可补拉，
		// 但不进 highPriorityEvents——照片不是紧急事件，不该占用离线补偿的高优通道。
		hub.Notify(pair, uid, WsMessage{Type: MsgAlbumNew, Data: gin.H{
			"album_id": albumID, "album_name": album.Name,
			"count": moved, "ts": time.Now().UnixMilli(),
		}})
	}
	ok(c, gin.H{"attached": moved, "album_id": albumID})
}

// ---------- 单张照片 ----------

// getOwnedPhoto 取正常状态的照片并校验归属当前 pair。
func getOwnedPhoto(pair *Pair, id int64) (*Photo, bool) {
	p, err := st.GetPhoto(id)
	if err != nil || p == nil || p.PairID != pair.ID || p.Status != 1 {
		return nil, false
	}
	return p, true
}

// getOwnedPhotoAnyStatus 只给回收站恢复路径使用；普通读写/点赞/评论接口
// 不应继续操作已经软删的照片。
func getOwnedPhotoAnyStatus(pair *Pair, id int64) (*Photo, bool) {
	p, err := st.GetPhoto(id)
	if err != nil || p == nil || p.PairID != pair.ID {
		return nil, false
	}
	return p, true
}

// handlePhotoByID 分派 GET /photos/:id；on-this-day 与 recycled 是保留字，原因同 handleAlbumByID。
// handleRenameUnclassified 改「未归类」的显示名（PUT /albums/0）。
//
// 复用同一个接口而不是新开一条，客户端就不必为"这是虚拟相册"分叉出第二套改名逻辑。
// 传空串或与缺省同名 → 恢复缺省「未归类」。
func handleRenameUnclassified(c *gin.Context, pair *Pair) {
	var req struct {
		Name *string `json:"name"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == nil {
		fail(c, http.StatusBadRequest, 1002, "参数错误")
		return
	}
	name := strings.TrimSpace(*req.Name)
	if len([]rune(name)) > maxAlbumNameLen {
		fail(c, http.StatusBadRequest, 1002,
			fmt.Sprintf("名称最长 %d 个字", maxAlbumNameLen))
		return
	}
	if err := st.SetUnclassifiedName(pair.ID, name); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "更新失败")
		return
	}
	ok(c, gin.H{"id": 0, "name": st.UnclassifiedName(pair.ID)})
}

// handlePhotoActionByID 把 POST /photos/<保留字> 分派到批量操作。
// gin 不允许同层同时注册静态段与通配段，故沿用既有的通配分派套路。
func handlePhotoActionByID(c *gin.Context) {
	switch c.Param("id") {
	case "batch-delete":
		handleBatchDeletePhotos(c)
	case "batch-move":
		handleBatchMovePhotos(c)
	case "purge-all":
		handlePurgeRecycleBin(c)
	default:
		fail(c, http.StatusNotFound, 1002, "接口不存在")
	}
}

func handlePhotoByID(c *gin.Context) {
	switch c.Param("id") {
	case "on-this-day":
		// 「这一天」开关的服务端校验。此前只有客户端隐藏入口，
		// 关掉之后接口照样返回数据——对旧版 App 与直接调接口的人完全无效。
		// 这条是 GET，所以不能靠 requireAlbumEnabled（它放行全部 GET）来兜。
		if !settingsNow().OnThisDayEnabled {
			fail(c, http.StatusForbidden, codeUploadDisabled, "「这一天」功能当前已关闭")
			return
		}
		handlePhotosOnThisDay(c)
		return
	case "recycled":
		handleListRecycledPhotos(c)
		return
	}
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	photo, owned := getOwnedPhoto(pair, id)
	if !owned {
		fail(c, http.StatusForbidden, 1017, "无权访问该照片")
		return
	}
	comments, err := st.ListPhotoComments(id)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	likeCount, liked, err := st.PhotoLikeState(id, uid)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	ok(c, gin.H{
		"photo": photo, "comments": comments,
		"like_count": likeCount, "liked": liked,
	})
}

func handleUpdatePhoto(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	photo, owned := getOwnedPhoto(pair, id)
	if !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该照片")
		return
	}
	var req struct {
		Caption *string `json:"caption"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Caption == nil {
		fail(c, http.StatusBadRequest, 1002, "参数错误")
		return
	}
	caption := strings.TrimSpace(*req.Caption)
	if len([]rune(caption)) > maxCaptionLen {
		fail(c, http.StatusBadRequest, 1002, "描述不能超过 500 字")
		return
	}
	if err := st.UpdatePhotoCaption(id, pair.ID, caption); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "更新失败")
		return
	}
	photo, err = st.GetPhoto(id)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "读取失败")
		return
	}
	ok(c, photo)
}

// handleDeletePhoto 软删进回收站（status=2）。
// 不删盘上文件：照片是不可再生资产，误删必须可恢复，故只改状态。
func handleDeletePhoto(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	if _, owned := getOwnedPhoto(pair, id); !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该照片")
		return
	}
	if err := st.SetPhotoStatus(id, pair.ID, 2); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "删除失败")
		return
	}
	ok(c, gin.H{"deleted": id})
}

func handleRestorePhoto(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	photo, owned := getOwnedPhotoAnyStatus(pair, id)
	if !owned || photo.Status != 2 {
		fail(c, http.StatusForbidden, 1017, "无权操作该照片")
		return
	}
	if err := st.SetPhotoStatus(id, pair.ID, 1); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "恢复失败")
		return
	}
	photo, err = st.GetPhoto(id)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "读取失败")
		return
	}
	ok(c, photo)
}

// handleListRecycledPhotos 回收站列表，供客户端做「最近删除」页。
func handleListRecycledPhotos(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	page, size := photoPageParams(c)
	list, total, err := st.ListRecycledPhotos(pair.ID, size, (page-1)*size)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	// 带上「还剩几天被自动彻底删除」（Q21=C）：
	// 不告知的话用户会把回收站当永久保险箱，照片自己没了会来找我。
	keep := settingsNow().RecycleBinDays
	// **必须用下标遍历**：list 是 []Photo（值切片），`for _, p := range list`
	// 拿到的是副本，往副本上写 RecycleRemainingDays 等于什么都没做 ——
	// 而该字段是 `*int` + `omitempty`，写不进去就直接从 JSON 里消失，
	// 客户端拿不到「还剩几天自动删除」，回收站页只能显示空白。
	for i := range list {
		d := recycleRemainingDays(list[i].deletedAt, list[i].CreatedAt, keep)
		list[i].RecycleRemainingDays = &d
	}
	ok(c, gin.H{
		"list": list, "total": total, "page": page, "size": size,
		"keep_days": keep,
	})
}

// handlePurgePhoto 彻底删除一张回收站里的照片（真删磁盘文件，不可恢复）。
func handlePurgePhoto(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	freed, err := st.PurgePhoto(id, pair.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			// 不在回收站 / 不属于本 pair / 不存在 —— 一律同一个响应，
			// 区别对待等于给出「该 id 存在」的探测信号。
			fail(c, http.StatusForbidden, 1017, "该照片不在回收站中")
			return
		}
		slog.Error("purge photo failed", "photo_id", id, "err", err)
		fail(c, http.StatusInternalServerError, 1010, "彻底删除失败")
		return
	}
	ok(c, gin.H{"purged": id, "freed_bytes": freed})
}

// handlePurgeRecycleBin 清空回收站（真删磁盘文件，不可恢复）。
func handlePurgeRecycleBin(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	count, freed, err := st.PurgeRecycleBin(pair.ID)
	if err != nil {
		slog.Error("purge recycle bin failed", "pair_id", pair.ID, "err", err)
		fail(c, http.StatusInternalServerError, 1010, "清空回收站失败")
		return
	}
	ok(c, gin.H{"purged": count, "freed_bytes": freed})
}

// handleBatchDeletePhotos 批量软删（网格多选删除）。
func handleBatchDeletePhotos(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	var req struct {
		PhotoIDs []int64 `json:"photo_ids"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.PhotoIDs) == 0 {
		fail(c, http.StatusBadRequest, 1002, "请选择要删除的照片")
		return
	}
	if len(req.PhotoIDs) > maxAttachPerCall {
		fail(c, http.StatusBadRequest, 1002,
			fmt.Sprintf("单次最多操作 %d 张", maxAttachPerCall))
		return
	}
	n, err := st.SetPhotosStatus(pair.ID, req.PhotoIDs, 2)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "删除失败")
		return
	}
	ok(c, gin.H{"deleted": n})
}

// handleBatchMovePhotos 批量移动到相册（多选「移动到」）。
// albumID=0 表示移出相册、退回「未归类」。
func handleBatchMovePhotos(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	var req struct {
		PhotoIDs []int64 `json:"photo_ids"`
		AlbumID  int64   `json:"album_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.PhotoIDs) == 0 {
		fail(c, http.StatusBadRequest, 1002, "请选择要移动的照片")
		return
	}
	if len(req.PhotoIDs) > maxAttachPerCall {
		fail(c, http.StatusBadRequest, 1002,
			fmt.Sprintf("单次最多操作 %d 张", maxAttachPerCall))
		return
	}
	// 目标相册必须属于本 pair（album_id=0 是虚拟的「未归类」，无需校验）。
	if req.AlbumID != 0 {
		if _, owned := getOwnedAlbum(pair, req.AlbumID); !owned {
			fail(c, http.StatusForbidden, 1017, "无权操作该相册")
			return
		}
	}
	n, err := st.MovePhotosToAlbum(pair.ID, req.AlbumID, req.PhotoIDs)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "移动失败")
		return
	}
	ok(c, gin.H{"moved": n})
}

// ---------- 点赞 / 评论 ----------

func handleLikePhoto(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	if _, owned := getOwnedPhoto(pair, id); !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该照片")
		return
	}
	if err := st.LikePhoto(id, uid); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "操作失败")
		return
	}
	count, liked, err := st.PhotoLikeState(id, uid)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	ok(c, gin.H{"like_count": count, "liked": liked})
}

func handleUnlikePhoto(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	if _, owned := getOwnedPhoto(pair, id); !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该照片")
		return
	}
	if err := st.UnlikePhoto(id, uid); err != nil {
		fail(c, http.StatusInternalServerError, 1010, "操作失败")
		return
	}
	count, liked, err := st.PhotoLikeState(id, uid)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	ok(c, gin.H{"like_count": count, "liked": liked})
}

func handleCreatePhotoComment(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	if _, owned := getOwnedPhoto(pair, id); !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该照片")
		return
	}
	var req struct {
		Content string `json:"content" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		fail(c, http.StatusBadRequest, 1002, "参数错误")
		return
	}
	content := strings.TrimSpace(req.Content)
	if content == "" || len([]rune(content)) > maxCommentLen {
		fail(c, http.StatusBadRequest, 1002, "评论长度 1-500")
		return
	}
	cm, err := st.CreatePhotoComment(id, pair.ID, uid, content)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "评论失败")
		return
	}
	ok(c, cm)
}

// handleDeletePhotoComment 只能删自己的评论：user_id 一并进 WHERE，受影响行数为 0 即视为越权。
func handleDeletePhotoComment(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	uid := currentUID(c)
	photoID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || photoID <= 0 {
		fail(c, http.StatusBadRequest, 1002, "照片 ID 非法")
		return
	}
	commentID, err := strconv.ParseInt(c.Param("cid"), 10, 64)
	if err != nil || commentID <= 0 {
		fail(c, http.StatusBadRequest, 1002, "评论 ID 非法")
		return
	}
	if _, owned := getOwnedPhoto(pair, photoID); !owned {
		fail(c, http.StatusForbidden, 1017, "无权操作该照片")
		return
	}
	n, err := st.DeletePhotoComment(commentID, photoID, uid)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "删除失败")
		return
	}
	if n == 0 {
		fail(c, http.StatusForbidden, 1017, "只能删除自己的评论")
		return
	}
	ok(c, gin.H{"deleted": commentID})
}

// ---------- 「这一天」 ----------

// handlePhotosOnThisDay 历年同月同日的照片。缺省用服务器当天（客户端可用 ?month=&day= 指定）。
func handlePhotosOnThisDay(c *gin.Context) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	now := time.Now()
	month, day := int(now.Month()), now.Day()
	if v, err := strconv.Atoi(c.Query("month")); err == nil {
		month = v
	}
	if v, err := strconv.Atoi(c.Query("day")); err == nil {
		day = v
	}
	if month < 1 || month > 12 || day < 1 || day > 31 {
		fail(c, http.StatusBadRequest, 1002, "月份或日期无效")
		return
	}
	list, err := st.PhotosOnThisDay(pair.ID, month, day)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "查询失败")
		return
	}
	ok(c, gin.H{"month": month, "day": day, "list": list, "total": len(list)})
}
