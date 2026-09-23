// Package runner 编排完整流程：拉取订阅 -> 合并节点池 -> 测速筛选 -> 生成 Clash 配置。
package runner

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"proxypool/internal/config"
	"proxypool/internal/generator"
	"proxypool/internal/logx"
	"proxypool/internal/model"
	"proxypool/internal/pool"
	"proxypool/internal/speedtest"
	"proxypool/internal/subscription"
)

// SubResult 单个订阅的抓取结果。
type SubResult struct {
	ID        string                `json:"id"`
	Name      string                `json:"name"`
	URL       string                `json:"url"`
	OK        bool                  `json:"ok"`
	NodeCount int                   `json:"node_count"`
	Error     string                `json:"error,omitempty"`
	Traffic   *subscription.Traffic `json:"traffic,omitempty"`
}

// Snapshot 一次生成的完整结果。
type Snapshot struct {
	At            time.Time     `json:"at"`
	Trigger       string        `json:"trigger"`
	DurationMS    int64         `json:"duration_ms"`
	Output        string        `json:"output"`
	Total         int           `json:"total"`
	Alive         int           `json:"alive"`
	Active        int           `json:"active"`
	Duplicated    int           `json:"duplicated"`
	Invalid       int           `json:"invalid"`
	Dropped       int           `json:"dropped"`
	Engine        string        `json:"engine"`
	Subscriptions []SubResult   `json:"subscriptions"`
	Nodes         []*model.Node `json:"nodes"`
	Error         string        `json:"error,omitempty"`
}

// Progress 当前任务进度。
type Progress struct {
	Running   bool      `json:"running"`
	Stage     string    `json:"stage"`
	Done      int       `json:"done"`
	Total     int       `json:"total"`
	Trigger   string    `json:"trigger"`
	StartedAt time.Time `json:"started_at"`
}

// Options 运行选项。
type Options struct {
	// ReuseNodes 复用上次的节点池（只重新测速，不重新拉订阅）。
	ReuseNodes bool
}

// Runner 任务编排器。
type Runner struct {
	store   *config.Store
	log     *logx.Buffer
	fetcher *subscription.Fetcher

	mu       sync.Mutex
	running  bool
	progress Progress
	last     *Snapshot
}

// New 创建编排器。
func New(store *config.Store, log *logx.Buffer) *Runner {
	return &Runner{
		store:   store,
		log:     log,
		fetcher: subscription.New(log, 90*time.Second),
	}
}

// Running 是否有任务在执行。
func (r *Runner) Running() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running
}

// Progress 返回当前进度。
func (r *Runner) Progress() Progress {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.progress
}

// Last 返回最近一次的结果。
func (r *Runner) Last() *Snapshot {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.last
}

func (r *Runner) setStage(stage string) {
	r.mu.Lock()
	r.progress.Stage = stage
	r.progress.Done, r.progress.Total = 0, 0
	r.mu.Unlock()
}

func (r *Runner) setProgress(stage string, done, total int) {
	r.mu.Lock()
	r.progress.Stage = stage
	r.progress.Done, r.progress.Total = done, total
	r.mu.Unlock()
}

func (r *Runner) logf(level, format string, args ...any) {
	if r.log == nil {
		return
	}
	switch level {
	case "DEBUG":
		r.log.Debug(format, args...)
	case "WARN":
		r.log.Warn(format, args...)
	case "ERROR":
		r.log.Error(format, args...)
	default:
		r.log.Info(format, args...)
	}
}

