package main

import (
	"encoding/binary"
	"image"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// 等比缩放尺寸：相册缩略图必须保长宽比（方裁会切掉人物），且小图不放大。
func Test等比缩放尺寸计算(t *testing.T) {
	cases := []struct {
		name             string
		srcW, srcH, edge int
		wantW, wantH     int
	}{
		{"横图长边归到512", 4000, 3000, 512, 512, 384},
		{"竖图长边归到512", 3000, 4000, 512, 384, 512},
		{"正方形", 2048, 2048, 512, 512, 512},
		{"小图不放大", 200, 100, 512, 200, 100},
		{"恰好等于长边", 512, 288, 512, 512, 288},
		{"仅一边超限", 800, 400, 512, 512, 256},
		{"极端长条_短边保底1像素", 4000, 3, 512, 512, 1},
		{"极端竖条_短边保底1像素", 3, 4000, 512, 1, 512},
		{"非法尺寸兜底", 0, 0, 512, 1, 1},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			gotW, gotH := fitDimensions(tc.srcW, tc.srcH, tc.edge)
			if gotW != tc.wantW || gotH != tc.wantH {
				t.Fatalf("fitDimensions(%d,%d,%d)=(%d,%d) want (%d,%d)",
					tc.srcW, tc.srcH, tc.edge, gotW, gotH, tc.wantW, tc.wantH)
			}
			// 长边不得超过上限（小图不放大的情形除外）。
			if tc.srcW > tc.edge || tc.srcH > tc.edge {
				if gotW > tc.edge || gotH > tc.edge {
					t.Fatalf("长边超限：(%d,%d) edge=%d", gotW, gotH, tc.edge)
				}
			}
		})
	}
}

// writeFit 端到端：宽图产出的缩略图必须是 512x384 而非 512x512（不方裁）。
func Test缩略图保持比例而非方裁(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "in.jpg")
	writeTestJPEG(t, src, 800, 400)

	w := newImageWorker(filepath.Join(dir, "out"))
	img, meta, err := w.decodeSource(src)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if meta.Width != 800 || meta.Height != 400 {
		t.Fatalf("meta=%#v", meta)
	}

	thumb := filepath.Join(dir, "thumb.jpg")
	if err := w.writeFit(img, thumb, 512, true); err != nil {
		t.Fatalf("writeFit: %v", err)
	}
	cfg := decodeConfigFile(t, thumb)
	if cfg.Width != 512 || cfg.Height != 256 {
		t.Fatalf("缩略图 %dx%d，期望 512x256（保持 2:1）", cfg.Width, cfg.Height)
	}

	// 头像仍必须方裁：确认没有把 writeSquare 改坏。
	square := filepath.Join(dir, "square.png")
	if err := w.writeSquare(img, square, 256); err != nil {
		t.Fatalf("writeSquare: %v", err)
	}
	assertSquarePNG(t, square, 256)
}

// PNG 源出 PNG（保住透明通道），JPEG 源出 JPEG（体积）。
func Test缩略图格式随源图(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "in.png")
	writeTestPNG(t, src, 900, 600)
	w := newImageWorker(filepath.Join(dir, "out"))
	img, _, err := w.decodeSource(src)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	asPNG := filepath.Join(dir, "t.png")
	if err := w.writeFit(img, asPNG, 512, false); err != nil {
		t.Fatalf("writeFit png: %v", err)
	}
	if got := decodeFormatFile(t, asPNG); got != "png" {
		t.Fatalf("format=%s want png", got)
	}
	asJPEG := filepath.Join(dir, "t.jpg")
	if err := w.writeFit(img, asJPEG, 512, true); err != nil {
		t.Fatalf("writeFit jpeg: %v", err)
	}
	if got := decodeFormatFile(t, asJPEG); got != "jpeg" {
		t.Fatalf("format=%s want jpeg", got)
	}
	// 非法长边必须报错而不是产出 0 尺寸文件。
	if err := w.writeFit(img, filepath.Join(dir, "bad.png"), 0, false); err == nil {
		t.Fatal("长边 0 应报错")
	}
}

