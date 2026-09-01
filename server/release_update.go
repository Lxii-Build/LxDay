package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

const (
	githubReleaseAPI = "https://api.github.com/repos/Lxii-Build/LxDay/releases"
	githubRepoURL    = "https://github.com/Lxii-Build/LxDay"
	releaseCacheTTL  = 5 * time.Minute
)

type githubReleaseAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
	ContentType        string `json:"content_type"`
	Size               int64  `json:"size"`
}

type githubRelease struct {
	TagName     string               `json:"tag_name"`
	Name        string               `json:"name"`
	Body        string               `json:"body"`
	HTMLURL     string               `json:"html_url"`
	Draft       bool                 `json:"draft"`
	Prerelease  bool                 `json:"prerelease"`
	PublishedAt *time.Time           `json:"published_at"`
	Assets      []githubReleaseAsset `json:"assets"`
}

type releaseCacheState struct {
	sync.Mutex
	expires time.Time
	etag    string
	items   []githubRelease
}

var releaseCache releaseCacheState

func fetchGitHubReleases(ctx context.Context) ([]githubRelease, error) {
	releaseCache.Lock()
	if time.Now().Before(releaseCache.expires) && releaseCache.items != nil {
		items := append([]githubRelease(nil), releaseCache.items...)
		releaseCache.Unlock()
		return items, nil
	}
	etag := releaseCache.etag
	releaseCache.Unlock()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, githubReleaseAPI+"?per_page=100", nil)
	if err != nil {
		return nil, fmt.Errorf("create github release request: %w", err)
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "LxDay-release-check/1")
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch github releases: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotModified {
		releaseCache.Lock()
		releaseCache.expires = time.Now().Add(releaseCacheTTL)
		items := append([]githubRelease(nil), releaseCache.items...)
		releaseCache.Unlock()
		return items, nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("github releases returned http %d", resp.StatusCode)
	}
	var releases []githubRelease
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&releases); err != nil {
		return nil, fmt.Errorf("decode github releases: %w", err)
	}
	filtered := make([]githubRelease, 0, len(releases))
	for _, release := range releases {
		if !release.Draft && strings.TrimSpace(release.TagName) != "" {
			filtered = append(filtered, release)
		}
	}
	sort.SliceStable(filtered, func(i, j int) bool {
		return releaseTime(filtered[i]).After(releaseTime(filtered[j]))
	})
	releaseCache.Lock()
	releaseCache.items = filtered
	releaseCache.expires = time.Now().Add(releaseCacheTTL)
	releaseCache.etag = resp.Header.Get("ETag")
	releaseCache.Unlock()
	return append([]githubRelease(nil), filtered...), nil
}

func releaseTime(r githubRelease) time.Time {
	if r.PublishedAt != nil {
		return r.PublishedAt.UTC()
	}
	return time.Time{}
}

func releaseVersionName(tag string) string {
	return strings.TrimPrefix(strings.TrimSpace(tag), "v")
}

func releaseVersionCode(tag string) int {
	parts := strings.Split(releaseVersionName(tag), ".")
	if len(parts) != 3 {
		return 0
	}
	major, err1 := strconv.Atoi(parts[0])
	minor, err2 := strconv.Atoi(parts[1])
	patch, err3 := strconv.Atoi(strings.SplitN(parts[2], "-", 2)[0])
	if err1 != nil || err2 != nil || err3 != nil || major < 0 || minor < 0 || patch < 0 || minor > 99 || patch > 99 {
		return 0
	}
	return major*10000 + minor*100 + patch
}

func releaseAPKURL(r githubRelease) string {
	for _, asset := range r.Assets {
		if strings.HasSuffix(strings.ToLower(asset.Name), ".apk") && asset.BrowserDownloadURL != "" {
			return asset.BrowserDownloadURL
		}
	}
	return ""
}

func releaseJSON(r githubRelease) gin.H {
	published := ""
	if r.PublishedAt != nil {
		published = r.PublishedAt.UTC().Format(time.RFC3339)
	}
	assets := make([]gin.H, 0, len(r.Assets))
	for _, asset := range r.Assets {
		assets = append(assets, gin.H{
			"name": asset.Name, "download_url": asset.BrowserDownloadURL,
			"content_type": asset.ContentType, "size": asset.Size,
		})
	}
	return gin.H{
		"tag_name": r.TagName, "version_name": releaseVersionName(r.TagName),
		"version_code": releaseVersionCode(r.TagName), "name": r.Name,
		"notes": r.Body, "html_url": r.HTMLURL, "apk_url": releaseAPKURL(r),
		"prerelease": r.Prerelease, "published_at": published, "assets": assets,
	}
}

func releaseMatchesChannel(r githubRelease, channel string) bool {
	if channel == "testing" {
		return true
	}
	return !r.Prerelease
}

// handleCheckUpdate keeps the old endpoint path for existing clients, but its
// source is now GitHub Releases. No release can force-install an APK: Android
// already has different signing/permission rules and a test release must stay
// explicitly optional.
func handleCheckUpdate(c *gin.Context) {
	cur, _ := strconv.Atoi(c.DefaultQuery("version_code", "0"))
	channel := strings.ToLower(strings.TrimSpace(c.DefaultQuery("channel", "stable")))
	if channel != "testing" {
		channel = "stable"
	}
	releases, err := fetchGitHubReleases(c.Request.Context())
	if err != nil {
		ok(c, gin.H{"has_update": false, "force": false, "channel": channel, "history": []gin.H{}})
		return
	}
	history := make([]gin.H, 0, len(releases))
	var latest *githubRelease
	for i := range releases {
		if !releaseMatchesChannel(releases[i], channel) {
			continue
		}
		history = append(history, releaseJSON(releases[i]))
		// 即使 Release 暂时没有 APK，也返回 GitHub Release 页面作为降级入口；
		// 不能跳过它继续推荐更旧的版本。
		if latest == nil && releaseVersionCode(releases[i].TagName) > 0 {
			latest = &releases[i]
		}
	}
	data := gin.H{"has_update": false, "force": false, "channel": channel, "history": history}
	if latest != nil {
		candidate := releaseJSON(*latest)
		data["version"] = candidate
		data["has_update"] = releaseVersionCode(latest.TagName) > cur
	}
	ok(c, data)
}

func handleAdminListAppReleases(c *gin.Context) {
	releases, err := fetchGitHubReleases(c.Request.Context())
	if err != nil {
		afail(c, http.StatusBadGateway, 502, "GitHub 版本信息暂时不可用")
		return
	}
	items := make([]gin.H, 0, len(releases))
	for _, release := range releases {
		items = append(items, releaseJSON(release))
	}
	aok(c, gin.H{"repository": githubRepoURL, "releases": items, "server_version": serverVersion, "server_commit": shortCommit(serverCommit)})
}
