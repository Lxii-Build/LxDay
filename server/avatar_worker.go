package main

import (
	"errors"
	"fmt"
	"image"
	"image/gif"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"time"

	// HEIC/AVIF 纯 Go 解码（wasm + wazero，无 CGO）。管理员 Q9=C：服务端要真支持这两种格式。
	// 一加 15 开「高效格式」直出就是 HEIC，客户端虽会转 JPEG，但转换失败/别的客户端直传时
	// 服务端也得能吃下来，否则又是一句笼统的"格式不支持"。
	"github.com/gen2brain/avif"
	"github.com/gen2brain/heic"
	"golang.org/x/image/bmp"
	"golang.org/x/image/draw"
	"golang.org/x/image/webp"
)

// errImageDecode 表示"容器认得、但内容解不开"（文件损坏/截断）。
// 与 ErrAvatarTooLarge（像素过大）分开，让 handler 能给出不同的业务码与文案：
// 前者让用户换张图，后者提示像素超限，都不该是笼统的 500。
var errImageDecode = errors.New("image decode failed")

// GoImageWorker 用纯 Go 标准库 + golang.org/x/image 完成解码、居中方裁与缩放。
//
// 为什么不用 libvips：运行镜像是 alpine 精简镜像，不含 libvips CLI，
// 旧实现 fork vipsheader/vipsthumbnail 在生产必然失败（返回 500）。
// 纯 Go 实现与「单容器 + 纯 Go SQLite」的架构一致，无外部依赖、无子进程。
//
// 代价：不支持 HEIF/AVIF 解码（Go 无纯实现），动图只取首帧转静态 PNG。
// 客户端会在上传前把 HEIC 转成 JPEG，故实际影响仅限「直接把 HEIC 文件喂给接口」。
type GoImageWorker struct {
	WorkDir    string        // 输出目录
	Timeout    time.Duration // 单作业墙钟预算（仅用于超大图的兜底判断）
	MaxPixels  int           // 解码前的像素上限，防解压炸弹
	Interpolat draw.Interpolator
}

func newImageWorker(workDir string) GoImageWorker {
	return GoImageWorker{
		WorkDir: workDir,
		Timeout: 20 * time.Second,
		// 像素上限读后台配置（album.photo_max_megapixels，默认 12M ≈ 4000×3000）。
		//
		// 这是**内存安全**闸门而不是业务规则：解码内存与像素数成正比
		// （RGBA 每像素 4 字节，12M 像素 ≈ 48MB），所以它直接决定单张最坏占用。
		// 原先写死 64M ≈ 256MB/张，配 3 路并发就是 768MB，足以打死小规格 VPS。
		MaxPixels:  settingsNow().PhotoMaxPixels,
		Interpolat: draw.CatmullRom,
	}
}

func (w GoImageWorker) Process(req AvatarWorkerRequest) (AvatarWorkerResult, error) {
	if err := os.MkdirAll(w.WorkDir, 0o755); err != nil {
		return AvatarWorkerResult{}, err
	}

	src, meta, err := w.decode(req.Source)
	if err != nil {
		return AvatarWorkerResult{}, err
	}

	base := randomCode(24)
	if base == "" {
		return AvatarWorkerResult{}, errors.New("secure random source unavailable")
	}
	// 统一输出 PNG：无损、无需质量参数、Go 标准库原生支持。
	mainPath := filepath.Join(w.WorkDir, base+".png")
	thumbPath := filepath.Join(w.WorkDir, base+"_thumb.png")

	if err := w.writeSquare(src, mainPath, req.OutputDimension); err != nil {
		return AvatarWorkerResult{}, err
	}
	if err := w.writeSquare(src, thumbPath, req.ThumbDimension); err != nil {
		_ = os.Remove(mainPath)
		return AvatarWorkerResult{}, err
	}
	return AvatarWorkerResult{Meta: meta, MainPath: mainPath, ThumbPath: thumbPath}, nil
}

