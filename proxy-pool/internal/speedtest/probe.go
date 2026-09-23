package speedtest

import (
	"context"
	"os"
	"time"

	"proxypool/internal/config"
	"proxypool/internal/model"
)

// ProbeResult 单个探测地址的测试结果。
type ProbeResult struct {
	URL   string `json:"url"`
	OK    bool   `json:"ok"`
	Delay int    `json:"delay"`
	Error string `json:"error,omitempty"`
}

// ProbeReport 单节点探测报告。
type ProbeReport struct {
	Node     string `json:"node"`
	Endpoint string `json:"endpoint"`
	Type     string `json:"type"`

	// TCPReachable 本机能否直接与节点端口建立 TCP 连接（绕开内核，用于区分
	// 「本机到节点不通」与「节点通但代理不通」）。
	TCPReachable bool   `json:"tcp_reachable"`
	TCPDelayMS   int    `json:"tcp_delay_ms"`
	TCPError     string `json:"tcp_error,omitempty"`

	Results  []ProbeResult `json:"results"`
	Direct   []ProbeResult `json:"direct"`
	CoreLogs []string      `json:"core_logs,omitempty"`
}

// ProbeNode 用内核单独探测一个节点：依次尝试各个候选探测地址，
// 同时给出本机直连（DIRECT）的结果与内核自身打印的错误日志，便于定位「为什么这个节点被判为不可用」。
func ProbeNode(ctx context.Context, cfg config.SpeedTestConfig, baseDir string, node *model.Node, urls []string, rep Reporter) (*ProbeReport, error) {
	if node == nil {
		return nil, os.ErrInvalid
	}
	if rep == nil {
		rep = &bufferReporter{}
	}
	tester := NewCore(cfg, baseDir, rep)
	if err := tester.Prepare(ctx); err != nil {
		return nil, err
	}
	if len(urls) == 0 {
		urls = tester.delayURLCandidates()
	}

	dir, err := os.MkdirTemp("", "proxypool-probe-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)

	inst, err := tester.startInstance(ctx, dir, []*model.Node{node})
	if err != nil {
		return nil, err
	}
	defer inst.Stop()

	timeout := cfg.Timeout.D()
	if timeout <= 0 {
		timeout = 5 * time.Second
	}

	report := &ProbeReport{Node: node.Name(), Endpoint: node.Endpoint(), Type: node.Type()}
	if delay, err := dialLatency(ctx, node.Endpoint(), timeout); err != nil {
		report.TCPError = shorten(err.Error())
	} else {
		report.TCPReachable = true
		report.TCPDelayMS = delay
	}
	for _, target := range urls {
		result := ProbeResult{URL: target}
		if delay, err := inst.Delay(ctx, node.Name(), timeout, target); err != nil {
			result.Error = shorten(err.Error())
		} else {
			result.OK, result.Delay = true, delay
		}
		report.Results = append(report.Results, result)
	}
	for _, target := range urls {
		result := ProbeResult{URL: target}
		if delay, err := inst.Delay(ctx, "DIRECT", timeout, target); err != nil {
			result.Error = shorten(err.Error())
		} else {
			result.OK, result.Delay = true, delay
		}
		report.Direct = append(report.Direct, result)
	}

	report.CoreLogs = extractCoreIssues(inst.tail.String())
	if len(report.CoreLogs) > 8 {
		report.CoreLogs = report.CoreLogs[len(report.CoreLogs)-8:]
	}
	return report, nil
}
