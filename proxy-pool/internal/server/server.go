// Package server 提供 HTTP 服务：可视化管理页面与管理 API。
package server

import (
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"proxypool/internal/config"
	"proxypool/internal/logx"
	"proxypool/internal/model"
	"proxypool/internal/runner"
	"proxypool/internal/schedule"
	"proxypool/internal/scheduler"
	"proxypool/internal/speedtest"
	"proxypool/internal/subscription"
)

//go:embed static
var staticFS embed.FS

// Server HTTP 服务。
type Server struct {
	store     *config.Store
	runner    *runner.Runner
	log       *logx.Buffer
	static    fs.FS
	startedAt time.Time
	schedule  *schedule.State
}

// SetScheduleState 注入调度状态，用于在状态接口中展示下一次运行时间。
func (s *Server) SetScheduleState(state *schedule.State) {
	s.schedule = state
}

// New 创建 HTTP 服务。
func New(store *config.Store, r *runner.Runner, log *logx.Buffer) (*Server, error) {
	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		return nil, err
	}
	return &Server{store: store, runner: r, log: log, static: sub, startedAt: time.Now()}, nil
}

// Handler 构建路由。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.Handle("GET /static/", http.StripPrefix("/static/", http.FileServerFS(s.static)))
	mux.HandleFunc("GET /{$}", s.handleIndex)

	mux.HandleFunc("GET /clash.yaml", s.guard(s.handleClash))
	mux.HandleFunc("GET /sub", s.guard(s.handleClash))
	mux.HandleFunc("GET /api/clash", s.guard(s.handleClash))

	mux.HandleFunc("GET /api/status", s.guard(s.handleStatus))
	mux.HandleFunc("GET /api/config", s.guard(s.handleGetConfig))
	mux.HandleFunc("PUT /api/config", s.guard(s.handlePutConfig))
	mux.HandleFunc("POST /api/reload", s.guard(s.handleReload))
	mux.HandleFunc("GET /api/schedule", s.guard(s.handleSchedulePreview))

	mux.HandleFunc("GET /api/subscriptions", s.guard(s.handleListSubs))
	mux.HandleFunc("POST /api/subscriptions", s.guard(s.handleCreateSub))
	mux.HandleFunc("PUT /api/subscriptions/{id}", s.guard(s.handleUpdateSub))
	mux.HandleFunc("DELETE /api/subscriptions/{id}", s.guard(s.handleDeleteSub))
	mux.HandleFunc("POST /api/subscriptions/{id}/check", s.guard(s.handleCheckSub))

	mux.HandleFunc("POST /api/run", s.guard(s.handleRun))
	mux.HandleFunc("POST /api/probe", s.guard(s.handleProbe))
	mux.HandleFunc("GET /api/nodes", s.guard(s.handleNodes))
	mux.HandleFunc("GET /api/logs", s.guard(s.handleLogs))
	mux.HandleFunc("GET /api/output", s.guard(s.handleClash))

	// 便于从其它页面跨域调用接口
	mux.HandleFunc("OPTIONS /", s.guard(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))

	return mux
}

// ---------- 鉴权 ----------

func (s *Server) guard(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := s.store.Get().Server.Token
		if strings.TrimSpace(token) != "" {
			provided := r.URL.Query().Get("token")
			if provided == "" {
				auth := r.Header.Get("Authorization")
				if strings.HasPrefix(strings.ToLower(auth), "bearer ") {
					provided = strings.TrimSpace(auth[7:])
				}
			}
			if provided == "" {
				provided = r.Header.Get("X-Token")
			}
			if provided != token {
				writeErr(w, http.StatusUnauthorized, "令牌无效，请在 URL 中带上 ?token=xxx")
				return
			}
		}
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization,Content-Type")
		w.Header().Set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
		next(w, r)
	}
}

// ---------- 页面 ----------

