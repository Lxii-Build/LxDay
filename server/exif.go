package main

import (
	"encoding/binary"
	"time"
)

// ================= 最小 EXIF 拍摄时间解析 =================
//
// 为什么自己写：Go 标准库不含 EXIF 解析，而相册只需要一个字段（拍摄时间）。
// 为此引入第三方库会把「纯 Go、无外部依赖」的架构底线破掉一个口子——
// 与去 libvips、去 MySQL/Redis 的取舍一致，这里只实现真正用到的那一小段。
//
// 覆盖范围：JPEG 的 APP1/Exif 段 → TIFF 头 → IFD0 → ExifIFD → DateTimeOriginal。
// 不支持 PNG/WebP 的 XMP、也不解析 GPS/朝向：解析失败一律返回零值让调用方留空，
// 绝不因为元数据读不出来就让整次上传失败（照片本身远比拍摄时间重要）。

// exifMaxScan 只在文件头部扫描 APP1。EXIF 段按规范紧跟 SOI，正常照片不会超过数百 KB；
// 设上限避免为了找一个可选字段去遍历整张几十 MB 的图。
const exifMaxScan = 512 * 1024

// EXIF 标签号。
const (
	tagDateTime         = 0x0132 // IFD0 的修改时间，作为兜底
	tagExifIFDPointer   = 0x8769
	tagDateTimeOriginal = 0x9003 // 快门按下的时间，优先用它
	tagDateTimeDigitize = 0x9004
)

// exifDateTimeOriginal 从完整 JPEG 字节里解析拍摄时间。
// 返回 nil 表示「没有可用的拍摄时间」（非 JPEG、无 EXIF、字段缺失或格式非法）。
func exifDateTimeOriginal(data []byte) *time.Time {
	seg, ok := findJPEGExifSegment(data)
	if !ok {
		return nil
	}
	return parseExifTIFF(seg)
}

// findJPEGExifSegment 遍历 JPEG 段结构，取出 APP1 中 "Exif\0\0" 之后的 TIFF 数据块。
func findJPEGExifSegment(data []byte) ([]byte, bool) {
	if len(data) < 4 || data[0] != 0xFF || data[1] != 0xD8 {
		return nil, false // 非 JPEG（PNG/WebP/GIF 无 APP1）
	}
	limit := len(data)
	if limit > exifMaxScan {
		limit = exifMaxScan
	}
	i := 2
	for i+4 <= limit {
		if data[i] != 0xFF {
			// 段边界错乱（截断或非标准文件）：不猜测，直接放弃。
			return nil, false
		}
		marker := data[i+1]
		// SOS(DA) 之后是压缩像素数据，不会再有 APP1；EOI(D9) 是文件尾。
		if marker == 0xDA || marker == 0xD9 {
			return nil, false
		}
		// 无长度字段的独立标记（填充 FF、RSTn、SOI/TEM）。
		if marker == 0xFF || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8) {
			i += 2
			continue
		}
		if i+4 > limit {
			return nil, false
		}
		// 段长度含自身 2 字节，故 <2 即非法。
		segLen := int(binary.BigEndian.Uint16(data[i+2 : i+4]))
		if segLen < 2 {
			return nil, false
		}
		body := i + 4
		end := i + 2 + segLen
		if end > limit {
			return nil, false
		}
		if marker == 0xE1 && end-body >= 6 && string(data[body:body+4]) == "Exif" &&
			data[body+4] == 0x00 {
			return data[body+6 : end], true
		}
		i = end
	}
	return nil, false
}

// exifDateTimes 汇集一次 IFD 扫描中拿到的候选时间与子 IFD 指针。
type exifDateTimes struct {
	original  string
	digitized string
	modified  string
	exifIFD   uint32
}

