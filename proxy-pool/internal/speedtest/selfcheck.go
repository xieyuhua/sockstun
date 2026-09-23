package speedtest

import (
	"context"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"proxypool/internal/model"
)

// maxLoggedFailures 单轮测速逐条打印失败节点的上限，其余只做统计，避免几百行刷屏。
const maxLoggedFailures = 20

// defaultDelayURLs 候选探测地址（均返回 204），用于在默认地址不可达时自动换址。
var defaultDelayURLs = []string{
	"http://www.gstatic.com/generate_204",
	"http://cp.cloudflare.com/generate_204",
	"http://connectivitycheck.gstatic.com/generate_204",
	"http://connect.rom.miui.com/generate_204",
}

// recordFailure 记录一次失败并返回是否还需要打印该条日志。
func (t *CoreTester) recordFailure(reason string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.reasons == nil {
		t.reasons = map[string]int{}
	}
	t.reasons[classifyFailure(reason)]++
	t.failCount++
	return t.failCount <= maxLoggedFailures
}

func (t *CoreTester) resetStats() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.reasons = map[string]int{}
	t.failCount = 0
}

// failureSummary 汇总失败原因，例如「超时 ×440、握手或协议错误 ×8」。
func (t *CoreTester) failureSummary() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.reasons) == 0 {
		return ""
	}
	type item struct {
		reason string
		count  int
	}
	list := make([]item, 0, len(t.reasons))
	for reason, count := range t.reasons {
		list = append(list, item{reason, count})
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].count != list[j].count {
			return list[i].count > list[j].count
		}
		return list[i].reason < list[j].reason
	})
	parts := make([]string, 0, len(list))
	for _, it := range list {
		parts = append(parts, fmt.Sprintf("%s ×%d", it.reason, it.count))
	}
	return strings.Join(parts, "、")
}

// classifyFailure 把内核返回的英文错误归类成可读原因。
func classifyFailure(msg string) string {
	switch {
	case strings.Contains(msg, "Timeout"):
		return "超时"
	case strings.Contains(msg, "An error occurred in the delay test"):
		return "握手或协议错误"
	case strings.Contains(msg, "context deadline"):
		return "本地请求超时"
	case strings.Contains(msg, "connection refused"), strings.Contains(msg, "connectex"):
		return "连接被拒绝"
	default:
		return "其它错误"
	}
}

// collectCoreLogs 收集内核进程中的 warning/error 日志，用于在探测失败时给出原因。
func (t *CoreTester) collectCoreLogs(inst *coreInstance) {
	if inst == nil || inst.tail == nil {
		return
	}
	lines := extractCoreIssues(inst.tail.String())
	if len(lines) == 0 {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.coreLogs = append(t.coreLogs, lines...)
	if len(t.coreLogs) > 20 {
		t.coreLogs = t.coreLogs[len(t.coreLogs)-20:]
	}
}

// coreLogExcerpt 返回最近 limit 条内核错误日志（去重后）。
func (t *CoreTester) coreLogExcerpt(limit int) []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.coreLogs) == 0 || limit <= 0 {
		return nil
	}
	seen := map[string]bool{}
	out := make([]string, 0, limit)
	for i := len(t.coreLogs) - 1; i >= 0 && len(out) < limit; i-- {
		line := t.coreLogs[i]
		if seen[line] {
			continue
		}
		seen[line] = true
		out = append(out, line)
	}
	// 反转成时间顺序
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out
}

// extractCoreIssues 从内核原始日志中抽取 warning/error 行，并只保留可读的 msg 内容。
func extractCoreIssues(text string) []string {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	var out []string
	for _, line := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		line = strings.TrimSpace(line)
		if !strings.Contains(line, "level=warning") && !strings.Contains(line, "level=error") {
			continue
		}
		msg := line
		if idx := strings.Index(line, `msg="`); idx >= 0 {
			msg = strings.TrimSuffix(line[idx+len(`msg="`):], `"`)
		}
		msg = strings.TrimSpace(msg)
		if msg == "" {
			continue
		}
		out = append(out, shorten(msg))
	}
	return out
}

