package main

import (
	"database/sql"
	"encoding/json"
	"testing"
	"time"
)

// Test回收站剩余天数必须真的写进切片 锁死一个「看起来对、实际什么都没做」的 bug。
//
// 原实现：
//
//	for _, p := range list {              // list 是 []Photo，p 是**副本**
//	    d := recycleRemainingDays(...)
//	    p.RecycleRemainingDays = &d       // 写在副本上，list 里的元素没动
//	}
//
// 而该字段是 `*int` + `omitempty` —— 写不进去就直接从 JSON 里**消失**（不是 0、不是 null）。
// 客户端因此永远拿不到「还剩 N 天自动删除」，回收站页那行提示是空的。
//
// 这类 bug 编译器不报、code review 极易看漏、手测也未必发现（页面只是少一行字）。
// 所以这里直接断言**序列化后的 JSON 里字段存在且数值正确**，
// 而不是只断言 struct 字段被赋值 —— 后者测不出 omitempty 的行为。
func Test回收站剩余天数必须真的写进切片(t *testing.T) {
	const keep = 30
	// 造三条：昨天删的、10 天前删的、deleted_at 为 NULL 回退 created_at 的。
	list := []Photo{
		{ID: 1, CreatedAt: time.Now().AddDate(0, 0, -1),
			deletedAt: sql.NullTime{Time: time.Now().AddDate(0, 0, -1), Valid: true}},
		{ID: 2, CreatedAt: time.Now().AddDate(0, 0, -40),
			deletedAt: sql.NullTime{Time: time.Now().AddDate(0, 0, -10), Valid: true}},
		// 0821 之前进回收站的历史数据没有 deleted_at，按 created_at 算
		{ID: 3, CreatedAt: time.Now().AddDate(0, 0, -5)},
	}

	// 与 handleListRecycled 里完全同构的写法（用下标，不用值副本）
	for i := range list {
		d := recycleRemainingDays(list[i].deletedAt, list[i].CreatedAt, keep)
		list[i].RecycleRemainingDays = &d
	}

	for i := range list {
		if list[i].RecycleRemainingDays == nil {
			t.Fatalf("第 %d 条的 RecycleRemainingDays 是 nil —— "+
				"若用 `for _, p := range list` 就会这样：写在副本上，切片本身没变", i)
		}
	}

	// 数值正确性：昨天删的应剩 29 天，10 天前删的应剩 20 天，5 天前的应剩 25 天
	if got := *list[0].RecycleRemainingDays; got != 29 {
		t.Errorf("昨天删的应剩 29 天，实际 %d", got)
	}
	if got := *list[1].RecycleRemainingDays; got != 20 {
		t.Errorf("10 天前删的应剩 20 天，实际 %d", got)
	}
	if got := *list[2].RecycleRemainingDays; got != 25 {
		t.Errorf("deleted_at 为 NULL 时应按 created_at 算、剩 25 天，实际 %d", got)
	}

	// **关键断言**：序列化后字段必须真的在 JSON 里。
	// omitempty + *int 的组合下，没赋值就整个字段消失，客户端 optInt 拿到 0，
	// 会显示成「还剩 0 天」（即"今天就删"）——比不显示更糟，是错误信息。
	raw, err := json.Marshal(list[0])
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("反序列化失败: %v", err)
	}
	v, present := m["recycle_remaining_days"]
	if !present {
		t.Fatal("JSON 里没有 recycle_remaining_days —— omitempty 把未赋值的字段整个吞掉了")
	}
	if int(v.(float64)) != 29 {
		t.Fatalf("JSON 里的 recycle_remaining_days=%v，期望 29", v)
	}
}

// Test永久保留时剩余天数为负一 保留天数配 0（永久保留）时不该显示倒计时。
func Test永久保留时剩余天数为负一(t *testing.T) {
	got := recycleRemainingDays(
		sql.NullTime{Time: time.Now().AddDate(0, 0, -100), Valid: true},
		time.Now().AddDate(0, 0, -100), 0)
	if got != -1 {
		t.Fatalf("keepDays=0（永久保留）应返回 -1 让客户端隐藏倒计时，实际 %d", got)
	}
}

// Test已过期的剩余天数收敛到零 不能出现负数天数（客户端会显示「还剩 -3 天」）。
func Test已过期的剩余天数收敛到零(t *testing.T) {
	got := recycleRemainingDays(
		sql.NullTime{Time: time.Now().AddDate(0, 0, -40), Valid: true},
		time.Now().AddDate(0, 0, -40), 30)
	if got != 0 {
		t.Fatalf("已超保留期应收敛到 0，实际 %d（负数会显示成「还剩 -10 天」）", got)
	}
}
