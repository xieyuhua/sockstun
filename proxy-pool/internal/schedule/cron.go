// Package schedule 提供 cron 表达式解析与调度状态共享。
package schedule

import (
	"fmt"
	"strings"
	"time"

	"github.com/robfig/cron/v3"
)

// parser 支持：
//   - 标准 5 字段：分 时 日 月 周   例如 "0 */6 * * *"
//   - 可选秒的 6 字段：秒 分 时 日 月 周  例如 "30 0 */6 * * *"
//   - 描述符：@every 2h / @daily / @hourly 等
var parser = cron.NewParser(
	cron.SecondOptional | cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow | cron.Descriptor,
)

// Parse 解析 cron 表达式。
func Parse(expr string) (cron.Schedule, error) {
	trimmed := strings.TrimSpace(expr)
	if trimmed == "" {
		return nil, fmt.Errorf("cron 表达式不能为空")
	}
	sched, err := parser.Parse(trimmed)
	if err != nil {
		return nil, fmt.Errorf("无法解析 cron 表达式 %q：%v", trimmed, err)
	}
	return sched, nil
}

// Validate 校验 cron 表达式是否合法。
func Validate(expr string) error {
	_, err := Parse(expr)
	return err
}

// LoadLocation 解析时区名称，留空时使用本机时区。
func LoadLocation(name string) (*time.Location, error) {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return time.Local, nil
	}
	loc, err := time.LoadLocation(trimmed)
	if err != nil {
		return nil, fmt.Errorf("无法加载时区 %q：%v", trimmed, err)
	}
	return loc, nil
}

// NextRun 计算下一次触发时间（按指定时区）。
func NextRun(expr string, loc *time.Location) (time.Time, error) {
	sched, err := Parse(expr)
	if err != nil {
		return time.Time{}, err
	}
	if loc == nil {
		loc = time.Local
	}
	return sched.Next(time.Now().In(loc)), nil
}

// Preview 列出接下来的 count 个触发时间，便于在管理页面确认表达式是否符合预期。
func Preview(expr string, loc *time.Location, count int) ([]time.Time, error) {
	sched, err := Parse(expr)
	if err != nil {
		return nil, err
	}
	if loc == nil {
		loc = time.Local
	}
	if count <= 0 {
		count = 5
	}
	out := make([]time.Time, 0, count)
	cursor := time.Now().In(loc)
	for i := 0; i < count; i++ {
		cursor = sched.Next(cursor)
		out = append(out, cursor)
	}
	return out, nil
}
