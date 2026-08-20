package main

import (
	"testing"
	"time"
)

func TestNormalizeRepeat(t *testing.T) {
	cases := []struct {
		rt, wd, wantRT, wantWD int
	}{
		{0, 5, 0, 0},                 // 仅一次：清掩码
		{1, 5, 1, 0},                 // 每天：清掩码
		{2, 0, 0, 0},                 // 每周但未选任何天 → 归为仅一次
		{2, allWeekdaysMask, 1, 0},   // 每周全选 → 归为每天
		{2, 0b0000101, 2, 0b0000101}, // 每周(周一+周三)
		{2, 0x80 | 0b101, 2, 0b101},  // 高位越界被掩掉
	}
	for _, c := range cases {
		rt, wd := normalizeRepeat(c.rt, c.wd)
		if rt != c.wantRT || wd != c.wantWD {
			t.Fatalf("normalizeRepeat(%d,%b)=(%d,%b) want (%d,%b)", c.rt, c.wd, rt, wd, c.wantRT, c.wantWD)
		}
	}
}

func TestNextRemindOnceReturnsNil(t *testing.T) {
	now := time.Date(2026, 8, 11, 9, 0, 0, 0, time.Local)
	cur := now.Add(-time.Hour)
	if got := nextRemind(cur, 0, 0, now); got != nil {
		t.Fatalf("once should not reschedule, got %v", got)
	}
}

func TestNextRemindDailyAdvancesToNextDaySameClock(t *testing.T) {
	now := time.Date(2026, 8, 11, 9, 0, 0, 0, time.Local)
	cur := time.Date(2026, 8, 11, 8, 0, 0, 0, time.Local) // 今天 8:00 已过
	got := nextRemind(cur, 1, 0, now)
	if got == nil || !got.After(now) {
		t.Fatalf("daily next must be after now, got %v", got)
	}
	if got.Hour() != 8 || got.Minute() != 0 || got.Day() != 12 {
		t.Fatalf("daily next want 08-12 08:00, got %v", got)
	}
}

func TestNextRemindWeeklyAdvancesToMatchingWeekday(t *testing.T) {
	now := time.Date(2026, 8, 11, 9, 0, 0, 0, time.Local)
	cur := time.Date(2026, 8, 11, 8, 0, 0, 0, time.Local)
	idxToday := (int(now.Weekday()) + 6) % 7 // 周一=0..周日=6
	wd := 1 << uint(idxToday)                // 仅选“今天”的星期
	got := nextRemind(cur, 2, wd, now)
	if got == nil || !got.After(now) {
		t.Fatalf("weekly next must be after now, got %v", got)
	}
	if (int(got.Weekday())+6)%7 != idxToday {
		t.Fatalf("weekly next weekday mismatch, got %v", got.Weekday())
	}
	if got.Hour() != 8 {
		t.Fatalf("weekly next should keep clock 08:00, got %v", got)
	}
	// 今天 08:00 已过 → 下次应为下周同一天（约 7 天后）
	if got.Sub(cur) < 6*24*time.Hour {
		t.Fatalf("weekly next should be ~next week, got delta %v", got.Sub(cur))
	}
}

func TestNextRemindWeeklyEmptyMaskReturnsNil(t *testing.T) {
	now := time.Now()
	if got := nextRemind(now.Add(-time.Hour), 2, 0, now); got != nil {
		t.Fatalf("weekly with empty mask should return nil, got %v", got)
	}
}
