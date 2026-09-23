package generator

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// PublishReport 发布结果。
type PublishReport struct {
	Path       string
	Size       int
	BackupPath string
}

// Publish 在整轮任务成功后发布新的配置文件：
//
//  1. 先校验内容（必须是含 proxies 与 proxy-groups 的合法 YAML），校验不过直接放弃；
//  2. 保留上一版为 <path>.bak（可选）；
//  3. 写入临时文件并 fsync；
//  4. 最后用 rename 原子替换目标文件。
//
// 因此订阅地址在整个过程中要么是旧文件、要么是新文件，不会读到写了一半的内容；
// 一旦任务失败或校验不过，旧文件保持原样。
func Publish(path string, data []byte, keepBackup bool) (*PublishReport, error) {
	if err := validateConfig(data); err != nil {
		return nil, err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}

	report := &PublishReport{Path: path, Size: len(data)}

	if keepBackup {
		if _, err := os.Stat(path); err == nil {
			backup := path + ".bak"
			if err := copyFile(path, backup); err != nil {
				return nil, fmt.Errorf("备份旧配置失败: %w", err)
			}
			report.BackupPath = backup
		}
	}

	tmp := path + ".tmp"
	if err := writeSync(tmp, data); err != nil {
		os.Remove(tmp)
		return nil, err
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return nil, fmt.Errorf("替换 %s 失败（文件可能被其它程序占用）: %w", path, err)
	}
	return report, nil
}

// validateConfig 校验生成结果，避免把半成品或坏内容发布出去。
func validateConfig(data []byte) error {
	if len(bytes.TrimSpace(data)) == 0 {
		return errors.New("生成内容为空，已放弃替换配置文件")
	}
	var doc struct {
		Proxies     []map[string]any `yaml:"proxies"`
		ProxyGroups []map[string]any `yaml:"proxy-groups"`
	}
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return fmt.Errorf("生成内容不是合法 YAML，已放弃替换配置文件: %w", err)
	}
	if len(doc.Proxies) == 0 {
		return errors.New("生成的配置中没有节点，已放弃替换配置文件")
	}
	if len(doc.ProxyGroups) == 0 {
		return errors.New("生成的配置中没有代理组，已放弃替换配置文件")
	}
	for _, proxy := range doc.Proxies {
		if proxy["name"] == nil || proxy["type"] == nil || proxy["server"] == nil || proxy["port"] == nil {
			return fmt.Errorf("生成的配置存在字段缺失的节点（%v），已放弃替换配置文件", proxy["name"])
		}
	}
	return nil
}

func writeSync(path string, data []byte) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
