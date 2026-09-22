// Package logx 提供带环形缓冲的日志器：既写标准输出，也供 HTTP 接口回看。
package logx

import (
	"fmt"
	"io"
	"log"
	"sync"
	"time"
)

// Entry 一条日志。
type Entry struct {
	Seq   int64  `json:"seq"`
	Time  string `json:"time"`
	Level string `json:"level"`
	Msg   string `json:"msg"`
}

// Buffer 环形日志缓冲，并发安全。
type Buffer struct {
	mu      sync.RWMutex
	entries []Entry
	limit   int
	seq     int64
	logger  *log.Logger
}

// New 创建日志器，capacity 为保留的日志条数。
func New(capacity int, out io.Writer) *Buffer {
	if capacity <= 0 {
		capacity = 500
	}
	var logger *log.Logger
	if out != nil {
		logger = log.New(out, "", log.LstdFlags)
	}
	return &Buffer{limit: capacity, logger: logger}
}

func (b *Buffer) logf(level, format string, args ...any) {
	if b == nil {
		return
	}
	msg := fmt.Sprintf(format, args...)
	b.mu.Lock()
	b.seq++
	entry := Entry{Seq: b.seq, Time: time.Now().Format("15:04:05"), Level: level, Msg: msg}
	b.entries = append(b.entries, entry)
	if len(b.entries) > b.limit {
		b.entries = b.entries[len(b.entries)-b.limit:]
	}
	b.mu.Unlock()
	if b.logger != nil {
		b.logger.Printf("[%s] %s", level, msg)
	}
}

// Info 普通日志。
func (b *Buffer) Info(format string, args ...any) { b.logf("INFO", format, args...) }

// Warn 警告日志。
func (b *Buffer) Warn(format string, args ...any) { b.logf("WARN", format, args...) }

// Error 错误日志。
func (b *Buffer) Error(format string, args ...any) { b.logf("ERROR", format, args...) }

// Debug 调试日志。
func (b *Buffer) Debug(format string, args ...any) { b.logf("DEBUG", format, args...) }

// Since 返回 seq 之后的新日志。
func (b *Buffer) Since(seq int64) []Entry {
	if b == nil {
		return nil
	}
	b.mu.RLock()
	defer b.mu.RUnlock()
	out := make([]Entry, 0, 16)
	for _, e := range b.entries {
		if e.Seq > seq {
			out = append(out, e)
		}
	}
	return out
}

// Seq 当前最新日志序号。
func (b *Buffer) Seq() int64 {
	if b == nil {
		return 0
	}
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.seq
}
