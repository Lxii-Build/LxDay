package main

import (
	"strings"
	"testing"
)

func TestNormalizeUploadIdempotencyKey(t *testing.T) {
	if got, err := normalizeUploadIdempotencyKey("  abc-123_X.y  "); err != nil || got != "abc-123_X.y" {
		t.Fatalf("normalize valid key=(%q,%v)", got, err)
	}
	for _, raw := range []string{"bad/key", "bad key", "中文", strings.Repeat("a", maxUploadIdempotencyKeyLen+1)} {
		if _, err := normalizeUploadIdempotencyKey(raw); err == nil {
			t.Fatalf("invalid key %q should be rejected", raw)
		}
	}
}

func TestPhotoUploadIdempotencyIsUniquePerUploader(t *testing.T) {
	s := withTestStore(t)
	pair, uid, _ := seedPair(t, s, "idem_a", "idem_b", "IDEMCODE")
	first, err := s.CreatePhotoWithIdempotency(
		pair.ID, uid, 0,
		"media/2026/08/31/first.jpg",
		"media/2026/08/31/first_thumb.jpg",
		"", 100, 100, 3, "image/jpeg", nil, "upload-attempt-1",
	)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreatePhotoWithIdempotency(
		pair.ID, uid, 0,
		"media/2026/08/31/second.jpg",
		"media/2026/08/31/second_thumb.jpg",
		"", 100, 100, 3, "image/jpeg", nil, "upload-attempt-1",
	); err == nil {
		t.Fatal("duplicate upload idempotency key should violate uniqueness")
	}
	existing, err := s.GetPhotoByUploadIdempotencyKey(uid, "upload-attempt-1")
	if err != nil {
		t.Fatal(err)
	}
	if existing == nil || existing.ID != first.ID {
		t.Fatalf("idempotency lookup=%v want photo %d", existing, first.ID)
	}
}
