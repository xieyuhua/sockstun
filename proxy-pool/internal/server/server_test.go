package server

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"gopkg.in/yaml.v3"

	"proxypool/internal/config"
	"proxypool/internal/logx"
	"proxypool/internal/runner"
)

func startTCPListener(t *testing.T) int {
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

// 覆盖「后台任务被请求上下文取消」的回归问题。
func TestRunAPIBackgroundTask(t *testing.T) {
	port := startTCPListener(t)
	subscription := fmt.Sprintf(`proxies:
  - name: 香港测试节点
    type: ss
    server: 127.0.0.1
    port: %d
    cipher: aes-128-gcm
    password: pass
`, port)

	subSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(subscription))
	}))
	defer subSrv.Close()

	dir := t.TempDir()
	cfg := config.Default()
	cfg.SpeedTest.Mode = config.ModeTCP
	cfg.SpeedTest.Download.Enabled = false
	cfg.SpeedTest.Timeout = config.Duration(time.Second)
	cfg.Output.Path = "data/clash.yaml"
	cfg.Scheduler.Enabled = false
	cfg.Subscriptions = []config.Subscription{{ID: "s1", Name: "测试订阅", URL: subSrv.URL}}

	data, err := yaml.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cfgPath := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(cfgPath, data, 0o644); err != nil {
		t.Fatal(err)
	}
	store, err := config.Load(cfgPath)
	if err != nil {
		t.Fatal(err)
	}

	r := runner.New(store, logx.New(100, nil))
	srv, err := New(store, r, logx.New(100, nil))
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	resp, err := http.Post(ts.URL+"/api/run", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("触发任务返回状态码 %d", resp.StatusCode)
	}

	deadline := time.Now().Add(15 * time.Second)
	var status struct {
		Progress struct {
			Running bool `json:"running"`
		} `json:"progress"`
		Snapshot *runner.Snapshot `json:"snapshot"`
	}
	for time.Now().Before(deadline) {
		body, err := http.Get(ts.URL + "/api/status")
		if err != nil {
			t.Fatal(err)
		}
		err = json.NewDecoder(body.Body).Decode(&status)
		body.Body.Close()
		if err != nil {
			t.Fatal(err)
		}
		if !status.Progress.Running && status.Snapshot != nil {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if status.Snapshot == nil {
		t.Fatal("任务未产生结果")
	}
	if status.Snapshot.Error != "" {
		t.Fatalf("任务执行失败: %s", status.Snapshot.Error)
	}
	if status.Snapshot.Alive != 1 {
		t.Fatalf("可用节点数应为 1，实际 %d", status.Snapshot.Alive)
	}

	// 订阅文件可访问
	fileResp, err := http.Get(ts.URL + "/clash.yaml")
	if err != nil {
		t.Fatal(err)
	}
	defer fileResp.Body.Close()
	if fileResp.StatusCode != http.StatusOK {
		t.Fatalf("/clash.yaml 状态码 %d", fileResp.StatusCode)
	}
	buf := make([]byte, 4096)
	n, _ := fileResp.Body.Read(buf)
	if !strings.Contains(string(buf[:n]), "🇭🇰HK-001") {
		t.Fatalf("订阅文件内容异常:\n%s", string(buf[:n]))
	}

	// 节点接口
	nodeResp, err := http.Get(ts.URL + "/api/nodes")
	if err != nil {
		t.Fatal(err)
	}
	defer nodeResp.Body.Close()
	var nodes struct {
		Total int `json:"total"`
		Alive int `json:"alive"`
	}
	if err := json.NewDecoder(nodeResp.Body).Decode(&nodes); err != nil {
		t.Fatal(err)
	}
	if nodes.Total != 1 || nodes.Alive != 1 {
		t.Fatalf("节点统计异常: total=%d alive=%d", nodes.Total, nodes.Alive)
	}
}

func TestTokenGuard(t *testing.T) {
	dir := t.TempDir()
	cfg := config.Default()
	cfg.Server.Token = "secret123"
	cfg.Scheduler.Enabled = false
	data, _ := yaml.Marshal(cfg)
	cfgPath := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(cfgPath, data, 0o644); err != nil {
		t.Fatal(err)
	}
	store, err := config.Load(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	srv, err := New(store, runner.New(store, logx.New(20, nil)), logx.New(20, nil))
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/api/status")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无令牌应返回 401，实际 %d", resp.StatusCode)
	}

	resp, err = http.Get(ts.URL + "/api/status?token=secret123")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("带令牌应返回 200，实际 %d", resp.StatusCode)
	}
}
