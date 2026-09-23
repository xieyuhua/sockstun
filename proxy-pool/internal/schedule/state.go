package schedule

import (
	"sync"
	"time"
)

// State 保存当前调度方式与下一次运行时间，供管理接口展示。并发安全。
type State struct {
	mu   sync.RWMutex
	mode string
	next time.Time
}

// Set 更新调度方式与下一次运行时间。
func (s *State) Set(mode string, next time.Time) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.mode, s.next = mode, next
}

// Snapshot 读取当前调度状态。
func (s *State) Snapshot() (mode string, next time.Time) {
	if s == nil {
		return "", time.Time{}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.mode, s.next
}