// 上传配额：张数与字节数各自独立生效，且按天分桶。
// 配额已改为「原子占额 + 失败回退」（reserveUploadQuota），此测试随之改写。
func Test上传配额计数(t *testing.T) {
	prev := st
	st = &Store{mem: newMemStore()}
	defer func() { st = prev }()

	limits := settingsNow()
	const uid = int64(7)
	now := time.Date(2026, 8, 20, 10, 0, 0, 0, time.Local)

	// 张数：第 N 张仍放行，第 N+1 张拒绝。
	for i := 0; i < limits.PhotosPerDay; i++ {
		if _, err := reserveUploadQuota(uid, 1024, now); err != nil {
			t.Fatalf("第 %d 张应放行，却报 %v", i+1, err)
		}
	}
	if _, err := reserveUploadQuota(uid, 1024, now); err == nil {
		t.Fatalf("超过每日 %d 张应被拒绝", limits.PhotosPerDay)
	}
	// 次日归零（键按日期分桶）。
	if _, err := reserveUploadQuota(uid, 1024, now.AddDate(0, 0, 1)); err != nil {
		t.Fatalf("次日应重新放行，却报 %v", err)
	}

	// 字节数：单独一个用户，累计逼近上限。
	const other = int64(8)
	half := limits.UploadBytesPerDay / 2
	if _, err := reserveUploadQuota(other, half, now); err != nil {
		t.Fatalf("首次应放行：%v", err)
	}
	if _, err := reserveUploadQuota(other, half, now); err != nil {
		t.Fatalf("刚好用满不应被拒：%v", err)
	}
	if _, err := reserveUploadQuota(other, 1, now); err == nil {
		t.Fatal("超过每日字节上限应被拒绝")
	}
	// 被拒时必须回退，不能白吃额度（否则拒一次就永久少一格）。
	day := now.Format("2006-01-02")
	if got := st.mem.count(photoBytesKey(other, day)); got != limits.UploadBytesPerDay {
		t.Fatalf("被拒后字节计数应回退到 %d，实际 %d", limits.UploadBytesPerDay, got)
	}
	// 两个用户互不影响。
	if _, err := reserveUploadQuota(int64(9), 1024, now); err != nil {
		t.Fatalf("其他用户不应受影响：%v", err)
	}
}

// 落盘失败要 rollback，额度必须还回去。
func Test上传配额失败回退(t *testing.T) {
	prev := st
	st = &Store{mem: newMemStore()}
	defer func() { st = prev }()

	const uid = int64(11)
	now := time.Now()
	day := now.Format("2006-01-02")

	rollback, err := reserveUploadQuota(uid, 5000, now)
	if err != nil {
		t.Fatalf("首次占额应成功：%v", err)
	}
	if got := st.mem.count(photoCountKey(uid, day)); got != 1 {
		t.Fatalf("占额后张数应为 1，实际 %d", got)
	}
	rollback()
	if got := st.mem.count(photoCountKey(uid, day)); got != 0 {
		t.Fatalf("回退后张数应为 0，实际 %d", got)
	}
	if got := st.mem.count(photoBytesKey(uid, day)); got != 0 {
		t.Fatalf("回退后字节应为 0，实际 %d", got)
	}
}

// 并发占额不能击穿配额：这是把客户端改成并发 3 路上传后必然会踩的竞态。
// 旧的「先 count 判断、后 incr 记账」两步实现下，N 路并发会同时读到同一个旧值。
func Test上传配额并发不击穿(t *testing.T) {
	prev := st
	st = &Store{mem: newMemStore()}
	defer func() { st = prev }()

	const uid = int64(12)
	now := time.Now()
	day := now.Format("2006-01-02")
	limit := settingsNow().PhotosPerDay

	// 并发发起 limit*3 次占额，只应有 limit 次成功。
	total := limit * 3
	results := make(chan error, total)
	var wg sync.WaitGroup
	for i := 0; i < total; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := reserveUploadQuota(uid, 1, now)
			results <- err
		}()
	}
	wg.Wait()
	close(results)

	granted := 0
	for err := range results {
		if err == nil {
			granted++
		}
	}
	if granted != limit {
		t.Fatalf("并发下应恰好放行 %d 次，实际 %d 次（配额被击穿）", limit, granted)
	}
	if got := st.mem.count(photoCountKey(uid, day)); got != int64(limit) {
		t.Fatalf("计数应为 %d，实际 %d", limit, got)
	}
}

