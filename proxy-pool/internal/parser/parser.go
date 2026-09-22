// Package parser 负责把订阅内容解析成统一的节点列表。
// 支持两类订阅：Clash(mihomo) YAML 配置、以及 Base64/明文分享链接列表
// （ss / ssr / vmess / vless / trojan / hysteria / hysteria2 / tuic / socks5）。
package parser

import (
	"encoding/base64"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"proxypool/internal/model"
)

// ParseContent 自动识别订阅格式并解析为节点列表。
func ParseContent(content []byte, source string) ([]*model.Node, error) {
	text := strings.TrimSpace(string(content))
	if text == "" {
		return nil, errors.New("订阅内容为空")
	}

	// 1) Clash YAML
	if looksLikeClash(text) {
		if nodes, err := ParseClash([]byte(text), source); err == nil && len(nodes) > 0 {
			return nodes, nil
		}
	}

	// 2) Base64 编码的链接列表
	if decoded, ok := tryBase64(text); ok {
		if nodes := parseLinkLines(string(decoded), source); len(nodes) > 0 {
			return nodes, nil
		}
	}

	// 3) 明文链接列表
	if nodes := parseLinkLines(text, source); len(nodes) > 0 {
		return nodes, nil
	}

	return nil, errors.New("无法识别的订阅格式（既不是 Clash YAML，也不含可解析的分享链接）")
}

func looksLikeClash(text string) bool {
	if strings.Contains(text, "proxies:") {
		return true
	}
	for _, key := range []string{"mixed-port:", "socks-port:", "port:", "proxy-groups:", "proxy-providers:"} {
		if strings.Contains(text, key) {
			return true
		}
	}
	return false
}

func parseLinkLines(text, source string) []*model.Node {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	lines := strings.Split(text, "\n")
	nodes := make([]*model.Node, 0, len(lines))
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "//") {
			continue
		}
		node, err := ParseLink(line, source)
		if err != nil || node == nil {
			continue
		}
		nodes = append(nodes, node)
	}
	return nodes
}

// ParseLink 解析单条分享链接。无法识别时返回 (nil, nil)。
func ParseLink(link, source string) (*model.Node, error) {
	line := strings.TrimSpace(link)
	if line == "" {
		return nil, nil
	}
	lower := strings.ToLower(line)
	switch {
	case strings.HasPrefix(lower, "ssr://"):
		return parseSSR(line, source)
	case strings.HasPrefix(lower, "ss://"):
		return parseSS(line, source)
	case strings.HasPrefix(lower, "vmess://"):
		return parseVmess(line, source)
	case strings.HasPrefix(lower, "vless://"):
		return parseVless(line, source)
	case strings.HasPrefix(lower, "trojan://"):
		return parseTrojan(line, source)
	case strings.HasPrefix(lower, "hysteria2://"), strings.HasPrefix(lower, "hy2://"):
		return parseHysteria2(line, source)
	case strings.HasPrefix(lower, "hysteria://"), strings.HasPrefix(lower, "hy://"):
		return parseHysteria(line, source)
	case strings.HasPrefix(lower, "tuic://"):
		return parseTuic(line, source)
	case strings.HasPrefix(lower, "socks5://"), strings.HasPrefix(lower, "socks://"):
		return parseSocks5(line, source)
	default:
		return nil, nil
	}
}

// tryBase64 尝试多种 Base64 变体解码（用于识别整份订阅内容）。
func tryBase64(s string) ([]byte, bool) { return tryBase64Min(s, 16) }

// tryBase64Min 尝试多种 Base64 变体解码，minLen 为最短可接受长度。
func tryBase64Min(s string, minLen int) ([]byte, bool) {
	cleaned := strings.Map(func(r rune) rune {
		switch r {
		case '\n', '\r', ' ', '\t':
			return -1
		}
		return r
	}, s)
	if len(cleaned) < minLen {
		return nil, false
	}
	for _, enc := range []*base64.Encoding{
		base64.StdEncoding,
		base64.RawStdEncoding,
		base64.URLEncoding,
		base64.RawURLEncoding,
	} {
		if out, err := enc.DecodeString(cleaned); err == nil && len(out) > 0 {
			return out, true
		}
	}
	return nil, false
}

