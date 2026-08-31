package main

import (
	"image"
	"image/color"
	"path/filepath"
	"runtime"
	"testing"

	"golang.org/x/image/draw"
)

// ================= 像素上限路径的内存回归 =================
//
// 帧炸弹已由结构扫描挡住（gif_bomb_verify_test.go：0.0 MB 就拒掉）。
// 但同族里还有一个**帧数完全合法**的变体：单帧就顶到像素上限。
// 它能过掉所有校验，然后走完整条派生图生成流程 —— 而那里藏着真凶：
//
//	draw.CatmullRom / BiLinear 的 Scale 会分配一块正比于**源图高度**的
//	临时缓冲。实测把公式钉死了：分配 ≈ 目标宽 × 源高 × 32 字节。
//	  源 8000×8000 → 目标  384×384 ：94.77 MB（公式 93.75）
//	  源 3464×3464 → 目标 1080×1080：114.69 MB（公式 114.17）
//	  ApproxBiLinear 同输入          ：0.00 MB
//
// 相册每张跑两次（缩略图 384 + 预览图 1080），单段走完约 157 MB。
// 这与图片格式无关，一张合法的大 PNG 就能触发。
//
// ★ 本文件的阈值都按上面那个公式推出来，不是随手取的整数 ★
// 阈值必须同时满足两件事：① 改回单段实现时会红；② 不会因运行时抖动误报。

// TestScaleIntoBoundsMemory 两段式缩放必须把大源图的开销压下来。
//
// ★ 把实现改回单段 `interp.Scale(...)` 时这条会红（分配约 95MB）。
func TestScaleIntoBoundsMemory(t *testing.T) {
	const side = 8000
	w := newImageWorker(t.TempDir())

	// Gray 每像素 1 字节，用它排除"源图本身很大"的干扰，
	// 只度量缩放过程的追加分配。
	src := image.NewGray(image.Rect(0, 0, side, side))
	dst := image.NewRGBA(image.Rect(0, 0, photoThumbEdge, photoThumbEdge))

	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	w.scaleInto(dst, src, src.Bounds())
	runtime.ReadMemStats(&after)

	allocMB := float64(after.TotalAlloc-before.TotalAlloc) / 1024 / 1024
	t.Logf("源图 %dx%d → 目标 %dx%d：分配 %.1f MB",
		side, side, photoThumbEdge, photoThumbEdge, allocMB)

	// 公式给出的两条边界：
	//   单段：384 × 8000 × 32 = 93.75 MB（实测 94.77）
	//   两段：384 × 480  × 32 = 5.6 MB + 中间图 480×480×4 = 0.9 MB（实测 6.60）
	// 阈值取 32MB：约为两段式实测的 5 倍余量，同时只有单段的 1/3 —— 两侧都拉得开。
	if allocMB > 32 {
		t.Errorf("两段式缩放应把分配压到 32MB 以内，实得 %.1f MB "+
			"—— 说明大源图仍在直接喂给 CatmullRom", allocMB)
	}
}

// TestScaleIntoSkipsPrescaleWhenClose 倍率不大时不该多做一次中间图。
// 预缩本身也有成本，小比例缩放时得不偿失。
func TestScaleIntoSkipsPrescaleWhenClose(t *testing.T) {
	w := newImageWorker(t.TempDir())
	// 源 500 → 目标 384，倍率 1.3 < prescaleThreshold(2)
	src := image.NewGray(image.Rect(0, 0, 500, 500))
	dst := image.NewRGBA(image.Rect(0, 0, 384, 384))

	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	w.scaleInto(dst, src, src.Bounds())
	runtime.ReadMemStats(&after)
	allocMB := float64(after.TotalAlloc-before.TotalAlloc) / 1024 / 1024
	t.Logf("小倍率缩放分配 %.2f MB", allocMB)
	if allocMB > 8 {
		t.Errorf("小倍率不该有大开销，实得 %.2f MB", allocMB)
	}
}

// TestScaleIntoProducesSameSize 两段式不得改变产物尺寸。
// 这次改动只该省内存，不该改结果。
func TestScaleIntoProducesSameSize(t *testing.T) {
	w := newImageWorker(t.TempDir())
	for _, tc := range []struct{ sw, sh, tw, th int }{
		{8000, 8000, 384, 384},
		{6000, 4000, 384, 256},
		{4000, 6000, 256, 384},
		{500, 500, 384, 384},
		{100, 80, 100, 80},
	} {
		src := image.NewGray(image.Rect(0, 0, tc.sw, tc.sh))
		dst := image.NewRGBA(image.Rect(0, 0, tc.tw, tc.th))
		w.scaleInto(dst, src, src.Bounds())
		if dst.Bounds().Dx() != tc.tw || dst.Bounds().Dy() != tc.th {
			t.Errorf("%dx%d → 期望 %dx%d，实得 %dx%d",
				tc.sw, tc.sh, tc.tw, tc.th, dst.Bounds().Dx(), dst.Bounds().Dy())
		}
	}
}