// 对外 URL 必须是 /media/<id> 形态，绝不能是 /upload/... 真实路径。
func Test照片URL为鉴权代理形态(t *testing.T) {
	// st==nil → siteBaseURL 返回空 → 相对路径（与 publicAvatarURL 的测试同一前提）。
	prev := st
	st = nil
	defer func() { st = prev }()

	if got := mediaURL(123); got != "/media/123" {
		t.Fatalf("mediaURL=%q want /media/123", got)
	}
	if got := mediaThumbURL(123); got != "/media/123/thumb" {
		t.Fatalf("mediaThumbURL=%q want /media/123/thumb", got)
	}
	// 关键断言：URL 里不得出现真实上传路径的任何痕迹。
	for _, u := range []string{mediaURL(1), mediaThumbURL(1)} {
		if containsAny(u, "/upload", "/uploads", ".jpg", ".png") {
			t.Fatalf("URL %q 泄露了真实存储路径形态", u)
		}
	}
}

// /media/<id> 反解回 id：客户端把上传返回体原样回传时要能识别。
func TestMediaURL反解照片ID(t *testing.T) {
	cases := []struct {
		in     string
		wantID int64
		wantOK bool
	}{
		{"/media/42", 42, true},
		{"/media/42/thumb", 42, true},
		{"https://love.lxii.cc/media/42", 42, true},
		{"https://love.lxii.cc/media/42/thumb", 42, true},
		{"/media/0", 0, false},
		{"/media/-1", 0, false},
		{"/media/abc", 0, false},
		{"/media/", 0, false},
		{"/upload/2026/08/20/x.jpg", 0, false},
		{"", 0, false},
		{"https://evil.com", 0, false},
	}
	for _, tc := range cases {
		gotID, gotOK := photoIDFromMediaURL(tc.in)
		if gotOK != tc.wantOK || gotID != tc.wantID {
			t.Fatalf("photoIDFromMediaURL(%q)=(%d,%v) want (%d,%v)",
				tc.in, gotID, gotOK, tc.wantID, tc.wantOK)
		}
	}
}

// 库里路径映射回磁盘时必须防穿越：脏数据也不能让请求读到 uploadDir 之外的文件。
func Test上传路径防穿越(t *testing.T) {
	good := map[string]string{
		"upload/2026/08/20/a.jpg": filepath.Join(uploadDir, "upload", "2026", "08", "20", "a.jpg"),
		"upload/x_thumb.png":      filepath.Join(uploadDir, "upload", "x_thumb.png"),
	}
	for rel, want := range good {
		got, ok := safeUploadPath(rel)
		if !ok || got != want {
			t.Fatalf("safeUploadPath(%q)=(%q,%v) want (%q,true)", rel, got, ok, want)
		}
	}
	bad := []string{
		"", "   ",
		"../../etc/passwd",
		"upload/../../etc/passwd",
		"/etc/passwd",
		"/upload/2026/08/20/a.jpg", // 绝对路径形态（库里存的是相对）
		"upload\\..\\..\\windows\\win.ini",
		"..",
	}
	for _, rel := range bad {
		if got, ok := safeUploadPath(rel); ok {
			t.Fatalf("safeUploadPath(%q) 应被拒绝，得到 %q", rel, got)
		}
	}
}

// 落盘扩展名与 mime 由魔数决定，不信客户端文件名。
func Test落盘扩展名与Mime由格式决定(t *testing.T) {
	cases := []struct {
		f              ImageFormat
		wantExt, wantM string
	}{
		{FormatJPEG, ".jpg", "image/jpeg"},
		{FormatPNG, ".png", "image/png"},
		{FormatGIF, ".gif", "image/gif"},
		{FormatWebP, ".webp", "image/webp"},
		{FormatBMP, ".bmp", "image/bmp"},
		{FormatHEIF, ".heic", "image/heic"},
		{FormatAVIF, ".avif", "image/avif"},
		{FormatUnknown, ".bin", "application/octet-stream"},
	}
	for _, tc := range cases {
		ext, mime := mediaExtMime(tc.f)
		if ext != tc.wantExt || mime != tc.wantM {
			t.Fatalf("mediaExtMime(%v)=(%q,%q) want (%q,%q)", tc.f, ext, mime, tc.wantExt, tc.wantM)
		}
	}
}

