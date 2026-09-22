package parser

import (
	"errors"
	"fmt"
	"strings"

	"gopkg.in/yaml.v3"

	"proxypool/internal/model"
)

// ParseClash 解析 Clash 订阅（YAML 中的 proxies 段）。
func ParseClash(content []byte, source string) ([]*model.Node, error) {
	var doc struct {
		Proxies []map[string]any `yaml:"proxies"`
	}
	if err := yaml.Unmarshal(content, &doc); err != nil {
		return nil, fmt.Errorf("解析 Clash 配置失败: %w", err)
	}
	if len(doc.Proxies) == 0 {
		return nil, errors.New("Clash 配置中没有 proxies 节点")
	}

	nodes := make([]*model.Node, 0, len(doc.Proxies))
	for _, p := range doc.Proxies {
		if len(p) == 0 {
			continue
		}
		normalizeProxy(p)
		node := model.New(p)
		node.Source = source
		node.OriginName = node.Name()
		nodes = append(nodes, node)
	}
	if len(nodes) == 0 {
		return nil, errors.New("Clash 配置中没有可用节点")
	}
	return nodes, nil
}

// normalizeProxy 统一字段类型，保证后续处理稳定。
func normalizeProxy(p map[string]any) {
	// name / type / server 统一为字符串
	for _, key := range []string{"name", "type", "server", "server-port"} {
		if v, ok := p[key]; ok && v != nil {
			p[key] = model.GetString(p, key)
		}
	}
	if t, ok := p["type"].(string); ok {
		p["type"] = strings.ToLower(strings.TrimSpace(t))
	}
	p["server"] = strings.TrimSpace(model.GetString(p, "server"))

	// 数值字段
	if v, ok := p["port"]; ok && v != nil {
		p["port"] = model.GetInt(p, "port")
	}
	for _, key := range []string{"alterId", "alterid"} {
		if v, ok := p[key]; ok && v != nil {
			p[key] = model.GetInt(p, key)
		}
	}
	if _, ok := p["udp"]; !ok {
		p["udp"] = true
	}
	if name, ok := p["name"].(string); ok {
		p["name"] = strings.TrimSpace(name)
	}
}
