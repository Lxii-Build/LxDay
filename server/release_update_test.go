package main

import (
	"testing"
	"time"
)

func TestReleaseVersionCode(t *testing.T) {
	for _, tc := range []struct {
		tag  string
		want int
	}{
		{"v1.0.9", 10009},
		{"1.12.3", 11203},
		{"v1.2.3-rc1", 10203},
		{"nightly", 0},
		{"v1.100.0", 0},
	} {
		if got := releaseVersionCode(tc.tag); got != tc.want {
			t.Fatalf("releaseVersionCode(%q)=%d, want %d", tc.tag, got, tc.want)
		}
	}
}

func TestReleaseChannelNeverTreatsPrereleaseAsStable(t *testing.T) {
	stable := githubRelease{TagName: "v1.0.8", Prerelease: false}
	testingRelease := githubRelease{TagName: "v1.0.9", Prerelease: true}
	if !releaseMatchesChannel(stable, "stable") || releaseMatchesChannel(testingRelease, "stable") {
		t.Fatal("stable channel selected a prerelease")
	}
	if !releaseMatchesChannel(testingRelease, "testing") {
		t.Fatal("testing channel did not select prerelease")
	}
}

func TestNewerReleaseUsesVersionCodeBeforePublishTime(t *testing.T) {
	newerVersion := githubRelease{
		TagName:     "v1.0.10",
		PublishedAt: ptrTime("2026-09-01T00:00:00Z"),
	}
	backfilledOlder := githubRelease{
		TagName:     "v1.0.9",
		PublishedAt: ptrTime("2026-09-02T00:00:00Z"),
	}
	if !newerRelease(newerVersion, backfilledOlder) {
		t.Fatal("higher versionCode must win over a later publication time")
	}
}

func ptrTime(value string) *time.Time {
	t, err := time.Parse(time.RFC3339, value)
	if err != nil {
		panic(err)
	}
	return &t
}

func TestReleaseAPKURLOnlyUsesAPKAsset(t *testing.T) {
	r := githubRelease{Assets: []githubReleaseAsset{
		{Name: "mapping.txt", BrowserDownloadURL: "https://example.invalid/mapping"},
		{Name: "app-release.apk", BrowserDownloadURL: "https://example.invalid/app.apk"},
	}}
	if got := releaseAPKURL(r); got != "https://example.invalid/app.apk" {
		t.Fatalf("releaseAPKURL()=%q", got)
	}
}

func TestParseChangelog(t *testing.T) {
	got := parseChangelog("" +
		"# Changelog\n\n" +
		"## [Unreleased]\n\n- not published\n\n" +
		"## [1.0.9] - 2026-09-02\n\n" +
		"### 修复\n- **更新日志**改为读取根文件\n\n" +
		"## 1.0.8 - 2026-09-01\n\n- old\n\n" +
		"[Unreleased]: https://example.invalid/compare\n" +
		"[1.0.8]: https://example.invalid/1.0.8\n")
	if _, ok := got["Unreleased"]; ok {
		t.Fatal("Unreleased must not be treated as a published release")
	}
	if got["1.0.9"] != "### 修复\n- **更新日志**改为读取根文件" {
		t.Fatalf("1.0.9 changelog=%q", got["1.0.9"])
	}
	if got["1.0.8"] != "- old" {
		t.Fatalf("1.0.8 changelog=%q", got["1.0.8"])
	}
}

func TestReleaseJSONUsesRootChangelogInsteadOfGitHubBody(t *testing.T) {
	release := githubRelease{TagName: "v1.0.9"}
	got := releaseJSON(release, map[string]string{"1.0.9": "### 修复\n- 根 CHANGELOG"})
	if got["notes"] != "### 修复\n- 根 CHANGELOG" {
		t.Fatalf("release notes=%v", got["notes"])
	}
}
