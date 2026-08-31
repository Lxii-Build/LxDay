package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/gin-gonic/gin"
)

func withReadyStore(t *testing.T) {
	t.Helper()
	db := openTempDB(t)
	if err := runMigrations(db); err != nil {
		t.Fatalf("migrate readiness db: %v", err)
	}
	previousStore, previousUploadDir := st, uploadDir
	st = &Store{DB: db, mem: newMemStore()}
	uploadDir = filepath.Join(t.TempDir(), "uploads")
	t.Cleanup(func() {
		st = previousStore
		uploadDir = previousUploadDir
	})
}

func TestReadinessCheckVerifiesWritableDependencies(t *testing.T) {
	withReadyStore(t)
	if err := readinessCheck(context.Background()); err != nil {
		t.Fatalf("ready check failed: %v", err)
	}
	for _, dir := range []string{uploadDir, privateMediaDir()} {
		if info, err := os.Stat(dir); err != nil || !info.IsDir() {
			t.Fatalf("ready check did not create usable dir %q: %v", dir, err)
		}
	}
}

func TestReadyzReportsDependencyFailure(t *testing.T) {
	previousStore := st
	st = nil
	t.Cleanup(func() { st = previousStore })

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerStatic(r)
	request := httptest.NewRequest(http.MethodGet, "/readyz", nil)
	response := httptest.NewRecorder()
	r.ServeHTTP(response, request)
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d body=%q, want 503", response.Code, response.Body.String())
	}
}