func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	data, err := fs.ReadFile(s.static, "index.html")
	if err != nil {
		http.Error(w, "页面缺失", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(data)
}

// ---------- 订阅文件 ----------

func (s *Server) handleClash(w http.ResponseWriter, r *http.Request) {
	path := s.store.ResolvePath(s.store.Get().Output.Path)
	data, err := os.ReadFile(path)
	if err != nil {
		writeErr(w, http.StatusNotFound, fmt.Sprintf("尚未生成配置文件（%s），请先执行一次生成", path))
		return
	}
	w.Header().Set("Content-Type", "text/yaml; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(data)
}

// ---------- 状态 ----------

type outputInfo struct {
	Path    string `json:"path"`
	Exists  bool   `json:"exists"`
	Size    int64  `json:"size"`
	ModTime string `json:"mod_time,omitempty"`
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	cfg := s.store.Get()
	path := s.store.ResolvePath(cfg.Output.Path)
	out := outputInfo{Path: path}
	if info, err := os.Stat(path); err == nil {
		out.Exists = true
		out.Size = info.Size()
		out.ModTime = info.ModTime().Format("2006-01-02 15:04:05")
	}

	resp := map[string]any{
		"progress":    s.runner.Progress(),
		"server_time": time.Now().Format("2006-01-02 15:04:05"),
		"started_at":  s.startedAt.Format("2006-01-02 15:04:05"),
		"uptime":      time.Since(s.startedAt).Round(time.Second).String(),
		"config_path": s.store.Path(),
		"output":      out,
		"token":       cfg.Server.Token != "",
	}
	if s.schedule != nil {
		mode, next := s.schedule.Snapshot()
		item := map[string]any{"mode": mode, "describe": scheduler.Describe(cfg.Scheduler)}
		if !next.IsZero() {
			item["next"] = next.Format("2006-01-02 15:04:05 MST")
		}
		resp["schedule"] = item
	}
	if snap := s.runner.Last(); snap != nil {
		summary := *snap
		summary.Nodes = nil
		resp["snapshot"] = &summary
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleNodes(w http.ResponseWriter, r *http.Request) {
	snap := s.runner.Last()
	if snap == nil {
		writeJSON(w, http.StatusOK, map[string]any{"nodes": []any{}, "at": nil})
		return
	}
	nodes := make([]*model.Node, len(snap.Nodes))
	copy(nodes, snap.Nodes)
	model.SortByQuality(nodes)
	if r.URL.Query().Get("all") == "" {
		alive := nodes[:0]
		for _, n := range nodes {
			if n.Alive {
				alive = append(alive, n)
			}
		}
		nodes = alive
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"nodes":         nodes,
		"at":            snap.At.Format("2006-01-02 15:04:05"),
		"engine":        snap.Engine,
		"total":         snap.Total,
		"alive":         snap.Alive,
		"active":        snap.Active,
		"duplicated":    snap.Duplicated,
		"invalid":       snap.Invalid,
		"dropped":       snap.Dropped,
		"subscriptions": snap.Subscriptions,
	})
}

func (s *Server) handleLogs(w http.ResponseWriter, r *http.Request) {
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	entries := s.log.Since(since)
	writeJSON(w, http.StatusOK, map[string]any{"seq": s.log.Seq(), "entries": entries})
}

// ---------- 配置 ----------

func (s *Server) handleGetConfig(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.store.Get())
}

// handlePutConfig 局部更新配置（只允许修改 server / pool / speedtest / output / scheduler）。
func (s *Server) handlePutConfig(w http.ResponseWriter, r *http.Request) {
	var patch map[string]any
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&patch); err != nil {
		writeErr(w, http.StatusBadRequest, "请求体解析失败: "+err.Error())
		return
	}
	allowed := map[string]bool{"server": true, "pool": true, "speedtest": true, "output": true, "scheduler": true}
	for key := range patch {
		if !allowed[key] {
			delete(patch, key)
		}
	}
	if len(patch) == 0 {
		writeErr(w, http.StatusBadRequest, "没有可更新的字段")
		return
	}
	if section, ok := patch["scheduler"].(map[string]any); ok {
		if expr, ok := section["cron"].(string); ok && strings.TrimSpace(expr) != "" {
			if err := schedule.Validate(expr); err != nil {
				writeErr(w, http.StatusBadRequest, err.Error())
				return
			}
		}
		if tz, ok := section["timezone"].(string); ok && strings.TrimSpace(tz) != "" {
			if _, err := schedule.LoadLocation(tz); err != nil {
				writeErr(w, http.StatusBadRequest, err.Error())
				return
			}
		}
	}
	data, err := yaml.Marshal(patch)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	err = s.store.Update(func(c *config.Config) error {
		return yaml.Unmarshal(data, c)
	})
	if err != nil {
		writeErr(w, http.StatusBadRequest, "配置更新失败: "+err.Error())
		return
	}
	s.log.Info("配置已更新（订阅列表未受影响）")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "config": s.store.Get()})
}

