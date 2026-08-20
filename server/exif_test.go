package main

import (
	"bytes"
	"encoding/binary"
	"testing"
	"time"
)

// buildExifJPEG 构造一张「只有 APP1/Exif 段 + 最小 JPEG 骨架」的字节流。
// 手写而非用真实照片：真实照片没法进仓库（隐私+体积），且手写能精确控制每个字段。
//
// 结构：SOI(FFD8) + APP1(FFE1 len "Exif\0\0" TIFF) + SOS(FFDA ..) + EOI(FFD9)
func buildExifJPEG(t *testing.T, bo binary.ByteOrder, dateTimeOriginal, dateTime string) []byte {
	t.Helper()
	tiff := buildTIFF(t, bo, dateTimeOriginal, dateTime)

	var b bytes.Buffer
	b.Write([]byte{0xFF, 0xD8}) // SOI
	payload := append([]byte("Exif\x00\x00"), tiff...)
	b.Write([]byte{0xFF, 0xE1})
	seg := make([]byte, 2)
	binary.BigEndian.PutUint16(seg, uint16(len(payload)+2)) // 段长含自身 2 字节
	b.Write(seg)
	b.Write(payload)
	b.Write([]byte{0xFF, 0xDA, 0x00, 0x02}) // SOS（空扫描，解析器到此即止）
	b.Write([]byte{0xFF, 0xD9})             // EOI
	return b.Bytes()
}

// buildTIFF 拼一个含 IFD0(DateTime + ExifIFD 指针) 与 ExifIFD(DateTimeOriginal) 的 TIFF 块。
// 空字符串表示该标签不写入，用来验证兜底与缺失路径。
func buildTIFF(t *testing.T, bo binary.ByteOrder, dateTimeOriginal, dateTime string) []byte {
	t.Helper()
	put16 := func(b *bytes.Buffer, v uint16) {
		tmp := make([]byte, 2)
		bo.PutUint16(tmp, v)
		b.Write(tmp)
	}
	put32 := func(b *bytes.Buffer, v uint32) {
		tmp := make([]byte, 4)
		bo.PutUint32(tmp, v)
		b.Write(tmp)
	}

	// 头 8 字节：字节序 + 魔数 + IFD0 偏移(=8)
	var head bytes.Buffer
	if bo == binary.LittleEndian {
		head.Write([]byte{'I', 'I'})
	} else {
		head.Write([]byte{'M', 'M'})
	}
	put16(&head, 0x002A)
	put32(&head, 8)

	// 先算布局：IFD0 条目数 → IFD0 → ExifIFD → 字符串数据区
	ifd0Entries := 1 // ExifIFD 指针
	if dateTime != "" {
		ifd0Entries++
	}
	exifEntries := 0
	if dateTimeOriginal != "" {
		exifEntries = 1
	}
	ifd0Size := 2 + ifd0Entries*12 + 4
	exifIFDOffset := 8 + ifd0Size
	exifIFDSize := 2 + exifEntries*12 + 4
	dataOffset := exifIFDOffset + exifIFDSize

	// 字符串区（各自以 NUL 结尾，长度含 NUL）
	var data bytes.Buffer
	dtOffset, dtoOffset := 0, 0
	if dateTime != "" {
		dtOffset = dataOffset + data.Len()
		data.WriteString(dateTime)
		data.WriteByte(0)
	}
	if dateTimeOriginal != "" {
		dtoOffset = dataOffset + data.Len()
		data.WriteString(dateTimeOriginal)
		data.WriteByte(0)
	}

	var ifd0 bytes.Buffer
	put16(&ifd0, uint16(ifd0Entries))
	if dateTime != "" {
		put16(&ifd0, tagDateTime)
		put16(&ifd0, 2) // ASCII
		put32(&ifd0, uint32(len(dateTime)+1))
		put32(&ifd0, uint32(dtOffset))
	}
	put16(&ifd0, tagExifIFDPointer)
	put16(&ifd0, 4) // LONG
	put32(&ifd0, 1)
	put32(&ifd0, uint32(exifIFDOffset))
	put32(&ifd0, 0) // 下一个 IFD：无

	var exifIFD bytes.Buffer
	put16(&exifIFD, uint16(exifEntries))
	if dateTimeOriginal != "" {
		put16(&exifIFD, tagDateTimeOriginal)
		put16(&exifIFD, 2)
		put32(&exifIFD, uint32(len(dateTimeOriginal)+1))
		put32(&exifIFD, uint32(dtoOffset))
	}
	put32(&exifIFD, 0)

	var out bytes.Buffer
	out.Write(head.Bytes())
	out.Write(ifd0.Bytes())
	out.Write(exifIFD.Bytes())
	out.Write(data.Bytes())
	return out.Bytes()
}

