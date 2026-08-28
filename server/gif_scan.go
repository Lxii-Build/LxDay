package main

import (
	"bufio"
	"errors"
	"fmt"
	"io"
)

// ================= GIF 结构扫描（只数帧，不解像素） =================
//
// ★ 为什么需要这个文件 ★
//
// 原先的解码路径是「先用 image.DecodeConfig 校验像素上限，再 gif.DecodeAll 解码」。
// 问题是 **DecodeConfig 给出的是首帧（逻辑屏幕）尺寸，而 DecodeAll 会把每一帧
// 都解成一张独立的 image.Paletted**。于是像素上限只管住了一帧，帧数完全没人管：
//
//	一个 20MB 以内、单帧 1000×1000（1M 像素，轻松过检）、但有几千帧的 GIF，
//	DecodeAll 会按帧数线性分配内存 —— 单个请求就能把进程打到 OOM。
//
// 而 AvatarLimits.MaxFrames（120）的校验在 `processAvatar` 里，**位置在
// worker.Process 返回之后**：等它执行时内存早就分配完了，拦不住任何东西。
// 相册上传路径（album_media.go → decodeSource）更是连这道事后检查都没有。
//
// 修法不是"把 MaxFrames 检查往前挪"——挪到哪都得先知道帧数，而知道帧数的
// 唯一现成办法就是 DecodeAll，那是循环论证。正确做法是**在不解码像素的前提下
// 数出帧数**：GIF 的帧是靠块结构分隔的，跳过每个块的数据段即可，
// 全程只读、不分配与图像尺寸相关的内存。
//
// 扫完拿到帧数与总时长，再决定要不要真解码；真解码时也只用 gif.Decode（只解首帧）。

// maxGIFFrames 允许的最大帧数（扫描阶段的硬上限）。
//
// 取 512 而不是复用 AvatarLimits.MaxFrames(120)：这一层是**内存安全**闸门，
// 与"头像动图不该太长"那条业务规则不是一回事。相册允许上传正常的动图 GIF，
// 而正常动图几百帧是常见的；真正要挡的是几千上万帧的畸形文件。
// 头像那条更严的 120 帧规则仍在 processAvatar 里各自生效。
const maxGIFFrames = 512

// errGIFTooManyFrames 帧数超过扫描上限。
var errGIFTooManyFrames = errors.New("gif frame count exceeds limit")

// gifSummary 是结构扫描的结果。
type gifSummary struct {
	Frames          int
	DurationSeconds float64
}

