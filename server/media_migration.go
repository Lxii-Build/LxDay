package main

import (
	"crypto/sha256"
	"database/sql"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
)

// migratePhotoFilesToPrivateRoot 将历史相册产物从公开 upload/ 前缀迁到
// 不被任何静态路由挂载的 media/ 根目录。
//
// 文件移动与数据库更新故意分成两个阶段：文件先移动，数据库后更新。
// 进程如果在两者之间退出，下次启动会识别“源不存在、目标存在”并补做数据库更新；
// 反过来先改库则可能留下仍可被 /upload 访问的私密文件。
func migratePhotoFilesToPrivateRoot(db *sql.DB) error {
	rows, err := db.Query(`
		SELECT id,url,thumb_url,preview_path FROM photo
		WHERE url LIKE 'upload/%' OR thumb_url LIKE 'upload/%' OR preview_path LIKE 'upload/%'`)
	if err != nil {
		return fmt.Errorf("list public photo paths: %w", err)
	}

	type photoPathRow struct {
		id                    int64
		url, thumb, preview   sql.NullString
	}
	var photos []photoPathRow
	for rows.Next() {
		var p photoPathRow
		if err := rows.Scan(&p.id, &p.url, &p.thumb, &p.preview); err != nil {
			rows.Close()
			return fmt.Errorf("scan public photo path: %w", err)
		}
		photos = append(photos, p)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return fmt.Errorf("iterate public photo paths: %w", err)
	}
	if err := rows.Close(); err != nil {
		return fmt.Errorf("close public photo paths: %w", err)
	}

	for _, p := range photos {
		newURL, changed := privatePhotoRel(p.url.String)
		newThumb, thumbChanged := privatePhotoRel(p.thumb.String)
		newPreview, previewChanged := privatePhotoRel(p.preview.String)
		if !changed && !thumbChanged && !previewChanged {
			continue
		}

		for _, pair := range [][2]string{
			{p.url.String, newURL},
			{p.thumb.String, newThumb},
			{p.preview.String, newPreview},
		} {
			if pair[0] == "" || pair[0] == pair[1] {
				continue
			}
			if err := migratePhotoArtifact(pair[0], pair[1]); err != nil {
				return fmt.Errorf("migrate photo %d artifact: %w", p.id, err)
			}
		}

		if _, err := db.Exec(
			`UPDATE photo SET url=?,thumb_url=?,preview_path=? WHERE id=?`,
			newURL, nullIfEmpty(newThumb), nullIfEmpty(newPreview), p.id,
		); err != nil {
			return fmt.Errorf("update photo %d private paths: %w", p.id, err)
		}
		slog.Info("migrated photo files to private root", "photo_id", p.id)
	}
	return nil
}

func privatePhotoRel(raw string) (string, bool) {
	rel := filepath.ToSlash(strings.TrimSpace(raw))
	if !strings.HasPrefix(rel, "upload/") || strings.Contains(rel, "..") {
		return raw, false
	}
	return "media/" + strings.TrimPrefix(rel, "upload/"), true
}

func publicPhotoPath(rel string) (string, bool) {
	clean := filepath.ToSlash(strings.TrimSpace(rel))
	if !strings.HasPrefix(clean, "upload/") || strings.Contains(clean, "..") {
		return "", false
	}
	return filepath.Join(uploadDir, filepath.FromSlash(clean)), true
}

func privatePhotoPath(rel string) (string, bool) {
	clean := filepath.ToSlash(strings.TrimSpace(rel))
	if !strings.HasPrefix(clean, "media/") || strings.Contains(clean, "..") {
		return "", false
	}
	return filepath.Join(privateMediaDir(), filepath.FromSlash(clean)), true
}

func migratePhotoArtifact(oldRel, newRel string) error {
	src, ok := publicPhotoPath(oldRel)
	if !ok {
		return fmt.Errorf("unsafe old path %q", oldRel)
	}
	dst, ok := privatePhotoPath(newRel)
	if !ok {
		return fmt.Errorf("unsafe new path %q", newRel)
	}

	srcInfo, srcErr := os.Stat(src)
	dstInfo, dstErr := os.Stat(dst)
	if srcErr == nil && srcInfo.IsDir() {
		return fmt.Errorf("source artifact is a directory: %q", oldRel)
	}
	if dstErr == nil && dstInfo.IsDir() {
		return fmt.Errorf("destination artifact is a directory: %q", newRel)
	}
	srcExists := srcErr == nil && !srcInfo.IsDir()
	dstExists := dstErr == nil && !dstInfo.IsDir()
	if srcErr != nil && !os.IsNotExist(srcErr) {
		return fmt.Errorf("stat source %q: %w", oldRel, srcErr)
	}
	if dstErr != nil && !os.IsNotExist(dstErr) {
		return fmt.Errorf("stat destination %q: %w", newRel, dstErr)
	}

	if srcExists && dstExists {
		// 兼容一次已复制但尚未清理源文件的中断场景；只有内容完全相同才删除公开副本。
		equal, err := sameFileContents(src, dst)
		if err != nil {
			return err
		}
		if !equal {
			return fmt.Errorf("source and destination conflict: %q and %q", oldRel, newRel)
		}
		if err := os.Remove(src); err != nil {
			return fmt.Errorf("remove duplicate public artifact %q: %w", oldRel, err)
		}
		return nil
	}
	if !srcExists {
		// 老库可能已经有丢失的文件；仍然改库路径，避免未来重新暴露这个旧 URL。
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o700); err != nil {
		return fmt.Errorf("create private media directory: %w", err)
	}
	if _, err := movePreservingBytes(src, dst); err != nil {
		return err
	}
	if err := os.Chmod(dst, 0o600); err != nil {
		return fmt.Errorf("restrict private artifact permissions: %w", err)
	}
	return nil
}

func sameFileContents(a, b string) (bool, error) {
	hashFile := func(path string) ([sha256.Size]byte, error) {
		f, err := os.Open(path)
		if err != nil {
			return [sha256.Size]byte{}, err
		}
		defer f.Close()
		h := sha256.New()
		if _, err := io.Copy(h, f); err != nil {
			return [sha256.Size]byte{}, err
		}
		var sum [sha256.Size]byte
		copy(sum[:], h.Sum(nil))
		return sum, nil
	}
	aHash, err := hashFile(a)
	if err != nil {
		return false, err
	}
	bHash, err := hashFile(b)
	if err != nil {
		return false, err
	}
	return aHash == bHash, nil
}
