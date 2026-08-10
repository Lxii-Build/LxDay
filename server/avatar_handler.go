package main

import (
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

// bytesHeaderSlack 为 multipart 边界与表单字段预留的额外字节，避免刚好等于文件上限的合法请求被误拒。
const bytesHeaderSlack = 1 << 20 // 1MB

// handleUploadAvatar 接收 multipart 头像上传，经受限处理链裁剪并原子替换，返回权威资料。
func handleUploadAvatar(c *gin.Context) {
	uid := currentUID(c)
	pair, okP := mustPair(c)
	if !okP {
		return
	}

	limits := defaultAvatarLimits()
	// 在解析 multipart 之前限制请求体，避免超大 body 在 file.Size 检查前耗尽资源。
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, limits.MaxBytes+bytesHeaderSlack)

	file, err := c.FormFile("file")
	if err != nil {
		fail(c, http.StatusBadRequest, 1002, "文件缺失或超过 15MB")
		return
	}
	if file.Size > limits.MaxBytes {
		fail(c, http.StatusBadRequest, 1002, "头像文件超过 15MB")
		return
	}

	crop, err := parseCropParams(c)
	if err != nil {
		fail(c, http.StatusBadRequest, 1002, "裁剪参数无效")
		return
	}

	// 落地临时文件后按文件头探测格式，禁止依赖扩展名或内容嗅探。
	tmp, err := saveTempUpload(c, file)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "临时存储失败")
		return
	}
	defer os.Remove(tmp)

	probe, valid := probeUploadedFile(tmp)
	if !valid {
		fail(c, http.StatusBadRequest, 1002, "不支持的图片格式")
		return
	}

	worker := newVipsWorker(filepath.Join(uploadDir, "avatar", strconv.FormatInt(pair.ID, 10)))
	result, err := processAvatar(AvatarInput{
		SizeBytes: file.Size,
		Probe:     probe,
		Crop:      crop,
		Source:    tmp,
	}, limits, worker)
	if err != nil {
		mapAvatarError(c, err)
		return
	}

	mainURL := publicAvatarURL(pair.ID, result.MainPath)
	thumbURL := publicAvatarURL(pair.ID, result.ThumbPath)

	oldMain, oldThumb := currentAvatarPaths(uid)
	if err := st.UpdateAvatar(uid, mainURL, thumbURL); err != nil {
		// 处理失败保留旧头像：删除本次新产物，不改库。
		_ = os.Remove(result.MainPath)
		_ = os.Remove(result.ThumbPath)
		fail(c, http.StatusInternalServerError, 1010, "保存头像失败")
		return
	}
	// 成功后清理旧文件（best-effort）。
	removeOldAvatar(oldMain)
	removeOldAvatar(oldThumb)

	profile, err := pairProfile(uid)
	if err != nil {
		fail(c, http.StatusInternalServerError, 1010, "读取资料失败")
		return
	}
	notifyProfileUpdated(uid, profile)
	ok(c, profile)
}

func parseCropParams(c *gin.Context) (CropParams, error) {
	cx, err1 := strconv.ParseFloat(c.DefaultPostForm("center_x", "0.5"), 64)
	cy, err2 := strconv.ParseFloat(c.DefaultPostForm("center_y", "0.5"), 64)
	scale, err3 := strconv.ParseFloat(c.DefaultPostForm("scale", "1.0"), 64)
	if err1 != nil || err2 != nil || err3 != nil {
		return CropParams{}, fmt.Errorf("invalid crop")
	}
	return CropParams{CenterX: cx, CenterY: cy, Scale: scale}, nil
}

func saveTempUpload(c *gin.Context, file *multipart.FileHeader) (string, error) {
	if err := os.MkdirAll(filepath.Join(uploadDir, "tmp"), 0o755); err != nil {
		return "", err
	}
	tmp := filepath.Join(uploadDir, "tmp", randomCode(24))
	if err := c.SaveUploadedFile(file, tmp); err != nil {
		return "", err
	}
	return tmp, nil
}

// probeUploadedFile 读取前 32 字节做魔数探测。
func probeUploadedFile(path string) (AvatarProbe, bool) {
	f, err := os.Open(path)
	if err != nil {
		return AvatarProbe{}, false
	}
	defer f.Close()
	head := make([]byte, 32)
	n, _ := io.ReadFull(f, head)
	return probeAvatar(head[:n])
}

func publicAvatarURL(pairID int64, path string) string {
	return fmt.Sprintf("/uploads/avatar/%d/%s", pairID, filepath.Base(path))
}

func currentAvatarPaths(uid int64) (string, string) {
	u, err := st.GetUserByID(uid)
	if err != nil || u == nil {
		return "", ""
	}
	var main, thumb string
	if u.AvatarURL != nil {
		main = *u.AvatarURL
	}
	if u.AvatarThumbnailURL != nil {
		thumb = *u.AvatarThumbnailURL
	}
	return main, thumb
}

// removeOldAvatar 将 /uploads/... 形式的旧 URL 直接映射回本地路径删除，避免全树遍历与跨对误删。
func removeOldAvatar(url string) {
	const prefix = "/uploads/"
	if !strings.HasPrefix(url, prefix) {
		return
	}
	rel := filepath.Clean(strings.TrimPrefix(url, prefix))
	// 防御路径穿越：清理后不得逃逸 uploadDir。
	if rel == "." || strings.HasPrefix(rel, "..") {
		return
	}
	_ = os.Remove(filepath.Join(uploadDir, rel))
}

func mapAvatarError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, ErrAvatarTooLarge):
		fail(c, http.StatusBadRequest, 1002, "头像文件超过 15MB")
	case errors.Is(err, ErrAvatarTooLong):
		fail(c, http.StatusBadRequest, 1002, "动态头像不能超过 10 秒")
	case errors.Is(err, ErrAvatarTooManyFrames):
		fail(c, http.StatusBadRequest, 1002, "动态头像帧数超过上限")
	case errors.Is(err, ErrAvatarBadCrop):
		fail(c, http.StatusBadRequest, 1002, "裁剪参数无效")
	case errors.Is(err, ErrAnimatedNotSupported):
		fail(c, http.StatusBadRequest, 1002, "暂不支持动态 HEIF/AVIF，请改用 GIF 或动态 WebP")
	default:
		fail(c, http.StatusInternalServerError, 1010, "头像处理失败，已保留原头像")
	}
}
