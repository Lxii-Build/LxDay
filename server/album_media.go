package main

import (
	"errors"
	"fmt"
	"io"
	"log/slog"
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
	// photoThumbEdge 网格缩略图长边。等比缩放（非方裁），见 GoImageWorker.writeFit。
	// 刻意不做成后台配置：改了之后历史照片的缩略图不会重新生成，只会造成新旧尺寸混杂。
	photoThumbEdge = 384
	// photoPreviewEdge 大图页先加载的预览尺寸。
	// 三档（thumb 384 / preview 1080 / origin）是为了让"点开大图"秒出：
	// 此前从 384 缩略图直接跳 2048 原图，弱网下要白屏等 3~5 秒。
	photoPreviewEdge = 1080
	// quotaTTL 略大于一天：键名已按日期分桶，TTL 只负责回收过期键。
	quotaTTL = 25 * time.Hour
	maxUploadIdempotencyKeyLen = 128
)

// 单张上限 / 每日配额已改为后台可配（settings.go），此处提供读取入口。
// 计数落在进程内存（memStore），重启归零属可接受损失：
// 这是防刷盘的护栏，不是计费，宁可偶尔放宽也不要为它引入外部存储。
func maxPhotoBytesNow() int64 { return settingsNow().PhotoMaxBytes }

