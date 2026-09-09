package main

import (
	"archive/zip"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

const (
	// Keep the compressed upload bounded as well as the expanded staging area.
	// The client uses the same limits; accepting a larger archive here would
	// make the admin path an avoidable memory/disk denial-of-service surface.
	maxLive2DArchiveBytes  = int64(50 * 1024 * 1024)
	maxLive2DEntries       = 1000
	maxLive2DUnpacked      = int64(300 * 1024 * 1024)
	maxLive2DEntryBytes    = int64(64 * 1024 * 1024)
	maxLive2DMocBytes      = int64(20 * 1024 * 1024)
	maxLive2DTextureEdge   = 4096
	maxLive2DTexturePixels = int64(16 * 1024 * 1024)
	maxLive2DTotalPixels   = int64(16 * 1024 * 1024)
)

// handleAdminListLive2DModels returns metadata only. The model archive is never
// exposed through /upload or a public static mount.
func handleAdminListLive2DModels(c *gin.Context) {
	limit, offset, current, size := pageParams(c)
	// Query the count before opening the paged rows. The server deliberately
	// runs SQLite with a single connection in production; issuing QueryRow while
	// rows is still open would wait forever for that same connection.
	var total int
	if err := st.DB.QueryRow(`SELECT COUNT(*) FROM live2d_model`).Scan(&total); err != nil {
		afail(c, 500, 500, "查询模型数量失败")
		return
	}
	rows, err := st.DB.Query(`SELECT id,name,version,status,bytes,sha256,texture_count,
		client_min_version,created_at,updated_at
		FROM live2d_model ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?`, limit, offset)
	if err != nil {
		afail(c, 500, 500, "查询模型目录失败")
		return
	}
	defer rows.Close()
	items := make([]gin.H, 0, size)
	for rows.Next() {
		var id, name, version, sha, minVersion, created, updated string
		var status, textureCount int
		var bytes int64
		if err := rows.Scan(&id, &name, &version, &status, &bytes, &sha, &textureCount,
			&minVersion, &created, &updated); err != nil {
			slog.Error("scan live2d model row failed", "err", err)
			continue
		}
		items = append(items, gin.H{
			"id": id, "name": name, "version": version, "status": live2DStatusName(status),
			"bytes": bytes, "sha256": sha, "texture_count": textureCount,
			"client_min_version": minVersion, "created_at": created, "updated_at": updated,
		})
	}
	if err := rows.Err(); err != nil {
		slog.Error("iterate live2d model rows failed", "err", err)
		afail(c, 500, 500, "读取模型目录失败")
		return
	}
	pageResp(c, items, total, current, size)
}

func handleAdminUploadLive2DModel(c *gin.Context) {
	// The body limit is enforced before multipart parsing; a rejected large body
	// still goes through afail so reverse proxies can reuse the connection.
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxLive2DArchiveBytes+1)
	fileHeader, err := c.FormFile("model")
	if err != nil || fileHeader == nil {
		afail(c, 400, 400, "请选择 ZIP 模型包")
		return
	}
	if fileHeader.Size <= 0 || fileHeader.Size > maxLive2DArchiveBytes {
		afail(c, 413, 413, "模型 ZIP 不能超过 50 MiB")
		return
	}

	modelID := newLive2DID()
	workRoot := filepath.Join(privateMediaDir(), "live2d")
	if err := os.MkdirAll(workRoot, 0o700); err != nil {
		afail(c, 500, 500, "模型目录不可写")
		return
	}
	zipPath := filepath.Join(workRoot, ".incoming-"+modelID+".zip")
	staging := filepath.Join(workRoot, ".staging-"+modelID)
	destination := filepath.Join(workRoot, modelID)
	defer os.Remove(zipPath)
	defer os.RemoveAll(staging)

	source, err := fileHeader.Open()
	if err != nil {
		afail(c, 400, 400, "无法读取上传文件")
		return
	}
	destinationFile, err := os.OpenFile(zipPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		source.Close()
		afail(c, 500, 500, "无法暂存模型文件")
		return
	}
	hash := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(destinationFile, hash), io.LimitReader(source, maxLive2DArchiveBytes+1))
	_ = destinationFile.Close()
	_ = source.Close()
	if copyErr != nil || written > maxLive2DArchiveBytes {
		afail(c, 413, 413, "模型 ZIP 不能超过 50 MiB")
		return
	}

	textureCount, unpackedBytes, err := extractLive2DArchive(zipPath, staging)
	if err != nil {
		afail(c, 400, 400, err.Error())
		return
	}
	if err := os.Rename(staging, destination); err != nil {
		afail(c, 500, 500, "无法完成模型安装")
		return
	}

	name := live2DName(fileHeader.Filename)
	version := cleanLive2DVersion(c.PostForm("version"))
	if version == "" {
		version = "0.1.0"
	}
	minVersion := strings.TrimSpace(c.PostForm("client_min_version"))
	relPath := filepath.ToSlash(filepath.Join("live2d", modelID))
	if _, err := st.DB.Exec(`INSERT INTO live2d_model
		(id,name,version,status,bytes,sha256,texture_count,client_min_version,rel_path)
		VALUES(?,?,?,?,?,?,?,?,?)`, modelID, name, version, 0, written, hex.EncodeToString(hash.Sum(nil)),
		textureCount, minVersion, relPath); err != nil {
		_ = os.RemoveAll(destination)
		afail(c, 500, 500, "模型目录写入失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "upload_live2d_model",
		"model_id="+modelID+" bytes="+strconv.FormatInt(unpackedBytes, 10), c.ClientIP())
	aok(c, gin.H{
		"id": modelID, "name": name, "version": version, "status": "draft",
		"bytes": written, "sha256": hex.EncodeToString(hash.Sum(nil)), "texture_count": textureCount,
		"client_min_version": minVersion,
	})
}

