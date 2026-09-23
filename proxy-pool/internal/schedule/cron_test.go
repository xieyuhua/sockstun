package schedule

import (
	"testing"
	"time"
)

func TestValidate(t *testing.T) {
	valid := []string{
		"0 */6 * * *",
		"30 8 * * 1-5",
		"0 0 1 * *",
		"*/15 * * * *",
		"30 0 */6 * * *", // 带秒
		"@every 2h",
		"@daily",
		"@hourly",
	}
	for _, expr := range valid {
		if err := Validate(expr); err != nil {
			t.Fatalf("表达式 %q 应合法: %v", expr, err)
		}
	}
	invalid := []string{"", "  ", "not a cron", "0 */6 * *", "61 * * * *", "0 0 * * 9"}
	for _, expr := range invalid {
		if err := Validate(expr); err == nil {
			t.Fatalf("表达式 %q 应判为非法", expr)
		}
	}
}

func TestPreviewEverySixHours(t *testing.T) {
	loc := time.FixedZone("CST", 8*3600)
	times, err := Preview("0 */6 * * *", loc, 4)
	if err != nil {
		t.Fatal(err)
	}
	if len(times) != 4 {
		t.Fatalf("应返回 4 个时间点，实际 %d", len(times))
	}
	for i, tm := range times {
		if tm.Minute() != 0 || tm.Hour()%6 != 0 {
			t.Fatalf("第 %d 个时间点不符合表达式: %s", i, tm)
		}
		if tm.Location() != loc {
			t.Fatalf("时间点时区不正确: %s", tm.Location())
		}
		if i > 0 && !tm.After(times[i-1]) {
			t.Fatalf("时间点未递增: %s -> %s", times[i-1], tm)
		}
	}
}

func TestLoadLocation(t *testing.T) {
	loc, err := LoadLocation("")
	if err != nil || loc != time.Local {
		t.Fatalf("留空应使用本机时区: %v %v", loc, err)
	}
	if _, err := LoadLocation("Not/AZone"); err == nil {
		t.Fatal("非法时区应报错")
	}
	if loc, err := LoadLocation("Asia/Shanghai"); err != nil {
		t.Logf("本机缺少时区数据库（已优雅降级）: %v", err)
	} else if loc.String() != "Asia/Shanghai" {
		t.Fatalf("时区解析结果异常: %s", loc)
	}
}

func TestNextRun(t *testing.T) {
	next, err := NextRun("@every 1h", time.UTC)
	if err != nil {
		t.Fatal(err)
	}
	if d := time.Until(next); d <= 0 || d > time.Hour {
		t.Fatalf("下一次运行时间不合理: %s", d)
	}
	if _, err := NextRun("bad expr", time.UTC); err == nil {
		t.Fatal("非法表达式应报错")
	}
}

func TestStateSnapshot(t *testing.T) {
	var state *State
	if mode, next := state.Snapshot(); mode != "" || !next.IsZero() {
		t.Fatal("nil State 应返回空值")
	}
	state = &State{}
	state.Set("cron 0 */6 * * *", time.Now().Add(time.Hour))
	if mode, next := state.Snapshot(); mode == "" || next.IsZero() {
		t.Fatalf("状态未正确保存: %q %v", mode, next)
	}
}
