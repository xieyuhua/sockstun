package speedtest

import (
	"strings"
	"testing"

	"proxypool/internal/config"
	"proxypool/internal/model"
)

func fakeNodes(n int) []*model.Node {
	nodes := make([]*model.Node, 0, n)
	for i := 0; i < n; i++ {
		nodes = append(nodes, model.New(map[string]any{
			"name":   "n",
			"type":   "ss",
			"server": "127.0.0.1",
			"port":   10000 + i,
		}))
	}
	return nodes
}

func TestSampleNodes(t *testing.T) {
	nodes := fakeNodes(100)
	sample := sampleNodes(nodes, 10)
	if len(sample) != 10 {
		t.Fatalf("抽样数量错误: %d", len(sample))
	}
	seen := map[*model.Node]bool{}
	for _, n := range sample {
		if seen[n] {
			t.Fatal("抽样出现重复节点")
		}
		seen[n] = true
	}
	if sample[0] != nodes[0] {
		t.Fatal("抽样应从第一个节点开始")
	}
	if got := len(sampleNodes(nodes, 200)); got != 100 {
		t.Fatalf("抽样上限超过总数时应返回全部: %d", got)
	}
	if sampleNodes(nodes, 0) != nil {
		t.Fatal("limit 为 0 时应返回 nil")
	}
}

func TestClassifyFailure(t *testing.T) {
	cases := map[string]string{
		"Timeout":                                     "超时",
		"An error occurred in the delay test":         "握手或协议错误",
		"Get \"http://x\": context deadline exceeded": "本地请求超时",
		"something else":                              "其它错误",
	}
	for input, want := range cases {
		if got := classifyFailure(input); got != want {
			t.Fatalf("classifyFailure(%q) = %q, 期望 %q", input, got, want)
		}
	}
}

func TestFailureStatsAreLimitedAndSummarized(t *testing.T) {
	tester := NewCore(config.SpeedTestConfig{}, "", nil)
	logged := 0
	for i := 0; i < 30; i++ {
		if tester.recordFailure("Timeout") {
			logged++
		}
	}
	for i := 0; i < 3; i++ {
		if tester.recordFailure("An error occurred in the delay test") {
			logged++
		}
	}
	if logged != maxLoggedFailures {
		t.Fatalf("逐条日志数量应为 %d，实际 %d", maxLoggedFailures, logged)
	}
	summary := tester.failureSummary()
	if !strings.Contains(summary, "超时 ×30") || !strings.Contains(summary, "握手或协议错误 ×3") {
		t.Fatalf("失败原因汇总不正确: %s", summary)
	}
	if !strings.HasPrefix(summary, "超时") {
		t.Fatalf("汇总应按数量倒序: %s", summary)
	}

	tester.resetStats()
	if tester.failureSummary() != "" {
		t.Fatal("重置后统计应为空")
	}
	if tester.recordFailure("Timeout") != true {
		t.Fatal("重置后应重新开始打印")
	}
}

func TestDelayURLCandidates(t *testing.T) {
	cfg := config.SpeedTestConfig{
		DelayURL:  "http://custom.example/generate_204",
		DelayURLs: []string{"http://second.example/generate_204", "http://custom.example/generate_204"},
	}
	tester := NewCore(cfg, "", nil)
	got := tester.delayURLCandidates()
	if len(got) == 0 || got[0] != cfg.DelayURL {
		t.Fatalf("当前探测地址应排在最前: %v", got)
	}
	if got[1] != cfg.DelayURLs[0] {
		t.Fatalf("自定义候选地址应紧随其后: %v", got)
	}
	seen := map[string]bool{}
	for _, url := range got {
		if seen[url] {
			t.Fatalf("候选地址出现重复: %v", got)
		}
		seen[url] = true
	}
	hasDefault := false
	for _, url := range got {
		if url == defaultDelayURLs[0] {
			hasDefault = true
		}
	}
	if !hasDefault {
		t.Fatalf("应补充内置候选地址: %v", got)
	}
}

func TestCountAliveAndReset(t *testing.T) {
	nodes := fakeNodes(3)
	nodes[0].Alive, nodes[0].Delay = true, 100
	nodes[1].Alive, nodes[1].Delay = true, -1
	nodes[2].Alive, nodes[2].Delay = false, 0
	if got := countAlive(nodes); got != 1 {
		t.Fatalf("可用节点统计错误: %d", got)
	}
	nodes[0].Speed, nodes[0].Error = 5, "x"
	resetNodes(nodes)
	for _, n := range nodes {
		if n.Alive || n.Delay != -1 || n.Speed != 0 || n.Error != "" {
			t.Fatalf("节点状态未重置: %+v", n)
		}
	}
}
