package main

import (
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

func normalizeNickname(raw string) (string, error) {
	nickname := strings.TrimSpace(raw)
	length := utf8.RuneCountInString(nickname)
	if length < 2 || length > 32 {
		return "", fmt.Errorf("nickname length must be 2-32 characters")
	}
	return nickname, nil
}

func parseAnniversary(raw string, now time.Time) (time.Time, error) {
	date, err := time.ParseInLocation("2006-01-02", raw, now.Location())
	if err != nil {
		return time.Time{}, fmt.Errorf("invalid anniversary: %w", err)
	}
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	if date.After(today) {
		return time.Time{}, fmt.Errorf("anniversary cannot be in the future")
	}
	return date, nil
}
