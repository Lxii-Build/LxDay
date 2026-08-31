package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPrivateMediaDirIsOutsidePublicUploadDir(t *testing.T) {
	withTempUploadDir(t)

	uploadAbs, err := filepath.Abs(uploadDir)
	if err != nil {
		t.Fatal(err)
	}
	privateAbs, err := filepath.Abs(privateMediaDir())
	if err != nil {
		t.Fatal(err)
	}
	rel, err := filepath.Rel(uploadAbs, privateAbs)
	if err != nil {
		t.Fatal(err)
	}
	if rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(os.PathSeparator))) {
		t.Fatalf("private media root %q must not be inside public root %q", privateAbs, uploadAbs)
	}
}

func TestMigratePhotoFilesToPrivateRoot(t *testing.T) {
	withTempUploadDir(t)
	s := withTestStore(t)

	oldRels := []string{
		"upload/2026/08/31/photo.jpg",
		"upload/2026/08/31/photo_thumb.jpg",
		"upload/2026/08/31/photo_preview.jpg",
	}
	for i, rel := range oldRels {
		full := filepath.Join(uploadDir, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte{byte(i + 1), 2, 3}, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	res, err := s.DB.Exec(
		`INSERT INTO photo(album_id,pair_id,uploader_id,url,thumb_url,preview_path,width,height,size_bytes,mime,status)
		 VALUES(0,1,1,?,?,?,100,100,3,'image/jpeg',1)`,
		oldRels[0], oldRels[1], oldRels[2],
	)
	if err != nil {
		t.Fatal(err)
	}
	id, err := res.LastInsertId()
	if err != nil {
		t.Fatal(err)
	}

	if err := migratePhotoFilesToPrivateRoot(s.DB); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	for _, oldRel := range oldRels {
		if _, err := os.Stat(filepath.Join(uploadDir, filepath.FromSlash(oldRel))); !os.IsNotExist(err) {
			t.Fatalf("old public file %q still exists: %v", oldRel, err)
		}
	}
	for _, oldRel := range oldRels {
		newRel, ok := privatePhotoRel(oldRel)
		if !ok {
			t.Fatalf("privatePhotoRel(%q) failed", oldRel)
		}
		if _, err := os.Stat(filepath.Join(privateMediaDir(), filepath.FromSlash(newRel))); err != nil {
			t.Fatalf("private file %q missing: %v", newRel, err)
		}
	}
	var url, thumb, preview string
	if err := s.DB.QueryRow(`SELECT url,thumb_url,preview_path FROM photo WHERE id=?`, id).
		Scan(&url, &thumb, &preview); err != nil {
		t.Fatal(err)
	}
	for _, rel := range []string{url, thumb, preview} {
		if len(rel) < len("media/") || rel[:len("media/")] != "media/" {
			t.Fatalf("photo path was not privatized: %q", rel)
		}
	}

	// 再跑一次必须幂等，且不能因为源文件已经移动而报错。
	if err := migratePhotoFilesToPrivateRoot(s.DB); err != nil {
		t.Fatalf("idempotent migrate: %v", err)
	}
}

func TestSafeUploadPathMapsPrivateMediaSeparately(t *testing.T) {
	withTempUploadDir(t)
	got, ok := safeUploadPath("media/2026/08/31/a.jpg")
	if !ok {
		t.Fatal("private media path should be accepted")
	}
	want := filepath.Join(privateMediaDir(), "media", "2026", "08", "31", "a.jpg")
	if got != want {
		t.Fatalf("safeUploadPath private=%q want %q", got, want)
	}
	public, ok := safeUploadPath("upload/2026/08/31/a.jpg")
	if !ok || public != filepath.Join(uploadDir, "upload", "2026", "08", "31", "a.jpg") {
		t.Fatalf("public path mapping changed: %q %v", public, ok)
	}
}
