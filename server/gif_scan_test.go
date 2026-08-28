package main

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	"image/gif"
	"os"
	"path/filepath"
	"testing"
)

// makeGIF 生成一个 frames 帧、每帧 w×h 的真 GIF。
//
// 用 image/gif 真编码而不是手搓字节：手搓的样本一旦与真实编码器的输出有出入，
// 测试就变成"我的扫描器能读懂我自己造的格式"，证明不了任何事。
func makeGIF(t *testing.T, frames, w, h int, delay int) []byte {
	t.Helper()
	pal := color.Palette{color.Black, color.White}
	g := &gif.GIF{}
	for i := 0; i < frames; i++ {
		img := image.NewPaletted(image.Rect(0, 0, w, h), pal)
		// 填一点内容，避免全零帧被编码器特殊处理
		img.SetColorIndex(i%w, 0, 1)
		g.Image = append(g.Image, img)
		g.Delay = append(g.Delay, delay)
	}
	var buf bytes.Buffer
	if err := gif.EncodeAll(&buf, g); err != nil {
		t.Fatalf("编码测试 GIF 失败: %v", err)
	}
	return buf.Bytes()
}

func TestScanGIFCountsFramesAndDuration(t *testing.T) {
	// 5 帧，每帧延时 10（= 0.1 秒），总时长应为 0.5 秒
	data := makeGIF(t, 5, 4, 4, 10)
	sum, err := scanGIF(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("扫描失败: %v", err)
	}
	if sum.Frames != 5 {
		t.Errorf("帧数应为 5，实得 %d", sum.Frames)
	}
	if sum.DurationSeconds < 0.49 || sum.DurationSeconds > 0.51 {
		t.Errorf("总时长应约 0.5 秒，实得 %v", sum.DurationSeconds)
	}
}

func TestScanGIFSingleFrame(t *testing.T) {
	data := makeGIF(t, 1, 8, 8, 0)
	sum, err := scanGIF(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("扫描失败: %v", err)
	}
	if sum.Frames != 1 {
		t.Errorf("单帧 GIF 帧数应为 1，实得 %d", sum.Frames)
	}
}

// TestScanGIFRejectsTooManyFrames 是这组测试的核心。
//
// ★ 旧实现（gif.DecodeAll）在这个输入上会把 600 帧全部解成独立 Paletted。
// 帧很小所以旧实现只是变慢而不会真 OOM，但生产上的畸形文件是
// 「单帧 1000×1000（1M 像素，能过 MaxPixels 校验）× 数千帧」——
// 那个内存量级会直接打死进程，而它在结构上与本测试的输入完全同型。
func TestScanGIFRejectsTooManyFrames(t *testing.T) {
	data := makeGIF(t, maxGIFFrames+100, 2, 2, 1)
	_, err := scanGIF(bytes.NewReader(data))
	if !errors.Is(err, errGIFTooManyFrames) {
		t.Fatalf("超过 %d 帧应返回 errGIFTooManyFrames，实得 %v", maxGIFFrames, err)
	}
}

// TestScanGIFStopsEarlyOnFrameBomb 验证它是**提前中止**而不是读完再判。
// 若读完再判，那就等于把整个文件走了一遍，帧数上限也就失去了"省内存"的意义。
func TestScanGIFStopsEarlyOnFrameBomb(t *testing.T) {
	data := makeGIF(t, maxGIFFrames+2000, 2, 2, 1)
	// countingReader 记录实际读了多少字节
	cr := &countingReader{data: data}
	if _, err := scanGIF(cr); !errors.Is(err, errGIFTooManyFrames) {
		t.Fatalf("应返回 errGIFTooManyFrames，实得 %v", err)
	}
	// bufio 会预读，故不能要求"恰好读到上限那帧"，只要求远小于整个文件。
	if cr.n >= len(data) {
		t.Errorf("应在读完整个文件之前就中止：读了 %d / 共 %d 字节", cr.n, len(data))
	}
}

func TestScanGIFRejectsNonGIF(t *testing.T) {
	if _, err := scanGIF(bytes.NewReader([]byte("PK\x03\x04not a gif at all"))); err == nil {
		t.Error("非 GIF 数据应报错")
	}
}

func TestScanGIFTruncated(t *testing.T) {
	data := makeGIF(t, 5, 4, 4, 10)
	// 砍掉后半段，模拟上传中断的文件
	_, err := scanGIF(bytes.NewReader(data[:len(data)/2]))
	if err == nil {
		t.Error("截断的 GIF 应报错，否则会把不完整文件当正常图入库")
	}
}

// TestDecodeGIFUsesFirstFrameOnly 端到端验证 worker.decode 走的是新路径：
// 帧数与时长仍然正确（来自结构扫描），返回的图是首帧。
func TestDecodeGIFUsesFirstFrameOnly(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "anim.gif")
	if err := os.WriteFile(path, makeGIF(t, 6, 16, 12, 20), 0o600); err != nil {
		t.Fatal(err)
	}

	w := newImageWorker(dir)
	img, meta, err := w.decode(path)
	if err != nil {
		t.Fatalf("解码失败: %v", err)
	}
	if meta.Frames != 6 {
		t.Errorf("帧数应为 6，实得 %d", meta.Frames)
	}
	if meta.DurationSeconds < 1.19 || meta.DurationSeconds > 1.21 {
		t.Errorf("总时长应约 1.2 秒，实得 %v", meta.DurationSeconds)
	}
	if b := img.Bounds(); b.Dx() != 16 || b.Dy() != 12 {
		t.Errorf("应返回首帧 16x12，实得 %dx%d", b.Dx(), b.Dy())
	}
}

// TestDecodeGIFFrameBombRejected 帧炸弹在 decode 层就被拒，
// 且映射成 ErrAvatarTooManyFrames（handler 据此给出可操作的中文提示）。
func TestDecodeGIFFrameBombRejected(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "bomb.gif")
	if err := os.WriteFile(path, makeGIF(t, maxGIFFrames+50, 4, 4, 1), 0o600); err != nil {
		t.Fatal(err)
	}
	w := newImageWorker(dir)
	if _, _, err := w.decode(path); !errors.Is(err, ErrAvatarTooManyFrames) {
		t.Fatalf("帧炸弹应返回 ErrAvatarTooManyFrames，实得 %v", err)
	}
}

type countingReader struct {
	data []byte
	pos  int
	n    int
}

func (c *countingReader) Read(p []byte) (int, error) {
	if c.pos >= len(c.data) {
		return 0, errors.New("EOF")
	}
	n := copy(p, c.data[c.pos:])
	c.pos += n
	c.n += n
	return n, nil
}
