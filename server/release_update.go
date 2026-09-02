package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

const (
	githubReleaseAPI   = "https://api.github.com/repos/Lxii-Build/LxDay/releases"
	githubChangelogURL = "https://raw.githubusercontent.com/Lxii-Build/LxDay/main/CHANGELOG.md"
	githubRepoURL      = "https://github.com/Lxii-Build/LxDay"
	releaseCacheTTL    = 5 * time.Minute
	maxChangelogBytes  = 2 << 20
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

type changelogCacheState struct {
	sync.Mutex
	expires time.Time
	etag    string
	content string
	entries map[string]string
}

var changelogCache changelogCacheState

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

// fetchGitHubChangelog reads the repository's root CHANGELOG.md. Release
// bodies are intentionally not used: the repository changelog is the single
// source of truth for both the server API and the Android update dialog.
func fetchGitHubChangelog(ctx context.Context) (map[string]string, error) {
	changelogCache.Lock()
	if time.Now().Before(changelogCache.expires) && changelogCache.entries != nil {
		entries := cloneChangelog(changelogCache.entries)
		changelogCache.Unlock()
		return entries, nil
	}
	etag := changelogCache.etag
	cachedContent := changelogCache.content
	changelogCache.Unlock()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, githubChangelogURL, nil)
	if err != nil {
		return nil, fmt.Errorf("create github changelog request: %w", err)
	}
	req.Header.Set("Accept", "text/plain")
	req.Header.Set("User-Agent", "LxDay-release-check/1")
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch github changelog: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotModified {
		entries := parseChangelog(cachedContent)
		changelogCache.Lock()
		changelogCache.expires = time.Now().Add(releaseCacheTTL)
		if changelogCache.entries != nil {
			entries = cloneChangelog(changelogCache.entries)
		}
		changelogCache.Unlock()
		return entries, nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("github changelog returned http %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxChangelogBytes+1))
	if err != nil {
		return nil, fmt.Errorf("read github changelog: %w", err)
	}
	if len(body) > maxChangelogBytes {
		return nil, fmt.Errorf("github changelog exceeds %d bytes", maxChangelogBytes)
	}
	content := string(body)
	entries := parseChangelog(content)
	changelogCache.Lock()
	changelogCache.content = content
	changelogCache.entries = entries
	changelogCache.expires = time.Now().Add(releaseCacheTTL)
	changelogCache.etag = resp.Header.Get("ETag")
	changelogCache.Unlock()
	return cloneChangelog(entries), nil
}

func cloneChangelog(entries map[string]string) map[string]string {
	clone := make(map[string]string, len(entries))
	for version, notes := range entries {
		clone[version] = notes
	}
	return clone
}

// parseChangelog extracts release sections from the root Markdown file. It
// accepts the project's `## [1.2.3] - date` form and also tolerates an
// unbracketed semantic version for older entries. The Unreleased section is
// deliberately excluded from release lookups.
func parseChangelog(markdown string) map[string]string {
	entries := make(map[string]string)
	lines := strings.Split(strings.ReplaceAll(markdown, "\r\n", "\n"), "\n")
	current := ""
	body := make([]string, 0)
	flush := func() {
		if current != "" {
			entries[current] = strings.TrimSpace(strings.Join(body, "\n"))
		}
	}
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "## ") {
			flush()
			current = changelogHeadingVersion(trimmed)
			body = body[:0]
			continue
		}
		if current != "" {
			// Reference definitions live at the end of the root CHANGELOG. If
			// there is no following release heading, keeping them here would
			// attach raw Markdown URLs to the final release's notes.
			if isMarkdownReferenceDefinition(trimmed) {
				continue
			}
			body = append(body, line)
		}
	}
	flush()
	return entries
}

func isMarkdownReferenceDefinition(line string) bool {
	if !strings.HasPrefix(line, "[") {
		return false
	}
	close := strings.Index(line, "]:")
	return close > 1 && strings.TrimSpace(line[close+2:]) != ""
}

func changelogHeadingVersion(heading string) string {
	value := strings.TrimSpace(strings.TrimPrefix(heading, "## "))
	if strings.HasPrefix(value, "[") {
		end := strings.IndexByte(value, ']')
		if end <= 1 {
			return ""
		}
		value = value[1:end]
	} else if fields := strings.Fields(value); len(fields) > 0 {
		value = fields[0]
	}
	value = strings.TrimSpace(value)
	value = strings.TrimPrefix(value, "v")
	value = strings.TrimPrefix(value, "V")
	if value == "" || strings.EqualFold(value, "unreleased") {
		return ""
	}
	return value
}

func releaseTime(r githubRelease) time.Time {
	if r.PublishedAt != nil {
		return r.PublishedAt.UTC()
	}
	return time.Time{}
}

func releaseVersionName(tag string) string {
	return strings.TrimLeft(strings.TrimSpace(tag), "vV")
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

func releaseJSON(r githubRelease, changelog map[string]string) gin.H {
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
		"notes": changelog[releaseVersionName(r.TagName)], "html_url": r.HTMLURL, "apk_url": releaseAPKURL(r),
		"prerelease": r.Prerelease, "published_at": published, "assets": assets,
	}
}

func releaseMatchesChannel(r githubRelease, channel string) bool {
	if channel == "testing" {
		return true
	}
	return !r.Prerelease
}

// newerRelease compares the monotonically increasing Android versionCode
// first. GitHub's API is sorted by publication time below, but a backfilled
// or re-published older tag must not win merely because it was published
// later than the actual newest version.
func newerRelease(candidate, current githubRelease) bool {
	candidateCode := releaseVersionCode(candidate.TagName)
	currentCode := releaseVersionCode(current.TagName)
	if candidateCode != currentCode {
		return candidateCode > currentCode
	}
	return releaseTime(candidate).After(releaseTime(current))
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
	changelog, err := fetchGitHubChangelog(c.Request.Context())
	if err != nil {
		// A temporary raw-file outage must not hide a valid release or make the
		// update check unusable; notes remain empty rather than falling back to
		// an unrelated GitHub Release body.
		slog.Warn("fetch root changelog failed", "err", err)
		changelog = map[string]string{}
	}
	history := make([]gin.H, 0, len(releases))
	var latest *githubRelease
	for i := range releases {
		if !releaseMatchesChannel(releases[i], channel) {
			continue
		}
		history = append(history, releaseJSON(releases[i], changelog))
		// 即使 Release 暂时没有 APK，也返回 GitHub Release 页面作为降级入口；
		// 不能跳过它继续推荐更旧的版本。
		if releaseVersionCode(releases[i].TagName) > 0 &&
			(latest == nil || newerRelease(releases[i], *latest)) {
			latest = &releases[i]
		}
	}
	data := gin.H{"has_update": false, "force": false, "channel": channel, "history": history}
	if latest != nil {
		candidate := releaseJSON(*latest, changelog)
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
	changelog, err := fetchGitHubChangelog(c.Request.Context())
	if err != nil {
		slog.Warn("fetch root changelog for admin failed", "err", err)
		changelog = map[string]string{}
	}
	items := make([]gin.H, 0, len(releases))
	for _, release := range releases {
		items = append(items, releaseJSON(release, changelog))
	}
	aok(c, gin.H{"repository": githubRepoURL, "releases": items, "server_version": serverVersion, "server_commit": shortCommit(serverCommit), "server_go": serverGoVersion()})
}
