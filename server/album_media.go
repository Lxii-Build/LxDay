package main

import (
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// ================= 相册媒体：统一上传 + 鉴权代理读取 =================

const (
	// maxPhotoBytes 单张上限 20MB。手机直出 JPEG 通常 3~8MB，20MB 足够容纳高像素直出。
	maxPhotoBytes = 20 * 1024 * 1024
	// photoThumbEdge 缩略图长边。等比缩放（非方裁），见 GoImageWorker.writeFit。
	photoThumbEdge = 512
	// 每人每日配额。计数落在进程内存（memStore），重启归零属可接受损失：
	// 这是防刷盘的护栏，不是计费，宁可偶尔放宽也不要为它引入外部存储。
	maxPhotosPerDay    = 200
	maxUploadBytesADay = 500 * 1024 * 1024
	// quotaTTL 略大于一天：键名已按日期分桶，TTL 只负责回收过期键。
	quotaTTL = 25 * time.Hour
)

var errQuotaExceeded = errors.New("上传配额已用尽")

func photoCountKey(uid int64, day string) string {
	return "media:cnt:" + day + ":" + strconv.FormatInt(uid, 10)
}

func photoBytesKey(uid int64, day string) string {
	return "media:bytes:" + day + ":" + strconv.FormatInt(uid, 10)
}

// checkUploadQuota 在落盘前判额度：张数已达上限，或加上本张后超出当日总字节数即拒绝。
func checkUploadQuota(uid int64, size int64, now time.Time) error {
	day := now.Format("2006-01-02")
	if st.mem.count(photoCountKey(uid, day)) >= maxPhotosPerDay {
		return errQuotaExceeded
	}
	if st.mem.count(photoBytesKey(uid, day))+size > maxUploadBytesADay {
		return errQuotaExceeded
	}
	return nil
}

// commitUploadQuota 落盘成功后才记账：失败的上传不该消耗用户额度。
func commitUploadQuota(uid int64, size int64, now time.Time) {
	day := now.Format("2006-01-02")
	st.mem.incr(photoCountKey(uid, day), quotaTTL)
	st.mem.incrBy(photoBytesKey(uid, day), size, quotaTTL)
}

// ---------- 对外 URL（鉴权代理形态） ----------

// mediaURL / mediaThumbURL 生成对外图片地址。
//
// 一律是 /media/<photoId>，**绝不返回 /upload/... 真实路径**：
// /upload 与 /uploads 两个静态目录完全无鉴权，只靠随机文件名保密，
// URL 一旦经截图、日志、Referer 外泄，任何人都能直接看到情侣私密照片。
// 真实路径只存在库里，读取统一过 handleGetMedia 的 pair 归属校验。
func mediaURL(photoID int64) string {
	return mediaPathURL("/media/" + strconv.FormatInt(photoID, 10))
}

func mediaThumbURL(photoID int64) string {
	return mediaPathURL("/media/" + strconv.FormatInt(photoID, 10) + "/thumb")
}

// mediaPathURL 与 publicUploadURL 同构：站点地址已配置则带域名，未配置回退相对路径。
func mediaPathURL(path string) string {
	if base := siteBaseURL(); base != "" {
		return base + path
	}
	return path
}

// ---------- POST /media 统一上传 ----------

// handleUploadMedia 接收单张照片：魔数校验 → 落盘（原字节，不重编码）→ 等比缩略图 → EXIF → 落库。
//
// 上传即建 photo 行（album_id=0 未归类），故返回的 url 一开始就是 /media/<id> 形态，
// 客户端全程拿不到真实路径；随后 POST /albums/:id/photos 只需把 id 挂进相册。
func handleUploadMedia(c *gin.Context) {
	uid := currentUID(c)
	pair, okP := mustPair(c)
	if !okP {
		return
	}

	// 先限死请求体：不这么做的话，超大 body 会在 file.Size 检查之前就把内存/磁盘吃掉。
	// slack 与头像上传一致，给 multipart 边界与表单字段留余量。
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxPhotoBytes+bytesHeaderSlack)

	file, err := c.FormFile("file")
	if err != nil {
		fail(c, http.StatusBadRequest, 1002, "文件缺失或超过 20MB")
		return
	}
	if file.Size > maxPhotoBytes {
		fail(c, http.StatusBadRequest, 1002, "照片不能超过 20MB")
		return
	}

	now := time.Now()
	if err := checkUploadQuota(uid, file.Size, now); errors.Is(err, errQuotaExceeded) {
		fail(c, http.StatusTooManyRequests, 1020, "今日上传已达上限（200 张 / 500MB），请明天再试")
		return
	}

	tmp, err := saveTempUpload(c, file)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "临时存储失败")
		return
	}
	defer os.Remove(tmp)

	// 魔数白名单：不看扩展名、不做内容嗅探。JPEG 必须支持——手机相册九成是 JPG。
	probe, valid := probeUploadedFile(tmp)
	if !valid {
		fail(c, http.StatusBadRequest, 1002, "不支持的图片格式")
		return
	}
	if !probe.Format.decodableInPureGo() {
		// 与头像一致：HEIC/AVIF 在纯 Go 链路无解码实现，提前给可操作提示而非笼统 500。
		fail(c, http.StatusBadRequest, 1002, "暂不支持该图片格式（HEIC/AVIF），请改用 JPG、PNG、WebP 或 GIF")
		return
	}

	photo, err := storeMediaFile(pair.ID, uid, tmp, probe, now)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "照片处理失败，请重试")
		return
	}
	commitUploadQuota(uid, photo.SizeBytes, now)
	ok(c, photo)
}