// parseExifTIFF 解析 TIFF 头与 IFD，按 DateTimeOriginal → DateTimeDigitized → DateTime 优先级取值。
func parseExifTIFF(tiff []byte) *time.Time {
	if len(tiff) < 8 {
		return nil
	}
	var bo binary.ByteOrder
	switch {
	case tiff[0] == 'I' && tiff[1] == 'I':
		bo = binary.LittleEndian
	case tiff[0] == 'M' && tiff[1] == 'M':
		bo = binary.BigEndian
	default:
		return nil
	}
	if bo.Uint16(tiff[2:4]) != 0x002A { // TIFF 魔数
		return nil
	}
	ifd0 := bo.Uint32(tiff[4:8])

	found := exifDateTimes{}
	scanExifIFD(tiff, bo, ifd0, &found)
	// 拍摄时间真正的存放位置是 ExifIFD（IFD0 只有 DateTime=文件修改时间）。
	if found.exifIFD != 0 {
		scanExifIFD(tiff, bo, found.exifIFD, &found)
	}
	for _, s := range []string{found.original, found.digitized, found.modified} {
		if t, ok := parseExifTime(s); ok {
			return &t
		}
	}
	return nil
}

// scanExifIFD 读取一个 IFD 的全部条目，把感兴趣的标签写入 out。
func scanExifIFD(tiff []byte, bo binary.ByteOrder, offset uint32, out *exifDateTimes) {
	if offset == 0 || int(offset)+2 > len(tiff) {
		return
	}
	p := int(offset)
	count := int(bo.Uint16(tiff[p : p+2]))
	p += 2
	// 每条目固定 12 字节：tag(2) type(2) count(4) value/offset(4)
	for n := 0; n < count; n++ {
		if p+12 > len(tiff) {
			return
		}
		entry := tiff[p : p+12]
		p += 12
		tag := bo.Uint16(entry[0:2])
		typ := bo.Uint16(entry[2:4])
		cnt := bo.Uint32(entry[4:8])
		switch tag {
		case tagExifIFDPointer:
			if typ == 4 && cnt == 1 { // LONG
				out.exifIFD = bo.Uint32(entry[8:12])
			}
		case tagDateTimeOriginal:
			out.original = exifASCII(tiff, bo, entry, typ, cnt)
		case tagDateTimeDigitize:
			out.digitized = exifASCII(tiff, bo, entry, typ, cnt)
		case tagDateTime:
			out.modified = exifASCII(tiff, bo, entry, typ, cnt)
		}
	}
}

// exifASCII 取 ASCII 型条目的字符串值。
// 长度 >4 时条目里存的是偏移量而非内容本身（EXIF 的 value/offset 复用规则），
// 时间字符串固定 20 字节，故实际总是走偏移分支。
func exifASCII(tiff []byte, bo binary.ByteOrder, entry []byte, typ uint16, cnt uint32) string {
	if typ != 2 || cnt == 0 || cnt > 64 {
		return ""
	}
	n := int(cnt)
	var raw []byte
	if n <= 4 {
		raw = entry[8 : 8+n]
	} else {
		off := int(bo.Uint32(entry[8:12]))
		if off <= 0 || off+n > len(tiff) {
			return ""
		}
		raw = tiff[off : off+n]
	}
	// 去掉结尾的 NUL 填充。
	for len(raw) > 0 && (raw[len(raw)-1] == 0x00 || raw[len(raw)-1] == ' ') {
		raw = raw[:len(raw)-1]
	}
	return string(raw)
}

// parseExifTime 解析 EXIF 时间字符串 "YYYY:MM:DD HH:MM:SS"。
//
// EXIF 不带时区，按本地时区解释——照片的「这一天」在用户心里就是拍摄地的当天，
// 强行当 UTC 会让夜里拍的照片整体偏移一天，「这一天」功能直接错位。
func parseExifTime(s string) (time.Time, bool) {
	if len(s) < 19 {
		return time.Time{}, false
	}
	t, err := time.ParseInLocation("2006:01:02 15:04:05", s[:19], time.Local)
	if err != nil {
		return time.Time{}, false
	}
	// 相机未设置时间时会写全零，解析出来是公元 0 年，这种值不该当拍摄时间用。
	if t.Year() < 1900 {
		return time.Time{}, false
	}
	return t, true
}
