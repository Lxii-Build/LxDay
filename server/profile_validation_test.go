package main

import (
	"testing"
	"time"
)

func TestNormalizeNicknameTrimsAndCountsRunes(t *testing.T) {
	got, err := normalizeNickname("  林曦  ")
	if err != nil {
		t.Fatalf("normalizeNickname() error = %v", err)
	}
	if got != "林曦" {
		t.Fatalf("normalizeNickname() = %q, want %q", got, "林曦")
	}
}

func TestNormalizeNicknameRejectsOutOfRange(t *testing.T) {
	for _, nickname := range []string{"林", "abcdefghijklmnopqrstuvwxyzabcdefg"} {
		if _, err := normalizeNickname(nickname); err == nil {
			t.Fatalf("normalizeNickname(%q) expected error", nickname)
		}
	}
}

func TestParseAnniversaryAcceptsLeapDayAndRejectsFuture(t *testing.T) {
	today := time.Date(2026, time.August, 9, 12, 0, 0, 0, time.Local)
	got, err := parseAnniversary("2024-02-29", today)
	if err != nil {
		t.Fatalf("parseAnniversary() error = %v", err)
	}
	if got.Format("2006-01-02") != "2024-02-29" {
		t.Fatalf("parseAnniversary() = %s", got.Format("2006-01-02"))
	}
	if _, err := parseAnniversary("2026-08-10", today); err == nil {
		t.Fatal("future anniversary expected error")
	}
	if _, err := parseAnniversary("2025-02-29", today); err == nil {
		t.Fatal("invalid leap day expected error")
	}
}
