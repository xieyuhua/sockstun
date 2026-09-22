// Package generator 把节点池渲染成 Clash(mihomo) 配置文件。
package generator

import (
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"

	"gopkg.in/yaml.v3"

	"proxypool/internal/config"
	"proxypool/internal/model"
)

// placeholder 自定义模板中代表「全部节点」的占位符。
const placeholder = "__NODES__"

// 节点字段输出顺序（其余字段按字母序追加）。
var preferredKeys = []string{
	"name", "type", "server", "port", "udp",
	"cipher", "password", "uuid", "alterId", "username",
	"network", "tls", "sni", "servername", "skip-cert-verify", "client-fingerprint", "alpn", "flow",
	"ws-opts", "grpc-opts", "h2-opts", "http-opts", "reality-opts", "plugin", "plugin-opts",
	"obfs", "obfs-password", "protocol", "protocol-param", "obfs-param", "up", "down",
	"congestion-controller", "udp-relay-mode", "auth-str", "fingerprint",
}

// Build 生成配置文件内容。templatePath 非空且文件存在时使用自定义模板。
func Build(out config.OutputConfig, st config.SpeedTestConfig, nodes []*model.Node, templatePath string) ([]byte, error) {
	if len(nodes) == 0 {
		return nil, errors.New("没有可用节点，无法生成配置")
	}
	nodes, err := resolveNameConflicts(nodes, out)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(templatePath) != "" {
		if data, err := os.ReadFile(templatePath); err == nil {
			return buildFromTemplate(data, nodes)
		} else if !os.IsNotExist(err) {
			return nil, fmt.Errorf("读取模板 %s 失败: %w", templatePath, err)
		}
	}
	return buildDefault(out, st, nodes)
}

func nodeNames(nodes []*model.Node) []string {
	names := make([]string, 0, len(nodes))
	for _, n := range nodes {
		names = append(names, n.Name())
	}
	return names
}

// resolveNameConflicts 避免节点名与代理组名冲突（会导致内核启动失败）。
func resolveNameConflicts(nodes []*model.Node, out config.OutputConfig) ([]*model.Node, error) {
	reserved := map[string]bool{
		strings.TrimSpace(out.ProxyGroupName): true,
		strings.TrimSpace(out.AutoGroupName):  true,
		"DIRECT":                              true,
		"REJECT":                              true,
		"GLOBAL":                              true,
		"PASS":                                true,
		"COMPATIBLE":                          true,
	}
	seen := map[string]bool{}
	for _, n := range nodes {
		name := n.Name()
		if seen[name] {
			return nil, fmt.Errorf("节点名重复: %s", name)
		}
		seen[name] = true
		if reserved[name] {
			newName := name + "-node"
			for seen[newName] || reserved[newName] {
				newName += "-1"
			}
			n.SetName(newName)
			seen[newName] = true
		}
	}
	return nodes, nil
}

func buildDefault(out config.OutputConfig, st config.SpeedTestConfig, nodes []*model.Node) ([]byte, error) {
	names := nodeNames(nodes)

	root := newMap()
	root.add("mixed-port", out.MixedPort)
	root.add("allow-lan", out.AllowLan)
	if out.AllowLan {
		root.add("bind-address", "*")
	}
	root.add("mode", out.Mode)
	root.add("log-level", out.LogLevel)
	root.add("ipv6", out.IPv6)
	root.add("unified-delay", out.UnifiedDelay)
	if strings.TrimSpace(out.ExternalController) != "" {
		root.add("external-controller", out.ExternalController)
	}
	if strings.TrimSpace(out.Secret) != "" {
		root.add("secret", out.Secret)
	}
	if out.DNSEnable {
		root.addNode("dns", dnsNode(out.IPv6))
	}
	if out.TunEnable {
		root.addNode("tun", tunNode())
	}
	root.addNode("proxies", proxySeq(nodes))

	autoGroup := newMap()
	autoGroup.add("name", out.AutoGroupName)
	autoGroup.add("type", "url-test")
	autoGroup.add("url", st.DelayURL)
	autoGroup.add("interval", 300)
	autoGroup.add("tolerance", 50)
	autoGroup.add("lazy", true)
	autoGroup.addNode("proxies", strSeq(names))

	selectProxies := make([]string, 0, len(names)+2)
	selectProxies = append(selectProxies, out.AutoGroupName, "DIRECT")
	selectProxies = append(selectProxies, names...)
	selectGroup := newMap()
	selectGroup.add("name", out.ProxyGroupName)
	selectGroup.add("type", "select")
	selectGroup.addNode("proxies", strSeq(selectProxies))

	root.addNode("proxy-groups", seq(autoGroup.node(), selectGroup.node()))

	rules := out.Rules
	if len(rules) == 0 {
		rules = []string{"MATCH," + out.ProxyGroupName}
	}
	root.addNode("rules", strSeq(rules))

	for _, key := range sortedKeys(out.Extra) {
		root.add(key, out.Extra[key])
	}

	return emit(root.node())
}

