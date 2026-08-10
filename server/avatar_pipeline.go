package main

import (
	"errors"
	"fmt"
	"os"
)

// 头像处理链的哨兵错误，供 handler 映射为具体 HTTP 提示。
var (
	ErrAvatarTooLarge         = errors.New("avatar file too large")
	ErrAvatarTooLong          = errors.New("avatar duration exceeds limit")
	ErrAvatarTooManyFrames    = errors.New("avatar frame count exceeds limit")
	ErrAvatarBadCrop          = errors.New("invalid crop parameters")
	ErrAnimatedNotSupported   = errors.New("animated heif/avif not supported")
	ErrAvatarProcessingFailed = errors.New("avatar processing failed")
)

// AvatarLimits 是资源与输出上限，来自规格：15MB / 10s / 120 帧 / 512 输出 / 256 缩略图。
type AvatarLimits struct {
	MaxBytes     int64
	MaxDuration  float64
	MaxFrames    int
	MaxDimension int
	ThumbSize    int
}

func defaultAvatarLimits() AvatarLimits {
	return AvatarLimits{
		MaxBytes:     15 * 1024 * 1024,
		MaxDuration:  10.0,
		MaxFrames:    120,
		MaxDimension: 512,
		ThumbSize:    256,
	}
}

// CropParams 是客户端归一化裁剪：中心点与缩放，输出恒为 1:1。
type CropParams struct {
	CenterX float64
	CenterY float64
	Scale   float64
}

func (c CropParams) valid() bool {
	return c.CenterX >= 0 && c.CenterX <= 1 &&
		c.CenterY >= 0 && c.CenterY <= 1 &&
		c.Scale > 0 && c.Scale <= 1
}

// AvatarInput 是进入处理链前已知的元信息（大小、格式探测、裁剪）。
type AvatarInput struct {
	SizeBytes int64
	Probe     AvatarProbe
	Crop      CropParams
	Source    string
}

// AvatarMeta 是 worker 解码后回报的真实媒体属性。
type AvatarMeta struct {
	Frames          int
	DurationSeconds float64
	Width           int
	Height          int
}

// AvatarWorkerRequest 传给受限 worker 的作业描述。
// 裁剪当前恒为居中方裁（客户端契约 center 0.5/0.5, scale 1.0），故不透传 Crop；
// CropParams 仅在 processAvatar 内做边界校验，未来支持任意裁剪时再下传。
type AvatarWorkerRequest struct {
	Source          string
	OutputDimension int
	ThumbDimension  int
	Animated        bool
}

// AvatarWorkerResult 是 worker 输出：主文件、缩略图与实测元信息。
type AvatarWorkerResult struct {
	Meta      AvatarMeta
	MainPath  string
	ThumbPath string
}

// AvatarWorker 抽象受限媒体处理执行体，便于测试替换真实 libvips CLI。
type AvatarWorker interface {
	Process(req AvatarWorkerRequest) (AvatarWorkerResult, error)
}

// AvatarResult 是处理链对外结果。
type AvatarResult struct {
	MainPath       string
	ThumbPath      string
	ThumbDimension int
	Animated       bool
}

// processAvatar 执行完整校验与裁剪编排；动画 HEIF/AVIF 明确拒绝而非静默降级。
func processAvatar(in AvatarInput, limits AvatarLimits, worker AvatarWorker) (AvatarResult, error) {
	if in.SizeBytes > limits.MaxBytes {
		return AvatarResult{}, ErrAvatarTooLarge
	}
	if !in.Crop.valid() {
		return AvatarResult{}, ErrAvatarBadCrop
	}
	// 规格：动态 HEIF/AVIF 不静默降级为静图，直接拒绝并保留旧头像。
	if in.Probe.Animated && (in.Probe.Format == FormatHEIF || in.Probe.Format == FormatAVIF) {
		return AvatarResult{}, ErrAnimatedNotSupported
	}

	result, err := worker.Process(AvatarWorkerRequest{
		Source:          in.Source,
		OutputDimension: limits.MaxDimension,
		ThumbDimension:  limits.ThumbSize,
		Animated:        in.Probe.Animated,
	})
	if err != nil {
		return AvatarResult{}, fmt.Errorf("%w: %v", ErrAvatarProcessingFailed, err)
	}

	if in.Probe.Animated {
		if result.Meta.Frames > limits.MaxFrames {
			discardWorkerOutput(result)
			return AvatarResult{}, ErrAvatarTooManyFrames
		}
		if result.Meta.DurationSeconds > limits.MaxDuration {
			discardWorkerOutput(result)
			return AvatarResult{}, ErrAvatarTooLong
		}
	}

	return AvatarResult{
		MainPath:       result.MainPath,
		ThumbPath:      result.ThumbPath,
		ThumbDimension: limits.ThumbSize,
		Animated:       in.Probe.Animated,
	}, nil
}

// discardWorkerOutput 删除已产出但因超限而拒绝的头像文件，避免刷盘残留。
func discardWorkerOutput(result AvatarWorkerResult) {
	if result.MainPath != "" {
		_ = os.Remove(result.MainPath)
	}
	if result.ThumbPath != "" {
		_ = os.Remove(result.ThumbPath)
	}
}