// decode 按容器格式解码首帧，并回报真实尺寸/帧数。
func (w GoImageWorker) decode(path string) (image.Image, AvatarMeta, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, AvatarMeta{}, err
	}
	defer f.Close()

	// 先只读配置校验像素规模，避免直接解码超大图打爆内存。
	//
	// heic/avif 的 DecodeConfig 由各自包的 init 注册进 image 的注册表，
	// 所以这里能直接认出来，与 jpeg/png/webp/bmp 同一条路径。
	cfg, format, err := image.DecodeConfig(f)
	if err != nil {
		return nil, AvatarMeta{}, fmt.Errorf("%w: decode config: %v", errImageDecode, err)
	}
	if cfg.Width <= 0 || cfg.Height <= 0 {
		return nil, AvatarMeta{}, fmt.Errorf("%w: invalid dimensions %dx%d",
			errImageDecode, cfg.Width, cfg.Height)
	}
	if cfg.Width*cfg.Height > w.MaxPixels {
		return nil, AvatarMeta{}, ErrAvatarTooLarge
	}
	if _, err := f.Seek(0, 0); err != nil {
		return nil, AvatarMeta{}, err
	}

	meta := AvatarMeta{Width: cfg.Width, Height: cfg.Height, Frames: 1}

	switch format {
	case "heic", "heif":
		img, err := heic.Decode(f)
		return img, meta, wrapDecode(err, "heic")
	case "avif":
		img, err := avif.Decode(f)
		return img, meta, wrapDecode(err, "avif")
	case "bmp":
		img, err := bmp.Decode(f)
		return img, meta, wrapDecode(err, "bmp")
	case "gif":
		// ★ 绝不能用 gif.DecodeAll ★
		//
		// 上面的像素上限校验用的是 DecodeConfig，而它只给**首帧**尺寸；
		// DecodeAll 却会把每一帧都解成独立的 image.Paletted。
		// 于是一个单帧仅 1M 像素（轻松过检）、但有几千帧的 GIF，
		// 内存占用按帧数线性增长 —— 单个上传请求即可打爆进程。
		// （头像链路的 MaxFrames=120 检查在 worker 返回**之后**才跑，拦不住；
		// 相册链路连那道事后检查都没有。）
		//
		// 改为两步：先只扫结构数帧（不解像素、内存与帧数无关），
		// 再用 gif.Decode 只解首帧 —— 首帧尺寸已经过了像素上限校验。
		sum, err := scanGIF(f)
		if err != nil {
			if errors.Is(err, errGIFTooManyFrames) {
				return nil, AvatarMeta{}, ErrAvatarTooManyFrames
			}
			// 截断的 GIF：结构扫描读到 EOF。首帧通常仍可解，
			// 但既然文件本身不完整，直接按解码失败处理，让用户换一张。
			return nil, AvatarMeta{}, fmt.Errorf("%w: scan gif: %v", errImageDecode, err)
		}
		if sum.Frames == 0 {
			return nil, AvatarMeta{}, fmt.Errorf("%w: gif has no frames", errImageDecode)
		}
		if _, err := f.Seek(0, 0); err != nil {
			return nil, AvatarMeta{}, err
		}
		img, err := gif.Decode(f) // 只解首帧
		if err != nil {
			return nil, AvatarMeta{}, wrapDecode(err, "gif")
		}
		meta.Frames = sum.Frames
		meta.DurationSeconds = sum.DurationSeconds
		return img, meta, nil
	case "jpeg":
		img, err := jpeg.Decode(f)
		return img, meta, wrapDecode(err, "jpeg")
	case "png":
		img, err := png.Decode(f)
		return img, meta, wrapDecode(err, "png")
	case "webp":
		img, err := webp.Decode(f)
		return img, meta, wrapDecode(err, "webp")
	default:
		// 其余格式交给 image.Decode 的注册表兜底。
		img, _, err := image.Decode(f)
		if err != nil {
			return nil, AvatarMeta{}, fmt.Errorf("%w: unsupported format %q: %v",
				errImageDecode, format, err)
		}
		return img, meta, nil
	}
}

// wrapDecode 统一把解码失败包成 errImageDecode，供 handler 用 errors.Is 分辨
// 「图坏了（用户换张图）」与「服务端故障（该报 500）」。
func wrapDecode(err error, kind string) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("%w: %s: %v", errImageDecode, kind, err)
}