func dnsNode(ipv6 bool) *yaml.Node {
	dns := newMap()
	dns.add("enable", true)
	dns.add("ipv6", ipv6)
	dns.add("enhanced-mode", "fake-ip")
	dns.add("fake-ip-range", "198.18.0.1/16")
	dns.addNode("fake-ip-filter", strSeq([]string{"*.lan", "*.local", "localhost.ptlogin2.qq.com"}))
	dns.addNode("default-nameserver", strSeq([]string{"223.5.5.5", "119.29.29.29"}))
	dns.addNode("nameserver", strSeq([]string{"https://223.5.5.5/dns-query", "https://doh.pub/dns-query"}))
	dns.addNode("fallback", strSeq([]string{"https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"}))
	return dns.node()
}

func tunNode() *yaml.Node {
	tun := newMap()
	tun.add("enable", true)
	tun.add("stack", "mixed")
	tun.add("auto-route", true)
	tun.add("auto-detect-interface", true)
	tun.add("dns-hijack", []string{"any:53"})
	return tun.node()
}

// buildFromTemplate 用节点替换模板中的 proxies 与 __NODES__ 占位符。
func buildFromTemplate(data []byte, nodes []*model.Node) ([]byte, error) {
	var doc yaml.Node
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return nil, fmt.Errorf("解析模板失败: %w", err)
	}
	root := docRoot(&doc)
	if root == nil || root.Kind != yaml.MappingNode {
		return nil, errors.New("模板格式不正确：根节点必须是映射")
	}

	names := nodeNames(nodes)
	setMappingKey(root, "proxies", proxySeq(nodes), true)
	replacePlaceholder(root, placeholder, names)
	if findMappingKey(root, "proxy-groups") == nil {
		autoGroup := newMap()
		autoGroup.add("name", "♻️ 自动选择")
		autoGroup.add("type", "url-test")
		autoGroup.add("url", "http://www.gstatic.com/generate_204")
		autoGroup.add("interval", 300)
		autoGroup.addNode("proxies", strSeq(names))
		setMappingKey(root, "proxy-groups", seq(autoGroup.node()), true)
	}
	return marshal(&doc)
}

func docRoot(doc *yaml.Node) *yaml.Node {
	if doc == nil {
		return nil
	}
	if doc.Kind == yaml.DocumentNode && len(doc.Content) > 0 {
		return doc.Content[0]
	}
	return doc
}

func findMappingKey(m *yaml.Node, key string) *yaml.Node {
	if m == nil || m.Kind != yaml.MappingNode {
		return nil
	}
	for i := 0; i+1 < len(m.Content); i += 2 {
		if m.Content[i].Value == key {
			return m.Content[i+1]
		}
	}
	return nil
}

func setMappingKey(m *yaml.Node, key string, value *yaml.Node, appendIfMissing bool) {
	if m == nil || m.Kind != yaml.MappingNode {
		return
	}
	for i := 0; i+1 < len(m.Content); i += 2 {
		if m.Content[i].Value == key {
			m.Content[i+1] = value
			return
		}
	}
	if appendIfMissing {
		m.Content = append(m.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, value)
	}
}

// replacePlaceholder 递归把占位符标量原地替换为节点名序列。
func replacePlaceholder(n *yaml.Node, ph string, names []string) {
	if n == nil {
		return
	}
	if n.Kind == yaml.ScalarNode && n.Value == ph {
		n.Kind = yaml.SequenceNode
		n.Tag = "!!seq"
		n.Value = ""
		n.Style = 0
		n.Content = strSeq(names).Content
		return
	}
	for _, child := range n.Content {
		replacePlaceholder(child, ph, names)
	}
}

func proxySeq(nodes []*model.Node) *yaml.Node {
	items := make([]*yaml.Node, 0, len(nodes))
	for _, n := range nodes {
		items = append(items, proxyNode(n))
	}
	return seq(items...)
}

func proxyNode(n *model.Node) *yaml.Node {
	m := newMap()
	added := map[string]bool{}
	for _, key := range preferredKeys {
		v, ok := n.Raw[key]
		if !ok || isEmptyValue(v) {
			continue
		}
		m.add(key, v)
		added[key] = true
	}
	for _, key := range sortedKeys(n.Raw) {
		if added[key] || isEmptyValue(n.Raw[key]) {
			continue
		}
		m.add(key, n.Raw[key])
	}
	return m.node()
}

func isEmptyValue(v any) bool {
	if v == nil {
		return true
	}
	switch t := v.(type) {
	case string:
		return strings.TrimSpace(t) == ""
	case []string:
		return len(t) == 0
	case []any:
		return len(t) == 0
	case map[string]any:
		return len(t) == 0
	}
	return false
}

func sortedKeys(m map[string]any) []string {
	if len(m) == 0 {
		return nil
	}
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func marshal(doc *yaml.Node) ([]byte, error) {
	doc.HeadComment = ""
	data, err := yaml.Marshal(doc)
	if err != nil {
		return nil, err
	}
	return data, nil
}
