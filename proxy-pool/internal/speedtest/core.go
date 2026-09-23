package speedtest

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"gopkg.in/yaml.v3"

	"proxypool/internal/config"
	"proxypool/internal/model"
)

// CoreTester 使用 mihomo 内核做真实可用性/速度测试。
type CoreTester struct {
	cfg     config.SpeedTestConfig
	baseDir string
	rep     Reporter
	binary  string

	mu        sync.Mutex
	reasons   map[string]int
	failCount int
	coreLogs  []string
}

// NewCore 创建内核测速器。
func NewCore(cfg config.SpeedTestConfig, baseDir string, rep Reporter) *CoreTester {
	return &CoreTester{cfg: cfg, baseDir: baseDir, rep: rep, reasons: map[string]int{}}
}

// Prepare 准备内核可执行文件。
func (t *CoreTester) Prepare(ctx context.Context) error {
	path, err := EnsureCore(ctx, t.cfg.Core, t.baseDir, t.rep)
	if err != nil {
		return err
	}
	t.binary = path
	t.rep.Log("INFO", "已就绪内核：%s", path)
	return nil
}

// Test 对全部节点执行测速。
// 若整轮探测没有任何节点成功，会先做一次自检（TCP 端口可达性 + 探测地址可达性），
// 并在发现更合适的探测地址时自动换地址重试一轮。
func (t *CoreTester) Test(ctx context.Context, nodes []*model.Node) error {
	if t.binary == "" {
		if err := t.Prepare(ctx); err != nil {
			return err
		}
	}
	if err := t.testOnce(ctx, nodes, t.cfg.DelayURL); err != nil {
		return err
	}
	if ctx.Err() != nil || countAlive(nodes) > 0 {
		return nil
	}
	t.selfCheck(ctx, nodes)
	return nil
}

// testOnce 使用指定探测地址完成一轮「延迟 + 下载」测速。
func (t *CoreTester) testOnce(ctx context.Context, nodes []*model.Node, delayURL string) error {
	t.resetStats()
	batches := splitBatches(nodes, t.cfg.Concurrency)
	t.rep.Log("INFO", "启动 %d 个内核实例并发测速，共 %d 个节点，探测地址 %s", len(batches), len(nodes), delayURL)

	var wg sync.WaitGroup
	errCh := make(chan error, len(batches))
	for i, batch := range batches {
		wg.Add(1)
		go func(idx int, batch []*model.Node) {
			defer wg.Done()
			if err := t.runBatch(ctx, idx, batch, delayURL); err != nil {
				errCh <- fmt.Errorf("实例%d: %w", idx+1, err)
			}
		}(i, batch)
	}
	wg.Wait()
	close(errCh)

	var errs []string
	for err := range errCh {
		errs = append(errs, err.Error())
	}
	if len(errs) > 0 && len(errs) == len(batches) {
		return errors.New(strings.Join(errs, "; "))
	}
	for _, msg := range errs {
		t.rep.Log("WARN", "部分批次测速失败：%s", msg)
	}
	return nil
}

func (t *CoreTester) runBatch(ctx context.Context, idx int, nodes []*model.Node, delayURL string) error {
	dir, err := os.MkdirTemp("", fmt.Sprintf("proxypool-%d-", idx))
	if err != nil {
		return fmt.Errorf("创建临时目录失败: %w", err)
	}
	defer os.RemoveAll(dir)

	inst, err := t.startInstance(ctx, dir, nodes)
	if err != nil {
		return err
	}
	defer inst.Stop()
	defer t.collectCoreLogs(inst)

	t.delayBatch(ctx, inst, nodes, delayURL)
	if t.cfg.Download.Enabled {
		t.speedBatch(ctx, inst, nodes)
	}
	return nil
}

// delayBatch 并发执行 HTTP 延迟探测。
func (t *CoreTester) delayBatch(ctx context.Context, inst *coreInstance, nodes []*model.Node, delayURL string) {
	timeout := t.cfg.Timeout.D()
	workers := t.cfg.DelayWorkers
	if workers <= 0 {
		workers = 8
	}
	if workers > len(nodes) {
		workers = len(nodes)
	}
	if workers == 0 {
		return
	}

	jobs := make(chan *model.Node)
	var wg sync.WaitGroup
	var done int64
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := range jobs {
				delay, err := inst.Delay(ctx, n.Name(), timeout, delayURL)
				if err != nil {
					n.Delay = -1
					n.Alive = false
					n.Error = shorten(err.Error())
					if t.recordFailure(n.Error) {
						t.rep.Log("INFO", "节点 %s 探测失败：%s", describeNode(n), n.Error)
					}
				} else {
					n.Delay = delay
					n.Alive = true
					n.Error = ""
				}
				t.rep.Progress("延迟测速", int(atomic.AddInt64(&done, 1)), len(nodes))
			}
		}()
	}

	for _, n := range nodes {
		select {
		case jobs <- n:
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return
		}
	}
	close(jobs)
	wg.Wait()
}