// TestScaleIntoKeepsQuality 两段式不能把画质做坏。
//
// 造一张黑白棋盘缩小，检查产物既不是全黑也不是全白 ——
// 若第一段预缩把信息丢干净了（比如错用了最近邻或缩过头），
// 结果会退化成单色块。这不是精密的画质度量，但能挡住"缩放链路写坏"这类问题。
func TestScaleIntoKeepsQuality(t *testing.T) {
	const side = 4096
	src := image.NewGray(image.Rect(0, 0, side, side))
	block := 64
	for y := 0; y < side; y++ {
		for x := 0; x < side; x++ {
			if ((x/block)+(y/block))%2 == 0 {
				src.SetGray(x, y, color.Gray{Y: 255})
			}
		}
	}
	w := newImageWorker(t.TempDir())
	dst := image.NewRGBA(image.Rect(0, 0, 256, 256))
	w.scaleInto(dst, src, src.Bounds())

	var min, max uint8 = 255, 0
	for y := 0; y < 256; y++ {
		for x := 0; x < 256; x++ {
			r, _, _, _ := dst.At(x, y).RGBA()
			v := uint8(r >> 8)
			if v < min {
				min = v
			}
			if v > max {
				max = v
			}
		}
	}
	t.Logf("棋盘缩放后灰度范围 %d..%d", min, max)
	if max-min < 32 {
		t.Errorf("产物几乎是单色（范围 %d..%d）——缩放丢失了全部结构", min, max)
	}
}

// TestUploadBizCodesAreDistinct 上传业务码不得撞号。
//
// 客户端 UploadRetryPolicy 按码判「能否重试」，而判错方向不对称：
// 把可重试的判成不可重试会让照片永久停在失败列表 —— 那就是管理员
// 反馈过的「照片会消失」。0829 实际踩过一次：单账号在飞上限最初复用了
// codeUploadQuotaFull(1020)，而客户端把 1020 判为不可重试，
// 于是"等前面几张传完就好"变成了永久失败。
//
// 这条测试挡住"以后又有人图省事复用一个现成的码"。
func TestUploadBizCodesAreDistinct(t *testing.T) {
	codes := map[string]int{
		"codeUploadQuotaFull":  codeUploadQuotaFull,
		"codeUploadTooLarge":   codeUploadTooLarge,
		"codeUploadBadFormat":  codeUploadBadFormat,
		"codeUploadNoDecoder":  codeUploadNoDecoder,
		"codeUploadCorrupted":  codeUploadCorrupted,
		"codeUploadTooManyPx":  codeUploadTooManyPx,
		"codeUploadDiskFailed": codeUploadDiskFailed,
		"codeUploadDisabled":   codeUploadDisabled,
		"codeUploadInFlight":   codeUploadInFlight,
	}
	seen := map[int]string{}
	for name, code := range codes {
		if prev, dup := seen[code]; dup {
			t.Errorf("业务码 %d 被 %s 与 %s 共用 —— 客户端无法分辨两者可否重试",
				code, prev, name)
		}
		seen[code] = name
	}

	// 尤其这两个：都回 429，但可重试性相反。
	if codeUploadInFlight == codeUploadQuotaFull {
		t.Error("在飞上限与当日配额必须分开：前者等一下就能成功（可重试），" +
			"后者当天无解（不可重试）")
	}
}

