package main

import (
	"archive/zip"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
)

// handleClientListLive2DModels exposes only published metadata to an
// authenticated app. Private imports never enter this list and no filesystem
// path is returned to the client.
func handleClientListLive2DModels(c *gin.Context) {
	rows, err := st.DB.Query(`SELECT id,name,version,bytes,sha256,texture_count,
		client_min_version,updated_at FROM live2d_model
		WHERE status=1 ORDER BY updated_at DESC, id DESC LIMIT 100`)
	if err != nil {
		fail(c, http.StatusInternalServerError, 500, "读取角色资源失败")
		return
	}
	defer rows.Close()
	items := make([]gin.H, 0, 16)
	for rows.Next() {
		var id, name, version, sha, minVersion, updated string
		var bytes int64
		var textureCount int
		if err := rows.Scan(&id, &name, &version, &bytes, &sha, &textureCount, &minVersion, &updated); err != nil {
			slog.Error("scan published live2d row failed", "err", err)
			continue
		}
		items = append(items, gin.H{
			"id": id, "name": name, "version": version, "bytes": bytes,
			"sha256": sha, "texture_count": textureCount,
			"client_min_version": minVersion, "updated_at": updated,
			"download_url": "/api/v1/live2d/models/" + id + "/download",
		})
	}
	if err := rows.Err(); err != nil {
		slog.Error("iterate published live2d rows failed", "err", err)
		fail(c, http.StatusInternalServerError, 500, "读取角色资源失败")
		return
	}
	ok(c, items)
}

// handleClientDownloadLive2DModel re-packages the validated private directory
// as a ZIP. The original upload is not publicly mounted, and a client can only
// download a published model with a valid user JWT.
func handleClientDownloadLive2DModel(c *gin.Context) {
	id := strings.TrimSpace(c.Param("id"))
	if !validLive2DID(id) {
		fail(c, http.StatusBadRequest, 400, "模型 ID 非法")
		return
	}
	var relPath string
	var status int
	if err := st.DB.QueryRow(`SELECT rel_path,status FROM live2d_model WHERE id=?`, id).Scan(&relPath, &status); err != nil {
		fail(c, http.StatusNotFound, 404, "模型不存在")
		return
	}
	if status != 1 {
		fail(c, http.StatusNotFound, 404, "模型暂不可下载")
		return
	}
	root, ok := safeLive2DPath(relPath)
	if !ok {
		fail(c, http.StatusNotFound, 404, "模型文件不存在")
		return
	}
	info, err := os.Stat(root)
	if err != nil || !info.IsDir() {
		fail(c, http.StatusNotFound, 404, "模型文件不存在")
		return
	}

	c.Header("Content-Type", "application/zip")
	c.Header("Content-Disposition", `attachment; filename="`+id+`.zip"`)
	c.Header("Cache-Control", "no-store")
	c.Header("Referrer-Policy", "no-referrer")
	c.Header("X-Content-Type-Options", "nosniff")
	writer := zip.NewWriter(c.Writer)
	walkErr := filepath.WalkDir(root, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if path == root {
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return os.ErrInvalid
		}
		if entry.IsDir() {
			return nil
		}
		// This is internal metadata, not part of the user model bundle.
		if entry.Name() == live2DMetadataFile {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		safeName, ok := safeLive2DEntry(filepath.ToSlash(rel))
		if !ok {
			return os.ErrInvalid
		}
		fileInfo, err := entry.Info()
		if err != nil {
			return err
		}
		header, err := zip.FileInfoHeader(fileInfo)
		if err != nil {
			return err
		}
		header.Name = safeName
		header.Method = zip.Deflate
		destination, err := writer.CreateHeader(header)
		if err != nil {
			return err
		}
		source, err := os.Open(path)
		if err != nil {
			return err
		}
		_, copyErr := io.Copy(destination, source)
		_ = source.Close()
		return copyErr
	})
	closeErr := writer.Close()
	if walkErr != nil || closeErr != nil {
		slog.Error("stream live2d archive failed", "model_id", id, "walk_error", walkErr, "close_error", closeErr)
	}
}

// Keep metadata naming in one place so it cannot accidentally become part of
// the downloadable model bundle.
const live2DMetadataFile = "model.properties"
