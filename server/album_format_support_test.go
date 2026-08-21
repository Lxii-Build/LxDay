package main

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/gen2brain/avif"
	"golang.org/x/image/bmp"
)

// 造一张有内容的测试图（纯色会被某些编码器压到极小，不利于验证真实解码路径）。
func makeTestImage(w, h int) image.Image {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{
				R: uint8((x * 255) / w),
				G: uint8((y * 255) / h),
				B: uint8((x + y) % 255),
				A: 255,
			})
		}
	}
	return img
}

// 端到端验证：每种格式都要走完「魔数探测 → 解码 → 生成缩略图」全链路。
//
// 管理员 Q9=C 要求服务端真支持 HEIC/AVIF。只验证 decodable() 返回 true 是不够的——
// 那只是个 switch；真正的问题是 image.DecodeConfig 能不能认出来、解码器能不能吐出像素。
// 0820 那轮的教训就是「只验证文件存在/编译通过」而没真跑，结果生产必 500。
func TestUploadFormatsEndToEnd(t *testing.T) {
	src := makeTestImage(240, 180)

	encode := func(t *testing.T, kind string) []byte {
		t.Helper()
		var buf bytes.Buffer
		var err error
		switch kind {
		case "jpeg":
			err = jpeg.Encode(&buf, src, &jpeg.Options{Quality: 90})
		case "png":
			err = png.Encode(&buf, src)
		case "gif":
			err = gif.Encode(&buf, src, nil)
		case "bmp":
			err = bmp.Encode(&buf, src)
		case "avif":
			err = avif.Encode(&buf, src, avif.Options{Quality: 60})
		default:
			t.Fatalf("unknown kind %q", kind)
		}
		if err != nil {
			t.Fatalf("encode %s: %v", kind, err)
		}
		return buf.Bytes()
	}

	// HEIC 没有纯 Go 编码器，无法在测试里合成，故只覆盖能编码的格式。
	// HEIC 的解码能力由 decodable()+displayName() 与 avatar_worker 的 case 分支保证，
	// 真机验证走管理员的一加 15（开「高效格式」直出即 HEIC）。
	cases := []struct {
		kind       string
		wantFormat ImageFormat
	}{
		{"jpeg", FormatJPEG},
		{"png", FormatPNG},
		{"gif", FormatGIF},
		{"bmp", FormatBMP},
		{"avif", FormatAVIF},
	}

	dir := t.TempDir()
	for _, tc := range cases {
		t.Run(tc.kind, func(t *testing.T) {
			raw := encode(t, tc.kind)
			path := filepath.Join(dir, tc.kind+".bin")
			if err := os.WriteFile(path, raw, 0o600); err != nil {
				t.Fatal(err)
			}

			// 1) 魔数探测必须认出正确的容器格式
			probe, valid := probeUploadedFile(path)
			if !valid {
				t.Fatalf("%s: 魔数探测失败（%d 字节）", tc.kind, len(raw))
			}
			if probe.Format != tc.wantFormat {
				t.Fatalf("%s: 探测到 %s，期望 %s",
					tc.kind, probe.Format.displayName(), tc.wantFormat.displayName())
			}
			if !probe.Format.decodable() {
				t.Fatalf("%s: decodable() 返回 false", tc.kind)
			}

			// 2) 真实解码：拿到像素与正确尺寸
			worker := newImageWorker(dir)
			img, meta, err := worker.decodeSource(path)
			if err != nil {
				t.Fatalf("%s: 解码失败 %v", tc.kind, err)
			}
			if img == nil {
				t.Fatalf("%s: 解码返回 nil 图像", tc.kind)
			}
			if meta.Width != 240 || meta.Height != 180 {
				t.Fatalf("%s: 尺寸 %dx%d，期望 240x180", tc.kind, meta.Width, meta.Height)
			}

			// 3) 生成缩略图（这是上传链路真正会做的事）
			asJPEG := tc.wantFormat == FormatJPEG || tc.wantFormat == FormatGIF
			thumbExt := ".png"
			if asJPEG {
				thumbExt = ".jpg"
			}
			thumb := filepath.Join(dir, tc.kind+"_thumb"+thumbExt)
			if err := worker.writeFit(img, thumb, photoThumbEdge, asJPEG); err != nil {
				t.Fatalf("%s: 生成缩略图失败 %v", tc.kind, err)
			}
			fi, err := os.Stat(thumb)
			if err != nil || fi.Size() == 0 {
				t.Fatalf("%s: 缩略图未落盘或为空", tc.kind)
			}
			t.Logf("%s: 探测=%s 解码=%dx%d 缩略图=%d 字节",
				tc.kind, probe.Format.displayName(), meta.Width, meta.Height, fi.Size())
		})
	}
}

