// Package config 定义代理池的配置结构、加载/保存以及并发安全的配置访问。
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"gopkg.in/yaml.v3"
)

const (
	// ModeCore 使用 mihomo/clash 内核做真实可用性+速度测试。
	ModeCore = "core"
	// ModeTCP 仅做 TCP 连通性测试（无需内核，但只代表端口可达）。
	ModeTCP = "tcp"
)

// Duration 兼容 "30s" / "5m" / "2h" / 90（秒）三种写法。
type Duration time.Duration

// D 转回 time.Duration。
func (d Duration) D() time.Duration { return time.Duration(d) }

// String 便于日志展示。
func (d Duration) String() string { return time.Duration(d).String() }

// UnmarshalYAML 解析 YAML 时长。
func (d *Duration) UnmarshalYAML(node *yaml.Node) error {
	var raw any
	if err := node.Decode(&raw); err != nil {
		return err
	}
	switch v := raw.(type) {
	case string:
		s := strings.TrimSpace(v)
		if s == "" {
			*d = 0
			return nil
		}
		if dur, err := time.ParseDuration(s); err == nil {
			*d = Duration(dur)
			return nil
		}
		if f, err := strconv.ParseFloat(s, 64); err == nil {
			*d = Duration(time.Duration(f * float64(time.Second)))
			return nil
		}
		return fmt.Errorf("无法解析时长 %q（示例：30s / 10m / 2h）", v)
	case int:
		*d = Duration(time.Duration(v) * time.Second)
	case float64:
		*d = Duration(time.Duration(v * float64(time.Second)))
	case nil:
		*d = 0
	default:
		return fmt.Errorf("无法解析时长 %v", raw)
	}
	return nil
}

// MarshalYAML 输出 "30m0s" 形式。
func (d Duration) MarshalYAML() (any, error) { return time.Duration(d).String(), nil }

// MarshalJSON 便于前端展示。
func (d Duration) MarshalJSON() ([]byte, error) {
	return json.Marshal(time.Duration(d).String())
}

// UnmarshalJSON 支持字符串或数字（秒）。
func (d *Duration) UnmarshalJSON(data []byte) error {
	s := strings.Trim(strings.TrimSpace(string(data)), `"`)
	if s == "" || s == "null" {
		*d = 0
		return nil
	}
	if dur, err := time.ParseDuration(s); err == nil {
		*d = Duration(dur)
		return nil
	}
	if f, err := strconv.ParseFloat(s, 64); err == nil {
		*d = Duration(time.Duration(f * float64(time.Second)))
		return nil
	}
	return fmt.Errorf("无法解析时长 %q", s)
}

// Subscription 一个订阅地址。
type Subscription struct {
	ID        string `yaml:"id" json:"id"`
	Name      string `yaml:"name" json:"name"`
	URL       string `yaml:"url" json:"url"`
	Disabled  bool   `yaml:"disabled,omitempty" json:"disabled"`
	UserAgent string `yaml:"user_agent,omitempty" json:"user_agent,omitempty"`
	Remark    string `yaml:"remark,omitempty" json:"remark,omitempty"`
}

// ServerConfig HTTP 服务配置。
type ServerConfig struct {
	Listen string `yaml:"listen" json:"listen"`
	Token  string `yaml:"token,omitempty" json:"token,omitempty"`
}

// PoolConfig 节点池配置。
type PoolConfig struct {
	// NameFormat 节点命名模板，支持 {emoji} {code} {region} {index} {source} {name}。
	NameFormat string `yaml:"name_format" json:"name_format"`
	// MaxNodes 最终保留的可用节点上限，0 表示不限制。
	MaxNodes int `yaml:"max_nodes" json:"max_nodes"`
	// Dedupe 是否按服务器/端口/凭据去重。
	Dedupe bool `yaml:"dedupe" json:"dedupe"`
	// DropTypes 丢弃的协议类型。为空时使用内置黑名单（不支持的协议）。
	DropTypes []string `yaml:"drop_types,omitempty" json:"drop_types,omitempty"`
}

// DownloadConfig 下载测速配置（只有内核模式支持）。
type DownloadConfig struct {
	// Enabled 是否做下载速度测试。
	Enabled bool `yaml:"enabled" json:"enabled"`
	// URL 测速文件地址。
	URL string `yaml:"url" json:"url"`
	// Duration 单个节点最大测速时长。
	Duration Duration `yaml:"duration" json:"duration"`
	// Top 只对延迟最优的前 N 个节点做下载测速，0 表示全部。
	Top int `yaml:"top" json:"top"`
	// MinSpeedMB 最低速度要求（MB/s），低于该值判定不可用，0 表示不限制。
	MinSpeedMB float64 `yaml:"min_speed_mb" json:"min_speed_mb"`
}