// Run 执行一次完整流程。
func (r *Runner) Run(ctx context.Context, trigger string, opts Options) (*Snapshot, error) {
	r.mu.Lock()
	if r.running {
		r.mu.Unlock()
		return nil, errors.New("已有任务正在运行，请稍候")
	}
	r.running = true
	r.progress = Progress{Running: true, Stage: "准备中", Trigger: trigger, StartedAt: time.Now()}
	lastSnapshot := r.last
	r.mu.Unlock()

	defer func() {
		r.mu.Lock()
		r.running = false
		r.progress.Running = false
		r.progress.Stage = "空闲"
		r.mu.Unlock()
	}()

	started := time.Now()
	cfg := r.store.Get()
	snap := &Snapshot{At: started, Trigger: trigger, Engine: cfg.SpeedTest.Mode}

	reporter := &taskReporter{r: r}

	var nodes []*model.Node

	if opts.ReuseNodes && lastSnapshot != nil && len(lastSnapshot.Nodes) > 0 {
		r.logf("INFO", "复用上次节点池（%d 个节点），仅重新测速", len(lastSnapshot.Nodes))
		nodes = lastSnapshot.Nodes
		for _, n := range nodes {
			n.Delay, n.Speed, n.Alive, n.Error = -1, 0, false, ""
		}
	} else {
		// 1) 拉取订阅
		nodes, snap.Subscriptions = r.fetchAll(ctx, cfg)
		if len(nodes) == 0 {
			snap.Error = "所有订阅都没有解析到节点"
			snap.DurationMS = time.Since(started).Milliseconds()
			r.finish(snap, cfg)
			return snap, errors.New(snap.Error)
		}

		// 2) 合并节点池
		r.setStage("合并节点池")
		built := pool.Build(nodes, cfg.Pool)
		snap.Total = len(built.Nodes) + built.Duplicated
		snap.Duplicated = built.Duplicated
		snap.Invalid = built.Invalid
		snap.Dropped = built.Dropped
		nodes = built.Nodes
		r.logf("INFO", "节点池：有效 %d，去重 %d，非法 %d，不支持 %d",
			len(nodes), built.Duplicated, built.Invalid, built.Dropped)
		if len(nodes) == 0 {
			snap.Error = "订阅中的节点全部无效或不支持"
			snap.DurationMS = time.Since(started).Milliseconds()
			r.finish(snap, cfg)
			return snap, errors.New(snap.Error)
		}
	}

	snap.Total = len(nodes)

	// 3) 测速
	r.setStage("测速中")
	engine, err := speedtest.Run(ctx, cfg.SpeedTest, r.store.Dir(), nodes, r.log, reporter)
	if err != nil {
		snap.Error = err.Error()
	}
	if engine != nil {
		snap.Engine = engine.Engine
	}
	if ctx.Err() != nil {
		snap.DurationMS = time.Since(started).Milliseconds()
		snap.Nodes = nodes
		r.finish(snap, cfg)
		return snap, ctx.Err()
	}

	// 4) 筛选可用节点
	r.setStage("筛选可用节点")
	alive := filterAlive(nodes, cfg)
	snap.Alive = len(alive)
	r.logf("INFO", "测速完成：%d/%d 个节点可用（引擎：%s）", len(alive), len(nodes), snap.Engine)

	if cfg.SpeedTest.MinAlive > 0 && len(alive) < cfg.SpeedTest.MinAlive {
		r.logf("WARN", "可用节点数量（%d）低于阈值 %d，请检查订阅或网络环境", len(alive), cfg.SpeedTest.MinAlive)
	}

	// 5) 生成配置
	snap.Nodes = nodes
	if len(alive) == 0 {
		snap.Error = "没有可用节点，已保留上一次生成的配置文件；请查看日志中的自检结论"
		r.logf("ERROR", "%s", snap.Error)
		snap.DurationMS = time.Since(started).Milliseconds()
		r.finish(snap, cfg)
		return snap, errors.New(snap.Error)
	}

	if cfg.Pool.MaxNodes > 0 && len(alive) > cfg.Pool.MaxNodes {
		alive = alive[:cfg.Pool.MaxNodes]
	}
	snap.Active = len(alive)

	outputPath := r.store.ResolvePath(cfg.Output.Path)
	templatePath := ""
	if cfg.Output.Template != "" {
		templatePath = r.store.ResolvePath(cfg.Output.Template)
	}
	r.setStage("生成配置")
	data, err := generator.Build(cfg.Output, cfg.SpeedTest, alive, templatePath)
	if err != nil {
		snap.Error = err.Error()
		snap.DurationMS = time.Since(started).Milliseconds()
		r.finish(snap, cfg)
		return snap, err
	}
	// 整轮任务到此才算完成：校验通过后原子替换，期间订阅地址始终可读到完整的旧文件。
	report, err := generator.Publish(outputPath, data, cfg.Output.KeepBackup)
	if err != nil {
		snap.Error = err.Error()
		r.logf("ERROR", "%s", err)
		snap.DurationMS = time.Since(started).Milliseconds()
		r.finish(snap, cfg)
		return snap, err
	}
	snap.Output = report.Path
	snap.DurationMS = time.Since(started).Milliseconds()
	if report.BackupPath != "" {
		r.logf("INFO", "上一版配置已备份为 %s", report.BackupPath)
	}
	r.logf("INFO", "整轮任务完成，已原子替换 %s（%d 个节点，%.1f KB，耗时 %.1fs）",
		report.Path, len(alive), float64(report.Size)/1024, time.Since(started).Seconds())

	r.finish(snap, cfg)
	return snap, nil
}

