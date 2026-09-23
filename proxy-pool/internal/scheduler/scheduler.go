// Package scheduler 负责按 cron 表达式或固定间隔自动执行「拉取 + 测速 + 生成」。
package scheduler

import (
	"context"
	"fmt"
	"strings"
	"time"

	"proxypool/internal/config"
	"proxypool/internal/logx"
	"proxypool/internal/runner"
	"proxypool/internal/schedule"
)

// 配置变化检查间隔：即使下一次触发还很远，也会定期醒来重新读取配置。
const configPollInterval = 30 * time.Second

// Scheduler 定时任务调度器。
type Scheduler struct {
	store  *config.Store
	runner *runner.Runner
	log    *logx.Buffer
	state  *schedule.State
}

// New 创建调度器。state 可为 nil。
func New(store *config.Store, r *runner.Runner, log *logx.Buffer, state *schedule.State) *Scheduler {
	if state == nil {
		state = &schedule.State{}
	}
	return &Scheduler{store: store, runner: r, log: log, state: state}
}

// Start 启动后台循环：先在启动时执行一次（可选），随后按计划触发。
func (s *Scheduler) Start(ctx context.Context) {
	if s.store.Get().Scheduler.RunOnStart {
		go func() {
			time.Sleep(2 * time.Second)
			s.runOnce(ctx, "启动时自动生成")
		}()
	}
	go s.loop(ctx)
}

// plan 计算当前的调度方案：key 用于识别配置是否变化。
func plan(sc config.SchedulerConfig, log *logx.Buffer) (key string, target time.Time, mode string) {
	if !sc.Enabled {
		return "disabled", time.Time{}, "未启用"
	}
	if expr := strings.TrimSpace(sc.Cron); expr != "" {
		loc, err := schedule.LoadLocation(sc.Timezone)
		if err != nil {
			log.Error("%v，已改用本机时区", err)
			loc = time.Local
		}
		next, err := schedule.NextRun(expr, loc)
		if err != nil {
			log.Error("%v，已回落到固定间隔模式", err)
		} else {
			mode := "cron " + expr
			if strings.TrimSpace(sc.Timezone) != "" {
				mode += "（" + sc.Timezone + "）"
			}
			return "cron:" + expr + "@" + sc.Timezone, next, mode
		}
	}
	interval := sc.Interval.D()
	if interval <= 0 {
		interval = 30 * time.Minute
	}
	return "interval:" + interval.String(), time.Now().Add(interval), "每 " + interval.String()
}

func (s *Scheduler) loop(ctx context.Context) {
	planKey := ""
	var target time.Time

	for {
		cfg := s.store.Get()
		key, next, mode := plan(cfg.Scheduler, s.log)
		if key != planKey {
			planKey = key
			target = next
			s.state.Set(mode, target)
			if target.IsZero() {
				s.log.Info("定时自动生成已关闭")
			} else {
				s.log.Info("下一次自动生成：%s（%s）", target.Format("2006-01-02 15:04:05 MST"), mode)
			}
		}

		if target.IsZero() {
			if !sleep(ctx, configPollInterval) {
				return
			}
			continue
		}

		wait := time.Until(target)
		if wait > configPollInterval {
			wait = configPollInterval
		}
		if !sleep(ctx, wait) {
			return
		}
		if time.Now().Before(target) {
			continue // 还没到点，继续等待（期间会重新读取配置）
		}

		s.runOnce(ctx, "定时任务")
		planKey = ""
		target = time.Time{}
	}
}

func (s *Scheduler) runOnce(ctx context.Context, trigger string) {
	if s.runner.Running() {
		s.log.Warn("上一轮任务尚未结束，跳过本次「%s」", trigger)
		return
	}
	start := time.Now()
	if _, err := s.runner.Run(ctx, trigger, runner.Options{}); err != nil {
		if ctx.Err() == nil {
			s.log.Error("「%s」执行失败：%v", trigger, err)
		}
		return
	}
	s.log.Info("「%s」执行完成，耗时 %s", trigger, time.Since(start).Round(time.Second))
}

func sleep(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return ctx.Err() == nil
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

// Describe 返回当前调度配置的可读描述，用于接口展示。
func Describe(sc config.SchedulerConfig) string {
	if !sc.Enabled {
		return "未启用"
	}
	if expr := strings.TrimSpace(sc.Cron); expr != "" {
		return fmt.Sprintf("cron：%s", expr)
	}
	return fmt.Sprintf("每 %s", sc.Interval.D())
}