// CoreConfig mihomo/clash 内核配置。
type CoreConfig struct {
	// Path 内核可执行文件路径，Windows 可省略 .exe。
	Path string `yaml:"path" json:"path"`
	// Args 额外启动参数。
	Args []string `yaml:"args,omitempty" json:"args,omitempty"`
	// AutoDownload 内核缺失时自动从 GitHub Release 下载。
	AutoDownload bool `yaml:"auto_download" json:"auto_download"`
	// GhProxy GitHub 加速前缀，例如 https://ghfast.top/
	GhProxy string `yaml:"gh_proxy,omitempty" json:"gh_proxy,omitempty"`
	// Version 指定版本号（如 v1.19.0），留空使用最新版。
	Version string `yaml:"version,omitempty" json:"version,omitempty"`
}

// SpeedTestConfig 测速配置。
type SpeedTestConfig struct {
	// Mode core 或 tcp。
	Mode string `yaml:"mode" json:"mode"`
	// Concurrency 内核实例数，每个实例负责一批节点。
	Concurrency int `yaml:"concurrency" json:"concurrency"`
	// DelayWorkers 单实例内并发延迟探测数。
	DelayWorkers int `yaml:"delay_workers" json:"delay_workers"`
	// Timeout 单个节点探测超时。
	Timeout Duration `yaml:"timeout" json:"timeout"`
	// DelayURL 延迟探测目标（返回 204 的地址最佳）。
	DelayURL string `yaml:"delay_url" json:"delay_url"`
	// DelayURLs 候选探测地址：当整轮探测全部失败时会抽样比较这些地址并自动换用最合适的。
	DelayURLs []string `yaml:"delay_urls,omitempty" json:"delay_urls,omitempty"`
	// MaxDelay 最大可接受延迟（毫秒），超过判定不可用，0 表示不限制。
	MaxDelay int `yaml:"max_delay" json:"max_delay"`
	// MinAlive 至少保留多少节点，不足时报警但仍输出。
	MinAlive int `yaml:"min_alive" json:"min_alive"`
	// Download 下载测速。
	Download DownloadConfig `yaml:"download" json:"download"`
	// Core 内核配置。
	Core CoreConfig `yaml:"core" json:"core"`
}

// OutputConfig Clash 文件输出配置。
type OutputConfig struct {
	// Path 生成文件的保存路径。
	Path string `yaml:"path" json:"path"`
	// KeepBackup 替换输出文件前是否保留上一版为 <path>.bak。
	KeepBackup bool `yaml:"keep_backup" json:"keep_backup"`
	// Template 可选：自定义 Clash 模板文件，代理组中使用 __NODES__ 占位。
	Template string `yaml:"template,omitempty" json:"template,omitempty"`

	MixedPort          int            `yaml:"mixed_port" json:"mixed_port"`
	AllowLan           bool           `yaml:"allow_lan" json:"allow_lan"`
	Mode               string         `yaml:"mode" json:"mode"`
	LogLevel           string         `yaml:"log_level" json:"log_level"`
	IPv6               bool           `yaml:"ipv6" json:"ipv6"`
	UnifiedDelay       bool           `yaml:"unified_delay" json:"unified_delay"`
	ExternalController string         `yaml:"external_controller,omitempty" json:"external_controller,omitempty"`
	Secret             string         `yaml:"secret,omitempty" json:"secret,omitempty"`
	TunEnable          bool           `yaml:"tun_enable" json:"tun_enable"`
	DNSEnable          bool           `yaml:"dns_enable" json:"dns_enable"`
	ProxyGroupName     string         `yaml:"proxy_group_name" json:"proxy_group_name"`
	AutoGroupName      string         `yaml:"auto_group_name" json:"auto_group_name"`
	Rules              []string       `yaml:"rules,omitempty" json:"rules,omitempty"`
	Extra              map[string]any `yaml:"extra,omitempty" json:"extra,omitempty"`
}

// SchedulerConfig 定时任务配置。
type SchedulerConfig struct {
	Enabled bool `yaml:"enabled" json:"enabled"`
	// Cron cron 表达式（5 字段，支持可选的秒字段与 @every 等描述符）。
	// 填写后优先使用 cron，留空则使用 Interval 固定间隔。
	Cron string `yaml:"cron,omitempty" json:"cron,omitempty"`
	// Timezone 解释 cron 时使用的时区，例如 Asia/Shanghai，留空使用本机时区。
	Timezone   string   `yaml:"timezone,omitempty" json:"timezone,omitempty"`
	Interval   Duration `yaml:"interval" json:"interval"`
	RunOnStart bool     `yaml:"run_on_start" json:"run_on_start"`
}

