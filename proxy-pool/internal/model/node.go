// Package model 定义代理节点的统一数据结构。
// 节点以 Clash(mihomo) 的字段为唯一真源保存在 Raw 中，
// 因此生成的配置文件可以完整保留协议参数（ws-opts / reality-opts / plugin-opts 等）。
package model

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// Node 一个代理节点。
type Node struct {
	// Raw 节点原始字段，键值遵循 Clash 规范。
	Raw map[string]any `json:"raw"`

	// Source 来源订阅名称。
	Source string `json:"source"`
	// OriginName 订阅中的原始名称。
	OriginName string `json:"origin_name"`
	// Region 所属地区中文名。
	Region string `json:"region"`
	// RegionCode 地区代码，如 HK / JP。
	RegionCode string `json:"region_code"`
	// Emoji 地区旗帜。
	Emoji string `json:"emoji"`

	// Delay 延迟（毫秒），-1 表示未测。
	Delay int `json:"delay"`
	// Speed 下载速度（MB/s）。
	Speed float64 `json:"speed"`
	// Alive 是否可用。
	Alive bool `json:"alive"`
	// Error 测速失败原因。
	Error string `json:"error,omitempty"`
}

// New 创建一个节点。
func New(raw map[string]any) *Node {
	if raw == nil {
		raw = map[string]any{}
	}
	return &Node{Raw: raw, Delay: -1}
}

// Name 节点名。
func (n *Node) Name() string { return GetString(n.Raw, "name") }

// SetName 设置节点名。
func (n *Node) SetName(name string) { n.Raw["name"] = name }

// Type 节点类型（小写）。
func (n *Node) Type() string { return strings.ToLower(GetString(n.Raw, "type")) }

// Server 服务器地址。
func (n *Node) Server() string { return GetString(n.Raw, "server") }

// Port 端口。
func (n *Node) Port() int { return GetInt(n.Raw, "port") }

// Endpoint 返回 server:port。
func (n *Node) Endpoint() string {
	return fmt.Sprintf("%s:%d", n.Server(), n.Port())
}

// Valid 校验必要字段。
func (n *Node) Valid() error {
	if n.Type() == "" {
		return fmt.Errorf("缺少 type")
	}
	if n.Server() == "" {
		return fmt.Errorf("缺少 server")
	}
	if n.Port() <= 0 || n.Port() > 65535 {
		return fmt.Errorf("端口不合法: %v", n.Raw["port"])
	}
	return nil
}

// Fingerprint 去重指纹：同协议、同服务器、同端口、同认证信息视为同一节点。
func (n *Node) Fingerprint() string {
	var sb strings.Builder
	sb.WriteString(n.Type())
	sb.WriteByte('|')
	sb.WriteString(strings.ToLower(strings.TrimSpace(n.Server())))
	sb.WriteByte('|')
	sb.WriteString(strconv.Itoa(n.Port()))
	for _, key := range []string{"uuid", "password", "cipher", "alterId", "network", "sni", "servername", "obfs", "plugin", "flow"} {
		if v, ok := n.Raw[key]; ok {
			s := GetString(n.Raw, key)
			if s != "" {
				sb.WriteByte('|')
				sb.WriteString(key)
				sb.WriteByte('=')
				sb.WriteString(strings.ToLower(s))
			}
			_ = v
		}
	}
	return sb.String()
}

// GetString 从 map 中读取字符串。
func GetString(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	v, ok := m[key]
	if !ok || v == nil {
		return ""
	}
	switch t := v.(type) {
	case string:
		return strings.TrimSpace(t)
	case fmt.Stringer:
		return t.String()
	case bool:
		return strconv.FormatBool(t)
	case int:
		return strconv.Itoa(t)
	case int64:
		return strconv.FormatInt(t, 10)
	case float64:
		if t == float64(int64(t)) {
			return strconv.FormatInt(int64(t), 10)
		}
		return strconv.FormatFloat(t, 'f', -1, 64)
	default:
		return strings.TrimSpace(fmt.Sprintf("%v", t))
	}
}

// GetInt 从 map 中读取整数（兼容字符串）。
func GetInt(m map[string]any, key string) int {
	if m == nil {
		return 0
	}
	v, ok := m[key]
	if !ok || v == nil {
		return 0
	}
	switch t := v.(type) {
	case int:
		return t
	case int64:
		return int(t)
	case float64:
		return int(t)
	case bool:
		if t {
			return 1
		}
		return 0
	case string:
		s := strings.TrimSpace(t)
		if s == "" {
			return 0
		}
		if i, err := strconv.Atoi(s); err == nil {
			return i
		}
		if f, err := strconv.ParseFloat(s, 64); err == nil {
			return int(f)
		}
	}
	return 0
}

// GetBool 从 map 中读取布尔值（兼容 0/1、"true"）。
func GetBool(m map[string]any, key string) bool {
	if m == nil {
		return false
	}
	v, ok := m[key]
	if !ok || v == nil {
		return false
	}
	switch t := v.(type) {
	case bool:
		return t
	case int:
		return t != 0
	case float64:
		return t != 0
	case string:
		s := strings.ToLower(strings.TrimSpace(t))
		return s == "true" || s == "1" || s == "yes"
	}
	return false
}

// SortByQuality 按「可用 > 延迟 > 速度」排序。
func SortByQuality(nodes []*Node) {
	sort.SliceStable(nodes, func(i, j int) bool {
		a, b := nodes[i], nodes[j]
		if a.Alive != b.Alive {
			return a.Alive
		}
		ad, bd := a.Delay, b.Delay
		if ad <= 0 {
			ad = 1 << 30
		}
		if bd <= 0 {
			bd = 1 << 30
		}
		if ad != bd {
			return ad < bd
		}
		return a.Speed > b.Speed
	})
}
