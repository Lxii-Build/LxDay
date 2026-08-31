package main

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	"image/gif"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// ================= GIF 帧炸弹的实测验证（0828 外部报告） =================
//
// 外部分析报告给的攻击参数：**1000 帧 × 1000×1000**，文件仅几十 KB
// （每帧只改一个像素，LZW 压缩率极高），而 `gif.DecodeAll` 会把每帧
// 都解成独立的 image.Paletted → 约 1GB 起，足以打死进程。
//
// 本文件的作用不是"再修一遍"（v1.0.2 已改为结构扫描 + 只解首帧），
// 而是**用报告给的确切参数实测当前实现真的挡得住**。
// "我改了" 不等于 "它挡得住"，这两件事必须分开验证。

// buildFrameBomb 造一个 frames 帧、每帧 w×h 的**合法** GIF。
//
// 不用 gif.EncodeAll 直接编 1000 帧：那样测试进程自己就要分配约 1GB
// （编码器需要持有全部帧），会把被测对象的内存变化淹没掉。
//
// 做法是先用编码器产出一个合法的单帧 GIF，再把它的「图像块」
// （0x2C 引导符到数据子块结束）原样复制 frames 份后接上结束符 0x3B。
// 得到的字节流在结构上与真实多帧 GIF 完全一致 —— DecodeAll 照样会
// 逐帧分配，所以它是一个真炸弹，而不是"看起来像炸弹的样本"。
func buildFrameBomb(t *testing.T, frames, w, h int) []byte {
	t.Helper()

	pal := color.Palette{color.Black, color.White}
	img := image.NewPaletted(image.Rect(0, 0, w, h), pal)
	// 只点一个像素：与报告描述一致，保证 LZW 压得极小
	img.SetColorIndex(0, 0, 1)

	var one bytes.Buffer
	if err := gif.EncodeAll(&one, &gif.GIF{
		Image: []*image.Paletted{img},
		Delay: []int{0},
	}); err != nil {
		t.Fatalf("编码基准帧失败: %v", err)
	}
	base := one.Bytes()

	// 定位图像块必须**按块结构走**，不能用 bytes.LastIndexByte 找 0x2C：
	// LZW 压缩后的数据里完全可能出现 0x2C 字节，那样切出来的边界落在
	// 图像数据中间，拼出来的样本结构就是坏的（第一版就踩了这个，
	// 表现为扫描器报 "gif image data: EOF" 而不是帧数超限 —— 样本的问题，不是被测对象的问题）。
	header, imageBlock := splitFirstImageBlock(t, base)
	const trailer = byte(0x3B)

	out := make([]byte, 0, len(header)+len(imageBlock)*frames+1)
	out = append(out, header...)
	for i := 0; i < frames; i++ {
		out = append(out, imageBlock...)
	}
	out = append(out, trailer)
	return out
}

// splitFirstImageBlock 按 GIF 块结构切出「头部」与「第一个完整图像块」。
//
// 与 scanGIF 走同一套块语义（但这里是测试辅助，独立实现以免
// "用被测对象来构造被测样本"）：
//
//	头部：签名(6) + 逻辑屏幕描述符(7) + [全局色表]
//	之后是块序列；0x2C 是图像描述符，0x21 是扩展块，0x3B 是结束符。
func splitFirstImageBlock(t *testing.T, b []byte) (header, imageBlock []byte) {
	t.Helper()
	i := 6 + 7 // 签名 + 逻辑屏幕描述符
	if len(b) < i {
		t.Fatalf("GIF 太短: %d 字节", len(b))
	}
	if b[10]&0x80 != 0 { // 全局色表标志位在 LSD 的第 5 字节(索引 10)
		i += 3 * (1 << ((b[10] & 0x07) + 1))
	}

	skipSub := func(p int) int { // 跳过长度前缀子块串
		for p < len(b) {
			n := int(b[p])
			p++
			if n == 0 {
				return p
			}
			p += n
		}
		t.Fatal("子块未正常结束")
		return p
	}

	blockStart := i
	for i < len(b) {
		switch b[i] {
		case 0x21: // 扩展块（图形控制扩展属于当前帧，要一并复制）
			i += 2 // 引导符 + 标签
			i = skipSub(i)
		case 0x2C: // 图像描述符 → 这一帧
			p := i + 1 + 9        // 引导符 + 描述符 9 字节
			if b[i+9]&0x80 != 0 { // 局部色表
				p += 3 * (1 << ((b[i+9] & 0x07) + 1))
			}
			p++            // LZW 最小码长
			p = skipSub(p) // 图像数据子块
			return b[:blockStart], b[blockStart:p]
		case 0x3B:
			t.Fatal("在找到图像块之前就遇到结束符")
		default:
			t.Fatalf("未知块引导符 0x%02X @%d", b[i], i)
		}
	}
	t.Fatal("未找到图像块")
	return nil, nil
}

