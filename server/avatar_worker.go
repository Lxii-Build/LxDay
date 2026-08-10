package main

import (
	"bufio"
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
	WorkDir    string        // 输出目录
	Timeout    time.Duration // 单作业墙钟超时
	MaxMemoryK int           // VIPS_DISC_THRESHOLD（KB），0 用默认
}

func newVipsWorker(workDir string) VipsWorker {
	return VipsWorker{
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

	meta, err := w.inspect(req.Source)
	if err != nil {
		return AvatarWorkerResult{}, err
	}

	// 主输出：动画加载全部帧([n=-1])，居中方裁到 OutputDimension。
	mainSource := req.Source
	if req.Animated {
		mainSource = req.Source + "[n=-1]"
	}
	if err := w.thumbnail(mainSource, mainPath, req.OutputDimension); err != nil {
		return AvatarWorkerResult{}, err
	}
	// 缩略图：始终静态首帧，ThumbDimension 见方。
	if err := w.thumbnail(req.Source, thumbPath, req.ThumbDimension); err != nil {
		_ = os.Remove(mainPath)
		return AvatarWorkerResult{}, err
	}
	return AvatarWorkerResult{Meta: meta, MainPath: mainPath, ThumbPath: thumbPath}, nil
}

// inspect 用单次 vipsheader -a 读取尺寸、帧数与帧延迟，避免多次 fork。
func (w VipsWorker) inspect(src string) (AvatarMeta, error) {
	ctx, cancel := context.WithTimeout(context.Background(), w.Timeout)
	defer cancel()
	out, err := w.run(ctx, "vipsheader", "-a", src)
	if err != nil {
		return AvatarMeta{}, err
	}
	fields := parseVipsHeader(out)
	meta := AvatarMeta{
		Width:  fields["width"],
		Height: fields["height"],
		Frames: 1,
	}
	if pages := fields["n-pages"]; pages > 0 {
		meta.Frames = pages
	}
	if delay := fields["gif-delay"]; delay > 0 {
		// gif-delay 单位 1/100s；用平均帧延迟乘帧数估算时长。
		meta.DurationSeconds = float64(meta.Frames) * float64(delay) / 100.0
	}
	return meta, nil
}

// parseVipsHeader 解析 `vipsheader -a` 的 "field: value" 行，仅提取所需整型字段。
func parseVipsHeader(out string) map[string]int {
	fields := map[string]int{}
	scanner := bufio.NewScanner(strings.NewReader(out))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		key, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		field := strings.Fields(strings.TrimSpace(value))
		if len(field) == 0 {
			continue
		}
		if n, err := strconv.Atoi(field[0]); err == nil {
			fields[key] = n
		}
	}
	return fields
}

// thumbnail 调用 vipsthumbnail：居中方裁到 dimension×dimension。
// 当前客户端契约为居中全幅(center 0.5/0.5, scale 1.0)，故用 --crop centre 忠实实现。
func (w VipsWorker) thumbnail(source, dst string, dimension int) error {
	ctx, cancel := context.WithTimeout(context.Background(), w.Timeout)
	defer cancel()
	size := fmt.Sprintf("%dx%d", dimension, dimension)
	_, err := w.run(ctx, "vipsthumbnail", source,
		"--size", size, "--crop", "centre", "-o", dst)
	return err
}

func (w VipsWorker) run(ctx context.Context, bin string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, bin, args...)
	cmd.Env = append(os.Environ(),
		"VIPS_CONCURRENCY=1",
		fmt.Sprintf("VIPS_DISC_THRESHOLD=%dk", w.discThreshold()),
	)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("%s %v: %w: %s", bin, args, err, string(out))
	}
	return string(out), nil
}

func (w VipsWorker) discThreshold() int {
	if w.MaxMemoryK > 0 {
		return w.MaxMemoryK
	}
	return 256 * 1024
}
