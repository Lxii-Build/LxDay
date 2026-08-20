package main

import "testing"

// 精确构造各容器最小文件头，覆盖 probeAvatar 的识别与动/静态判定分支。
func TestProbeAvatarDetectsStillFormats(t *testing.T) {
	cases := []struct {
		name string
		head []byte
		want ImageFormat
	}{
		{"png", []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, FormatPNG},
		{"bmp", []byte{'B', 'M', 0, 0, 0, 0}, FormatBMP},
		{
			"webp_lossy",
			append(append([]byte("RIFF"), 0x1A, 0, 0, 0), []byte("WEBPVP8 ")...),
			FormatWebP,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			probe, ok := probeAvatar(tc.head)
			if !ok || probe.Format != tc.want || probe.Animated {
				t.Fatalf("probe=%#v ok=%v", probe, ok)
			}
		})
	}
}

func TestProbeAvatarDetectsAnimatedWebP(t *testing.T) {
	// RIFF + size + WEBP + VP8X，flags 字节含 animation 位(0x02)。
	head := append(append([]byte("RIFF"), 0x2C, 0, 0, 0), []byte("WEBPVP8X")...)
	head = append(head, 0x0A, 0, 0, 0) // VP8X chunk size
	head = append(head, 0x02)          // flags: animation bit
	probe, ok := probeAvatar(head)
	if !ok || probe.Format != FormatWebP || !probe.Animated {
		t.Fatalf("probe=%#v ok=%v", probe, ok)
	}
}

func TestProbeAvatarDetectsAvifStillAndAnimated(t *testing.T) {
	still := ftypHead("avif")
	if probe, ok := probeAvatar(still); !ok || probe.Format != FormatAVIF || probe.Animated {
		t.Fatalf("still avif probe=%#v ok=%v", probe, ok)
	}
	seq := ftypHead("avis")
	if probe, ok := probeAvatar(seq); !ok || probe.Format != FormatAVIF || !probe.Animated {
		t.Fatalf("animated avif probe=%#v ok=%v", probe, ok)
	}
}

func TestProbeAvatarDetectsHeifStillAndSequence(t *testing.T) {
	still := ftypHead("heic")
	if probe, ok := probeAvatar(still); !ok || probe.Format != FormatHEIF || probe.Animated {
		t.Fatalf("still heic probe=%#v ok=%v", probe, ok)
	}
	seq := ftypHead("msf1")
	if probe, ok := probeAvatar(seq); !ok || probe.Format != FormatHEIF || !probe.Animated {
		t.Fatalf("heif sequence probe=%#v ok=%v", probe, ok)
	}
}

func TestProbeAvatarMarksGifAsAnimatedContainer(t *testing.T) {
	probe, ok := probeAvatar([]byte("GIF89a"))
	if !ok || probe.Format != FormatGIF || !probe.Animated {
		t.Fatalf("probe=%#v ok=%v", probe, ok)
	}
}

func TestProbeAvatarRejectsUnknownOrSniffableContent(t *testing.T) {
	// 纯文本与过短输入必须被拒绝，禁止内容嗅探。
	for _, head := range [][]byte{
		[]byte("<html>"),
		{0x89, 'P'},        // 过短
		{0xFF, 0xD8},       // JPEG 头不完整
		{0x00, 0x01, 0x02}, // 随机字节
	} {
		if probe, ok := probeAvatar(head); ok {
			t.Fatalf("expected reject, got probe=%#v", probe)
		}
	}
}

// JPEG 是手机相册最常见格式，旧白名单漏了它导致选 JPG 必失败，此处锁定回归。
func TestProbeAvatarAcceptsJPEG(t *testing.T) {
	for _, head := range [][]byte{
		{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F'}, // JFIF
		{0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x16, 'E', 'x', 'i', 'f'}, // EXIF
		{0xFF, 0xD8, 0xFF, 0xDB},                                 // 无 APPn 段
	} {
		probe, ok := probeAvatar(head)
		if !ok || probe.Format != FormatJPEG || probe.Animated {
			t.Fatalf("jpeg probe=%#v ok=%v", probe, ok)
		}
	}
}

// 纯 Go 解码链的能力边界：JPEG/PNG/GIF/WebP 可解，HEIF/AVIF/BMP 不可解。
func TestFormatDecodableInPureGo(t *testing.T) {
	for _, f := range []ImageFormat{FormatJPEG, FormatPNG, FormatGIF, FormatWebP} {
		if !f.decodableInPureGo() {
			t.Fatalf("format %v should be decodable", f)
		}
	}
	for _, f := range []ImageFormat{FormatHEIF, FormatAVIF, FormatBMP, FormatUnknown} {
		if f.decodableInPureGo() {
			t.Fatalf("format %v should not be decodable", f)
		}
	}
}

// ftypHead 构造 ISOBMFF ftyp box：4字节大小 + "ftyp" + major brand + minor + brand。
func ftypHead(major string) []byte {
	head := []byte{0, 0, 0, 0x18}
	head = append(head, []byte("ftyp")...)
	head = append(head, []byte(major)...)
	head = append(head, 0, 0, 0, 0)
	head = append(head, []byte(major)...)
	return head
}
