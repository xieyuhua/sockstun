package speedtest

import (
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"proxypool/internal/config"
)

const releaseAPI = "https://api.github.com/repos/MetaCubeX/mihomo/releases"

type ghAsset struct {
	Name string `json:"name"`
	URL  string `json:"browser_download_url"`
}

type ghRelease struct {
	TagName string    `json:"tag_name"`
	Assets  []ghAsset `json:"assets"`
}

// EnsureCore 确保内核可执行文件存在，必要时自动下载。
func EnsureCore(ctx context.Context, core config.CoreConfig, baseDir string, rep Reporter) (string, error) {
	path := strings.TrimSpace(core.Path)
	if path == "" {
		path = "bin/mihomo"
	}
	if !filepath.IsAbs(path) {
		path = filepath.Join(baseDir, path)
	}
	if config.IsWindows() && !strings.HasSuffix(strings.ToLower(path), ".exe") {
		if _, err := os.Stat(path); err != nil {
			path += ".exe"
		}
	}
	if info, err := os.Stat(path); err == nil && !info.IsDir() {
		return path, nil
	}
	if !core.AutoDownload {
		return "", fmt.Errorf("未找到内核 %s，请手动放置该文件，或开启 speedtest.core.auto_download", path)
	}
	if rep != nil {
		rep.Log("INFO", "未找到内核 %s，开始自动下载…", path)
	}
	if err := downloadCore(ctx, core, path, rep); err != nil {
		return "", err
	}
	return path, nil
}

func downloadCore(ctx context.Context, core config.CoreConfig, target string, rep Reporter) error {
	asset, err := resolveAsset(ctx, core)
	if err != nil {
		return err
	}
	downloadURL := asset.URL
	if proxy := strings.TrimSpace(core.GhProxy); proxy != "" {
		downloadURL = strings.TrimRight(proxy, "/") + "/" + asset.URL
	}
	if rep != nil {
		rep.Log("INFO", "下载内核文件 %s", asset.Name)
	}

	data, err := fetchBytes(ctx, downloadURL)
	if err != nil {
		return fmt.Errorf("下载内核失败: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}
	tmp := target + ".download"

	switch {
	case strings.HasSuffix(strings.ToLower(asset.Name), ".zip"):
		err = extractZip(data, tmp)
	case strings.HasSuffix(strings.ToLower(asset.Name), ".gz"):
		err = extractGz(data, tmp)
	default:
		err = os.WriteFile(tmp, data, 0o755)
	}
	if err != nil {
		os.Remove(tmp)
		return err
	}
	_ = os.Chmod(tmp, 0o755)
	if err := os.Rename(tmp, target); err != nil {
		return err
	}
	if rep != nil {
		rep.Log("INFO", "内核已保存至 %s", target)
	}
	return nil
}

func resolveAsset(ctx context.Context, core config.CoreConfig) (ghAsset, error) {
	api := releaseAPI + "/latest"
	if v := strings.TrimSpace(core.Version); v != "" {
		api = releaseAPI + "/tags/" + v
	}
	reqCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, api, nil)
	if err != nil {
		return ghAsset{}, err
	}
	req.Header.Set("User-Agent", "proxypool")
	req.Header.Set("Accept", "application/vnd.github+json")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return ghAsset{}, fmt.Errorf("访问 GitHub 失败（可在配置中设置 speedtest.core.gh_proxy 加速）: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode != http.StatusOK {
		return ghAsset{}, fmt.Errorf("GitHub API 返回 %s", resp.Status)
	}

	var rel ghRelease
	if err := json.Unmarshal(body, &rel); err != nil {
		return ghAsset{}, fmt.Errorf("解析 GitHub 响应失败: %w", err)
	}

	prefix := fmt.Sprintf("mihomo-%s-%s-", runtime.GOOS, runtime.GOARCH)
	bestScore := -1
	var chosen ghAsset
	for i := range rel.Assets {
		a := rel.Assets[i]
		lower := strings.ToLower(a.Name)
		if !strings.HasPrefix(lower, prefix) {
			continue
		}
		if !strings.HasSuffix(lower, ".zip") && !strings.HasSuffix(lower, ".gz") {
			continue
		}
		// 优先选择标准构建，其次 go1.x 兼容构建，最后 compatible 构建
		score := 0
		switch {
		case strings.Contains(lower, "compatible"):
			score = 2
		case strings.Contains(lower, "-go1"):
			score = 1
		}
		if bestScore == -1 || score < bestScore {
			bestScore = score
			chosen = a
		}
		if bestScore == 0 {
			break
		}
	}
	if bestScore >= 0 {
		return chosen, nil
	}
	return ghAsset{}, fmt.Errorf("GitHub Release %s 中没有适配 %s/%s 的内核文件", rel.TagName, runtime.GOOS, runtime.GOARCH)
}

// downloadClient 刻意禁用 HTTP/2：部分 GitHub 加速镜像在大文件传输时会返回
// "stream error: stream ID 1; INTERNAL_ERROR"，改用 HTTP/1.1 更稳定。
var downloadClient = &http.Client{
	Timeout: 5 * time.Minute,
	Transport: &http.Transport{
		Proxy:             http.ProxyFromEnvironment,
		ForceAttemptHTTP2: false,
		TLSNextProto:      map[string]func(string, *tls.Conn) http.RoundTripper{},
		MaxIdleConns:      4,
		IdleConnTimeout:   30 * time.Second,
	},
}

func fetchBytes(ctx context.Context, url string) ([]byte, error) {
	var lastErr error
	for attempt := 1; attempt <= 3; attempt++ {
		if attempt > 1 {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(time.Duration(attempt) * time.Second):
			}
		}
		data, err := fetchOnce(ctx, url)
		if err == nil {
			return data, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func fetchOnce(ctx context.Context, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "proxypool")
	resp, err := downloadClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New(resp.Status)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 256<<20))
	if err != nil {
		return nil, err
	}
	return data, nil
}

func extractZip(data []byte, target string) error {
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return fmt.Errorf("解压内核失败: %w", err)
	}
	for _, f := range reader.File {
		if f.FileInfo().IsDir() {
			continue
		}
		name := strings.ToLower(filepath.Base(f.Name))
		if !strings.Contains(name, "mihomo") && !strings.Contains(name, "clash") {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		defer rc.Close()
		out, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o755)
		if err != nil {
			return err
		}
		if _, err := io.Copy(out, rc); err != nil {
			out.Close()
			return err
		}
		return out.Close()
	}
	return errors.New("压缩包中没有找到内核文件")
}

func extractGz(data []byte, target string) error {
	zr, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return fmt.Errorf("解压内核失败: %w", err)
	}
	defer zr.Close()
	out, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o755)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := io.Copy(out, zr); err != nil {
		return err
	}
	return nil
}