// handleSchedulePreview 预览 cron 表达式接下来的触发时间，便于确认表达式是否符合预期。
func (s *Server) handleSchedulePreview(w http.ResponseWriter, r *http.Request) {
	expr := r.URL.Query().Get("cron")
	timezone := r.URL.Query().Get("timezone")

	loc, err := schedule.LoadLocation(timezone)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	times, err := schedule.Preview(expr, loc, 5)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	list := make([]string, 0, len(times))
	for _, t := range times {
		list = append(list, t.Format("2006-01-02 15:04:05 MST"))
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "next": list})
}

func (s *Server) handleReload(w http.ResponseWriter, r *http.Request) {
	store, err := config.Load(s.store.Path())
	if err != nil {
		writeErr(w, http.StatusBadRequest, "重新加载失败: "+err.Error())
		return
	}
	if err := s.store.Update(func(c *config.Config) error {
		next := store.Get()
		*c = *next
		return nil
	}); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.log.Info("已重新加载配置文件")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ---------- 订阅管理 ----------

func (s *Server) handleListSubs(w http.ResponseWriter, r *http.Request) {
	cfg := s.store.Get()
	writeJSON(w, http.StatusOK, map[string]any{"subscriptions": cfg.Subscriptions})
}

type subPayload struct {
	Name      string `json:"name"`
	URL       string `json:"url"`
	Disabled  *bool  `json:"disabled"`
	UserAgent string `json:"user_agent"`
	Remark    string `json:"remark"`
}

func (s *Server) handleCreateSub(w http.ResponseWriter, r *http.Request) {
	var payload subPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&payload); err != nil {
		writeErr(w, http.StatusBadRequest, "请求体解析失败: "+err.Error())
		return
	}
	payload.URL = strings.TrimSpace(payload.URL)
	payload.Name = strings.TrimSpace(payload.Name)
	if payload.URL == "" {
		writeErr(w, http.StatusBadRequest, "订阅地址不能为空")
		return
	}
	if !strings.HasPrefix(payload.URL, "http://") && !strings.HasPrefix(payload.URL, "https://") {
		writeErr(w, http.StatusBadRequest, "订阅地址必须以 http:// 或 https:// 开头")
		return
	}
	sub := config.Subscription{
		ID:        config.NewID(),
		Name:      payload.Name,
		URL:       payload.URL,
		UserAgent: strings.TrimSpace(payload.UserAgent),
		Remark:    strings.TrimSpace(payload.Remark),
	}
	if sub.Name == "" {
		sub.Name = fmt.Sprintf("订阅%s", sub.ID[:4])
	}
	if payload.Disabled != nil {
		sub.Disabled = *payload.Disabled
	}
	if err := s.store.Update(func(c *config.Config) error {
		c.Subscriptions = append(c.Subscriptions, sub)
		return nil
	}); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.log.Info("已添加订阅「%s」", sub.Name)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "subscription": sub})
}

func (s *Server) handleUpdateSub(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var payload subPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&payload); err != nil {
		writeErr(w, http.StatusBadRequest, "请求体解析失败: "+err.Error())
		return
	}
	var updated config.Subscription
	err := s.store.Update(func(c *config.Config) error {
		idx := c.FindSubscription(id)
		if idx < 0 {
			return fmt.Errorf("订阅不存在")
		}
		sub := &c.Subscriptions[idx]
		if name := strings.TrimSpace(payload.Name); name != "" {
			sub.Name = name
		}
		if url := strings.TrimSpace(payload.URL); url != "" {
			if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
				return fmt.Errorf("订阅地址必须以 http:// 或 https:// 开头")
			}
			sub.URL = url
		}
		if payload.Disabled != nil {
			sub.Disabled = *payload.Disabled
		}
		if payload.UserAgent != "" {
			sub.UserAgent = strings.TrimSpace(payload.UserAgent)
		}
		if payload.Remark != "" {
			sub.Remark = strings.TrimSpace(payload.Remark)
		}
		updated = *sub
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "subscription": updated})
}