const (
	// prescaleThreshold 触发两段式缩放的倍率。
	//
	// 源图长边超过目标的这个倍数时，先做一次廉价预缩再交给高质量插值器。
	//
	// 取 2 而不是 4：临时缓冲正比于 **目标宽 × 源高**（见 scaleInto 的公式），
	// 所以只要源图明显高于目标就已经很贵了，不需要等到 4 倍。
	// 实测源 3464×3464 → 目标 1080×1080（倍率仅 3.2）：
	//   单段     114.69 MB
	//   两段式    51.71 MB
	// 若阈值留在 4，这条倍率 3.2 的路径反而不预缩 —— 而它正是
	// 「顶到像素上限的照片生成预览图」这条最贵的真实路径。
	prescaleThreshold = 2

	// prescaleFactor 中间图相对目标的倍数。
	//
	// 中间图既是第二段的源，又直接决定第二段的缓冲大小
	//（= 目标宽 × 中间图高 × 32），所以它越小越省；
	// 但太小会让第一段（廉价插值）承担过多降采样、画质变差。
	// 取 1.25 的代价与收益（1080 预览图）：
	//   1.25 → 第二段 44.8 MB，第一段仍有 1.25 倍余量做高质量重采样
	//   1.00 → 第二段 35.8 MB，但第二段退化成等尺寸重采样、画质白给
	// 省下的 9 MB 不值得拿画质换，故保留 1.25。
	prescaleFactor = 1.25
)

// scaleInto 把 src 的 srcRect 区域缩放进 dst，**必要时走两段式**。
//
// ★ 为什么需要这个函数（0828 实测出来的真凶）★
//
// `draw.CatmullRom`（以及 `BiLinear`）的 Scale 会分配一块与**源图高度**
// 成正比的临时缓冲，而不是只与目标尺寸有关。实测把公式钉死了：
//
//	分配字节 ≈ 目标宽 × 源高 × 32      （每个中间像素一个 [4]float64）
//
//	源 8000×8000 → 目标  384×384 ：实测 94.77 MB / 按公式 93.75 MB
//	源 3464×3464 → 目标 1080×1080：实测 114.69 MB / 按公式 114.17 MB
//	ApproxBiLinear 同输入          ：0.00 MB   ← 不分配这块缓冲
//
// 所以**目标越大、源图越高，就越贵**，而相册每张要跑两次
// （缩略图 384 + 预览图 1080）。顶到像素上限的一张图单段走完两次约 157 MB，
// 配 3 路并发闸门最坏约 470 MB —— 足以打死小规格 VPS。
// 而这**与图片格式无关**：一张合法的大 PNG 就能触发，文件不大、帧数为 1、
// 所有既有校验都放行，比 GIF 帧炸弹更隐蔽。
//
// 修法是两段式：先用 ApproxBiLinear（不分配那块缓冲）把源图廉价缩到目标的
// prescaleFactor 倍，再用 CatmullRom 从那张中间图收尾。这样公式里的"源高"
// 从原图高度降到了目标高度的 1.25 倍，与原图多大彻底脱钩。
// 实测缩略图 94.77 MB → 6.60 MB。
//
// 为什么不干脆全用 ApproxBiLinear：它在大比例缩小时会有明显的锯齿与摩尔纹，
// 而相册缩略图是用户第一眼看到的东西。两段式是"省内存"与"保画质"的交点。
//
// ★ 剩下的下限是 API 决定的，不是没优化到 ★
// 第二段的成本 = 目标宽 × (目标高 × 1.25) × 32，对 1080 预览图约 44.8 MB，
// 且这是**与原图无关的常量**。即便 prescaleFactor 取 1（第二段退化成等尺寸
// 重采样、画质白给），也仍需约 35.8 MB。所以 1080 预览图的开销压不到更低，
// 除非换掉插值器 —— 那是画质取舍，不在本次内存修复的范围内。
func (w GoImageWorker) scaleInto(dst *image.RGBA, src image.Image, srcRect image.Rectangle) {
	interp := w.Interpolat
	if interp == nil {
		interp = draw.CatmullRom
	}
	db := dst.Bounds()

	// 判定是否值得预缩：按长边比例。
	srcLong := srcRect.Dx()
	if srcRect.Dy() > srcLong {
		srcLong = srcRect.Dy()
	}
	dstLong := db.Dx()
	if db.Dy() > dstLong {
		dstLong = db.Dy()
	}
	if dstLong <= 0 || srcLong < dstLong*prescaleThreshold {
		interp.Scale(dst, db, src, srcRect, draw.Over, nil)
		return
	}

	// 第一段：廉价缩到目标的 prescaleFactor 倍（留一点余量给第二段重采样）。
	midW := int(float64(db.Dx()) * prescaleFactor)
	midH := int(float64(db.Dy()) * prescaleFactor)
	// 中间图必须仍小于源图，否则等于放大、白做一次。
	if midW >= srcRect.Dx() || midH >= srcRect.Dy() {
		interp.Scale(dst, db, src, srcRect, draw.Over, nil)
		return
	}
	mid := image.NewRGBA(image.Rect(0, 0, midW, midH))
	draw.ApproxBiLinear.Scale(mid, mid.Bounds(), src, srcRect, draw.Over, nil)
	// 第二段：高质量收尾。
	interp.Scale(dst, db, mid, mid.Bounds(), draw.Over, nil)
}