// Config 全局配置。
type Config struct {
	Server        ServerConfig    `yaml:"server" json:"server"`
	Subscriptions []Subscription  `yaml:"subscriptions" json:"subscriptions"`
	Pool          PoolConfig      `yaml:"pool" json:"pool"`
	SpeedTest     SpeedTestConfig `yaml:"speedtest" json:"speedtest"`
	Output        OutputConfig    `yaml:"output" json:"output"`
	Scheduler     SchedulerConfig `yaml:"scheduler" json:"scheduler"`
}

// Default 返回一份可直接运行的默认配置。
func Default() *Config {
	return &Config{
		Server: ServerConfig{Listen: "0.0.0.0:18080"},
		Pool: PoolConfig{
			NameFormat: "{emoji}{code}-{index:03d}",
			MaxNodes:   300,
			Dedupe:     true,
		},
		SpeedTest: SpeedTestConfig{
			Mode:         ModeCore,
			Concurrency:  4,
			DelayWorkers: 12,
			Timeout:      Duration(5 * time.Second),
			DelayURL:     "http://www.gstatic.com/generate_204",
			MaxDelay:     1000,
			MinAlive:     1,
			Download: DownloadConfig{
				Enabled:    true,
				URL:        "https://speed.cloudflare.com/__down?bytes=50000000",
				Duration:   Duration(6 * time.Second),
				Top:        30,
				MinSpeedMB: 0.3,
			},
			Core: CoreConfig{
				Path:         defaultCorePath(),
				AutoDownload: true,
			},
		},
		Output: OutputConfig{
			Path:           "data/clash.yaml",
			KeepBackup:     true,
			MixedPort:      7890,
			AllowLan:       true,
			Mode:           "rule",
			LogLevel:       "info",
			IPv6:           false,
			UnifiedDelay:   true,
			DNSEnable:      true,
			ProxyGroupName: "🚀 节点选择",
			AutoGroupName:  "♻️ 自动选择",
			Rules: []string{
				"GEOIP,CN,DIRECT",
				"MATCH,🚀 节点选择",
			},
		},
		Scheduler: SchedulerConfig{
			Enabled:    true,
			Interval:   Duration(30 * time.Minute),
			RunOnStart: true,
		},
	}
}

func defaultCorePath() string {
	if IsWindows() {
		return "bin/mihomo.exe"
	}
	return "bin/mihomo"
}

// Normalize 用默认值补齐非法字段。
func (c *Config) Normalize() {
	def := Default()
	if strings.TrimSpace(c.Server.Listen) == "" {
		c.Server.Listen = def.Server.Listen
	}
	if strings.TrimSpace(c.Pool.NameFormat) == "" {
		c.Pool.NameFormat = def.Pool.NameFormat
	}
	if strings.TrimSpace(c.Output.Path) == "" {
		c.Output.Path = def.Output.Path
	}
	if c.Output.MixedPort <= 0 || c.Output.MixedPort > 65535 {
		c.Output.MixedPort = def.Output.MixedPort
	}
	if strings.TrimSpace(c.Output.Mode) == "" {
		c.Output.Mode = def.Output.Mode
	}
	if strings.TrimSpace(c.Output.LogLevel) == "" {
		c.Output.LogLevel = def.Output.LogLevel
	}
	if strings.TrimSpace(c.Output.ProxyGroupName) == "" {
		c.Output.ProxyGroupName = def.Output.ProxyGroupName
	}
	if strings.TrimSpace(c.Output.AutoGroupName) == "" {
		c.Output.AutoGroupName = def.Output.AutoGroupName
	}
	if len(c.Output.Rules) == 0 {
		c.Output.Rules = def.Output.Rules
	}

	st := &c.SpeedTest
	switch strings.ToLower(strings.TrimSpace(st.Mode)) {
	case ModeTCP:
		st.Mode = ModeTCP
	default:
		st.Mode = ModeCore
	}
	if st.Concurrency <= 0 {
		st.Concurrency = def.SpeedTest.Concurrency
	}
	if st.Concurrency > 16 {
		st.Concurrency = 16
	}
	if st.DelayWorkers <= 0 {
		st.DelayWorkers = def.SpeedTest.DelayWorkers
	}
	if st.Timeout.D() <= 0 {
		st.Timeout = def.SpeedTest.Timeout
	}
	if strings.TrimSpace(st.DelayURL) == "" {
		st.DelayURL = def.SpeedTest.DelayURL
	}
	if len(st.DelayURLs) > 0 {
		cleaned := make([]string, 0, len(st.DelayURLs))
		for _, url := range st.DelayURLs {
			if url = strings.TrimSpace(url); url != "" {
				cleaned = append(cleaned, url)
			}
		}
		st.DelayURLs = cleaned
	}
	if st.MaxDelay < 0 {
		st.MaxDelay = 0
	}
	if st.Download.Enabled {
		if strings.TrimSpace(st.Download.URL) == "" {
			st.Download.URL = def.SpeedTest.Download.URL
		}
		if st.Download.Duration.D() <= 0 {
			st.Download.Duration = def.SpeedTest.Download.Duration
		}
		if st.Download.Top < 0 {
			st.Download.Top = 0
		}
	}
	if strings.TrimSpace(st.Core.Path) == "" {
		st.Core.Path = def.SpeedTest.Core.Path
	}
	c.Scheduler.Cron = strings.TrimSpace(c.Scheduler.Cron)
	c.Scheduler.Timezone = strings.TrimSpace(c.Scheduler.Timezone)
	if c.Scheduler.Enabled && c.Scheduler.Cron == "" && c.Scheduler.Interval.D() < 10*time.Second {
		c.Scheduler.Interval = def.Scheduler.Interval
	}
	// 补齐订阅 ID
	seen := map[string]bool{}
	for i := range c.Subscriptions {
		s := &c.Subscriptions[i]
		if strings.TrimSpace(s.ID) == "" || seen[s.ID] {
			s.ID = NewID()
		}
		seen[s.ID] = true
		if strings.TrimSpace(s.Name) == "" {
			s.Name = fmt.Sprintf("订阅%d", i+1)
		}
	}
}