// speedBatch 对延迟最优的节点做下载速度测试。
func (t *CoreTester) speedBatch(ctx context.Context, inst *coreInstance, nodes []*model.Node) {
	candidates := make([]*model.Node, 0, len(nodes))
	for _, n := range nodes {
		if !n.Alive {
			continue
		}
		if t.cfg.MaxDelay > 0 && n.Delay > t.cfg.MaxDelay {
			continue
		}
		candidates = append(candidates, n)
	}
	if len(candidates) == 0 {
		return
	}
	sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].Delay < candidates[j].Delay })
	if t.cfg.Download.Top > 0 && len(candidates) > t.cfg.Download.Top {
		candidates = candidates[:t.cfg.Download.Top]
	}
	if len(candidates) == 0 {
		return
	}

	groups := inst.dlGroups
	if len(groups) > len(candidates) {
		groups = groups[:len(candidates)]
	}
	if len(groups) == 0 {
		return
	}

	jobs := make(chan *model.Node)
	var wg sync.WaitGroup
	var done int64
	for _, group := range groups {
		wg.Add(1)
		go func(group string) {
			defer wg.Done()
			for n := range jobs {
				speed, err := inst.Speed(ctx, group, n.Name(), t.cfg.Download.URL, t.cfg.Download.Duration.D())
				n.Speed = speed
				if err != nil {
					// 延迟探测已经证明节点可以走通外网，下载测速失败只作为质量信息，
					// 不判定节点不可用（避免测速文件本身不可达时误杀好节点）。
					if speed > 0 {
						n.Error = shorten(fmt.Sprintf("测速中断: %v", err))
					} else {
						n.Error = shorten(fmt.Sprintf("下载测速失败: %v", err))
					}
				}
				t.rep.Progress("下载测速", int(atomic.AddInt64(&done, 1)), len(candidates))
			}
		}(group)
	}

	for _, n := range candidates {
		select {
		case jobs <- n:
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return
		}
	}
	close(jobs)
	wg.Wait()
}

// ---------- 内核实例 ----------

type coreInstance struct {
	dir       string
	mixedPort int
	ctrlPort  int
	secret    string
	cmd       *exec.Cmd
	cancel    context.CancelFunc
	apiClient *http.Client
	pxClient  *http.Client
	dlGroups  []string
	exited    chan struct{}
	exitErr   error
	tail      *tailBuffer
}

type testProxyGroup struct {
	Name    string   `yaml:"name"`
	Type    string   `yaml:"type"`
	Proxies []string `yaml:"proxies"`
}

type testConfig struct {
	MixedPort          int              `yaml:"mixed-port"`
	AllowLan           bool             `yaml:"allow-lan"`
	BindAddress        string           `yaml:"bind-address"`
	Mode               string           `yaml:"mode"`
	LogLevel           string           `yaml:"log-level"`
	IPv6               bool             `yaml:"ipv6"`
	UnifiedDelay       bool             `yaml:"unified-delay"`
	FindProcessMode    string           `yaml:"find-process-mode"`
	ExternalController string           `yaml:"external-controller"`
	Secret             string           `yaml:"secret"`
	Proxies            []map[string]any `yaml:"proxies"`
	ProxyGroups        []testProxyGroup `yaml:"proxy-groups"`
	Rules              []string         `yaml:"rules"`
	DNS                map[string]any   `yaml:"dns,omitempty"`
}