func normalizeUploadIdempotencyKey(raw string) (string, error) {
	key := strings.TrimSpace(raw)
	if key == "" {
		return "", nil
	}
	if len(key) > maxUploadIdempotencyKeyLen {
		return "", errors.New("幂等键过长")
	}
	for i := 0; i < len(key); i++ {
		b := key[i]
		if (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') ||
			(b >= '0' && b <= '9') || b == '-' || b == '_' || b == '.' {
			continue
		}
		return "", errors.New("幂等键格式不正确")
	}
	return key, nil
}

var errQuotaExceeded = errors.New("上传配额已用尽")
var errUploadIdempotent = errors.New("upload already completed")

func photoCountKey(uid int64, day string) string {
	return "media:cnt:" + day + ":" + strconv.FormatInt(uid, 10)
}

func photoBytesKey(uid int64, day string) string {
	return "media:bytes:" + day + ":" + strconv.FormatInt(uid, 10)
}

// reserveUploadQuota 原子占额：**先记账再判断，超了立即回退**。
//
// 此前是"先查后写"两步（count() 判断 → 上传成功后 incr()），并发上传时形同虚设：
// 3 路并发同时读到 199 张，3 张全部放过去。客户端这轮改成并发 3 路上传后，
// 这个竞态从"理论问题"变成"每次上传都会踩"。
//
// memStore 的 incr/incrBy 是原子的，故这里用「先自增拿到新值，越界则减回去」的模式。
// 返回的 rollback 供落盘失败时释放额度——失败的上传不该消耗用户配额。
func reserveUploadQuota(uid int64, size int64, now time.Time) (rollback func(), err error) {
	s := settingsNow()
	day := now.Format("2006-01-02")
	cntKey, byteKey := photoCountKey(uid, day), photoBytesKey(uid, day)

	newCount := st.mem.incr(cntKey, quotaTTL)
	if newCount > int64(s.PhotosPerDay) {
		st.mem.incrBy(cntKey, -1, quotaTTL)
		return nil, errQuotaExceeded
	}
	newBytes := st.mem.incrBy(byteKey, size, quotaTTL)
	if newBytes > s.UploadBytesPerDay {
		st.mem.incrBy(byteKey, -size, quotaTTL)
		st.mem.incrBy(cntKey, -1, quotaTTL)
		return nil, errQuotaExceeded
	}
	return func() {
		st.mem.incrBy(byteKey, -size, quotaTTL)
		st.mem.incrBy(cntKey, -1, quotaTTL)
	}, nil
}

// quotaMessage 配额提示文案按当前配置动态生成，不再硬编码「200 张 / 500MB」。
func quotaMessage() string {
	s := settingsNow()
	return fmt.Sprintf("今日上传已达上限（%d 张 / %dMB），请明天再试",
		s.PhotosPerDay, s.UploadBytesPerDay/(1024*1024))
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

// mediaPreviewURL 大图页先加载的中间尺寸（长边 1080）。
func mediaPreviewURL(photoID int64) string {
	return mediaPathURL("/media/" + strconv.FormatInt(photoID, 10) + "/preview")
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
// 上传失败的业务码分家（Q11=B）：客户端据此逐张显示具体原因并决定能否重试。
// 此前全部混用 1002/1010，客户端只能甩一句「格式不支持或超过 20MB」，
// 把 OOM、解码失败、配额用尽、网络中断全糊在一起，管理员无法反馈有效信息。
const (
	codeUploadTooLarge   = 1021 // 超过单张上限
	codeUploadBadFormat  = 1022 // 魔数不认识
	codeUploadNoDecoder  = 1023 // 认识容器但无解码器（HEIC/AVIF 且未编译 heif 支持）
	codeUploadCorrupted  = 1024 // 能识别但解码失败（文件损坏/截断）
	codeUploadTooManyPx  = 1025 // 像素数超上限（解压炸弹防护）
	codeUploadQuotaFull  = 1020 // 当日配额用尽
	codeUploadDiskFailed = 1026 // 落盘/入库失败
	codeUploadDisabled   = 1027 // 相册功能被后台关闭
	// codeUploadInFlight 该账号同时在传的张数到顶。
	//
	// **必须与 1020（当日配额用尽）分开**：客户端的 isRetryableUploadCode
	// 把 1020 判为不可重试（"今天重试也没用"），而"等前面几张传完"
	// 恰恰是**等一下就能成功**的。复用 1020 会让批量上传里被这条挡下的照片
	// 全部变成不可重试的永久失败，用户只能重新选图 —— 又一次"照片会消失"。
	codeUploadInFlight = 1028
)

func handleUploadMedia(c *gin.Context) {
	uid := currentUID(c)
	pair, okP := mustPair(c)
	if !okP {
		return
	}
	s := settingsNow()
	if !s.AlbumEnabled {
		fail(c, http.StatusForbidden, codeUploadDisabled, "相册功能当前已关闭")
		return
	}
	limit := s.PhotoMaxBytes
	limitMB := limit / (1024 * 1024)
	idempotencyKey, err := normalizeUploadIdempotencyKey(c.GetHeader("Idempotency-Key"))
	if err != nil {
		fail(c, http.StatusBadRequest, 1002, err.Error())
		return
	}
	// 幂等键重试可以在解析 multipart 前直接命中已存在的照片，避免重复解码和落盘。
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, limit+bytesHeaderSlack)
	if idempotencyKey != "" {
		existing, lookupErr := st.GetPhotoByUploadIdempotencyKey(uid, idempotencyKey)
		if lookupErr != nil {
			fail(c, http.StatusInternalServerError, codeUploadDiskFailed, "读取上传状态失败，请重试")
			return
		}
		if existing != nil {
			// 已经成功落库但客户端没收到响应时，直接返回原照片，避免重传产生副本。
			drainRequestBody(c)
			ok(c, existing)
			return
		}
	}

	// 必须在 FormFile 之前占槽：Gin 的 FormFile 会先解析整个 multipart，
	// 若把用户级图片闸门放在后面，攻击者仍可在解析阶段并发占住内存。
	releaseUserSlot, slotOK := acquireUserSlot(uid)
	if !slotOK {
		fail(c, http.StatusTooManyRequests, codeUploadInFlight,
			"你有多张照片正在上传中，请等前面几张完成再试")
		return
	}
	defer releaseUserSlot()
	releaseMultipartSlot, parseOK := acquireMultipartParseSlot()
	if !parseOK {
		rejectMultipartBusy(c, false)
		return
	}
	defer releaseMultipartSlot()
	defer releaseParsedMultipartForm(c)

	file, err := c.FormFile("file")
	if err != nil {
		fail(c, http.StatusBadRequest, codeUploadTooLarge,
			fmt.Sprintf("文件缺失或超过 %dMB", limitMB))
		return
	}
	if file.Size > limit {
		fail(c, http.StatusBadRequest, codeUploadTooLarge,
			fmt.Sprintf("照片不能超过 %dMB（这张 %.1fMB）", limitMB, float64(file.Size)/1024/1024))
		return
	}

	now := time.Now()
	// 原子占额：并发上传下不会被击穿。失败路径全部 rollback，不白吃额度。
	rollback, err := reserveUploadQuota(uid, file.Size, now)
	if errors.Is(err, errQuotaExceeded) {
		fail(c, http.StatusTooManyRequests, codeUploadQuotaFull, quotaMessage())
		return
	}
	quotaCommitted := false
	defer func() {
		if !quotaCommitted && rollback != nil {
			rollback()
		}
	}()

	tmp, err := saveTempUpload(c, file)
	if err != nil {
		fail(c, http.StatusInternalServerError, codeUploadDiskFailed, "临时存储失败，请重试")
		return
	}
	releaseParsedMultipartForm(c)
	defer os.Remove(tmp)

	// 魔数白名单：不看扩展名、不做内容嗅探。JPEG 必须支持——手机相册九成是 JPG。
	probe, valid := probeUploadedFile(tmp)
	if !valid {
		fail(c, http.StatusBadRequest, codeUploadBadFormat,
			"这个文件不是能识别的图片格式（支持 JPG/PNG/WebP/GIF/BMP/HEIC/AVIF）")
		return
	}
	if !probe.Format.decodable() {
		fail(c, http.StatusBadRequest, codeUploadNoDecoder,
			fmt.Sprintf("服务端暂不支持 %s 格式，请换一张或改用 JPG", probe.Format.displayName()))
		return
	}

	photo, err := storeMediaFile(pair.ID, uid, tmp, probe, now, idempotencyKey)
	if err != nil {
		if errors.Is(err, errUploadIdempotent) && photo != nil {
			// 另一条并发请求已经用同一个幂等键完成；本次预占的配额
			// 由 defer 回退，重试不应再消耗一张照片额度。
			ok(c, photo)
			return
		}
		// 分辨"图坏了"与"盘/库出问题"：前者用户换张图就行，后者是服务端故障。
		switch {
		case errors.Is(err, ErrAvatarTooLarge):
			fail(c, http.StatusBadRequest, codeUploadTooManyPx,
				"这张图的像素数过大，服务端无法处理，请换一张")
		case errors.Is(err, errImageDecode):
			fail(c, http.StatusBadRequest, codeUploadCorrupted,
				fmt.Sprintf("这张 %s 图片已损坏或被截断，无法解码", probe.Format.displayName()))
		case errors.Is(err, ErrAvatarTooManyFrames):
			fail(c, http.StatusBadRequest, codeUploadBadFormat,
				fmt.Sprintf("这张动图的帧数超过 %d 帧上限，请换一张", maxGIFFrames))
		case errors.Is(err, errImageBusy):
			// 并发闸门超时：服务端在忙，不是这张图的问题。
			// 明确告诉客户端可以重试——不这么说用户只会以为"上传又失败了"。
			fail(c, http.StatusServiceUnavailable, codeUploadDiskFailed,
				"服务器正忙，请稍后重试这张照片")
		default:
			slog.Error("store media failed", "err", err, "uid", uid, "pair_id", pair.ID)
			fail(c, http.StatusInternalServerError, codeUploadDiskFailed, "照片保存失败，请重试")
		}
		return
	}
	quotaCommitted = true
	ok(c, photo)
}

// storeMediaFile 把临时文件转成正式照片：落盘 + 缩略图 + EXIF + 落库。
func storeMediaFile(pairID, uid int64, tmp string, probe AvatarProbe, now time.Time, idempotencyKey string) (*Photo, error) {
	datePath := privateMediaDatePath(now)
	dir := filepath.Join(privateMediaDir(), filepath.FromSlash(datePath))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}

	worker := newImageWorker(dir)

	base := randomCode(24)
	if base == "" {
		return nil, errors.New("secure random source unavailable")
	}
	ext, mime := mediaExtMime(probe.Format)
	rel := datePath + "/" + base + ext
	full := filepath.Join(privateMediaDir(), filepath.FromSlash(rel))

	// 三档产物（Q23=B）：thumb 384（网格）/ preview 1080（大图页先显示）/ origin（双指放大才拉）。
	//
	// 输出格式选择：JPEG 源出 JPEG（体积小一个数量级）；
	// **GIF 也出 JPEG**——缩略图只取首帧、不需要透明通道，PNG 会大好几倍（Q50=A）；
	// PNG/WebP/HEIC/AVIF 出 PNG，因为它们可能真有透明通道，转 JPEG 会把透明区压成黑块。
	asJPEG := probe.Format == FormatJPEG || probe.Format == FormatGIF
	derivExt := ".png"
	if asJPEG {
		derivExt = ".jpg"
	}
	thumbRel := datePath + "/" + base + "_thumb" + derivExt
	thumbFull := filepath.Join(privateMediaDir(), filepath.FromSlash(thumbRel))
	previewRel := ""
	previewFull := ""

	var meta AvatarMeta
	var size int64

	// ★ 解码 + 两次派生图生成整段放进并发闸门（见 image_budget.go）★
	//
	// 闸门必须包住这一整段而不是只包 decodeSource：解出的 src 就有数十 MB
	// （12M 像素上限 × RGBA 4 字节 ≈ 48MB），而它要一直活到 preview 写完。
	// 只在解码期间持闸的话，释放后 src 仍在内存里，N 个并发请求照样各持一份。
	if err := withImageBudget(func() error {
		// 先解码取真实尺寸（同时挡下解压炸弹：decode 内含 MaxPixels 校验）。
		src, m, err := worker.decodeSource(tmp)
		if err != nil {
			return err
		}
		meta = m

		// 原图按原字节保存，不重新编码：重编码既损画质又会抹掉 EXIF，
		// 而相册的原图就是用户的底片，必须逐字节保真。
		sz, err := movePreservingBytes(tmp, full)
		if err != nil {
			return err
		}
		size = sz

		if err := worker.writeFit(src, thumbFull, photoThumbEdge, asJPEG); err != nil {
			_ = os.Remove(full)
			return err
		}

		// 预览图：源图长边本来就 <= 1080 时不必生成（writeFit 不放大，等于白占一份盘）。
		if maxOf(meta.Width, meta.Height) > photoPreviewEdge {
			previewRel = datePath + "/" + base + "_preview" + derivExt
			previewFull = filepath.Join(privateMediaDir(), filepath.FromSlash(previewRel))
			if err := worker.writeFit(src, previewFull, photoPreviewEdge, asJPEG); err != nil {
				// 预览图失败不该让整张上传失败：它只是加速手段，缺了回退原图即可。
				// 只记 photo 尺寸与错误，**不记 rel** —— 那是真实磁盘相对路径，
				// 按 AGENTS §6 不出服务端（docker logs 常被随手查看）。
				slog.Warn("write preview failed, fallback to origin",
					"err", err, "w", meta.Width, "h", meta.Height)
				_ = os.Remove(previewFull)
				previewRel, previewFull = "", ""
			}
		}
		return nil
	}); err != nil {
		return nil, err
	}

	// EXIF 拍摄时间：只读文件头部，解析失败留空（拍摄时间是锦上添花，不该阻断上传）。
	// 放在闸门外：只读文件头 512KB，与解码的内存量级完全不同。
	takenAt := readTakenAt(full)

	photo, err := st.CreatePhotoWithIdempotency(pairID, uid, 0, rel, thumbRel, previewRel,
		meta.Width, meta.Height, size, mime, takenAt, idempotencyKey)
	if err != nil {
		// 入库失败就把盘上产物清掉，避免刷出无主文件占满磁盘。
		_ = os.Remove(full)
		_ = os.Remove(thumbFull)
		if previewFull != "" {
			_ = os.Remove(previewFull)
		}
		if idempotencyKey != "" {
			// 并发请求可能由另一方先完成同一个幂等键；返回已有照片，
			// 让客户端把这次请求视为成功，而不是产生重复照片；
			// 用专用错误通知 handler 回退本次重复请求预占的配额。
			if existing, lookupErr := st.GetPhotoByUploadIdempotencyKey(uid, idempotencyKey); lookupErr == nil && existing != nil {
				return existing, errUploadIdempotent
			}
		}
		return nil, err
	}
	return photo, nil
}

func maxOf(a, b int) int {
	if a > b {
		return a
	}
	return b
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
	case FormatBMP:
		return ".bmp", "image/bmp"
	case FormatHEIF:
		return ".heic", "image/heic"
	case FormatAVIF:
		return ".avif", "image/avif"
	default:
		return ".bin", "application/octet-stream"
	}
}

