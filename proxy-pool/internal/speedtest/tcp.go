package speedtest

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"proxypool/internal/config"
	"proxypool/internal/model"
)

// TCPTester 只做 TCP 握手探测，用于内核不可用时的兜底。
type TCPTester struct {
	cfg          config.SpeedTestConfig
	rep          Reporter
	OnlyUntested bool
}

// NewTCP 创建 TCP 探测引擎。
func NewTCP(cfg config.SpeedTestConfig, rep Reporter) *TCPTester {
	return &TCPTester{cfg: cfg, rep: rep}
}

// Test 执行 TCP 连通性探测。
func (t *TCPTester) Test(ctx context.Context, nodes []*model.Node) error {
	candidates := make([]*model.Node, 0, len(nodes))
	for _, n := range nodes {
		if t.OnlyUntested && n.Delay > 0 {
			continue
		}
		candidates = append(candidates, n)
	}
	if len(candidates) == 0 {
		return nil
	}

	timeout := t.cfg.Timeout.D()
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	workers := t.cfg.DelayWorkers
	if workers <= 0 {
		workers = 16
	}
	if workers > len(candidates) {
		workers = len(candidates)
	}
	t.rep.Log("INFO", "TCP 连通性探测：%d 个节点，%d 并发", len(candidates), workers)

	jobs := make(chan *model.Node)
	var wg sync.WaitGroup
	var done int64
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := range jobs {
				delay, err := dialLatency(ctx, n.Endpoint(), timeout)
				if err != nil {
					n.Delay = -1
					n.Alive = false
					n.Error = shorten(err.Error())
				} else {
					n.Delay = delay
					n.Alive = true
					n.Error = ""
					n.Speed = 0
				}
				t.rep.Progress("TCP 探测", int(atomic.AddInt64(&done, 1)), len(candidates))
			}
		}()
	}

	for _, n := range candidates {
		select {
		case jobs <- n:
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return ctx.Err()
		}
	}
	close(jobs)
	wg.Wait()
	return nil
}

// dialLatency 返回 TCP 握手耗时（毫秒），取两次中最优值。
func dialLatency(ctx context.Context, addr string, timeout time.Duration) (int, error) {
	best := 0
	var lastErr error
	for i := 0; i < 2; i++ {
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		default:
		}
		start := time.Now()
		d := net.Dialer{Timeout: timeout}
		conn, err := d.DialContext(ctx, "tcp", addr)
		if err != nil {
			lastErr = err
			continue
		}
		conn.Close()
		ms := int(time.Since(start).Milliseconds())
		if ms <= 0 {
			ms = 1
		}
		if best == 0 || ms < best {
			best = ms
		}
	}
	if best == 0 {
		if lastErr == nil {
			lastErr = errors.New("无法连接")
		}
		return 0, lastErr
	}
	return best, nil
}