// writeSquare 居中方裁后缩放到 dim×dim 并写出 PNG。
// 裁剪语义与旧 vipsthumbnail `--crop centre` 一致：取源图最大居中正方形。
func (w GoImageWorker) writeSquare(src image.Image, dst string, dim int) error {
	if dim <= 0 {
		return fmt.Errorf("invalid output dimension %d", dim)
	}
	square := centerSquare(src.Bounds())
	out := image.NewRGBA(image.Rect(0, 0, dim, dim))
	w.scaleInto(out, src, square)

	f, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer f.Close()
	enc := png.Encoder{CompressionLevel: png.DefaultCompression}
	if err := enc.Encode(f, out); err != nil {
		_ = os.Remove(dst)
		return err
	}
	return nil
}

// writeFit 保持长宽比缩放到「长边 = maxEdge」并写出。
//
// 与 writeSquare（头像，居中方裁）并存而非替代：头像位是圆形/方形槽位，必须方裁；
// 相册网格若沿用方裁，竖构图的人像会被切掉头和脚——照片是内容本体，不能裁。
// 小图不放大（放大只是徒增体积与模糊）。
//
// 输出格式随源图：JPEG 源出 JPEG（照片体积小一个数量级），
// 其余（PNG/GIF/WebP，可能带透明通道）出 PNG——JPEG 不支持透明，会把透明区压成黑块。
func (w GoImageWorker) writeFit(src image.Image, dst string, maxEdge int, asJPEG bool) error {
	if maxEdge <= 0 {
		return fmt.Errorf("invalid thumb edge %d", maxEdge)
	}
	b := src.Bounds()
	tw, th := fitDimensions(b.Dx(), b.Dy(), maxEdge)
	out := image.NewRGBA(image.Rect(0, 0, tw, th))
	// 走 scaleInto 而非直接 interp.Scale：大源图 → 小目标时省 8 倍内存，
	// 理由见 scaleInto 的注释（这是 0828 实测出的 400MB/张 的真凶）。
	w.scaleInto(out, src, b)

	f, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer f.Close()
	if asJPEG {
		// 质量 85：网格缩略图肉眼无损，体积约为 PNG 的 1/10。
		if err := jpeg.Encode(f, out, &jpeg.Options{Quality: 85}); err != nil {
			_ = os.Remove(dst)
			return err
		}
		return nil
	}
	enc := png.Encoder{CompressionLevel: png.DefaultCompression}
	if err := enc.Encode(f, out); err != nil {
		_ = os.Remove(dst)
		return err
	}
	return nil
}

// fitDimensions 计算「长边缩到 maxEdge、短边按比例取整」的目标尺寸。
// 短边至少为 1：极端长条图（如 4000x3）按比例算出 0 会让编码器直接失败。
func fitDimensions(srcW, srcH, maxEdge int) (int, int) {
	if srcW <= 0 || srcH <= 0 {
		return 1, 1
	}
	if srcW <= maxEdge && srcH <= maxEdge {
		return srcW, srcH // 小图不放大
	}
	if srcW >= srcH {
		h := srcH * maxEdge / srcW
		if h < 1 {
			h = 1
		}
		return maxEdge, h
	}
	wd := srcW * maxEdge / srcH
	if wd < 1 {
		wd = 1
	}
	return wd, maxEdge
}

// decodeSource 只解码不裁剪，供相册上传取真实尺寸并生成等比缩略图。
func (w GoImageWorker) decodeSource(path string) (image.Image, AvatarMeta, error) {
	return w.decode(path)
}

// centerSquare 返回 b 内部最大的居中正方形。
func centerSquare(b image.Rectangle) image.Rectangle {
	wd, ht := b.Dx(), b.Dy()
	side := wd
	if ht < side {
		side = ht
	}
	offX := b.Min.X + (wd-side)/2
	offY := b.Min.Y + (ht-side)/2
	return image.Rect(offX, offY, offX+side, offY+side)
}
