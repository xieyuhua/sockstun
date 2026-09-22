// Package subscription 负责拉取订阅地址并解析为节点。
package subscription

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"proxypool/internal/config"
	"proxypool/internal/logx"
	"proxypool/internal/model"
	"proxypool/internal/parser"
)

// DefaultUserAgent 部分机场按 UA 返回不同格式，Clash 客户端 UA 通常能拿到 Clash 配置。
const DefaultUserAgent = "clash-verge/v2.0.0"

// Traffic 订阅流量信息（如果服务端返回）。
type Traffic struct {
	Used   int64 `json:"used"`
	Total  int64 `json:"total"`
	Expire int64 `json:"expire"`
}

// Result 单个订阅的拉取结果。
type Result struct {
	Nodes   []*model.Node
	Traffic *Traffic
	Size    int
}

// Fetcher 订阅拉取器。
type Fetcher struct {
	client      *http.Client
	log         *logx.Buffer
	maxBodySize int64
}

// New 创建拉取器。
func New(log *logx.Buffer, timeout time.Duration) *Fetcher {
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	return &Fetcher{
		client: &http.Client{
			Timeout: timeout,
			Transport: &http.Transport{
				MaxIdleConns:        16,
				IdleConnTimeout:     60 * time.Second,
				TLSHandshakeTimeout: 15 * time.Second,
				Proxy:               http.ProxyFromEnvironment,
			},
		},
		log:         log,
		maxBodySize: 16 << 20,
	}
}

// Fetch 拉取并解析一个订阅。
func (f *Fetcher) Fetch(ctx context.Context, sub config.Subscription) (*Result, error) {
	url := strings.TrimSpace(sub.URL)
	if url == "" {
		return nil, fmt.Errorf("订阅地址为空")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	ua := strings.TrimSpace(sub.UserAgent)
	if ua == "" {
		ua = DefaultUserAgent
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "*/*")

	resp, err := f.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求订阅失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("订阅返回状态码 %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, f.maxBodySize))
	if err != nil {
		return nil, fmt.Errorf("读取订阅内容失败: %w", err)
	}

	nodes, err := parser.ParseContent(body, sub.Name)
	if err != nil {
		return nil, err
	}
	return &Result{
		Nodes:   nodes,
		Traffic: parseTraffic(resp.Header.Get("subscription-userinfo")),
		Size:    len(body),
	}, nil
}

func parseTraffic(header string) *Traffic {
	if strings.TrimSpace(header) == "" {
		return nil
	}
	t := &Traffic{}
	found := false
	for _, part := range strings.Split(header, ";") {
		key, value, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok {
			continue
		}
		n, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
		if err != nil {
			continue
		}
		switch strings.ToLower(strings.TrimSpace(key)) {
		case "upload":
			t.Used += n
			found = true
		case "download":
			t.Used += n
			found = true
		case "total":
			t.Total = n
			found = true
		case "expire":
			t.Expire = n
			found = true
		}
	}
	if !found {
		return nil
	}
	return t
}
