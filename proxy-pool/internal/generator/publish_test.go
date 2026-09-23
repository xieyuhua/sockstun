package generator

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const validConfig = `mixed-port: 7890
proxies:
  - name: 🇭🇰HK-001
    type: ss
    server: 1.2.3.4
    port: 8388
    cipher: aes-128-gcm
    password: pwd
proxy-groups:
  - name: 🚀 节点选择
    type: select
    proxies:
      - 🇭🇰HK-001
rules:
  - MATCH,🚀 节点选择
`

func TestPublishWritesAndBacksUp(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "data", "clash.yaml")

	report, err := Publish(path, []byte(validConfig), true)
	if err != nil {
		t.Fatalf("首次发布失败: %v", err)
	}
	if report.BackupPath != "" {
		t.Fatal("首次发布不应产生备份")
	}
	if data, err := os.ReadFile(path); err != nil || !strings.Contains(string(data), "HK-001") {
		t.Fatalf("文件内容不正确: %v", err)
	}

	updated := strings.Replace(validConfig, "HK-001", "🇯🇵JP-002", 1)
	updated = strings.ReplaceAll(updated, "HK-001", "JP-002")
	report, err = Publish(path, []byte(updated), true)
	if err != nil {
		t.Fatalf("第二次发布失败: %v", err)
	}
	if report.BackupPath == "" {
		t.Fatal("应保留上一版备份")
	}
	backup, err := os.ReadFile(path + ".bak")
	if err != nil || !strings.Contains(string(backup), "HK-001") {
		t.Fatalf("备份内容应为上一版: %v", err)
	}
	current, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(current), "JP-002") {
		t.Fatalf("新文件未生效: %v", err)
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("临时文件应被清理")
	}
}

func TestPublishRejectsInvalidContent(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "clash.yaml")
	if _, err := Publish(path, []byte(validConfig), true); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(path)

	cases := map[string]string{
		"空内容":     "",
		"非法 YAML": "proxies: [\n  - name",
		"没有节点":    "proxy-groups:\n  - name: A\n    type: select\n",
		"没有代理组":   "proxies:\n  - name: A\n    type: ss\n    server: 1.1.1.1\n    port: 80\n",
		"节点字段缺失":  "proxies:\n  - name: A\n    type: ss\nproxy-groups:\n  - name: A\n    type: select\n",
	}
	for name, content := range cases {
		if _, err := Publish(path, []byte(content), true); err == nil {
			t.Fatalf("%s：应拒绝发布", name)
		}
		after, _ := os.ReadFile(path)
		if string(before) != string(after) {
			t.Fatalf("%s：发布会污染已有文件", name)
		}
	}
}

func TestPublishWithoutBackup(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "clash.yaml")
	if _, err := Publish(path, []byte(validConfig), false); err != nil {
		t.Fatal(err)
	}
	if _, err := Publish(path, []byte(validConfig), false); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path + ".bak"); !os.IsNotExist(err) {
		t.Fatal("关闭备份时不应生成 .bak")
	}
}