// storeMediaFile 把临时文件转成正式照片：落盘 + 缩略图 + EXIF + 落库。
func storeMediaFile(pairID, uid int64, tmp string, probe AvatarProbe, now time.Time) (*Photo, error) {
	datePath := uploadDatePath(now)
	dir := filepath.Join(uploadDir, filepath.FromSlash(datePath))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}

	worker := newImageWorker(dir)
	// 先解码取真实尺寸（同时挡下解压炸弹：decode 内含 MaxPixels 校验）。
	src, meta, err := worker.decodeSource(tmp)
	if err != nil {
		return nil, err
	}

	base := randomCode(24)
	ext, mime := mediaExtMime(probe.Format)
	rel := datePath + "/" + base + ext
	full := filepath.Join(uploadDir, filepath.FromSlash(rel))

	// 原图按原字节保存，不重新编码：重编码既损画质又会抹掉 EXIF，
	// 而相册的原图就是用户的底片，必须逐字节保真。
	size, err := movePreservingBytes(tmp, full)
	if err != nil {
		return nil, err
	}

	// 缩略图等比缩放（长边 512）。JPEG 源出 JPEG，其余出 PNG 以保住透明通道。
	asJPEG := probe.Format == FormatJPEG
	thumbExt := ".png"
	if asJPEG {
		thumbExt = ".jpg"
	}
	thumbRel := datePath + "/" + base + "_thumb" + thumbExt
	thumbFull := filepath.Join(uploadDir, filepath.FromSlash(thumbRel))
	if err := worker.writeFit(src, thumbFull, photoThumbEdge, asJPEG); err != nil {
		_ = os.Remove(full)
		return nil, err
	}

	// EXIF 拍摄时间：只读文件头部，解析失败留空（拍摄时间是锦上添花，不该阻断上传）。
	takenAt := readTakenAt(full)

	photo, err := st.CreatePhoto(pairID, uid, 0, rel, thumbRel,
		meta.Width, meta.Height, size, mime, takenAt)
	if err != nil {
		// 入库失败就把盘上产物清掉，避免刷出无主文件占满磁盘。
		_ = os.Remove(full)
		_ = os.Remove(thumbFull)
		return nil, err
	}
	return photo, nil
}

// mediaExtMime 由探测到的容器格式决定落盘扩展名与 mime，不信客户端给的文件名。
func mediaExtMime(f ImageFormat) (string, string) {
	switch f {
	case FormatJPEG:
		return ".jpg", "image/jpeg"
	case FormatPNG:
		return ".png", "image/png"
	case FormatGIF:
		return ".gif", "image/gif"
	case FormatWebP:
		return ".webp", "image/webp"
	default:
		return ".bin", "application/octet-stream"
	}
}

// movePreservingBytes 把临时文件移到目标路径并返回字节数。
// 优先 rename（同卷零拷贝）；跨卷时回退复制——容器里 tmp 与目标同在 uploadDir 卷，正常走 rename。
func movePreservingBytes(src, dst string) (int64, error) {
	if err := os.Rename(src, dst); err == nil {
		if fi, err := os.Stat(dst); err == nil {
			return fi.Size(), nil
		}
		return 0, nil
	}
	in, err := os.Open(src)
	if err != nil {
		return 0, err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return 0, err
	}
	defer out.Close()
	n, err := io.Copy(out, in)
	if err != nil {
		_ = os.Remove(dst)
		return 0, err
	}
	return n, nil
}