// movePreservingBytes 把临时文件移到目标路径并返回字节数。
// 优先 rename（同存储卷零拷贝）；跨卷时回退复制——容器里 tmp 与目标通常同卷，正常走 rename。
func movePreservingBytes(src, dst string) (int64, error) {
	if err := os.Rename(src, dst); err == nil {
		if fi, err := os.Stat(dst); err == nil {
			return fi.Size(), nil
		}
		return 0, fmt.Errorf("stat renamed file %q: %w", dst, err)
	}
	in, err := os.Open(src)
	if err != nil {
		return 0, err
	}
	defer func() { _ = in.Close() }()
	out, err := os.Create(dst)
	if err != nil {
		return 0, err
	}
	n, err := io.Copy(out, in)
	if err != nil {
		_ = out.Close()
		_ = os.Remove(dst)
		return 0, err
	}
	if err := out.Sync(); err != nil {
		_ = out.Close()
		_ = os.Remove(dst)
		return 0, err
	}
	if err := out.Close(); err != nil {
		_ = os.Remove(dst)
		return 0, err
	}
	if err := os.Remove(src); err != nil {
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
// mediaVariant 三档产物的选择。缺失的档位一律回退原图，
// 客户端拿到的 URL 永远可用（历史照片没有 preview 也不会 404）。
type mediaVariant int

const (
	variantOrigin mediaVariant = iota
	variantThumb
	variantPreview
)

func handleGetMedia(c *gin.Context) {
	serveMedia(c, variantOrigin)
}

func handleGetMediaThumb(c *gin.Context) {
	serveMedia(c, variantThumb)
}

func handleGetMediaPreview(c *gin.Context) {
	serveMedia(c, variantPreview)
}

func canServeMediaVariant(status int, variant mediaVariant, hasThumb bool) bool {
	switch status {
	case 1:
		return true
	case 2:
		// 回收站仅允许已有缩略图，绝不以原图作为缩略图的回退。
		return variant == variantThumb && hasThumb
	default:
		return false
	}
}

func serveMedia(c *gin.Context, variant mediaVariant) {
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
	// 回收站列表只需要缩略图；原图和预览图在软删后都不应继续可下载。
	// 缩略图缺失时也不能回退到原图，否则历史照片会绕过这条隐私边界。
	if !canServeMediaVariant(photo.Status, variant, photo.diskThumb != "") {
		fail(c, http.StatusNotFound, 1010, "照片不存在")
		return
	}
	// 档位选择，缺失一律回退原图：历史照片没有 preview，不能因此 404。
	rel := photo.diskPath
	switch variant {
	case variantThumb:
		if photo.diskThumb != "" {
			rel = photo.diskThumb
		}
	case variantPreview:
		if photo.diskPreview != "" {
			rel = photo.diskPreview
		} else if photo.diskThumb != "" && photo.Status == 2 {
			// 回收站里的照片只给缩略图，避免误点开时又把原图整张拉下来。
			rel = photo.diskThumb
		}
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
	//
	// max-age 从 1 天提到 30 天 + immutable（Q50=A）：/media/<id> 的内容天然不可变
	// （编辑描述不改图片本体，换图必然是新 id），一天就过期纯属让客户端反复重下。
	h.Set("Cache-Control", "private, max-age=2592000, immutable")
	// ETag 让客户端在缓存过期后也能用 304 省下整张图的流量。
	// 用 id+档位+文件大小+修改时间：任一变化都会得到新的 ETag。
	etag := fmt.Sprintf(`"p%d-v%d-%d-%d"`, photo.ID, variant, fi.Size(), fi.ModTime().Unix())
	h.Set("ETag", etag)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Disposition", "inline")
	if photo.Mime != "" && variant == variantOrigin {
		h.Set("Content-Type", photo.Mime)
	}
	// net/http 的 File/ServeContent 不会拿自定义 ETag 自动完成 If-None-Match
	// 比较；只设置 ETag 而不处理请求头会让客户端每次都重新传完整图片。
	if etagMatches(c.GetHeader("If-None-Match"), etag) {
		c.Status(http.StatusNotModified)
		return
	}
	c.File(full)
}

// etagMatches 实现 GET/HEAD 所需的 If-None-Match 弱比较。
// 代理可能发送逗号分隔的多个值或 W/ 前缀，不能只做字符串全等。
func etagMatches(header, current string) bool {
	for _, candidate := range strings.Split(header, ",") {
		candidate = strings.TrimSpace(candidate)
		if candidate == "*" {
			return true
		}
		candidate = strings.TrimPrefix(candidate, "W/")
		if candidate == current {
			return true
		}
	}
	return false
}

// safeUploadPath 把库里的相对路径映射到对应的公开或私密根目录，并防路径穿越。
// media/ 只允许映射到 privateMediaDir；upload/ 等历史公开路径仍映射到 uploadDir，
// 以便迁移期间和旧头像/后台资源继续可用。
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
	root := uploadDir
	cleanSlash := filepath.ToSlash(clean)
	if cleanSlash == "media" || strings.HasPrefix(cleanSlash, "media/") {
		root = privateMediaDir()
	}
	return filepath.Join(root, clean), true
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