func TestExif解析拍摄时间(t *testing.T) {
	cases := []struct {
		name     string
		bo       binary.ByteOrder
		original string
		modified string
		want     time.Time
		wantNil  bool
	}{
		{
			name:     "小端_DateTimeOriginal优先",
			bo:       binary.LittleEndian,
			original: "2024:07:15 18:30:45",
			modified: "2025:01:01 00:00:00",
			want:     time.Date(2024, 7, 15, 18, 30, 45, 0, time.Local),
		},
		{
			name:     "大端_MM字节序同样可解",
			bo:       binary.BigEndian,
			original: "2019:12:31 23:59:59",
			want:     time.Date(2019, 12, 31, 23, 59, 59, 0, time.Local),
		},
		{
			name:     "缺DateTimeOriginal时回退IFD0的DateTime",
			bo:       binary.LittleEndian,
			modified: "2020:02:29 08:00:00", // 闰日
			want:     time.Date(2020, 2, 29, 8, 0, 0, 0, time.Local),
		},
		{
			name:    "两个时间标签都没有则留空",
			bo:      binary.LittleEndian,
			wantNil: true,
		},
		{
			name:     "相机未设时间写全零_不得当拍摄时间",
			bo:       binary.LittleEndian,
			original: "0000:00:00 00:00:00",
			wantNil:  true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			raw := buildExifJPEG(t, tc.bo, tc.original, tc.modified)
			got := exifDateTimeOriginal(raw)
			if tc.wantNil {
				if got != nil {
					t.Fatalf("期望留空，得到 %v", got)
				}
				return
			}
			if got == nil {
				t.Fatal("期望解析出时间，得到 nil")
			}
			if !got.Equal(tc.want) {
				t.Fatalf("got %v want %v", got, tc.want)
			}
		})
	}
}

// 非 JPEG / 无 EXIF / 垃圾数据都必须安全返回 nil，绝不能 panic——
// 上传任意文件都会走到这里，一次越界读取就是一个可被远程触发的崩溃。
func TestExif对非法输入安全返回空(t *testing.T) {
	cases := map[string][]byte{
		"空":            {},
		"仅SOI":         {0xFF, 0xD8},
		"PNG魔数":        {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3},
		"纯文本":          []byte("hello world, not an image"),
		"APP1头但截断":     {0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x20, 'E', 'x', 'i', 'f'},
		"段长度非法":        {0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x00, 0x00},
		"Exif头但TIFF过短": {0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x0A, 'E', 'x', 'i', 'f', 0x00, 0x00},
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if got := exifDateTimeOriginal(raw); got != nil {
				t.Fatalf("期望 nil，得到 %v", got)
			}
		})
	}
}

// IFD 条目里的偏移量指向数据区之外时必须拒绝，而不是越界读别的内存。
// 直接喂 TIFF 块（绕开 JPEG 段长度校验），确保拦下来的是 exifASCII 的边界检查本身。
func TestExif偏移越界不越界读(t *testing.T) {
	tiff := buildTIFF(t, binary.LittleEndian, "2024:07:15 18:30:45", "")
	if got := parseExifTIFF(tiff); got == nil {
		t.Fatal("完整 TIFF 应能解析，先确认构造正确")
	}
	// 截掉字符串数据区，使条目里的偏移量指向块外。
	for _, cut := range []int{5, 12, 20} {
		if cut >= len(tiff) {
			continue
		}
		if got := parseExifTIFF(tiff[:len(tiff)-cut]); got != nil {
			t.Fatalf("截断 %d 字节后应返回 nil，得到 %v", cut, got)
		}
	}
	// TIFF 头本身非法（字节序标记与魔数错误）。
	if got := parseExifTIFF([]byte{'X', 'X', 0, 0, 0, 0, 0, 8}); got != nil {
		t.Fatalf("非法字节序应返回 nil，得到 %v", got)
	}
	if got := parseExifTIFF([]byte{'I', 'I', 0xFF, 0xFF, 8, 0, 0, 0}); got != nil {
		t.Fatalf("魔数错误应返回 nil，得到 %v", got)
	}
}

func TestExif时间字符串解析(t *testing.T) {
	cases := []struct {
		in   string
		ok   bool
		want time.Time
	}{
		{"2024:07:15 18:30:45", true, time.Date(2024, 7, 15, 18, 30, 45, 0, time.Local)},
		{"2024:07:15 18:30:45\x00", true, time.Date(2024, 7, 15, 18, 30, 45, 0, time.Local)},
		{"2024-07-15 18:30:45", false, time.Time{}}, // 连字符不是 EXIF 格式
		{"2024:07:15", false, time.Time{}},          // 长度不足
		{"", false, time.Time{}},
		{"0000:00:00 00:00:00", false, time.Time{}},
		{"abcd:ef:gh ij:kl:mn", false, time.Time{}},
	}
	for _, tc := range cases {
		got, gotOK := parseExifTime(tc.in)
		if gotOK != tc.ok {
			t.Fatalf("parseExifTime(%q) ok=%v want %v", tc.in, gotOK, tc.ok)
		}
		if tc.ok && !got.Equal(tc.want) {
			t.Fatalf("parseExifTime(%q)=%v want %v", tc.in, got, tc.want)
		}
	}
}
