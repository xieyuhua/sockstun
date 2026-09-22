package runner

import (
	"context"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"

	"proxypool/internal/config"
	"proxypool/internal/logx"
)

// startFakeNode 起一个本地 TCP 监听，充当「可达节点」。
func startFakeNode(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			conn.Close()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port
}

// unusedPort 返回一个当前未被占用的端口（用于构造不可用节点）。
func unusedPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	return port
}

func TestRunnerEndToEnd(t *testing.T) {
	port := startFakeNode(t)
	// 指向两个没有监听的端口，用于验证不可用节点会被过滤掉
	deadPort := unusedPort(t)
	deadPort2 := unusedPort(t)

	// 订阅 A：一个 Clash 配置格式的可用节点
	subscriptionA := fmt.Sprintf(`proxies:
  - name: 香港节点A
    type: ss
    server: 127.0.0.1
    port: %d
    cipher: aes-128-gcm
    password: pass
`, port)
	// 订阅 B：一条与 A 重复的分享链接（验证去重）+ 两条不可用节点
	dupUser := base64.StdEncoding.EncodeToString([]byte("aes-128-gcm:pass"))
	subscriptionB := fmt.Sprintf(`ss://%s@127.0.0.1:%d#香港节点A副本
trojan://pwd@127.0.0.1:%d#美国节点B
trojan://pwd2@127.0.0.1:%d#美国节点C
`, dupUser, port, deadPort, deadPort2)

	subServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/a":
			_, _ = w.Write([]byte(subscriptionA))
		case "/b":
			_, _ = w.Write([]byte(subscriptionB))
		default:
			http.NotFound(w, r)
		}
	}))
	defer subServer.Close()

	dir := t.TempDir()
	cfg := config.Default()
	cfg.Server.Listen = "127.0.0.1:0"
	cfg.SpeedTest.Mode = config.ModeTCP
	cfg.SpeedTest.Download.Enabled = false
	cfg.SpeedTest.MaxDelay = 0
	cfg.SpeedTest.Timeout = config.Duration(1e9) // 1s
	cfg.Output.Path = "data/clash.yaml"
	cfg.Scheduler.Enabled = false
	cfg.Subscriptions = []config.Subscription{
		{ID: "a", Name: "订阅A", URL: subServer.URL + "/a"},
		{ID: "b", Name: "订阅B", URL: subServer.URL + "/b"},
		{ID: "c", Name: "坏订阅", URL: subServer.URL + "/notfound"},
	}

	cfgData, err := yaml.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cfgPath := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(cfgPath, cfgData, 0o644); err != nil {
		t.Fatal(err)
	}

	store, err := config.Load(cfgPath)
	if err != nil {
		t.Fatal(err)
	}

	r := New(store, logx.New(200, nil))
	snap, err := r.Run(context.Background(), "测试", Options{})
	if err != nil {
		t.Fatalf("执行失败: %v", err)
	}
	if snap.Duplicated != 1 {
		t.Fatalf("应去重 1 个节点，实际 %d", snap.Duplicated)
	}
	if snap.Total != 3 {
		t.Fatalf("去重后应有 3 个节点，实际 %d", snap.Total)
	}
	if snap.Alive != 1 || snap.Active != 1 {
		t.Fatalf("应只有 1 个可用节点，实际 alive=%d active=%d", snap.Alive, snap.Active)
	}

	// 校验生成的配置文件
	outPath := filepath.Join(dir, "data", "clash.yaml")
	data, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatalf("未生成配置文件: %v", err)
	}
	text := string(data)
	for _, expect := range []string{"mixed-port", "proxies:", "proxy-groups:", "rules:", "🇭🇰HK-001"} {
		if !strings.Contains(text, expect) {
			t.Fatalf("生成的配置缺少 %q\n%s", expect, text)
		}
	}

	var parsed struct {
		Proxies []map[string]any `yaml:"proxies"`
		Groups  []map[string]any `yaml:"proxy-groups"`
	}
	if err := yaml.Unmarshal(data, &parsed); err != nil {
		t.Fatalf("生成的配置不是合法 YAML: %v", err)
	}
	if len(parsed.Proxies) != 1 || len(parsed.Groups) != 2 {
		t.Fatalf("代理或代理组数量不符: %d, %d", len(parsed.Proxies), len(parsed.Groups))
	}
	if parsed.Proxies[0]["name"] != "🇭🇰HK-001" {
		t.Fatalf("节点命名不符: %v", parsed.Proxies[0]["name"])
	}
	if got, ok := parsed.Proxies[0]["port"].(int); !ok || got != port {
		t.Fatalf("端口字段错误: %v(%T), 期望 %d", parsed.Proxies[0]["port"], parsed.Proxies[0]["port"], port)
	}

	// 订阅统计
	if len(snap.Subscriptions) != 3 {
		t.Fatalf("订阅统计数量不符: %d", len(snap.Subscriptions))
	}
	okCount := 0
	for _, s := range snap.Subscriptions {
		if s.OK {
			okCount++
		}
	}
	if okCount != 2 {
		t.Fatalf("应有 2 个订阅成功，实际 %d", okCount)
	}

	// 复用节点池再次测速
	snap2, err := r.Run(context.Background(), "复用测速", Options{ReuseNodes: true})
	if err != nil {
		t.Fatalf("复用节点池执行失败: %v", err)
	}
	if snap2.Active != 1 {
		t.Fatalf("复用测速结果不符: %d", snap2.Active)
	}
}
