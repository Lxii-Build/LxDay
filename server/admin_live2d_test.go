package main

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

func TestLive2DModelMigrationCreatesCatalog(t *testing.T) {
	db := openTempDB(t)
	if err := runMigrations(db); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	var table string
	if err := db.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name='live2d_model'`).Scan(&table); err != nil {
		t.Fatalf("模型目录表未创建: %v", err)
	}
	if table != "live2d_model" {
		t.Fatalf("模型目录表名称=%q", table)
	}
}

func TestSafeLive2DEntryRejectsTraversal(t *testing.T) {
	for _, path := range []string{"../escape.txt", "a/../../escape", `/absolute.txt`, `a\\..\\escape`, "a/./b"} {
		if _, ok := safeLive2DEntry(path); ok {
			t.Errorf("路径 %q 不应通过安全校验", path)
		}
	}
	for _, path := range []string{"model/model3.json", "textures/a.png"} {
		if clean, ok := safeLive2DEntry(path); !ok || clean != path {
			t.Errorf("安全路径 %q 被错误拒绝: %q, %v", path, clean, ok)
		}
	}
}

func TestExtractLive2DArchiveRequiresBundle(t *testing.T) {
	root := t.TempDir()
	archivePath := filepath.Join(root, "model.zip")
	file, err := os.Create(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	entry, err := archive.Create("model/model3.json")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := entry.Write([]byte(`{"FileReferences":{"Textures":["a.png"]}}`)); err != nil {
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	if _, _, err := extractLive2DArchive(archivePath, filepath.Join(root, "staging")); err == nil {
		t.Fatal("缺少 moc3/PNG 的模型包不应通过校验")
	}
}

func TestExtractLive2DArchiveValidatesManifestReferences(t *testing.T) {
	root := t.TempDir()
	archivePath := filepath.Join(root, "model.zip")
	file, err := os.Create(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	write := func(name, contents string) {
		t.Helper()
		entry, err := archive.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte(contents)); err != nil {
			t.Fatal(err)
		}
	}
	writeBytes := func(name string, contents []byte) {
		t.Helper()
		entry, err := archive.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write(contents); err != nil {
			t.Fatal(err)
		}
	}
	write("model/model3.json", `{"FileReferences":{"Moc":"model.moc3","Textures":["texture.png"]}}`)
	writeBytes("model/model.moc3", []byte{'M', 'O', 'C', '3', 1, 0, 0, 0})
	writeBytes("model/texture.png", live2DTestPNGHeader(2, 2))
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	textures, unpacked, err := extractLive2DArchive(archivePath, filepath.Join(root, "staging"))
	if err != nil {
		t.Fatalf("合法模型包不应失败: %v", err)
	}
	if textures != 1 || unpacked <= 0 {
		t.Fatalf("纹理=%d 解压字节=%d", textures, unpacked)
	}
}

func live2DTestPNGHeader(width, height uint32) []byte {
	return []byte{
		0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
		0, 0, 0, 13, 'I', 'H', 'D', 'R',
		byte(width >> 24), byte(width >> 16), byte(width >> 8), byte(width),
		byte(height >> 24), byte(height >> 16), byte(height >> 8), byte(height),
	}
}

func TestLive2DTextureBudgetRejectsCurrentLargeAtlas(t *testing.T) {
	path := filepath.Join(t.TempDir(), "texture.png")
	if err := os.WriteFile(path, live2DTestPNGHeader(8192, 8192), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := validateLive2DTexture(path); err == nil {
		t.Fatal("8192x8192 贴图不应通过移动端预算")
	}
}

func TestLive2DMocHeaderRejectsNonCubismBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "model.moc3")
	if err := os.WriteFile(path, []byte("moc"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := validateLive2DMoc(path); err == nil {
		t.Fatal("非 Cubism moc3 文件不应通过头部预检")
	}
}

func TestLive2DTextureBudgetRejectsAggregatePixels(t *testing.T) {
	root := t.TempDir()
	staging := filepath.Join(root, "staging")
	if err := os.MkdirAll(filepath.Join(staging, "model"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(staging, "model", "model3.json"), []byte(
		`{"FileReferences":{"Moc":"model.moc3","Textures":["a.png","b.png","c.png"]}}`,
	), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(staging, "model", "model.moc3"), []byte("moc"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, item := range []struct {
		name   string
		width  uint32
		height uint32
	}{
		{name: "a.png", width: 4096, height: 2048},
		{name: "b.png", width: 4096, height: 2048},
		{name: "c.png", width: 1, height: 1},
	} {
		if err := os.WriteFile(filepath.Join(staging, "model", item.name), live2DTestPNGHeader(item.width, item.height), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := validateLive2DManifest(staging, "model/model3.json"); err == nil {
		t.Fatal("纹理单张合规但合计超预算时不应通过")
	}
}

func TestSafeLive2DPathAnchorsAtPrivateMediaRoot(t *testing.T) {
	path, ok := safeLive2DPath(filepath.ToSlash(filepath.Join("live2d", "model-test")))
	if !ok {
		t.Fatal("合法模型相对路径被拒绝")
	}
	want := filepath.Join(privateMediaDir(), "live2d", "model-test")
	got, err := filepath.Abs(path)
	if err != nil {
		t.Fatal(err)
	}
	expected, err := filepath.Abs(want)
	if err != nil {
		t.Fatal(err)
	}
	if got != expected {
		t.Fatalf("模型路径=%q, want %q", got, expected)
	}
	for _, rel := range []string{"../escape", "live2d/../escape", "live2d/../../escape"} {
		if _, ok := safeLive2DPath(rel); ok {
			t.Errorf("路径 %q 不应通过私有目录校验", rel)
		}
	}
}

func TestLive2DReferenceResolvesNestedManifestAndSafeParent(t *testing.T) {
	root := t.TempDir()
	staging := filepath.Join(root, "staging")
	base := filepath.Join(staging, "exports", "yumi")
	if err := os.MkdirAll(filepath.Join(staging, "exports", "shared"), 0o700); err != nil {
		t.Fatal(err)
	}
	physics := filepath.Join(staging, "exports", "shared", "yumi.physics3.json")
	if err := os.WriteFile(physics, []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}
	got, ok := live2DReferencePath(staging, base, "../shared/yumi.physics3.json", ".json")
	if !ok {
		t.Fatal("安全的父目录引用不应被拒绝")
	}
	want, err := filepath.Abs(physics)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("解析路径=%q, want %q", got, want)
	}
	if validLive2DReference(staging, base, "../../outside.json", ".json") {
		t.Fatal("越出模型包根目录的引用不应通过")
	}
}