// scanGIF 顺序扫描 GIF 块结构，统计帧数与总延时。
//
// 只读不分配：整个过程的内存占用与图像尺寸、帧数都无关（除了 256 字节的块缓冲）。
// 帧数一旦超过 maxGIFFrames 立即返回 errGIFTooManyFrames，
// 不会继续读完整个文件。
//
// 参考 GIF89a 规范的块结构：
//
//	Header("GIF87a"/"GIF89a") + 逻辑屏幕描述符(7B) + [全局色表]
//	然后是块序列，每块由一个字节的引导符区分：
//	  0x2C 图像描述符 → 一帧
//	  0x21 扩展块（其中 0xF9 图形控制扩展带该帧的延时）
//	  0x3B 结束符
func scanGIF(r io.Reader) (gifSummary, error) {
	br := bufio.NewReader(r)

	// ---- 头部 ----
	var header [6]byte
	if _, err := io.ReadFull(br, header[:]); err != nil {
		return gifSummary{}, fmt.Errorf("gif header: %w", err)
	}
	if string(header[:3]) != "GIF" {
		return gifSummary{}, errors.New("not a gif")
	}

	// ---- 逻辑屏幕描述符 ----
	var lsd [7]byte
	if _, err := io.ReadFull(br, lsd[:]); err != nil {
		return gifSummary{}, fmt.Errorf("gif screen descriptor: %w", err)
	}
	// lsd[4] 的最高位表示有全局色表，低三位是色表大小指数。
	if lsd[4]&0x80 != 0 {
		size := 3 * (1 << ((lsd[4] & 0x07) + 1))
		if err := discardN(br, size); err != nil {
			return gifSummary{}, fmt.Errorf("gif global color table: %w", err)
		}
	}

	var out gifSummary
	// pendingDelay 记录最近一个图形控制扩展给出的延时，
	// 它出现在对应帧的**前面**，故要暂存到读到帧时再累加。
	pendingDelay := 0

	for {
		b, err := br.ReadByte()
		if err != nil {
			if errors.Is(err, io.EOF) {
				// 文件在结束符之前就断了。已经数出来的帧数仍然有效
				// （调用方要的是"这文件有多少帧"，截断的尾部无所谓），
				// 但要让上层知道它不完整。
				return out, io.ErrUnexpectedEOF
			}
			return out, err
		}

		switch b {
		case 0x3B: // Trailer：正常结束
			return out, nil

		case 0x2C: // Image Descriptor：一帧
			out.Frames++
			if out.Frames > maxGIFFrames {
				return out, errGIFTooManyFrames
			}
			out.DurationSeconds += float64(pendingDelay) / 100.0
			pendingDelay = 0

			// 图像描述符共 9 字节，前 8 是位置与尺寸，第 9 字节含局部色表标志。
			var idesc [9]byte
			if _, err := io.ReadFull(br, idesc[:]); err != nil {
				return out, fmt.Errorf("gif image descriptor: %w", err)
			}
			if idesc[8]&0x80 != 0 {
				size := 3 * (1 << ((idesc[8] & 0x07) + 1))
				if err := discardN(br, size); err != nil {
					return out, fmt.Errorf("gif local color table: %w", err)
				}
			}
			// LZW 最小码长（1 字节），随后是数据子块序列。
			if _, err := br.ReadByte(); err != nil {
				return out, fmt.Errorf("gif lzw code size: %w", err)
			}
			if err := skipSubBlocks(br); err != nil {
				return out, fmt.Errorf("gif image data: %w", err)
			}

		case 0x21: // Extension
			label, err := br.ReadByte()
			if err != nil {
				return out, fmt.Errorf("gif extension label: %w", err)
			}
			if label == 0xF9 {
				// 图形控制扩展：块大小(1) + 标志(1) + 延时(2, 小端) + 透明索引(1) + 终止符(1)
				var gce [6]byte
				if _, err := io.ReadFull(br, gce[:]); err != nil {
					return out, fmt.Errorf("gif graphic control: %w", err)
				}
				// gce[0] 是块大小（规范为 4），gce[2..3] 是延时
				pendingDelay = int(gce[2]) | int(gce[3])<<8
				continue
			}
			if err := skipSubBlocks(br); err != nil {
				return out, fmt.Errorf("gif extension data: %w", err)
			}

		case 0x00:
			// 落单的块终止符。宽容跳过：真实世界里有编码器会多写一个。
			continue

		default:
			return out, fmt.Errorf("gif unknown block 0x%02X", b)
		}
	}
}

// skipSubBlocks 跳过一串「长度前缀子块」，直到长度为 0 的终止子块。
func skipSubBlocks(br *bufio.Reader) error {
	for {
		n, err := br.ReadByte()
		if err != nil {
			return err
		}
		if n == 0 {
			return nil
		}
		if err := discardN(br, int(n)); err != nil {
			return err
		}
	}
}

// discardN 丢弃 n 字节。bufio.Reader.Discard 在缓冲不足时会自行读取，
// 但短读时返回的 err 需要显式检查，否则会把截断文件当成正常读完。
func discardN(br *bufio.Reader, n int) error {
	got, err := br.Discard(n)
	if err != nil {
		return err
	}
	if got != n {
		return io.ErrUnexpectedEOF
	}
	return nil
}