func handleAdminPublishLive2DModel(c *gin.Context) { updateLive2DStatus(c, 1, "publish_live2d_model") }

func handleAdminWithdrawLive2DModel(c *gin.Context) {
	updateLive2DStatus(c, 2, "withdraw_live2d_model")
}

func updateLive2DStatus(c *gin.Context, status int, auditAction string) {
	id := strings.TrimSpace(c.Param("id"))
	if !validLive2DID(id) {
		afail(c, 400, 400, "模型 ID 非法")
		return
	}
	result, err := st.DB.Exec(`UPDATE live2d_model SET status=?,updated_at=datetime('now') WHERE id=?`, status, id)
	if err != nil {
		afail(c, 500, 500, "模型状态更新失败")
		return
	}
	changed, _ := result.RowsAffected()
	if changed == 0 {
		afail(c, 404, 404, "模型不存在")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), auditAction, "model_id="+id, c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func handleAdminDeleteLive2DModel(c *gin.Context) {
	id := strings.TrimSpace(c.Param("id"))
	if !validLive2DID(id) {
		afail(c, 400, 400, "模型 ID 非法")
		return
	}
	var relPath string
	var status int
	if err := st.DB.QueryRow(`SELECT rel_path,status FROM live2d_model WHERE id=?`, id).Scan(&relPath, &status); err != nil {
		afail(c, 404, 404, "模型不存在")
		return
	}
	if status == 1 {
		afail(c, 409, 409, "已发布模型请先撤回")
		return
	}
	full, ok := safeLive2DPath(relPath)
	if !ok {
		afail(c, 404, 404, "模型文件不存在")
		return
	}
	if err := os.RemoveAll(full); err != nil {
		afail(c, 500, 500, "模型文件删除失败")
		return
	}
	if _, err := st.DB.Exec(`DELETE FROM live2d_model WHERE id=?`, id); err != nil {
		afail(c, 500, 500, "模型记录删除失败")
		return
	}
	st.AddAudit(c.GetInt64("aid"), c.GetString("admin_name"), "delete_live2d_model", "model_id="+id, c.ClientIP())
	aok(c, gin.H{"ok": true})
}

func registerAdminLive2DRoutes(sup *gin.RouterGroup) {
	sup.GET("/live2d/models", handleAdminListLive2DModels)
	sup.POST("/live2d/models", handleAdminUploadLive2DModel)
	sup.POST("/live2d/models/:id/publish", handleAdminPublishLive2DModel)
	sup.POST("/live2d/models/:id/withdraw", handleAdminWithdrawLive2DModel)
	sup.DELETE("/live2d/models/:id", handleAdminDeleteLive2DModel)
}

func extractLive2DArchive(zipPath, staging string) (textureCount int, unpackedBytes int64, err error) {
	archive, err := zip.OpenReader(zipPath)
	if err != nil {
		return 0, 0, errors.New("模型文件不是有效 ZIP")
	}
	defer archive.Close()
	if len(archive.File) == 0 || len(archive.File) > maxLive2DEntries {
		return 0, 0, errors.New("模型文件数量不合法")
	}
	if err := os.MkdirAll(staging, 0o700); err != nil {
		return 0, 0, errors.New("无法创建模型隔离区")
	}
	var hasMOC, hasPNG, hasVtubeStudioConfig bool
	var manifestPath string
	seenCaseFolded := make(map[string]struct{}, len(archive.File))
	for _, entry := range archive.File {
		safeName, ok := safeLive2DEntry(entry.Name)
		if !ok {
			return 0, 0, errors.New("ZIP 包含不安全的文件路径")
		}
		caseKey := strings.ToLower(safeName)
		if _, exists := seenCaseFolded[caseKey]; exists {
			return 0, 0, errors.New("模型 ZIP 包含重复或大小写冲突的文件名")
		}
		seenCaseFolded[caseKey] = struct{}{}
		if entry.FileInfo().Mode()&os.ModeSymlink != 0 {
			return 0, 0, errors.New("模型 ZIP 不允许包含符号链接")
		}
		if entry.FileInfo().IsDir() {
			continue
		}
		output := filepath.Join(staging, filepath.FromSlash(safeName))
		if err := os.MkdirAll(filepath.Dir(output), 0o700); err != nil {
			return 0, 0, errors.New("无法创建模型文件目录")
		}
		source, err := entry.Open()
		if err != nil {
			return 0, 0, errors.New("无法读取模型文件")
		}
		destination, err := os.OpenFile(output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err != nil {
			source.Close()
			return 0, 0, errors.New("模型文件写入失败")
		}
		entryLimit := maxLive2DEntryBytes
		if strings.HasSuffix(strings.ToLower(safeName), ".moc3") {
			entryLimit = maxLive2DMocBytes
		}
		copied, copyErr := io.Copy(
			io.MultiWriter(
				destination,
				live2DByteCounter{total: &unpackedBytes},
				&live2DEntryByteCounter{limit: entryLimit},
			),
			io.LimitReader(source, maxLive2DUnpacked+1),
		)
		_ = destination.Close()
		_ = source.Close()
		if copyErr != nil || copied < 0 || unpackedBytes > maxLive2DUnpacked {
			return 0, 0, errors.New("模型解压后不能超过 300 MiB")
		}
		lower := strings.ToLower(safeName)
		switch {
		case isLive2DManifest(safeName):
			if manifestPath != "" {
				return 0, 0, errors.New("模型包包含多个角色，请拆分后导入")
			}
			manifestPath = safeName
		case strings.HasSuffix(lower, ".moc3"):
			if err := validateLive2DMoc(output); err != nil {
				return 0, 0, err
			}
			hasMOC = true
		case strings.HasSuffix(lower, ".png"):
			hasPNG = true
		case strings.HasSuffix(lower, ".vtube.json"):
			hasVtubeStudioConfig = true
		}
	}
	if !hasMOC && hasVtubeStudioConfig {
		return 0, 0, errors.New("这是 VTube Studio 配置包，缺少 Cubism .moc3；请提供同一模型的完整 model3.json、moc3 与 PNG 包")
	}
	if manifestPath == "" || !hasMOC || !hasPNG {
		return 0, 0, errors.New("缺少完整模型文件：需要 model3.json、moc3 与至少一张 PNG")
	}
	var errManifest error
	textureCount, errManifest = validateLive2DManifest(staging, manifestPath)
	if errManifest != nil {
		return 0, 0, errManifest
	}
	return textureCount, unpackedBytes, nil
}

func isLive2DManifest(path string) bool {
	base := strings.ToLower(filepath.Base(filepath.FromSlash(path)))
	return base == "model3.json" || strings.HasSuffix(base, ".model3.json")
}

// live2DByteCounter lets io.MultiWriter enforce the aggregate decompressed cap.
type live2DByteCounter struct{ total *int64 }

func (w live2DByteCounter) Write(p []byte) (int, error) {
	*w.total += int64(len(p))
	if *w.total > maxLive2DUnpacked {
		return 0, errors.New("model archive expands beyond limit")
	}
	return len(p), nil
}

type live2DEntryByteCounter struct {
	limit int64
	bytes int64
}

func (w *live2DEntryByteCounter) Write(p []byte) (int, error) {
	w.bytes += int64(len(p))
	if w.bytes > w.limit {
		return 0, fmt.Errorf("model entry exceeds %d bytes", w.limit)
	}
	return len(p), nil
}

func validateLive2DManifest(staging, manifestPath string) (int, error) {
	var manifest struct {
		FileReferences struct {
			Moc      string   `json:"Moc"`
			Textures []string `json:"Textures"`
		} `json:"FileReferences"`
	}
	path := filepath.Join(staging, filepath.FromSlash(manifestPath))
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 2*1024*1024 {
		return 0, errors.New("model3.json 不可读取或超过 2 MiB")
	}
	contents, err := os.ReadFile(path)
	if err != nil || json.Unmarshal(contents, &manifest) != nil {
		return 0, errors.New("model3.json 格式无效")
	}
	base := filepath.Dir(path)
	if !validLive2DReference(staging, base, manifest.FileReferences.Moc, ".moc3") {
		return 0, errors.New("model3.json 缺少有效的 moc3 引用")
	}
	if len(manifest.FileReferences.Textures) == 0 || len(manifest.FileReferences.Textures) > 128 {
		return 0, errors.New("模型纹理数量不合法")
	}
	var totalTexturePixels int64
	for _, texture := range manifest.FileReferences.Textures {
		texturePath, ok := live2DReferencePath(staging, base, texture, ".png")
		if !ok {
			return 0, errors.New("model3.json 包含缺失或不安全的纹理引用")
		}
		width, height, err := live2DTextureInfo(texturePath)
		if err != nil {
			return 0, err
		}
		totalTexturePixels += width * height
		if totalTexturePixels > maxLive2DTotalPixels {
			return 0, fmt.Errorf("模型贴图合计不能超过 %d 像素", maxLive2DTotalPixels)
		}
	}
	return len(manifest.FileReferences.Textures), nil
}

func validLive2DReference(staging, base, raw, suffix string) bool {
	_, ok := live2DReferencePath(staging, base, raw, suffix)
	return ok
}

func live2DReferencePath(staging, base, raw, suffix string) (string, bool) {
	if raw == "" || strings.ContainsRune(raw, 0) || strings.Contains(raw, "://") || strings.HasPrefix(raw, "/") {
		return "", false
	}
	// Model3 paths are relative to the manifest directory and valid exports
	// may use a safe ../ sibling. Archive entry names themselves still reject
	// traversal; only this resolver may normalize it inside the private root.
	name := strings.ReplaceAll(raw, "\\", "/")
	baseRel, err := filepath.Rel(staging, base)
	if err != nil || baseRel == ".." || strings.HasPrefix(baseRel, ".."+string(os.PathSeparator)) {
		return "", false
	}
	parts := make([]string, 0)
	if baseRel != "." {
		parts = append(parts, strings.Split(filepath.ToSlash(baseRel), "/")...)
	}
	for _, segment := range strings.Split(name, "/") {
		switch segment {
		case "", ".":
			continue
		case "..":
			if len(parts) == 0 {
				return "", false
			}
			parts = parts[:len(parts)-1]
		default:
			parts = append(parts, segment)
		}
	}
	clean := strings.Join(parts, "/")
	if clean == "" || !strings.HasSuffix(strings.ToLower(clean), suffix) {
		return "", false
	}
	path := filepath.Clean(filepath.Join(staging, filepath.FromSlash(clean)))
	rootAbs, err := filepath.Abs(staging)
	if err != nil {
		return "", false
	}
	pathAbs, err := filepath.Abs(path)
	if err != nil || (pathAbs != rootAbs && !strings.HasPrefix(pathAbs, rootAbs+string(os.PathSeparator))) {
		return "", false
	}
	info, err := os.Stat(pathAbs)
	if err != nil || !info.Mode().IsRegular() {
		return "", false
	}
	return pathAbs, true
}

func validateLive2DTexture(path string) error {
	_, _, err := live2DTextureInfo(path)
	return err
}

func validateLive2DMoc(path string) error {
	header := make([]byte, 8)
	file, err := os.Open(path)
	if err != nil {
		return errors.New("无法读取 moc3 文件")
	}
	_, readErr := io.ReadFull(file, header)
	_ = file.Close()
	if readErr != nil || string(header[:4]) != "MOC3" {
		return errors.New("moc3 文件头无效，请提供 Cubism 导出的运行模型")
	}
	return nil
}

func live2DTextureInfo(path string) (int64, int64, error) {
	header := make([]byte, 24)
	file, err := os.Open(path)
	if err != nil {
		return 0, 0, errors.New("无法读取 PNG 纹理")
	}
	_, readErr := io.ReadFull(file, header)
	_ = file.Close()
	if readErr != nil {
		return 0, 0, errors.New("PNG 纹理文件不完整")
	}
	signature := []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}
	if !equalBytes(header[:8], signature) || string(header[12:16]) != "IHDR" {
		return 0, 0, errors.New("纹理不是有效 PNG")
	}
	width := uint32(header[16])<<24 | uint32(header[17])<<16 | uint32(header[18])<<8 | uint32(header[19])
	height := uint32(header[20])<<24 | uint32(header[21])<<16 | uint32(header[22])<<8 | uint32(header[23])
	if width == 0 || height == 0 || width > maxLive2DTextureEdge || height > maxLive2DTextureEdge ||
		uint64(width)*uint64(height) > uint64(maxLive2DTexturePixels) {
		return 0, 0, fmt.Errorf("PNG 纹理不能超过 %dpx 单边或 %d 像素", maxLive2DTextureEdge, maxLive2DTexturePixels)
	}
	return int64(width), int64(height), nil
}

func equalBytes(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for index := range a {
		if a[index] != b[index] {
			return false
		}
	}
	return true
}

func safeLive2DEntry(raw string) (string, bool) {
	name := strings.ReplaceAll(raw, "\\", "/")
	segments := strings.Split(name, "/")
	if name == "" || strings.HasPrefix(name, "/") || strings.ContainsRune(name, 0) {
		return "", false
	}
	clean := make([]string, 0, len(segments))
	for _, segment := range segments {
		if segment == "" {
			continue
		}
		if segment == "." || segment == ".." {
			return "", false
		}
		clean = append(clean, segment)
	}
	if len(clean) == 0 {
		return "", false
	}
	return strings.Join(clean, "/"), true
}

func safeLive2DPath(rel string) (string, bool) {
	// rel_path is stored relative to the private media root (for example
	// live2d/model-123). Keep the check anchored at that root so a catalog row
	// cannot accidentally point outside it and so delete/download use the same
	// representation.
	cleanRel, ok := safeLive2DEntry(rel)
	if !ok || !strings.HasPrefix(cleanRel, "live2d/") {
		return "", false
	}
	root := privateMediaDir()
	clean := filepath.Clean(filepath.Join(root, filepath.FromSlash(cleanRel)))
	rootAbs, _ := filepath.Abs(root)
	cleanAbs, _ := filepath.Abs(clean)
	if cleanAbs == rootAbs || !strings.HasPrefix(cleanAbs, rootAbs+string(os.PathSeparator)) {
		return "", false
	}
	return cleanAbs, true
}

func newLive2DID() string {
	var random [8]byte
	if _, err := rand.Read(random[:]); err != nil {
		return fmt.Sprintf("model-%d", time.Now().UnixNano())
	}
	return "model-" + strconv.FormatInt(time.Now().UnixNano(), 10) + "-" + hex.EncodeToString(random[:])
}

func validLive2DID(id string) bool {
	return strings.HasPrefix(id, "model-") && len(id) <= 80 && !strings.ContainsAny(id, `/\\.`)
}

func live2DName(filename string) string {
	name := strings.TrimSpace(filepath.Base(filename))
	name = strings.TrimSuffix(name, filepath.Ext(name))
	if name == "" {
		return "未命名模型"
	}
	runes := []rune(strings.TrimSpace(name))
	if len(runes) > 80 {
		runes = runes[:80]
	}
	return string(runes)
}

func cleanLive2DVersion(value string) string {
	value = strings.TrimSpace(value)
	if len(value) > 32 || strings.ContainsAny(value, "\\/\r\n") {
		return ""
	}
	return value
}

func live2DStatusName(status int) string {
	switch status {
	case 1:
		return "published"
	case 2:
		return "withdrawn"
	default:
		return "draft"
	}
}