func (r *Runner) finish(snap *Snapshot, cfg *config.Config) {
	r.mu.Lock()
	r.last = snap
	r.mu.Unlock()
}

func (r *Runner) fetchAll(ctx context.Context, cfg *config.Config) ([]*model.Node, []SubResult) {
	enabled := make([]config.Subscription, 0, len(cfg.Subscriptions))
	for _, s := range cfg.Subscriptions {
		if !s.Disabled {
			enabled = append(enabled, s)
		}
	}
	results := make([]SubResult, 0, len(cfg.Subscriptions))
	if len(enabled) == 0 {
		r.logf("ERROR", "没有启用任何订阅，请先在管理页面添加订阅地址")
		return nil, results
	}

	r.logf("INFO", "开始拉取 %d 个订阅", len(enabled))
	nodes := make([]*model.Node, 0, 256)
	for i, sub := range enabled {
		r.setProgress("拉取订阅", i+1, len(enabled))
		res := SubResult{ID: sub.ID, Name: sub.Name, URL: sub.URL}
		resp, err := r.fetcher.Fetch(ctx, sub)
		if err != nil {
			res.Error = err.Error()
			r.logf("WARN", "订阅「%s」拉取失败：%v", sub.Name, err)
		} else {
			res.OK = true
			res.NodeCount = len(resp.Nodes)
			res.Traffic = resp.Traffic
			nodes = append(nodes, resp.Nodes...)
			r.logf("INFO", "订阅「%s」解析到 %d 个节点", sub.Name, len(resp.Nodes))
		}
		results = append(results, res)
	}
	return nodes, results
}

// filterAlive 应用延迟/速度阈值，返回按质量排序的可用节点。
func filterAlive(nodes []*model.Node, cfg *config.Config) []*model.Node {
	st := cfg.SpeedTest
	alive := make([]*model.Node, 0, len(nodes))
	for _, n := range nodes {
		if !n.Alive || n.Delay <= 0 {
			continue
		}
		if st.MaxDelay > 0 && n.Delay > st.MaxDelay {
			n.Alive = false
			n.Error = fmt.Sprintf("延迟 %dms 超过阈值", n.Delay)
			continue
		}
		if st.Download.Enabled && st.Download.MinSpeedMB > 0 && n.Speed > 0 && n.Speed < st.Download.MinSpeedMB {
			n.Alive = false
			n.Error = fmt.Sprintf("速度 %.2fMB/s 低于阈值", n.Speed)
			continue
		}
		alive = append(alive, n)
	}
	model.SortByQuality(alive)
	return alive
}

// taskReporter 把测速过程转发到日志与进度。
type taskReporter struct {
	r *Runner
}

func (t *taskReporter) Log(level, format string, args ...any) {
	t.r.logf(level, format, args...)
}

func (t *taskReporter) Progress(stage string, done, total int) {
	t.r.setProgress(stage, done, total)
}
