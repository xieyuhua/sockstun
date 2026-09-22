package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"gopkg.in/yaml.v3"
)

func TestLoadCreatesDefaultConfig(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")

	store, err := Load(path)
	if err != nil {
		t.Fatalf("首次加载失败: %v", err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("应自动生成配置文件: %v", err)
	}

	cfg := store.Get()
	if cfg.Server.Listen == "" || cfg.Output.Path == "" || cfg.SpeedTest.Mode != ModeCore {
		t.Fatalf("默认配置不完整: %+v", cfg)
	}
	if cfg.Scheduler.Interval.D() <= 0 {
		t.Fatalf("默认定时间隔异常: %v", cfg.Scheduler.Interval)
	}
}

func TestUpdatePersistsAndClones(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	store, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}

	if err := store.Update(func(c *Config) error {
		c.Server.Token = "abc"
		c.Subscriptions = append(c.Subscriptions, Subscription{Name: "测试", URL: "https://example.com/sub"})
		return nil
	}); err != nil {
		t.Fatal(err)
	}

	// 内存副本不应被外部修改影响
	snapshot := store.Get()
	snapshot.Server.Token = "hacked"
	if store.Get().Server.Token != "abc" {
		t.Fatal("Get 应返回深拷贝")
	}

	// 重新加载验证落盘
	reloaded, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	cfg := reloaded.Get()
	if cfg.Server.Token != "abc" || len(cfg.Subscriptions) != 1 {
		t.Fatalf("配置未正确持久化: %+v", cfg)
	}
	if cfg.Subscriptions[0].ID == "" {
		t.Fatal("订阅 ID 应自动补齐")
	}
	if cfg.Subscriptions[0].Name != "测试" {
		t.Fatalf("订阅名丢失: %q", cfg.Subscriptions[0].Name)
	}
}

func TestDurationAndDefaults(t *testing.T) {
	var cfg Config
	if err := yaml.Unmarshal([]byte("speedtest:\n  timeout: 3s\n  max_delay: 800\nscheduler:\n  interval: 15m\npool:\n  dedupe: false\n"), &cfg); err != nil {
		t.Fatal(err)
	}
	base := Default()
	merged := Default()
	if err := yaml.Unmarshal([]byte("speedtest:\n  timeout: 3s\nscheduler:\n  interval: 15m\n"), merged); err != nil {
		t.Fatal(err)
	}
	if merged.SpeedTest.Timeout.D() != 3*time.Second {
		t.Fatalf("时长解析错误: %v", merged.SpeedTest.Timeout.D())
	}
	if merged.Scheduler.Interval.D() != 15*time.Minute {
		t.Fatalf("时长解析错误: %v", merged.Scheduler.Interval.D())
	}
	// 未配置的字段应保留默认值
	if merged.SpeedTest.Mode != base.SpeedTest.Mode || merged.Output.Path != base.Output.Path {
		t.Fatal("未配置字段应保留默认值")
	}
	if merged.SpeedTest.Download.URL != base.SpeedTest.Download.URL {
		t.Fatal("嵌套默认值未保留")
	}
}

func TestNormalizeFillsInvalidValues(t *testing.T) {
	cfg := &Config{}
	cfg.Normalize()
	if cfg.Server.Listen == "" || cfg.SpeedTest.Mode != ModeCore ||
		cfg.SpeedTest.Concurrency <= 0 || cfg.Output.MixedPort <= 0 ||
		len(cfg.Output.Rules) == 0 {
		t.Fatalf("Normalize 未补齐字段: %+v", cfg)
	}
}

func TestResolvePath(t *testing.T) {
	dir := t.TempDir()
	store, err := Load(filepath.Join(dir, "config.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	got := store.ResolvePath("data/clash.yaml")
	want := filepath.Join(dir, "data", "clash.yaml")
	if got != want {
		t.Fatalf("相对路径解析错误: %s != %s", got, want)
	}
	if store.ResolvePath("") != "" {
		t.Fatal("空路径应返回空")
	}
}
