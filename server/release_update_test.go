package main

import "testing"

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

func TestReleaseAPKURLOnlyUsesAPKAsset(t *testing.T) {
	r := githubRelease{Assets: []githubReleaseAsset{
		{Name: "mapping.txt", BrowserDownloadURL: "https://example.invalid/mapping"},
		{Name: "app-release.apk", BrowserDownloadURL: "https://example.invalid/app.apk"},
	}}
	if got := releaseAPKURL(r); got != "https://example.invalid/app.apk" {
		t.Fatalf("releaseAPKURL()=%q", got)
	}
}