func (t *CoreTester) startInstance(ctx context.Context, dir string, nodes []*model.Node) (*coreInstance, error) {
	mixedPort, err := freePort()
	if err != nil {
		return nil, err
	}
	ctrlPort, err := freePort()
	if err != nil {
		return nil, err
	}
	secret := randomHex(16)

	names := make([]string, 0, len(nodes))
	for _, n := range nodes {
		names = append(names, n.Name())
	}

	groupCount := len(nodes)
	if groupCount > 4 {
		groupCount = 4
	}
	if groupCount < 1 {
		groupCount = 1
	}
	groups := make([]testProxyGroup, 0, groupCount)
	dlGroups := make([]string, 0, groupCount)
	mainGroup := "__PROXYPOOL_MAIN__"
	for i := 0; i < groupCount; i++ {
		g := testProxyGroup{Name: fmt.Sprintf("__PROXYPOOL_DL%d__", i), Type: "select", Proxies: names}
		groups = append(groups, g)
		dlGroups = append(dlGroups, g.Name)
	}
	groups = append(groups, testProxyGroup{Name: mainGroup, Type: "select", Proxies: names})

	proxies := make([]map[string]any, 0, len(nodes))
	for _, n := range nodes {
		proxies = append(proxies, n.Raw)
	}

	tc := testConfig{
		MixedPort:          mixedPort,
		AllowLan:           false,
		BindAddress:        "127.0.0.1",
		Mode:               "rule",
		LogLevel:           "warning",
		IPv6:               false,
		UnifiedDelay:       true,
		FindProcessMode:    "off",
		ExternalController: fmt.Sprintf("127.0.0.1:%d", ctrlPort),
		Secret:             secret,
		Proxies:            proxies,
		ProxyGroups:        groups,
		Rules:              []string{"MATCH," + mainGroup},
		// 注意：这里不能配置 dns.fallback，否则会启用基于 GeoIP 的 fallback-filter，
		// 内核启动时会去下载 country.mmdb（网络受限时会导致启动挂起）。
		DNS: map[string]any{
			"enable":             true,
			"ipv6":               false,
			"default-nameserver": []string{"223.5.5.5", "119.29.29.29"},
			"nameserver":         []string{"223.5.5.5", "119.29.29.29", "1.1.1.1"},
		},
	}
	data, err := yaml.Marshal(&tc)
	if err != nil {
		return nil, fmt.Errorf("生成测试配置失败: %w", err)
	}
	cfgPath := filepath.Join(dir, "test-config.yaml")
	if err := os.WriteFile(cfgPath, data, 0o644); err != nil {
		return nil, fmt.Errorf("写入测试配置失败: %w", err)
	}

	procCtx, cancel := context.WithCancel(context.Background())
	args := []string{"-d", dir, "-f", cfgPath}
	args = append(args, t.cfg.Core.Args...)

	cmd := exec.CommandContext(procCtx, t.binary, args...)
	cmd.Dir = dir
	tail := newTailBuffer(16 << 10)
	cmd.Stdout = tail
	cmd.Stderr = tail

	if err := cmd.Start(); err != nil {
		cancel()
		return nil, fmt.Errorf("启动内核失败: %w", err)
	}

	inst := &coreInstance{
		dir:       dir,
		mixedPort: mixedPort,
		ctrlPort:  ctrlPort,
		secret:    secret,
		cmd:       cmd,
		cancel:    cancel,
		dlGroups:  dlGroups,
		exited:    make(chan struct{}),
		tail:      tail,
	}
	inst.apiClient = &http.Client{Timeout: 30 * time.Second}
	proxyURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", mixedPort))
	inst.pxClient = &http.Client{
		Transport: &http.Transport{
			Proxy:             http.ProxyURL(proxyURL),
			DisableKeepAlives: true,
			TLSClientConfig:   &tls.Config{InsecureSkipVerify: true},
			DialContext:       (&net.Dialer{Timeout: 8 * time.Second, KeepAlive: -1}).DialContext,
		},
	}

	go func() {
		inst.exitErr = cmd.Wait()
		close(inst.exited)
	}()

	if err := inst.waitReady(ctx, 40*time.Second); err != nil {
		tail.dump(t.rep, "内核启动失败输出")
		inst.Stop()
		return nil, err
	}
	return inst, nil
}

func (c *coreInstance) waitReady(ctx context.Context, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		select {
		case <-c.exited:
			return fmt.Errorf("内核进程已退出: %v", c.exitErr)
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(300 * time.Millisecond):
		}
		if time.Now().After(deadline) {
			return errors.New("等待内核就绪超时")
		}
		reqCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
		req, err := http.NewRequestWithContext(reqCtx, http.MethodGet,
			fmt.Sprintf("http://127.0.0.1:%d/version", c.ctrlPort), nil)
		if err == nil {
			req.Header.Set("Authorization", "Bearer "+c.secret)
			resp, err := c.apiClient.Do(req)
			if err == nil {
				io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
				resp.Body.Close()
				cancel()
				if resp.StatusCode == http.StatusOK {
					return nil
				}
				continue
			}
		}
		cancel()
	}
}

// Stop 关闭内核实例。
func (c *coreInstance) Stop() {
	if c == nil {
		return
	}
	if c.cancel != nil {
		c.cancel()
	}
	if c.cmd != nil && c.cmd.Process != nil {
		_ = c.cmd.Process.Kill()
	}
	select {
	case <-c.exited:
	case <-time.After(3 * time.Second):
	}
}