// TestPixelCeilingLoweredAndConfigurable 像素上限已下调且可由后台调整。
func TestPixelCeilingLoweredAndConfigurable(t *testing.T) {
	d := defaultRuntimeSettings()
	if d.PhotoMaxPixels != 12<<20 {
		t.Errorf("默认像素上限应为 12M，实得 %dM", d.PhotoMaxPixels>>20)
	}
	// 客户端压缩后的正常图（长边 2048，4:3 约 3.1M 像素）必须仍然过得去，
	// 否则这次收紧就把正常用户拦住了。
	const clientTypicalPixels = 2048 * 1536
	if d.PhotoMaxPixels < clientTypicalPixels*2 {
		t.Errorf("上限 %dM 对客户端正常产物（约 %.1fM）余量不足",
			d.PhotoMaxPixels>>20, float64(clientTypicalPixels)/(1<<20))
	}
	// 解码内存与像素数成正比：RGBA 每像素 4 字节
	worstMB := float64(d.PhotoMaxPixels) * 4 / 1024 / 1024
	t.Logf("默认上限 %dM 像素 → 单张源图最坏约 %.0f MB；%d 路并发约 %.0f MB",
		d.PhotoMaxPixels>>20, worstMB, maxConcurrentDecodes, worstMB*float64(maxConcurrentDecodes))
	if worstMB > 128 {
		t.Errorf("单张最坏 %.0f MB 仍然过高", worstMB)
	}

	// spec 存在、限超管、范围收敛
	if !isRuntimeSettingKey("album.photo_max_megapixels") {
		t.Fatal("像素上限应可后台配置")
	}
	if !isSuperOnlySettingKey("album.photo_max_megapixels") {
		t.Error("像素上限是内存安全闸门，必须限超管")
	}
	s := defaultRuntimeSettings()
	applySettingValue(&s, "album.photo_max_megapixels", "9999")
	if s.PhotoMaxPixels != 64<<20 {
		t.Errorf("越界值应收敛到 64M，实得 %dM", s.PhotoMaxPixels>>20)
	}
	applySettingValue(&s, "album.photo_max_megapixels", "0")
	if s.PhotoMaxPixels != 4<<20 {
		t.Errorf("过小值应收敛到 4M（否则会拒掉客户端正常压缩后的图），实得 %dM",
			s.PhotoMaxPixels>>20)
	}
}

// TestWriteFitAtCeilingBounded 端到端：顶到像素上限的图走完派生图生成，
// 累计分配必须有上界。这是「单张最坏内存」的直接度量。
func TestWriteFitAtCeilingBounded(t *testing.T) {
	dir := t.TempDir()
	w := newImageWorker(dir)
	// 按默认上限 12M 像素取方图：3464×3464 ≈ 12.0M
	const side = 3464
	src := image.NewGray(image.Rect(0, 0, side, side))

	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	if err := w.writeFit(src, filepath.Join(dir, "t.jpg"), photoThumbEdge, true); err != nil {
		t.Fatal(err)
	}
	if err := w.writeFit(src, filepath.Join(dir, "p.jpg"), photoPreviewEdge, true); err != nil {
		t.Fatal(err)
	}
	runtime.ReadMemStats(&after)
	allocMB := float64(after.TotalAlloc-before.TotalAlloc) / 1024 / 1024
	t.Logf("上限尺寸 %dx%d 生成缩略图+预览图：分配 %.1f MB", side, side, allocMB)
	// 阈值 96MB 的来历（**不是**随手取的）：
	//   单段实现：42.6（thumb 384×3464×32）+ 114.2（preview 1080×3464×32）≈ 157 MB
	//   两段式  ： 7.2（实测）           +  56.2（实测）                  ≈  63 MB
	// 96MB 落在两者之间且离两侧都够远：改回单段必红（157 > 96），
	// 而正常路径有 1.5 倍余量。
	//
	// 早先这里写 64MB，实测 63.3MB —— 只差 0.7MB，等于随时会因抖动变红的哑弹。
	// 阈值贴着实测值不代表"卡得严"，只代表它迟早会以假警报的形式浪费一次排查。
	//
	// 其中 56.2MB 的预览图部分**是 API 下限**而非未优化：
	// 第二段固定需要 目标宽 × 目标高×1.25 × 32 ≈ 44.8 MB，与原图多大无关。
	if allocMB > 96 {
		t.Errorf("单张派生图生成分配 %.1f MB 过高（单段实现约 157MB，"+
			"超过 96MB 说明两段式没生效）", allocMB)
	}
}

// TestInterpolatorAllocatesBySourceSize 记录这个反直觉事实本身。
//
// 这条测试的价值是**文档性**的：它证明"按源图尺寸分配"不是我的猜测。
// 将来若有人想把 scaleInto 简化回单段，这条会告诉他为什么不能。
func TestInterpolatorAllocatesBySourceSize(t *testing.T) {
	const side = 4096
	measure := func(interp draw.Interpolator) float64 {
		src := image.NewGray(image.Rect(0, 0, side, side))
		dst := image.NewRGBA(image.Rect(0, 0, 64, 64)) // 目标极小
		runtime.GC()
		var a, b runtime.MemStats
		runtime.ReadMemStats(&a)
		interp.Scale(dst, dst.Bounds(), src, src.Bounds(), draw.Over, nil)
		runtime.ReadMemStats(&b)
		return float64(b.TotalAlloc-a.TotalAlloc) / 1024 / 1024
	}
	cat := measure(draw.CatmullRom)
	approx := measure(draw.ApproxBiLinear)
	t.Logf("目标恒为 64x64，源图 %dx%d：CatmullRom %.1f MB vs ApproxBiLinear %.2f MB",
		side, side, cat, approx)
	if cat <= approx*4 {
		t.Errorf("预期 CatmullRom 因按源尺寸分配而显著更高，实得 %.1f vs %.2f", cat, approx)
	}
}
