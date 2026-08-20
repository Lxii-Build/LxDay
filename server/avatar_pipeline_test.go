package main

import (
	"errors"
	"testing"
)

func stillProbe(f ImageFormat) AvatarProbe { return AvatarProbe{Format: f, Animated: false} }
func animProbe(f ImageFormat) AvatarProbe  { return AvatarProbe{Format: f, Animated: true} }

func baseLimits() AvatarLimits {
	return AvatarLimits{
		MaxBytes:     15 * 1024 * 1024,
		MaxDuration:  10.0,
		MaxFrames:    120,
		MaxDimension: 512,
		ThumbSize:    256,
	}
}

func unitCrop() CropParams { return CropParams{CenterX: 0.5, CenterY: 0.5, Scale: 1.0} }

func TestProcessAvatarRejectsOversizeFile(t *testing.T) {
	limits := baseLimits()
	_, err := processAvatar(AvatarInput{
		SizeBytes: limits.MaxBytes + 1,
		Probe:     stillProbe(FormatPNG),
		Crop:      unitCrop(),
	}, limits, stubWorker{})
	if !errors.Is(err, ErrAvatarTooLarge) {
		t.Fatalf("err=%v want ErrAvatarTooLarge", err)
	}
}

func TestProcessAvatarRejectsAnimatedHeifAndAvifWithoutFallback(t *testing.T) {
	limits := baseLimits()
	for _, f := range []ImageFormat{FormatHEIF, FormatAVIF} {
		_, err := processAvatar(AvatarInput{
			SizeBytes: 1024,
			Probe:     animProbe(f),
			Crop:      unitCrop(),
		}, limits, stubWorker{})
		if !errors.Is(err, ErrAnimatedNotSupported) {
			t.Fatalf("format=%v err=%v want ErrAnimatedNotSupported", f, err)
		}
	}
}

func TestProcessAvatarRejectsWorkerProbeExceedingFrameOrDuration(t *testing.T) {
	limits := baseLimits()
	worker := stubWorker{meta: AvatarMeta{Frames: limits.MaxFrames + 1, DurationSeconds: 3}}
	_, err := processAvatar(AvatarInput{
		SizeBytes: 2048,
		Probe:     animProbe(FormatWebP),
		Crop:      unitCrop(),
	}, limits, worker)
	if !errors.Is(err, ErrAvatarTooManyFrames) {
		t.Fatalf("err=%v want ErrAvatarTooManyFrames", err)
	}

	worker = stubWorker{meta: AvatarMeta{Frames: 30, DurationSeconds: limits.MaxDuration + 0.1}}
	_, err = processAvatar(AvatarInput{
		SizeBytes: 2048,
		Probe:     animProbe(FormatGIF),
		Crop:      unitCrop(),
	}, limits, worker)
	if !errors.Is(err, ErrAvatarTooLong) {
		t.Fatalf("err=%v want ErrAvatarTooLong", err)
	}
}

// 纯 Go 解码链不支持的容器（静态 HEIF/AVIF/BMP）必须在进 worker 前就被拒，
// 且给出专门的错误而不是笼统的“处理失败”。
func TestProcessAvatarRejectsFormatsPureGoCannotDecode(t *testing.T) {
	limits := baseLimits()
	for _, f := range []ImageFormat{FormatHEIF, FormatAVIF, FormatBMP} {
		_, err := processAvatar(AvatarInput{
			SizeBytes: 1024,
			Probe:     stillProbe(f),
			Crop:      unitCrop(),
		}, limits, stubWorker{})
		if !errors.Is(err, ErrFormatNotDecodable) {
			t.Fatalf("format=%v err=%v want ErrFormatNotDecodable", f, err)
		}
	}
}

// JPEG 必须能一路走通到 worker（此前白名单漏 JPEG，手机相册主力格式全挂）。
func TestProcessAvatarAcceptsJPEG(t *testing.T) {
	limits := baseLimits()
	worker := stubWorker{meta: AvatarMeta{Frames: 1, Width: 800, Height: 600}}
	out, err := processAvatar(AvatarInput{
		SizeBytes: 4096,
		Probe:     stillProbe(FormatJPEG),
		Crop:      unitCrop(),
	}, limits, worker)
	if err != nil {
		t.Fatalf("jpeg must be accepted, err=%v", err)
	}
	if out.Animated {
		t.Fatal("jpeg output must not be animated")
	}
}

func TestProcessAvatarRejectsInvalidCrop(t *testing.T) {
	limits := baseLimits()
	_, err := processAvatar(AvatarInput{
		SizeBytes: 2048,
		Probe:     stillProbe(FormatPNG),
		Crop:      CropParams{CenterX: 1.4, CenterY: 0.5, Scale: 1.0},
	}, limits, stubWorker{})
	if !errors.Is(err, ErrAvatarBadCrop) {
		t.Fatalf("err=%v want ErrAvatarBadCrop", err)
	}
}

func TestProcessAvatarClampsOutputToMaxDimensionAndBuildsThumb(t *testing.T) {
	limits := baseLimits()
	worker := stubWorker{
		meta:      AvatarMeta{Frames: 24, DurationSeconds: 2.0, Width: 1080, Height: 1080},
		mainName:  "avatar.webp",
		thumbName: "avatar_thumb.png",
	}
	out, err := processAvatar(AvatarInput{
		SizeBytes: 4096,
		Probe:     animProbe(FormatWebP),
		Crop:      unitCrop(),
	}, limits, worker)
	if err != nil {
		t.Fatalf("unexpected err=%v", err)
	}
	if out.ThumbDimension != limits.ThumbSize {
		t.Fatalf("thumb=%d want %d", out.ThumbDimension, limits.ThumbSize)
	}
	if !out.Animated {
		t.Fatalf("expected animated output preserved")
	}
}

func TestProcessAvatarStillOutputIsNotAnimated(t *testing.T) {
	limits := baseLimits()
	worker := stubWorker{meta: AvatarMeta{Frames: 1, DurationSeconds: 0, Width: 400, Height: 400}}
	out, err := processAvatar(AvatarInput{
		SizeBytes: 4096,
		Probe:     stillProbe(FormatPNG),
		Crop:      unitCrop(),
	}, limits, worker)
	if err != nil {
		t.Fatalf("unexpected err=%v", err)
	}
	if out.Animated {
		t.Fatalf("still input must not yield animated output")
	}
}

func TestProcessAvatarPropagatesWorkerFailure(t *testing.T) {
	limits := baseLimits()
	worker := stubWorker{err: errors.New("worker timeout")}
	_, err := processAvatar(AvatarInput{
		SizeBytes: 4096,
		Probe:     animProbe(FormatWebP),
		Crop:      unitCrop(),
	}, limits, worker)
	if !errors.Is(err, ErrAvatarProcessingFailed) {
		t.Fatalf("err=%v want ErrAvatarProcessingFailed", err)
	}
}

type stubWorker struct {
	meta      AvatarMeta
	mainName  string
	thumbName string
	err       error
}

func (w stubWorker) Process(req AvatarWorkerRequest) (AvatarWorkerResult, error) {
	if w.err != nil {
		return AvatarWorkerResult{}, w.err
	}
	main := w.mainName
	if main == "" {
		main = "avatar.out"
	}
	thumb := w.thumbName
	if thumb == "" {
		thumb = "avatar.thumb"
	}
	return AvatarWorkerResult{
		Meta:      w.meta,
		MainPath:  main,
		ThumbPath: thumb,
	}, nil
}