// decodeComponent 宽容地处理 URL 转义。
func decodeComponent(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if out, err := url.PathUnescape(s); err == nil {
		return out
	}
	if out, err := url.QueryUnescape(s); err == nil {
		return out
	}
	return s
}

func atoiSafe(s string) int {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	if i, err := strconv.Atoi(s); err == nil {
		return i
	}
	if f, err := strconv.ParseFloat(s, 64); err == nil {
		return int(f)
	}
	return 0
}

func cutColon(s string) (string, string) {
	if i := strings.Index(s, ":"); i >= 0 {
		return s[:i], s[i+1:]
	}
	return s, ""
}

// splitHostPort 解析 host:port，兼容 IPv6。
func splitHostPort(s string) (string, int, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", 0, errors.New("缺少服务器地址")
	}
	if strings.HasPrefix(s, "[") {
		end := strings.Index(s, "]")
		if end < 0 {
			return "", 0, errors.New("IPv6 地址格式错误")
		}
		host := s[1:end]
		rest := strings.TrimPrefix(s[end+1:], ":")
		port := atoiSafe(rest)
		if port <= 0 {
			return "", 0, errors.New("缺少端口")
		}
		return host, port, nil
	}
	i := strings.LastIndex(s, ":")
	if i < 0 {
		return "", 0, errors.New("缺少端口")
	}
	host := strings.TrimSpace(s[:i])
	port := atoiSafe(s[i+1:])
	if host == "" || port <= 0 {
		return "", 0, fmt.Errorf("地址不合法: %s", s)
	}
	return host, port, nil
}

// linkParts 手工拆解的通用链接结构，比 net/url 更宽容。
type linkParts struct {
	scheme string
	user   string
	host   string
	port   int
	path   string
	query  url.Values
	frag   string
}

func splitLink(link string) (*linkParts, error) {
	idx := strings.Index(link, "://")
	if idx < 0 {
		return nil, errors.New("不是合法的分享链接")
	}
	lp := &linkParts{scheme: strings.ToLower(link[:idx]), query: url.Values{}}
	rest := link[idx+3:]

	if i := strings.Index(rest, "#"); i >= 0 {
		lp.frag = decodeComponent(rest[i+1:])
		rest = rest[:i]
	}
	if i := strings.Index(rest, "?"); i >= 0 {
		q, _ := url.ParseQuery(rest[i+1:])
		if q != nil {
			lp.query = q
		}
		rest = rest[:i]
	}
	if i := strings.Index(rest, "/"); i >= 0 {
		lp.path = rest[i:]
		rest = rest[:i]
	}
	if i := strings.LastIndex(rest, "@"); i >= 0 {
		lp.user = rest[:i]
		rest = rest[i+1:]
	}
	host, port, err := splitHostPort(rest)
	if err != nil {
		return nil, err
	}
	lp.host, lp.port = host, port
	return lp, nil
}

func (lp *linkParts) q(keys ...string) string {
	for _, k := range keys {
		if v := strings.TrimSpace(lp.query.Get(k)); v != "" {
			return v
		}
	}
	return ""
}

func (lp *linkParts) qBool(keys ...string) bool {
	for _, k := range keys {
		v := strings.ToLower(strings.TrimSpace(lp.query.Get(k)))
		if v == "" {
			continue
		}
		switch v {
		case "1", "true", "yes", "on":
			return true
		case "0", "false", "no", "off":
			return false
		}
	}
	return false
}

func (lp *linkParts) userParts() (string, string) {
	u := decodeComponent(lp.user)
	return cutColon(u)
}

// newNode 创建一个节点并设置公共字段。
func newNode(source, typ, name, server string, port int) *model.Node {
	raw := map[string]any{
		"name":   strings.TrimSpace(name),
		"type":   typ,
		"server": server,
		"port":   port,
		"udp":    true,
	}
	if raw["name"] == "" {
		raw["name"] = fmt.Sprintf("%s:%d", server, port)
	}
	node := model.New(raw)
	node.Source = source
	node.OriginName = raw["name"].(string)
	return node
}

func setIfNotEmpty(raw map[string]any, key, value string) {
	if strings.TrimSpace(value) != "" {
		raw[key] = strings.TrimSpace(value)
	}
}

func splitList(s string) []string {
	parts := strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ';' })
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
