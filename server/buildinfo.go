package main

import (
	"runtime"
	"strings"
)

// These values are replaced by the release/build workflows with -ldflags.
// Keeping safe defaults makes local development and tests independent of CI.
var (
	serverVersion = "dev"
	serverCommit  = "unknown"
)

func shortCommit(commit string) string {
	commit = strings.TrimSpace(commit)
	if len(commit) > 7 {
		return commit[:7]
	}
	return commit
}

func serverGoVersion() string { return runtime.Version() }