// movePreservingBytes 必须逐字节保真（不重编码），并返回真实大小。
func Test原图移动逐字节保真(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "src.jpg")
	writeTestJPEG(t, src, 120, 80)
	original, err := os.ReadFile(src)
	if err != nil {
		t.Fatal(err)
	}
	dst := filepath.Join(dir, "moved.jpg")
	n, err := movePreservingBytes(src, dst)
	if err != nil {
		t.Fatalf("move: %v", err)
	}
	moved, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	if n != int64(len(original)) {
		t.Fatalf("size=%d want %d", n, len(original))
	}
	if string(moved) != string(original) {
		t.Fatal("字节不一致：原图不得被重编码")
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Fatal("源临时文件应已被移走")
	}
}

// EXIF 拍摄时间能从落盘的真实 JPEG 文件里读出来（readTakenAt 的文件读取路径）。
func Test从文件读取EXIF拍摄时间(t *testing.T) {
	dir := t.TempDir()
	withExif := filepath.Join(dir, "exif.jpg")
	raw := buildExifJPEG(t, binary.LittleEndian, "2023:05:04 07:08:09", "")
	if err := os.WriteFile(withExif, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	got := readTakenAt(withExif)
	if got == nil {
		t.Fatal("应解析出拍摄时间")
	}
	want := time.Date(2023, 5, 4, 7, 8, 9, 0, time.Local)
	if !got.Equal(want) {
		t.Fatalf("takenAt=%v want %v", got, want)
	}

	// 无 EXIF 的正常 JPEG：留空而非报错。
	plain := filepath.Join(dir, "plain.jpg")
	writeTestJPEG(t, plain, 60, 60)
	if got := readTakenAt(plain); got != nil {
		t.Fatalf("无 EXIF 应留空，得到 %v", got)
	}
	// 文件不存在：同样留空，不 panic。
	if got := readTakenAt(filepath.Join(dir, "missing.jpg")); got != nil {
		t.Fatalf("文件缺失应留空，得到 %v", got)
	}
}

func TestETagMatches(t *testing.T) {
	current := `"photo-7-v1-123-456"`
	cases := []struct {
		name   string
		header string
		want   bool
	}{
		{"exact", current, true},
		{"weak", "W/" + current, true},
		{"list", `"other", W/` + current, true},
		{"wildcard", "*", true},
		{"mismatch", `"photo-7-v1-123-999"`, false},
		{"empty", "", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := etagMatches(tc.header, current); got != tc.want {
				t.Fatalf("etagMatches(%q, %q)=%v want %v", tc.header, current, got, tc.want)
			}
		})
	}
}

func TestMediaVariantPolicy(t *testing.T) {
	cases := []struct {
		name     string
		want     bool
		status   int
		variant  mediaVariant
		hasThumb bool
	}{
		{name: "active origin", status: 1, variant: variantOrigin, want: true},
		{name: "active preview", status: 1, variant: variantPreview, want: true},
		{name: "recycled thumb", status: 2, variant: variantThumb, hasThumb: true, want: true},
		{name: "recycled origin", status: 2, variant: variantOrigin, hasThumb: true, want: false},
		{name: "recycled preview", status: 2, variant: variantPreview, hasThumb: true, want: false},
		{name: "recycled without thumb", status: 2, variant: variantThumb, want: false},
		{name: "invalid status", status: 0, variant: variantThumb, hasThumb: true, want: false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := canServeMediaVariant(tc.status, tc.variant, tc.hasThumb); got != tc.want {
				t.Fatalf("canServeMediaVariant(%d, %d, %v)=%v want %v", tc.status, tc.variant, tc.hasThumb, got, tc.want)
			}
		})
	}
}

// ---------- 测试辅助 ----------

func containsAny(s string, subs ...string) bool {
	for _, sub := range subs {
		if sub != "" && strings.Contains(s, sub) {
			return true
		}
	}
	return false
}

func decodeConfigFile(t *testing.T, path string) image.Config {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open %s: %v", path, err)
	}
	defer f.Close()
	cfg, _, err := image.DecodeConfig(f)
	if err != nil {
		t.Fatalf("decode %s: %v", path, err)
	}
	return cfg
}

func decodeFormatFile(t *testing.T, path string) string {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open %s: %v", path, err)
	}
	defer f.Close()
	_, format, err := image.DecodeConfig(f)
	if err != nil {
		t.Fatalf("decode %s: %v", path, err)
	}
	return format
}