// Delay 通过内核 REST API 探测单个节点延迟。
func (c *coreInstance) Delay(ctx context.Context, node string, timeout time.Duration, target string) (int, error) {
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	q := url.Values{}
	q.Set("timeout", strconv.Itoa(int(timeout.Milliseconds())))
	q.Set("url", target)

	reqCtx, cancel := context.WithTimeout(ctx, timeout+3*time.Second)
	defer cancel()
	endpoint := fmt.Sprintf("http://127.0.0.1:%d/proxies/%s/delay?%s", c.ctrlPort, url.PathEscape(node), q.Encode())
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Authorization", "Bearer "+c.secret)

	resp, err := c.apiClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode != http.StatusOK {
		return 0, errors.New(apiMessage(body, resp.Status))
	}
	var out struct {
		Delay int `json:"delay"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return 0, fmt.Errorf("解析延迟结果失败: %w", err)
	}
	if out.Delay <= 0 {
		return 0, errors.New("延迟结果无效")
	}
	return out.Delay, nil
}

// Speed 切换代理组到指定节点后通过混合端口下载测速，返回 MB/s。
func (c *coreInstance) Speed(ctx context.Context, group, node, rawURL string, duration time.Duration) (float64, error) {
	if duration <= 0 {
		duration = 5 * time.Second
	}
	if err := c.selectProxy(ctx, group, node); err != nil {
		return 0, err
	}

	reqCtx, cancel := context.WithTimeout(ctx, duration)
	defer cancel()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, rawURL, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

	start := time.Now()
	resp, err := c.pxClient.Do(req)
	if err != nil {
		if reqCtx.Err() != nil {
			return 0, errors.New("下载超时")
		}
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return 0, fmt.Errorf("下载返回 HTTP %d", resp.StatusCode)
	}

	buf := make([]byte, 128*1024)
	var total int64
	for {
		n, err := resp.Body.Read(buf)
		total += int64(n)
		if err != nil {
			break
		}
		if reqCtx.Err() != nil {
			break
		}
	}
	elapsed := time.Since(start).Seconds()
	if total == 0 || elapsed <= 0 {
		if reqCtx.Err() != nil {
			return 0, errors.New("下载超时且无数据")
		}
		return 0, errors.New("未获取到数据")
	}
	return float64(total) / elapsed / 1000 / 1000, nil
}

func (c *coreInstance) selectProxy(ctx context.Context, group, node string) error {
	body, _ := json.Marshal(map[string]string{"name": node})
	reqCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodPut,
		fmt.Sprintf("http://127.0.0.1:%d/proxies/%s", c.ctrlPort, url.PathEscape(group)),
		strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.secret)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.apiClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return errors.New(apiMessage(data, resp.Status))
	}
	return nil
}

func apiMessage(body []byte, status string) string {
	var out struct {
		Message string `json:"message"`
	}
	if err := json.Unmarshal(body, &out); err == nil && strings.TrimSpace(out.Message) != "" {
		return shorten(out.Message)
	}
	return status
}

// ---------- 工具函数 ----------

func splitBatches(nodes []*model.Node, n int) [][]*model.Node {
	if n <= 0 {
		n = 1
	}
	if n > len(nodes) {
		n = len(nodes)
	}
	if n <= 0 {
		return nil
	}
	batches := make([][]*model.Node, n)
	for i, node := range nodes {
		batches[i%n] = append(batches[i%n], node)
	}
	return batches
}

func freePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func randomHex(n int) string {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 16)
	}
	return hex.EncodeToString(buf)
}

func shorten(s string) string {
	s = strings.TrimSpace(strings.ReplaceAll(s, "\n", " "))
	if len(s) > 120 {
		return s[:120] + "..."
	}
	return s
}

type tailBuffer struct {
	mu  sync.Mutex
	buf []byte
	max int
}

func newTailBuffer(max int) *tailBuffer {
	return &tailBuffer{max: max}
}

func (t *tailBuffer) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.buf = append(t.buf, p...)
	if len(t.buf) > t.max {
		t.buf = t.buf[len(t.buf)-t.max:]
	}
	return len(p), nil
}

func (t *tailBuffer) String() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return string(t.buf)
}

func (t *tailBuffer) dump(rep Reporter, title string) {
	text := strings.TrimSpace(t.String())
	if text == "" || rep == nil {
		return
	}
	lines := strings.Split(text, "\n")
	if len(lines) > 12 {
		lines = lines[len(lines)-12:]
	}
	rep.Log("WARN", "%s：\n%s", title, strings.Join(lines, "\n"))
}
