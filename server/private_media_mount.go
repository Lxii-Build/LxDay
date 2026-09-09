package main

import (
	"log/slog"
	"os"
	"path"
	"runtime"
	"strings"
)

// privateMediaMountPresent reports whether target is itself a Linux mount
// point. Comparing device IDs is insufficient for bind mounts, which may use
// the same filesystem as their parent; mountinfo is the kernel's source of
// truth.
func privateMediaMountPresent(target, mountInfo string) (bool, error) {
	// mountinfo is a Linux/POSIX format even when the pure helper is exercised
	// by a Windows CI runner. Do not use filepath.Abs/Clean here: on Windows a
	// leading /app path becomes C:\\app and can never match the fixture.
	want := path.Clean(target)
	for _, line := range strings.Split(mountInfo, "\n") {
		fields := strings.Fields(line)
		// Linux mountinfo field 5 is the mount point. It is present before
		// the optional fields and the literal " - " separator.
		if len(fields) < 5 {
			continue
		}
		mountedAt := path.Clean(unescapeMountInfoPath(fields[4]))
		if mountedAt == want {
			return true, nil
		}
	}
	return false, nil
}

func unescapeMountInfoPath(v string) string {
	return strings.NewReplacer(
		`\040`, " ",
		`\011`, "\t",
		`\012`, "\n",
		`\134`, `\`,
	).Replace(v)
}

// warnIfPrivateMediaIsEphemeral turns a silent deployment mistake into a
// startup warning. It intentionally only applies inside a Docker container:
// local development naturally uses ordinary directories rather than mounts.
func warnIfPrivateMediaIsEphemeral() {
	if runtime.GOOS != "linux" {
		return
	}
	if _, err := os.Stat("/.dockerenv"); err != nil {
		return
	}
	contents, err := os.ReadFile("/proc/self/mountinfo")
	if err != nil {
		slog.Warn("无法检查私密相册目录是否已挂载为卷", "path", privateMediaDir(), "err", err)
		return
	}
	mounted, err := privateMediaMountPresent(privateMediaDir(), string(contents))
	if err != nil {
		slog.Warn("无法解析私密相册目录挂载状态", "path", privateMediaDir(), "err", err)
		return
	}
	if !mounted {
		slog.Warn(
			"私密相册目录未挂载为卷，容器重建会丢失原图和缩略图",
			"path", privateMediaDir(),
			"fix", "在 compose volumes 中添加 uploads_private:/app/uploads-private",
		)
	}
}
