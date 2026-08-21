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
		WorkDir:    workDir,
		Timeout:    20 * time.Second,
		MaxPixels:  64 << 20, // 64M 像素（约 8000x8000），超过即拒绝
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
		g, err := gif.DecodeAll(f)
		if err != nil || len(g.Image) == 0 {
			return nil, AvatarMeta{}, fmt.Errorf("decode gif: %w", err)
		}
		meta.Frames = len(g.Image)
		total := 0
		for _, d := range g.Delay {
			total += d // 单位 1/100 秒
		}
		meta.DurationSeconds = float64(total) / 100.0
		return g.Image[0], meta, nil
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

// writeSquare 居中方裁后缩放到 dim×dim 并写出 PNG。
// 裁剪语义与旧 vipsthumbnail `--crop centre` 一致：取源图最大居中正方形。
func (w GoImageWorker) writeSquare(src image.Image, dst string, dim int) error {
	if dim <= 0 {
		return fmt.Errorf("invalid output dimension %d", dim)
	}
	square := centerSquare(src.Bounds())
	out := image.NewRGBA(image.Rect(0, 0, dim, dim))
	interp := w.Interpolat
	if interp == nil {
		interp = draw.CatmullRom
	}
	interp.Scale(out, out.Bounds(), src, square, draw.Over, nil)

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
	interp := w.Interpolat
	if interp == nil {
		interp = draw.CatmullRom
	}
	interp.Scale(out, out.Bounds(), src, b, draw.Over, nil)

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
