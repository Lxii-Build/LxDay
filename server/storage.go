package main

import (
	"os"
	"path/filepath"
)

// ================= 存储抽象层 =================
// 头像/图片等静态资源的存储后端抽象。本地磁盘为默认实现；
// 云存储(阿里云 OSS / 腾讯云 COS / 七牛 Kodo)为预留驱动，需引入对应 SDK 后接入，
// 由后台「系统设置 → storage.driver」切换，未实现或未配置时回落本地。

type Storage interface {
	// Save 写入相对路径的对象，返回可公开访问的 URL。
	Save(relPath string, data []byte) (string, error)
	// Delete 删除相对路径对象。
	Delete(relPath string) error
	// PublicURL 返回相对路径对应的公开访问 URL。
	PublicURL(relPath string) string
}

// LocalStorage 本地磁盘实现：写入 baseDir，URL 前缀 urlPrefix（Nginx 静态映射）。
type LocalStorage struct {
	baseDir   string
	urlPrefix string
}

func (l *LocalStorage) Save(relPath string, data []byte) (string, error) {
	full := filepath.Join(l.baseDir, filepath.FromSlash(relPath))
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		return "", err
	}
	if err := os.WriteFile(full, data, 0o644); err != nil {
		return "", err
	}
	return l.PublicURL(relPath), nil
}

func (l *LocalStorage) Delete(relPath string) error {
	return os.Remove(filepath.Join(l.baseDir, filepath.FromSlash(relPath)))
}

func (l *LocalStorage) PublicURL(relPath string) string {
	return l.urlPrefix + "/" + filepath.ToSlash(relPath)
}

// newStorage 依据后台设置 storage.driver 选择存储后端；云驱动未接入时回落本地。
func newStorage() Storage {
	driver := ""
	if st != nil {
		driver, _ = st.GetSetting("storage.driver")
	}
	switch driver {
	// case "oss": return newOSSStorage(...)   // 预留：阿里云 OSS
	// case "cos": return newCOSStorage(...)   // 预留：腾讯云 COS
	// case "kodo": return newKodoStorage(...) // 预留：七牛 Kodo
	default:
		return &LocalStorage{baseDir: uploadDir, urlPrefix: "/uploads"}
	}
}
