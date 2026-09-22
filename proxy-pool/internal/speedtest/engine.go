// Package speedtest 提供两种测速引擎：
//   - core：拉起 mihomo 内核，通过其 REST API 对每个节点做真实的 HTTP 延迟探测与下载测速，
//     只有真正能连通外网的节点才会被保留（推荐）。
//   - tcp：仅做 TCP 握手连通性探测，无需内核，但只能证明端口可达。
package speedtest

import (
	"context"
	"fmt"
	"strings"

	"proxypool/internal/config"
	"proxypool/internal/logx"
	"proxypool/internal/model"
)

// Reporter 测速过程回调。
type Reporter interface {
	Log(level, format string, args ...any)
	Progress(stage string, done, total int)
}

// Result 测速执行结果。
type Result struct {
	Engine   string
	Tested   int
	Alive    int
	Fallback bool
}

// Run 执行测速：优先使用内核引擎，失败时自动回落到 TCP 探测。
func Run(ctx context.Context, cfg config.SpeedTestConfig, baseDir string, nodes []*model.Node, log *logx.Buffer, rep Reporter) (*Result, error) {
	if len(nodes) == 0 {
		return &Result{}, nil
	}
	if rep == nil {
		rep = &bufferReporter{log: log}
	}
	res := &Result{Engine: cfg.Mode}

	if strings.EqualFold(cfg.Mode, config.ModeCore) {
		core := NewCore(cfg, baseDir, rep)
		if err := core.Prepare(ctx); err != nil {
			log.Warn("内核引擎不可用（%v），已回落到 TCP 连通性探测；如需真实速度测试请放置 mihomo 内核", err)
			res.Fallback = true
			res.Engine = config.ModeTCP
		} else if err := core.Test(ctx, nodes); err != nil {
			if ctx.Err() != nil {
				return res, ctx.Err()
			}
			log.Warn("内核测速中断：%v", err)
			res.Fallback = true
		} else {
			res.Engine = config.ModeCore
			countResults(res, nodes)
			return res, nil
		}
	}

	tcp := NewTCP(cfg, rep)
	tcp.OnlyUntested = res.Fallback
	if err := tcp.Test(ctx, nodes); err != nil {
		return res, err
	}
	countResults(res, nodes)
	return res, nil
}

func countResults(res *Result, nodes []*model.Node) {
	res.Tested, res.Alive = 0, 0
	for _, n := range nodes {
		if n.Delay > 0 {
			res.Tested++
		}
		if n.Alive {
			res.Alive++
		}
	}
}

type bufferReporter struct {
	log *logx.Buffer
}

func (b *bufferReporter) Log(level, format string, args ...any) {
	if b.log == nil {
		return
	}
	switch strings.ToUpper(level) {
	case "WARN":
		b.log.Warn(format, args...)
	case "ERROR":
		b.log.Error(format, args...)
	default:
		b.log.Info(format, args...)
	}
}

func (b *bufferReporter) Progress(stage string, done, total int) {
	if b.log != nil && total > 0 && (done == total || done%20 == 0) {
		b.log.Debug("%s 进度 %d/%d", stage, done, total)
	}
}

func delayTimeout(cfg config.SpeedTestConfig) int {
	ms := int(cfg.Timeout.D().Milliseconds())
	if ms <= 0 {
		ms = 5000
	}
	return ms
}

func describeNode(n *model.Node) string {
	return fmt.Sprintf("%s(%s)", n.Name(), n.Endpoint())
}
