package main

import (
	"bytes"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"testing"
)

// 纯 Go worker 的端到端：真实编码一张图 → 解码 → 居中方裁 → 输出双尺寸 PNG。
// 这条链路替换了原先 fork libvips CLI 的实现（alpine 镜像里根本没有 libvips）。
func TestGoImageWorkerProcessesJPEG(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "in.jpg")
	writeTestJPEG(t, src, 800, 400)

	w := newImageWorker(filepath.Join(dir, "out"))
	res, err := w.Process(AvatarWorkerRequest{Source: src, OutputDimension: 512, ThumbDimension: 256})
	if err != nil {
		t.Fatalf("process: %v", err)
	}
	if res.Meta.Width != 800 || res.Meta.Height != 400 {
		t.Fatalf("meta=%#v", res.Meta)
	}
	if res.Meta.Frames != 1 {
		t.Fatalf("frames=%d, want 1", res.Meta.Frames)
	}
	assertSquarePNG(t, res.MainPath, 512)
	assertSquarePNG(t, res.ThumbPath, 256)
}

func TestGoImageWorkerProcessesPNGAndWritesIntoWorkDir(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "in.png")
	writeTestPNG(t, src, 300, 300)

	out := filepath.Join(dir, "nested", "out")
	w := newImageWorker(out)
	res, err := w.Process(AvatarWorkerRequest{Source: src, OutputDimension: 512, ThumbDimension: 256})
	if err != nil {
		t.Fatalf("process: %v", err)
	}
	// 工作目录应被自动创建，且产物落在其中。
	if filepath.Dir(res.MainPath) != out {
		t.Fatalf("main path %q not in workdir %q", res.MainPath, out)
	}
	assertSquarePNG(t, res.MainPath, 512)
}

// GIF 取首帧并回报帧数与时长，供 pipeline 做动图上限校验。
func TestGoImageWorkerReportsGifFramesAndDuration(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "in.gif")
	writeTestGIF(t, src, 3, 50) // 3 帧，每帧 0.5s

	w := newImageWorker(filepath.Join(dir, "out"))
	res, err := w.Process(AvatarWorkerRequest{
		Source: src, OutputDimension: 512, ThumbDimension: 256, Animated: true,
	})
	if err != nil {
		t.Fatalf("process: %v", err)
	}
	if res.Meta.Frames != 3 {
		t.Fatalf("frames=%d, want 3", res.Meta.Frames)
	}
	if res.Meta.DurationSeconds < 1.4 || res.Meta.DurationSeconds > 1.6 {
		t.Fatalf("duration=%v, want ~1.5", res.Meta.DurationSeconds)
	}
}

func TestGoImageWorkerRejectsGarbageAndOversizedPixels(t *testing.T) {
	dir := t.TempDir()
	bad := filepath.Join(dir, "bad.bin")
	if err := os.WriteFile(bad, []byte("not an image at all"), 0o600); err != nil {
		t.Fatal(err)
	}
	w := newImageWorker(filepath.Join(dir, "out"))
	if _, err := w.Process(AvatarWorkerRequest{Source: bad, OutputDimension: 512, ThumbDimension: 256}); err == nil {
		t.Fatal("expected error for garbage input")
	}

	// 像素上限：把上限压到极小，正常图也应被判定为过大（防解压炸弹）。
	src := filepath.Join(dir, "in.png")
	writeTestPNG(t, src, 100, 100)
	tiny := newImageWorker(filepath.Join(dir, "out2"))
	tiny.MaxPixels = 10
	if _, err := tiny.Process(AvatarWorkerRequest{Source: src, OutputDimension: 64, ThumbDimension: 32}); err == nil {
		t.Fatal("expected error for oversized pixel count")
	}
}

// 居中方裁：宽图应取中间的正方形，而不是拉伸或取左上角。
func TestCenterSquarePicksCenteredRegion(t *testing.T) {
	got := centerSquare(image.Rect(0, 0, 800, 400))
	want := image.Rect(200, 0, 600, 400)
	if got != want {
		t.Fatalf("wide: got %v want %v", got, want)
	}
	got = centerSquare(image.Rect(0, 0, 400, 900))
	want = image.Rect(0, 250, 400, 650)
	if got != want {
		t.Fatalf("tall: got %v want %v", got, want)
	}
	got = centerSquare(image.Rect(0, 0, 500, 500))
	if got != image.Rect(0, 0, 500, 500) {
		t.Fatalf("square: got %v", got)
	}
}

func newGradient(w, h int) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{R: uint8(x % 256), G: uint8(y % 256), B: 128, A: 255})
		}
	}
	return img
}

func writeTestJPEG(t *testing.T, path string, w, h int) {
	t.Helper()
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, newGradient(w, h), &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
}

func writeTestPNG(t *testing.T, path string, w, h int) {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, newGradient(w, h)); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
}

func writeTestGIF(t *testing.T, path string, frames, delayCentis int) {
	t.Helper()
	g := &gif.GIF{}
	for i := 0; i < frames; i++ {
		pal := image.NewPaletted(image.Rect(0, 0, 60, 60), color.Palette{
			color.RGBA{A: 255}, color.RGBA{R: 255, A: 255}, color.RGBA{G: 255, A: 255},
		})
		for y := 0; y < 60; y++ {
			for x := 0; x < 60; x++ {
				pal.SetColorIndex(x, y, uint8((x+i)%3))
			}
		}
		g.Image = append(g.Image, pal)
		g.Delay = append(g.Delay, delayCentis)
	}
	var buf bytes.Buffer
	if err := gif.EncodeAll(&buf, g); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
}

func assertSquarePNG(t *testing.T, path string, dim int) {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open %s: %v", path, err)
	}
	defer f.Close()
	cfg, format, err := image.DecodeConfig(f)
	if err != nil {
		t.Fatalf("decode %s: %v", path, err)
	}
	if format != "png" {
		t.Fatalf("format=%s, want png", format)
	}
	if cfg.Width != dim || cfg.Height != dim {
		t.Fatalf("size=%dx%d, want %dx%d", cfg.Width, cfg.Height, dim, dim)
	}
}
