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

	"github.com/gin-gonic/gin"
)

// handleUploadAvatar 接收 multipart 头像上传，经受限处理链裁剪并原子替换，返回权威资料。
func handleUploadAvatar(c *gin.Context) {
	uid := currentUID(c)
	pair, okP := mustPair(c)
	if !okP {
		return
	}

	file, err := c.FormFile("file")
	if err != nil {
		fail(c, http.StatusBadRequest, 1002, "缺少文件字段 file")
		return
	}
	limits := defaultAvatarLimits()
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

	probe, ok := probeUploadedFile(tmp)
	if !ok {
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
	src, err := file.Open()
	if err != nil {
		return "", err
	}
	defer src.Close()
	if err := os.MkdirAll(filepath.Join(uploadDir, "tmp"), 0o755); err != nil {
		return "", err
	}
	tmp := filepath.Join(uploadDir, "tmp", randomCode(24))
	dst, err := os.Create(tmp)
	if err != nil {
		return "", err
	}
	defer dst.Close()
	if _, err := io.Copy(dst, src); err != nil {
		os.Remove(tmp)
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

func removeOldAvatar(url string) {
	if url == "" {
		return
	}
	// 仅清理本服务本地目录下的旧文件。
	rel := filepath.Base(url)
	_ = filepath.Walk(filepath.Join(uploadDir, "avatar"), func(p string, info os.FileInfo, err error) error {
		if err == nil && info != nil && !info.IsDir() && filepath.Base(p) == rel {
			os.Remove(p)
		}
		return nil
	})
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
