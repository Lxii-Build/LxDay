package main

import "encoding/binary"

// ImageFormat 是头像允许的容器格式。禁止内容嗅探：仅按文件头精确匹配。
type ImageFormat int

const (
	FormatUnknown ImageFormat = iota
	FormatPNG
	FormatWebP
	FormatGIF
	FormatBMP
	FormatHEIF
	FormatAVIF
)

// AvatarProbe 是对上传文件头的识别结果。
type AvatarProbe struct {
	Format   ImageFormat
	Animated bool // 容器是否可能包含动画（GIF/动态 WebP/AVIF 序列/HEIF 序列）
}

// probeAvatar 只依据魔数与容器结构判定格式与动/静态，不解码像素、不做扩展名嗅探。
func probeAvatar(head []byte) (AvatarProbe, bool) {
	if len(head) < 2 {
		return AvatarProbe{}, false
	}
	switch {
	case matchPNG(head):
		return AvatarProbe{Format: FormatPNG}, true
	case matchBMP(head):
		return AvatarProbe{Format: FormatBMP}, true
	case matchGIF(head):
		// GIF 作为动画容器统一按动态处理，首帧用于缩略图。
		return AvatarProbe{Format: FormatGIF, Animated: true}, true
	case matchRIFFWebP(head):
		return probeWebP(head)
	case matchISOBMFF(head):
		return probeISOBMFF(head)
	}
	return AvatarProbe{}, false
}

func matchPNG(b []byte) bool {
	return len(b) >= 8 && b[0] == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' &&
		b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A
}

func matchBMP(b []byte) bool { return len(b) >= 2 && b[0] == 'B' && b[1] == 'M' }

func matchGIF(b []byte) bool {
	return len(b) >= 6 && string(b[0:3]) == "GIF" &&
		(string(b[3:6]) == "87a" || string(b[3:6]) == "89a")
}

func matchRIFFWebP(b []byte) bool {
	return len(b) >= 12 && string(b[0:4]) == "RIFF" && string(b[8:12]) == "WEBP"
}

func probeWebP(b []byte) (AvatarProbe, bool) {
	if len(b) < 16 {
		return AvatarProbe{}, false
	}
	switch string(b[12:16]) {
	case "VP8 ", "VP8L":
		return AvatarProbe{Format: FormatWebP}, true
	case "VP8X":
		// VP8X: 4字节 chunk 头 + 4字节 size，随后 flags 字节 bit1 表示动画。
		if len(b) < 21 {
			return AvatarProbe{}, false
		}
		animated := b[20]&0x02 != 0
		return AvatarProbe{Format: FormatWebP, Animated: animated}, true
	}
	return AvatarProbe{}, false
}

func matchISOBMFF(b []byte) bool {
	return len(b) >= 12 && string(b[4:8]) == "ftyp"
}

// probeISOBMFF 解析 ftyp box 的 major brand 与 compatible brands，区分 AVIF/HEIF 及动/静态。
func probeISOBMFF(b []byte) (AvatarProbe, bool) {
	if len(b) < 16 {
		return AvatarProbe{}, false
	}
	size := int(binary.BigEndian.Uint32(b[0:4]))
	if size < 16 || size > len(b) {
		size = len(b)
	}
	brands := []string{string(b[8:12])}
	for off := 16; off+4 <= size; off += 4 {
		brands = append(brands, string(b[off:off+4]))
	}
	var format ImageFormat
	animated := false
	for _, brand := range brands {
		switch brand {
		case "avif":
			format = FormatAVIF
		case "avis":
			format = FormatAVIF
			animated = true
		case "heic", "heix", "mif1":
			if format == FormatUnknown {
				format = FormatHEIF
			}
		case "hevc", "msf1", "hevx":
			format = FormatHEIF
			animated = true
		}
	}
	if format == FormatUnknown {
		return AvatarProbe{}, false
	}
	return AvatarProbe{Format: format, Animated: animated}, true
}