// readTakenAt 读文件头部解析 EXIF 拍摄时间；任何失败都返回 nil（留空而非报错）。
//
// 用 LimitReader 而非固定 make([]byte, exifMaxScan)：后者对每次上传都固定分配 512KB，
// 而绝大多数请求只需读到文件实际长度（且 io.ReadFull 遇到短文件会返回 ErrUnexpectedEOF，
// 需要额外分辨"读到一部分"与"真失败"）。LimitReader 按实际长度增长，短文件不报错。
func readTakenAt(path string) *time.Time {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	head, err := io.ReadAll(io.LimitReader(f, exifMaxScan))
	if err != nil || len(head) == 0 {
		return nil
	}
	return exifDateTimeOriginal(head)
}

// ---------- GET /media/:id 鉴权代理 ----------

// handleGetMedia 与 handleGetMediaThumb：校验照片属于当前用户所在 pair，再流式吐出文件。
//
// 这是相册隐私的唯一闸门。直接暴露 /upload 静态路径的后果：
// 谁拿到 URL 谁就能看图（无需登录、无需是伴侣、无需 App）——
// 对一个存放情侣私密照片的功能，这是最致命的隐私面。
func handleGetMedia(c *gin.Context) {
	serveMedia(c, false)
}

func handleGetMediaThumb(c *gin.Context) {
	serveMedia(c, true)
}

func serveMedia(c *gin.Context, thumb bool) {
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		fail(c, http.StatusBadRequest, 1002, "参数错误")
		return
	}
	photo, err := st.GetPhoto(id)
	if err != nil || photo == nil || photo.PairID != pair.ID {
		// 归属不符与不存在返回同一个响应：区别对待等于给出「该 id 存在」的探测信号。
		fail(c, http.StatusForbidden, 1017, "无权访问该照片")
		return
	}
	rel := photo.diskPath
	if thumb && photo.diskThumb != "" {
		rel = photo.diskThumb
	}
	full, okPath := safeUploadPath(rel)
	if !okPath {
		fail(c, http.StatusNotFound, 1010, "照片不存在")
		return
	}
	fi, err := os.Stat(full)
	if err != nil || fi.IsDir() {
		fail(c, http.StatusNotFound, 1010, "照片不存在")
		return
	}
	h := c.Writer.Header()
	// private：这是私密内容，只允许终端浏览器/客户端自己缓存，禁止中间代理与 CDN 留副本。
	h.Set("Cache-Control", "private, max-age=86400")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Disposition", "inline")
	if photo.Mime != "" && !thumb {
		h.Set("Content-Type", photo.Mime)
	}
	c.File(full)
}

// safeUploadPath 把库里的相对路径映射为 uploadDir 下的绝对路径，并防路径穿越。
// 即便库值被写坏（或历史脏数据带 ../），也不能让请求读到 uploadDir 之外的文件。
func safeUploadPath(rel string) (string, bool) {
	rel = strings.TrimSpace(rel)
	if rel == "" || strings.Contains(rel, "..") || strings.HasPrefix(rel, "/") ||
		strings.Contains(rel, "\\") {
		return "", false
	}
	clean := filepath.Clean(filepath.FromSlash(rel))
	if clean == "." || strings.HasPrefix(clean, "..") || filepath.IsAbs(clean) {
		return "", false
	}
	return filepath.Join(uploadDir, clean), true
}

// photoIDFromMediaURL 从 /media/<id> 或 /media/<id>/thumb 形态的 URL 取出照片 id。
// 客户端把上传接口返回的 url 原样回传时用得上，避免它必须自己拆字符串。
func photoIDFromMediaURL(raw string) (int64, bool) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return 0, false
	}
	// 去掉可能的 scheme://host 前缀，只看路径。
	if i := strings.Index(s, "://"); i >= 0 {
		slash := strings.IndexByte(s[i+3:], '/')
		if slash < 0 {
			return 0, false
		}
		s = s[i+3+slash:]
	}
	if !strings.HasPrefix(s, "/media/") {
		return 0, false
	}
	rest := strings.TrimPrefix(s, "/media/")
	rest = strings.TrimSuffix(rest, "/thumb")
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		rest = rest[:i]
	}
	id, err := strconv.ParseInt(rest, 10, 64)
	if err != nil || id <= 0 {
		return 0, false
	}
	return id, true
}