func (s *Server) handleDeleteSub(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	err := s.store.Update(func(c *config.Config) error {
		idx := c.FindSubscription(id)
		if idx < 0 {
			return fmt.Errorf("订阅不存在")
		}
		c.Subscriptions = append(c.Subscriptions[:idx], c.Subscriptions[idx+1:]...)
		return nil
	})
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	s.log.Info("已删除订阅 %s", id)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// handleCheckSub 单独测试一个订阅，返回解析到的节点数与前几个节点名。
func (s *Server) handleCheckSub(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	cfg := s.store.Get()
	idx := cfg.FindSubscription(id)
	if idx < 0 {
		writeErr(w, http.StatusNotFound, "订阅不存在")
		return
	}
	sub := cfg.Subscriptions[idx]

	fetcher := subscription.New(s.log, 60*time.Second)
	res, err := fetcher.Fetch(r.Context(), sub)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	samples := make([]string, 0, 8)
	for i, n := range res.Nodes {
		if i >= 8 {
			break
		}
		samples = append(samples, fmt.Sprintf("%s (%s)", n.Name(), n.Type()))
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"count":   len(res.Nodes),
		"size":    res.Size,
		"samples": samples,
		"traffic": res.Traffic,
	})
}

// ---------- 执行 ----------

func (s *Server) handleRun(w http.ResponseWriter, r *http.Request) {
	reuse := r.URL.Query().Get("cache") == "1" || r.URL.Query().Get("reuse") == "1"
	trigger := r.URL.Query().Get("trigger")
	if trigger == "" {
		if reuse {
			trigger = "手动重新测速"
		} else {
			trigger = "手动生成"
		}
	}
	if s.runner.Running() {
		writeErr(w, http.StatusConflict, "已有任务正在运行")
		return
	}
	// 后台任务必须脱离 HTTP 请求的生命周期，否则响应写完上下文就被取消。
	taskCtx := context.WithoutCancel(r.Context())
	go func() {
		if _, err := s.runner.Run(taskCtx, trigger, runner.Options{ReuseNodes: reuse}); err != nil {
			s.log.Error("任务执行失败：%v", err)
		}
	}()
	writeJSON(w, http.StatusAccepted, map[string]any{"ok": true, "message": "任务已开始"})
}

// handleProbe 用内核单独探测某个节点，返回各探测地址的原始结果与内核日志。
func (s *Server) handleProbe(w http.ResponseWriter, r *http.Request) {
	var payload struct {
		Name string   `json:"name"`
		URLs []string `json:"urls"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&payload); err != nil {
		writeErr(w, http.StatusBadRequest, "请求体解析失败: "+err.Error())
		return
	}
	if strings.TrimSpace(payload.Name) == "" {
		writeErr(w, http.StatusBadRequest, "缺少节点名称")
		return
	}
	snap := s.runner.Last()
	if snap == nil || len(snap.Nodes) == 0 {
		writeErr(w, http.StatusBadRequest, "还没有节点数据，请先执行一次生成")
		return
	}
	var target *model.Node
	for _, n := range snap.Nodes {
		if n.Name() == payload.Name {
			target = n
			break
		}
	}
	if target == nil {
		writeErr(w, http.StatusNotFound, "节点不存在："+payload.Name)
		return
	}

	cfg := s.store.Get()
	ctx, cancel := context.WithTimeout(context.WithoutCancel(r.Context()), 3*time.Minute)
	defer cancel()
	s.log.Info("开始探测节点 %s", target.Name())
	report, err := speedtest.ProbeNode(ctx, cfg.SpeedTest, s.store.Dir(), target, payload.URLs, &logReporter{log: s.log})
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "探测失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, report)
}

// logReporter 把测速包的日志转发到服务日志。
type logReporter struct {
	log *logx.Buffer
}

func (l *logReporter) Log(level, format string, args ...any) {
	if l.log == nil {
		return
	}
	switch strings.ToUpper(level) {
	case "DEBUG":
		l.log.Debug(format, args...)
	case "WARN":
		l.log.Warn(format, args...)
	case "ERROR":
		l.log.Error(format, args...)
	default:
		l.log.Info(format, args...)
	}
}

func (l *logReporter) Progress(string, int, int) {}

// ---------- 工具 ----------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]any{"ok": false, "error": msg})
}