// 损坏文件必须报 errImageDecode（可辨识的"图坏了"），
// 而不是笼统错误——handler 据此回 400「这张图已损坏」而非 500。
func TestDecodeCorruptedReportsDecodeError(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "broken.jpg")
	// 合法 JPEG 头 + 垃圾字节：魔数能过，解码必失败。
	broken := append([]byte{0xFF, 0xD8, 0xFF, 0xE0}, bytes.Repeat([]byte{0x41}, 512)...)
	if err := os.WriteFile(path, broken, 0o600); err != nil {
		t.Fatal(err)
	}
	worker := newImageWorker(dir)
	_, _, err := worker.decodeSource(path)
	if err == nil {
		t.Fatal("损坏文件竟然解码成功了")
	}
	if !errors.Is(err, errImageDecode) {
		t.Fatalf("应包裹 errImageDecode，实际 %v", err)
	}
}

// GIF 缩略图出 JPEG 而非 PNG（Q50=A）：缩略图只取首帧、不需要透明通道，
// PNG 会大好几倍，白占磁盘与流量。
func TestGifThumbnailIsJpeg(t *testing.T) {
	dir := t.TempDir()
	src := makeTestImage(300, 200)

	var gifBuf bytes.Buffer
	if err := gif.Encode(&gifBuf, src, nil); err != nil {
		t.Fatal(err)
	}
	gifPath := filepath.Join(dir, "a.gif")
	if err := os.WriteFile(gifPath, gifBuf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}

	worker := newImageWorker(dir)
	img, _, err := worker.decodeSource(gifPath)
	if err != nil {
		t.Fatalf("decode gif: %v", err)
	}

	asJPEG := filepath.Join(dir, "a_thumb.jpg")
	asPNG := filepath.Join(dir, "a_thumb.png")
	if err := worker.writeFit(img, asJPEG, photoThumbEdge, true); err != nil {
		t.Fatalf("write jpeg thumb: %v", err)
	}
	if err := worker.writeFit(img, asPNG, photoThumbEdge, false); err != nil {
		t.Fatalf("write png thumb: %v", err)
	}
	jf, _ := os.Stat(asJPEG)
	pf, _ := os.Stat(asPNG)
	t.Logf("GIF 缩略图：JPEG=%d 字节  PNG=%d 字节", jf.Size(), pf.Size())
	if jf.Size() >= pf.Size() {
		t.Fatalf("JPEG 缩略图(%d) 应显著小于 PNG(%d)", jf.Size(), pf.Size())
	}
}

// 三档缩略图的尺寸约定：thumb 384 / preview 1080，且都不放大小图。
func TestThreeTierThumbnailEdges(t *testing.T) {
	if photoThumbEdge != 384 {
		t.Fatalf("photoThumbEdge=%d want 384", photoThumbEdge)
	}
	if photoPreviewEdge != 1080 {
		t.Fatalf("photoPreviewEdge=%d want 1080", photoPreviewEdge)
	}
	// 长边 2048 的源图：thumb 与 preview 都要缩，且比例保持。
	w, h := fitDimensions(2048, 1536, photoThumbEdge)
	if w != 384 || h != 288 {
		t.Fatalf("thumb 尺寸 %dx%d，期望 384x288", w, h)
	}
	w, h = fitDimensions(2048, 1536, photoPreviewEdge)
	if w != 1080 || h != 810 {
		t.Fatalf("preview 尺寸 %dx%d，期望 1080x810", w, h)
	}
	// 小图不放大
	w, h = fitDimensions(200, 150, photoPreviewEdge)
	if w != 200 || h != 150 {
		t.Fatalf("小图被放大了：%dx%d", w, h)
	}
}
