// Package scheduler 负责按配置的间隔自动执行「拉取 + 测速 + 生成」。
package scheduler

import (
	"context"
	"time"

	"proxypool/internal/config"
	"proxypool/internal/logx"
	"proxypool/internal/runner"
)

// Scheduler 定时任务调度器。
type Scheduler struct {
	store  *config.Store
	runner *runner.Runner
	log    *logx.Buffer
}

// New 创建调度器。
func New(store *config.Store, r *runner.Runner, log *logx.Buffer) *Scheduler {
	return &Scheduler{store: store, runner: r, log: log}
}

// Start 启动后台循环。
func (s *Scheduler) Start(ctx context.Context) {
	if s.store.Get().Scheduler.RunOnStart {
		go func() {
			time.Sleep(2 * time.Second)
			if _, err := s.runner.Run(ctx, "启动时自动生成", runner.Options{}); err != nil && ctx.Err() == nil {
				s.log.Error("启动时自动生成失败：%v", err)
			}
		}()
	}
	go s.loop(ctx)
}

func (s *Scheduler) loop(ctx context.Context) {
	for {
		cfg := s.store.Get()
		interval := cfg.Scheduler.Interval.D()
		if !cfg.Scheduler.Enabled || interval <= 0 {
			if !sleep(ctx, 30*time.Second) {
				return
			}
			continue
		}
		s.log.Info("下一次自动生成将在 %s 后", interval)
		if !sleep(ctx, interval) {
			return
		}
		if _, err := s.runner.Run(ctx, "定时任务", runner.Options{}); err != nil && ctx.Err() == nil {
			s.log.Error("定时生成失败：%v", err)
		}
	}
}

func sleep(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return true
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