func countAlive(nodes []*model.Node) int {
	count := 0
	for _, n := range nodes {
		if n.Alive && n.Delay > 0 {
			count++
		}
	}
	return count
}

func resetNodes(nodes []*model.Node) {
	for _, n := range nodes {
		n.Delay, n.Speed, n.Alive, n.Error = -1, 0, false, ""
	}
}

// sampleNodes 均匀抽样，便于用少量节点代表整个节点池。
func sampleNodes(nodes []*model.Node, limit int) []*model.Node {
	if limit <= 0 || len(nodes) == 0 {
		return nil
	}
	if len(nodes) <= limit {
		return append([]*model.Node(nil), nodes...)
	}
	out := make([]*model.Node, 0, limit)
	step := float64(len(nodes)) / float64(limit)
	for i := 0; i < limit; i++ {
		idx := int(float64(i) * step)
		if idx >= len(nodes) {
			idx = len(nodes) - 1
		}
		out = append(out, nodes[idx])
	}
	return out
}

// delayURLCandidates 返回候选探测地址：配置的 delay_urls 优先，其次内置候选。
func (t *CoreTester) delayURLCandidates() []string {
	candidates := make([]string, 0, len(defaultDelayURLs)+2)
	seen := map[string]bool{}
	add := func(url string) {
		url = strings.TrimSpace(url)
		if url == "" || seen[url] {
			return
		}
		seen[url] = true
		candidates = append(candidates, url)
	}
	add(t.cfg.DelayURL)
	for _, url := range t.cfg.DelayURLs {
		add(url)
	}
	for _, url := range defaultDelayURLs {
		add(url)
	}
	return candidates
}

// tcpReachabilitySample 抽样检查本机到节点端口的 TCP 可达性。
func (t *CoreTester) tcpReachabilitySample(ctx context.Context, nodes []*model.Node, limit int, timeout time.Duration) (int, int) {
	sample := sampleNodes(nodes, limit)
	if len(sample) == 0 {
		return 0, 0
	}
	ok := 0
	for _, n := range sample {
		if ctx.Err() != nil {
			break
		}
		if _, err := dialLatency(ctx, n.Endpoint(), timeout); err == nil {
			ok++
		}
	}
	return ok, len(sample)
}

// probeCandidates 用一个仅含抽样节点的内核实例，依次测试各候选探测地址：
// 既统计通过节点的成功数，也测试本机直连（DIRECT）的可达性。
func (t *CoreTester) probeCandidates(ctx context.Context, nodes []*model.Node) (best string, bestOK, total int, direct []string) {
	candidates := t.delayURLCandidates()
	sample := sampleNodes(nodes, 6)
	if len(candidates) == 0 || len(sample) == 0 {
		return "", 0, 0, nil
	}

	dir, err := os.MkdirTemp("", "proxypool-probe-")
	if err != nil {
		t.rep.Log("WARN", "自检失败：%v", err)
		return "", 0, 0, nil
	}
	defer os.RemoveAll(dir)

	inst, err := t.startInstance(ctx, dir, sample)
	if err != nil {
		t.rep.Log("WARN", "自检内核启动失败：%v", err)
		return "", 0, 0, nil
	}
	defer inst.Stop()

	probeTimeout := 3 * time.Second
	for _, candidate := range candidates {
		if ctx.Err() != nil {
			break
		}
		ok := probeNodesParallel(ctx, inst, sample, candidate, probeTimeout, 2)
		if _, err := inst.Delay(ctx, "DIRECT", probeTimeout, candidate); err == nil {
			direct = append(direct, fmt.Sprintf("%s（本机直连可达）", candidate))
		} else {
			direct = append(direct, fmt.Sprintf("%s（本机直连不可达）", candidate))
		}
		if ok > bestOK {
			best, bestOK = candidate, ok
		}
	}
	return best, bestOK, len(sample), direct
}

