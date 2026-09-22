package parser

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestParseSSLinks(t *testing.T) {
	cases := []struct {
		link   string
		server string
		port   int
	}{
		{"ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@1.2.3.4:8388#%E9%A6%99%E6%B8%AFA", "1.2.3.4", 8388},
		{"ss://" + base64.StdEncoding.EncodeToString([]byte("aes-256-gcm:pwd@5.6.7.8:443")) + "#B", "5.6.7.8", 443},
		{"ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@[2001:db8::1]:1080#C", "2001:db8::1", 1080},
	}
	for _, c := range cases {
		node, err := ParseLink(c.link, "test")
		if err != nil {
			t.Fatalf("解析 %s 失败: %v", c.link, err)
		}
		if node.Type() != "ss" || node.Server() != c.server || node.Port() != c.port {
			t.Fatalf("%s 解析结果不符: %s %s:%d", c.link, node.Type(), node.Server(), node.Port())
		}
	}
}

func TestParseSSPlugin(t *testing.T) {
	link := "ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@1.2.3.4:8388?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#D"
	node, err := ParseLink(link, "test")
	if err != nil {
		t.Fatal(err)
	}
	if node.Raw["plugin"] != "obfs" {
		t.Fatalf("plugin 解析错误: %v", node.Raw["plugin"])
	}
}

func TestParseVmessLink(t *testing.T) {
	payload := `{"v":"2","ps":"日本节点","add":"1.1.1.1","port":"443","id":"11111111-1111-1111-1111-111111111111",
		"aid":"0","scy":"auto","net":"ws","type":"none","host":"example.com","path":"/ws","tls":"tls","sni":"example.com"}`
	link := "vmess://" + base64.StdEncoding.EncodeToString([]byte(payload))
	node, err := ParseLink(link, "test")
	if err != nil {
		t.Fatal(err)
	}
	if node.Type() != "vmess" || node.Server() != "1.1.1.1" || node.Port() != 443 {
		t.Fatalf("vmess 解析错误: %+v", node.Raw)
	}
	if node.Name() != "日本节点" || node.Raw["network"] != "ws" {
		t.Fatalf("vmess 字段错误: %+v", node.Raw)
	}
	opts, ok := node.Raw["ws-opts"].(map[string]any)
	if !ok || opts["path"] != "/ws" {
		t.Fatalf("ws-opts 解析错误: %+v", node.Raw["ws-opts"])
	}
	if node.Raw["tls"] != true {
		t.Fatalf("tls 字段未识别")
	}
}

func TestParseURLStyleLinks(t *testing.T) {
	cases := []struct {
		link string
		typ  string
		host string
		port int
	}{
		{"vless://11111111-1111-1111-1111-111111111111@a.com:443?encryption=none&security=tls&type=ws&path=%2Fws&host=a.com&sni=a.com#X", "vless", "a.com", 443},
		{"trojan://pwd123@b.com:443?sni=b.com&allowInsecure=1#Y", "trojan", "b.com", 443},
		{"hysteria2://pwd@c.com:8443?sni=c.com&insecure=1#Z", "hysteria2", "c.com", 8443},
		{"tuic://uuid-1:pwd@d.com:443?sni=d.com#T", "tuic", "d.com", 443},
		{"socks5://u:p@e.com:1080#S", "socks5", "e.com", 1080},
	}
	for _, c := range cases {
		node, err := ParseLink(c.link, "test")
		if err != nil {
			t.Fatalf("解析 %s 失败: %v", c.link, err)
		}
		if node.Type() != c.typ || node.Server() != c.host || node.Port() != c.port {
			t.Fatalf("%s 解析结果不符: %s %s:%d", c.link, node.Type(), node.Server(), node.Port())
		}
	}
}

func TestParseSSR(t *testing.T) {
	raw := "1.2.3.4:8388:auth_aes128_md5:aes-256-cfb:tls1.2_ticket_auth:" +
		base64.RawURLEncoding.EncodeToString([]byte("pwd")) + "/?remarks=" +
		base64.RawURLEncoding.EncodeToString([]byte("香港SSR"))
	link := "ssr://" + base64.RawURLEncoding.EncodeToString([]byte(raw))
	node, err := ParseLink(link, "test")
	if err != nil {
		t.Fatal(err)
	}
	if node.Type() != "ssr" || node.Port() != 8388 || node.Raw["password"] != "pwd" {
		t.Fatalf("ssr 解析错误: %+v", node.Raw)
	}
	if node.Name() != "香港SSR" {
		t.Fatalf("ssr 备注解析错误: %q", node.Name())
	}
}

func TestParseContentFormats(t *testing.T) {
	clash := `mixed-port: 7890
proxies:
  - name: A
    type: ss
    server: 1.2.3.4
    port: 8388
    cipher: aes-128-gcm
    password: pwd
`
	nodes, err := ParseContent([]byte(clash), "clash")
	if err != nil || len(nodes) != 1 {
		t.Fatalf("Clash 解析失败: %v, %d", err, len(nodes))
	}
	if nodes[0].Port() != 8388 {
		t.Fatalf("端口字段类型未规范化: %v", nodes[0].Raw["port"])
	}

	links := "vless://uuid@a.com:443?security=tls&type=ws&path=/x#节点1\ntrojan://pwd@b.com:443#节点2"
	encoded := base64.StdEncoding.EncodeToString([]byte(links))
	nodes, err = ParseContent([]byte(encoded), "base64")
	if err != nil || len(nodes) != 2 {
		t.Fatalf("Base64 订阅解析失败: %v, %d", err, len(nodes))
	}

	nodes, err = ParseContent([]byte(links), "plain")
	if err != nil || len(nodes) != 2 {
		t.Fatalf("明文订阅解析失败: %v, %d", err, len(nodes))
	}

	if _, err = ParseContent([]byte("not a subscription"), "x"); err == nil {
		t.Fatal("无法识别的内容应返回错误")
	}
	if !strings.Contains(links, "vless://") {
		t.Fatal("测试数据异常")
	}
}