// IsWindows 是否 Windows 平台。
func IsWindows() bool { return runtime.GOOS == "windows" }

// Store 并发安全的配置容器。
type Store struct {
	mu   sync.RWMutex
	path string
	cfg  *Config
}

// Load 读取配置文件，不存在时创建默认配置。
func Load(path string) (*Store, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		abs = path
	}
	store := &Store{path: abs, cfg: Default()}
	data, err := os.ReadFile(abs)
	switch {
	case err == nil:
		cfg := Default()
		if err := yaml.Unmarshal(data, cfg); err != nil {
			return nil, fmt.Errorf("解析配置文件 %s 失败: %w", abs, err)
		}
		cfg.Normalize()
		store.cfg = cfg
	case os.IsNotExist(err):
		store.cfg.Normalize()
		if err := store.Save(); err != nil {
			return nil, err
		}
	default:
		return nil, fmt.Errorf("读取配置文件失败: %w", err)
	}
	return store, nil
}

// Path 配置文件绝对路径。
func (s *Store) Path() string { return s.path }

// Dir 配置文件所在目录（用于解析相对路径）。
func (s *Store) Dir() string { return filepath.Dir(s.path) }

// ResolvePath 把相对路径解析为相对配置文件目录的绝对路径。
func (s *Store) ResolvePath(p string) string {
	if strings.TrimSpace(p) == "" {
		return ""
	}
	if filepath.IsAbs(p) {
		return p
	}
	return filepath.Join(s.Dir(), p)
}

// Get 返回配置深拷贝，调用方可安全读写。
func (s *Store) Get() *Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg.Clone()
}

// Raw 返回内部配置引用（只读场景使用）。
func (s *Store) Raw() *Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg
}

// Update 在锁内修改并落盘。
func (s *Store) Update(fn func(*Config) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.cfg.Clone()
	if err := fn(next); err != nil {
		return err
	}
	next.Normalize()
	s.cfg = next
	return s.saveLocked()
}

// Override 仅修改内存中的配置，不写入文件（用于命令行参数临时覆盖）。
func (s *Store) Override(fn func(*Config)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.cfg.Clone()
	fn(next)
	next.Normalize()
	s.cfg = next
}

// Save 落盘当前配置。
func (s *Store) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.saveLocked()
}

func (s *Store) saveLocked() error {
	data, err := yaml.Marshal(s.cfg)
	if err != nil {
		return err
	}
	header := "# 代理池配置（由服务自动维护，也可手工编辑）\n" +
		"# 修改后可通过 HTTP 接口 /api/reload 热加载，或重启服务\n"
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, append([]byte(header), data...), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// Clone 深拷贝配置。
func (c *Config) Clone() *Config {
	if c == nil {
		return Default()
	}
	data, err := yaml.Marshal(c)
	if err != nil {
		return c
	}
	out := Default()
	if err := yaml.Unmarshal(data, out); err != nil {
		return c
	}
	out.Normalize()
	return out
}

// FindSubscription 按 ID 查找订阅，返回下标。
func (c *Config) FindSubscription(id string) int {
	for i := range c.Subscriptions {
		if c.Subscriptions[i].ID == id {
			return i
		}
	}
	return -1
}

// NewID 生成一个短随机 ID。
func NewID() string {
	const chars = "abcdefghijklmnopqrstuvwxyz0123456789"
	buf := make([]byte, 8)
	seed := time.Now().UnixNano()
	for i := range buf {
		seed = seed*6364136223846793005 + 1442695040888963407
		idx := int((seed >> 33) % int64(len(chars)))
		if idx < 0 {
			idx = -idx
		}
		buf[i] = chars[idx]
	}
	return string(buf)
}