// probeNodesParallel 并发用抽样节点探测同一个地址，达到 successLimit 个成功后立即提前结束。
func probeNodesParallel(ctx context.Context, inst *coreInstance, nodes []*model.Node, target string, timeout time.Duration, successLimit int) int {
	if len(nodes) == 0 {
		return 0
	}
	probeCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	var mu sync.Mutex
	ok := 0
	var wg sync.WaitGroup
	for _, n := range nodes {
		wg.Add(1)
		go func(name string) {
			defer wg.Done()
			if _, err := inst.Delay(probeCtx, name, timeout, target); err != nil {
				return
			}
			mu.Lock()
			ok++
			if ok >= successLimit {
				cancel()
			}
			mu.Unlock()
		}(n.Name())
	}
	wg.Wait()
	return ok
}

// selfCheck 在一轮测速全部失败时给出可执行的诊断结论，并在可能时自动换址重试。
func (t *CoreTester) selfCheck(ctx context.Context, nodes []*model.Node) {
	t.rep.Log("WARN", "本轮没有任何节点探测成功，开始自检…")

	reach, reachTotal := t.tcpReachabilitySample(ctx, nodes, 6, 2*time.Second)
	if reachTotal > 0 {
		t.rep.Log("INFO", "本机到节点端口的 TCP 连通性抽检：%d/%d 可达", reach, reachTotal)
	}
	if summary := t.failureSummary(); summary != "" {
		t.rep.Log("INFO", "探测失败原因统计：%s", summary)
	}
	if logs := t.coreLogExcerpt(6); len(logs) > 0 {
		t.rep.Log("INFO", "内核日志摘录：\n  %s", strings.Join(logs, "\n  "))
	}
	if ctx.Err() != nil {
		return
	}

	best, bestOK, probeTotal, direct := t.probeCandidates(ctx, nodes)
	for _, line := range direct {
		t.rep.Log("INFO", "探测地址可达性：%s", line)
	}

	if reachTotal > 0 && reach == 0 {
		t.rep.Log("WARN", "本机无法连接任何节点端口：请检查网络与防火墙（是否拦截了 mihomo 内核进程），或确认订阅是否已失效")
		return
	}
	if best == "" || bestOK == 0 {
		t.rep.Log("WARN", "所有候选探测地址都无法探测成功：节点可能已失效，或其握手参数不被内核支持")
		if reach > 0 {
			t.rep.Log("WARN", "注意：本机端口可达但内核探测全部失败——若你的客户端能正常使用这些节点，"+
				"请检查安全软件/防火墙是否拦截了 mihomo 内核进程的出站连接，或尝试调小 speedtest.concurrency 与 delay_workers")
		}
		t.logHints()
		return
	}
	if best == t.cfg.DelayURL {
		t.rep.Log("WARN", "当前探测地址 %s 抽样可达，但节点全部失败：失败节点应当确实不可用", best)
		return
	}

	t.rep.Log("INFO", "发现更合适的探测地址 %s（抽样成功 %d/%d），换地址重新测速…", best, bestOK, probeTotal)
	resetNodes(nodes)
	if err := t.testOnce(ctx, nodes, best); err != nil {
		t.rep.Log("WARN", "换用 %s 重试失败：%v", best, err)
		return
	}
	t.rep.Log("INFO", "换用 %s 后可用节点 %d 个；可在配置 speedtest.delay_url 中固定该地址", best, countAlive(nodes))
	if countAlive(nodes) == 0 {
		t.logHints()
	}
}

func (t *CoreTester) logHints() {
	t.rep.Log("INFO", "排查建议：1) 在管理页面对订阅点「测试」确认能解析到节点；"+
		"2) 在 speedtest.delay_urls 中配置多个候选探测地址；"+
		"3) 若只想确认端口是否可达，可临时把 speedtest.mode 改为 tcp；"+
		"4) 适当调大 speedtest.timeout 或调小 speedtest.concurrency 后重试")
}
