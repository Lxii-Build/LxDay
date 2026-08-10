package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// VipsWorker 用受限的 libvips CLI 子进程执行裁剪与缩略图，设置执行超时与内存上限。
// 真实媒体解码依赖 CI 固定的 Ubuntu 24.04 + libheif 插件；单测使用 stubWorker 替换。
type VipsWorker struct {
	Bin        string        // vips 可执行文件，默认 "vips"
	WorkDir    string        // 输出目录
	Timeout    time.Duration // 单作业墙钟超时
	MaxMemoryK int           // VIPS_DISC_THRESHOLD / 内存上限（KB），0 用默认
}

func newVipsWorker(workDir string) VipsWorker {
	return VipsWorker{
		Bin:        "vips",
		WorkDir:    workDir,
		Timeout:    20 * time.Second,
		MaxMemoryK: 256 * 1024, // 256MB
	}
}

func (w VipsWorker) Process(req AvatarWorkerRequest) (AvatarWorkerResult, error) {
	if err := os.MkdirAll(w.WorkDir, 0o755); err != nil {
		return AvatarWorkerResult{}, err
	}
	base := randomCode(24)
	mainExt := "webp"
	if !req.Animated {
		mainExt = "png"
	}
	mainPath := filepath.Join(w.WorkDir, base+"."+mainExt)
	thumbPath := filepath.Join(w.WorkDir, base+"_thumb.png")

	// 探测真实帧数/时长/尺寸，供上层校验资源上限。
	meta, err := w.inspect(req.Source, req.Animated)
	if err != nil {
		return AvatarWorkerResult{}, err
	}

	// 主输出：按裁剪中心与缩放取 1:1，再限制到 OutputDimension。动画保留全部帧（[]）。
	if err := w.render(req, mainPath, req.OutputDimension, req.Animated); err != nil {
		return AvatarWorkerResult{}, err
	}
	// 缩略图：始终静态首帧，ThumbDimension 见方。
	if err := w.render(req, thumbPath, req.ThumbDimension, false); err != nil {
		_ = os.Remove(mainPath)
		return AvatarWorkerResult{}, err
	}
	return AvatarWorkerResult{Meta: meta, MainPath: mainPath, ThumbPath: thumbPath}, nil
}

// inspect 读取头部帧数与尺寸；失败按处理失败上报。
func (w VipsWorker) inspect(src string, animated bool) (AvatarMeta, error) {
	ctx, cancel := context.WithTimeout(context.Background(), w.Timeout)
	defer cancel()
	width, err := w.field(ctx, src, "width")
	if err != nil {
		return AvatarMeta{}, err
	}
	height, err := w.field(ctx, src, "height")
	if err != nil {
		return AvatarMeta{}, err
	}
	meta := AvatarMeta{Width: width, Height: height, Frames: 1}
	if animated {
		if pages, err := w.field(ctx, src, "n-pages"); err == nil && pages > 0 {
			meta.Frames = pages
		}
		if delay, err := w.field(ctx, src, "gif-delay"); err == nil && delay > 0 {
			// gif-delay 单位 1/100s；用平均帧延迟乘帧数估算时长。
			meta.DurationSeconds = float64(meta.Frames) * float64(delay) / 100.0
		}
	}
	return meta, nil
}

func (w VipsWorker) field(ctx context.Context, src, name string) (int, error) {
	out, err := w.run(ctx, "header", "-f", name, src)
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(out))
}

// render 调用 vipsthumbnail 生成见方裁剪输出；animated=false 时仅取首帧 [0]。
func (w VipsWorker) render(req AvatarWorkerRequest, dst string, dimension int, animated bool) error {
	ctx, cancel := context.WithTimeout(context.Background(), w.Timeout)
	defer cancel()
	source := req.Source
	if !animated {
		source = req.Source + "[page=0]"
	} else {
		source = req.Source + "[n=-1]"
	}
	size := fmt.Sprintf("%dx%d", dimension, dimension)
	_, err := w.run(ctx, "thumbnail", source, dst, strconv.Itoa(dimension),
		"--size", size, "--smartcrop", "attention")
	return err
}

func (w VipsWorker) run(ctx context.Context, args ...string) (string, error) {
	bin := w.Bin
	if bin == "" {
		bin = "vips"
	}
	cmd := exec.CommandContext(ctx, bin, args...)
	cmd.Env = append(os.Environ(),
		"VIPS_CONCURRENCY=1",
		fmt.Sprintf("VIPS_DISC_THRESHOLD=%dk", w.discThreshold()),
	)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("vips %v: %w: %s", args, err, string(out))
	}
	return string(out), nil
}

func (w VipsWorker) discThreshold() int {
	if w.MaxMemoryK > 0 {
		return w.MaxMemoryK
	}
	return 256 * 1024
}