// TestFrameBombRejectedWithBoundedMemory 是本次外部报告的直接回归测试。
//
// 断言两件事，缺一不可：
//  1. 它被**拒绝**（返回 ErrAvatarTooManyFrames，handler 会给出中文提示）；
//  2. 拒绝过程中**内存增长有上界**。只断言"被拒绝"是不够的 ——
//     如果实现是"先全解完再判帧数"，它同样会返回错误，但内存已经炸了。
//     这正是 v1.0.1 头像链路的真实形态（MaxFrames 检查在解码之后）。
func TestFrameBombRejectedWithBoundedMemory(t *testing.T) {
	const frames, w, h = 1000, 1000, 1000

	data := buildFrameBomb(t, frames, w, h)
	onDiskKB := len(data) / 1024
	// 若 DecodeAll 全解：1000 帧 × 1M 像素 × 1 字节调色板索引 ≈ 953MB
	//（Paletted 每像素 1 字节；报告按 4 字节算得 4GB，量级结论一致）
	fullDecodeMB := frames * w * h / 1024 / 1024
	ratio := float64(fullDecodeMB*1024) / float64(onDiskKB)
	t.Logf("炸弹文件 %d KB；DecodeAll 全解约需 %d MB；放大比 %.0f 倍",
		onDiskKB, fullDecodeMB, ratio)

	// 断言**放大比**而非绝对体积：绝对体积取决于编码器的 LZW 实现
	//（Go 的标准库编码器不如手工优化的紧，每帧约 1.7KB），
	// 而"文件小、解开大"这个攻击特征体现在放大比上。
	// 报告里那个几十 KB 的样本放大比更高，本样本已足以证明问题成立。
	if ratio < 50 {
		t.Fatalf("放大比仅 %.0f 倍，样本不构成炸弹 —— 构造可能不对", ratio)
	}

	dir := t.TempDir()
	path := filepath.Join(dir, "bomb.gif")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}

	worker := newImageWorker(dir)

	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)

	_, _, err := worker.decode(path)

	runtime.ReadMemStats(&after)
	// TotalAlloc 单调累加，不受 GC 影响，是"这段代码一共申请了多少"的可靠度量。
	allocMB := float64(after.TotalAlloc-before.TotalAlloc) / 1024 / 1024
	t.Logf("decode 期间累计分配 %.1f MB", allocMB)

	if !errors.Is(err, ErrAvatarTooManyFrames) {
		t.Fatalf("1000 帧炸弹必须被拒绝且返回 ErrAvatarTooManyFrames，实得 %v", err)
	}

	// 上界取 64MB：结构扫描只用一个 256 字节缓冲，正常应当远低于此。
	// 给足余量是为了不受 Go 运行时与测试框架自身分配的干扰，
	// 但仍远小于 DecodeAll 那条路径的 1000MB —— 量级上无法混淆。
	if allocMB > 64 {
		t.Errorf("拒绝一个帧炸弹不该分配 %.1f MB —— 说明它在判定之前就解码了", allocMB)
	}
}

// TestFrameBombViaAvatarPipeline 覆盖头像链路。
//
// v1.0.1 的头像链路是**先全量解码、再检查 MaxFrames**，
// 所以它虽然会返回"帧数超限"，但内存早就炸了。这条测试确认现在
// 拦截发生在解码之前。
func TestFrameBombViaAvatarPipeline(t *testing.T) {
	const frames = 1000
	data := buildFrameBomb(t, frames, 1000, 1000)

	dir := t.TempDir()
	path := filepath.Join(dir, "bomb.gif")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}

	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)

	_, err := processAvatar(
		AvatarInput{
			Source:    path,
			SizeBytes: int64(len(data)),
			Probe:     AvatarProbe{Format: FormatGIF, Animated: true},
			Crop:      CropParams{CenterX: 0.5, CenterY: 0.5, Scale: 1},
		},
		defaultAvatarLimits(),
		newImageWorker(dir),
	)

	runtime.ReadMemStats(&after)
	allocMB := float64(after.TotalAlloc-before.TotalAlloc) / 1024 / 1024
	t.Logf("头像链路累计分配 %.1f MB, err=%v", allocMB, err)

	if err == nil {
		t.Fatal("头像链路必须拒绝帧炸弹")
	}
	if !errors.Is(err, ErrAvatarTooManyFrames) {
		t.Errorf("应返回 ErrAvatarTooManyFrames（handler 据此给中文提示），实得 %v", err)
	}
	if allocMB > 64 {
		t.Errorf("头像链路拒绝帧炸弹时分配了 %.1f MB —— 说明仍是先解码后校验", allocMB)
	}
}

// TestLargeSingleFrameGIFStillBounded 报告没提但同族的一个变体：
// **帧数合法、但首帧极大**。
//
// 结构扫描只管帧数，管不了首帧尺寸；挡它的是 decode 里的 MaxPixels 校验。
// 这条测试确认那道校验对 GIF 同样生效 —— 否则"帧数不超限"就成了绕过口。
func TestLargeSingleFrameGIFStillBounded(t *testing.T) {
	// 单帧 12000×12000 = 1.44 亿像素，远超默认 MaxPixels(12M)
	data := buildFrameBomb(t, 1, 12000, 12000)
	dir := t.TempDir()
	path := filepath.Join(dir, "huge.gif")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}

	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)

	_, _, err := newImageWorker(dir).decode(path)

	runtime.ReadMemStats(&after)
	allocMB := float64(after.TotalAlloc-before.TotalAlloc) / 1024 / 1024
	t.Logf("超大单帧累计分配 %.1f MB, err=%v", allocMB, err)

	if !errors.Is(err, ErrAvatarTooLarge) {
		t.Fatalf("超过 MaxPixels 的单帧 GIF 应返回 ErrAvatarTooLarge，实得 %v", err)
	}
	if allocMB > 64 {
		t.Errorf("像素超限的图不该被解码，却分配了 %.1f MB", allocMB)
	}
}
