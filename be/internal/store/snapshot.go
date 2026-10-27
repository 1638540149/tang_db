package store

import "sync/atomic"

// 活跃快照登记(SPEC-P5 G-3 / FR-602 / INV-7)。
//
// GC 水位 = 最老活跃快照序号;没有活跃快照时 = 当前最大 commit_ts。
// 水位不持久化 —— 重启后天然等于最大 commit_ts。

// BeginSnapshot 取一个快照并登记为活跃(长事务必须调用,否则旧版本可能被回收)。
func (e *Engine) BeginSnapshot() uint64 {
	e.snapMu.Lock()
	defer e.snapMu.Unlock()
	ts := e.lastCommitTS.Load()
	e.activeSnapshots[ts]++
	return ts
}

// ReleaseSnapshot 释放一个活跃快照。
func (e *Engine) ReleaseSnapshot(ts uint64) {
	e.snapMu.Lock()
	defer e.snapMu.Unlock()
	if n, ok := e.activeSnapshots[ts]; ok {
		if n <= 1 {
			delete(e.activeSnapshots, ts)
		} else {
			e.activeSnapshots[ts] = n - 1
		}
	}
}

// ActiveSnapshotCount 返回当前活跃快照数(诊断 / 测试用)。
func (e *Engine) ActiveSnapshotCount() int {
	e.snapMu.Lock()
	defer e.snapMu.Unlock()
	return len(e.activeSnapshots)
}

// oldestActiveSnapshot 返回 GC 水位:最老活跃快照;没有则为当前最大 commit_ts。
func (e *Engine) oldestActiveSnapshot() uint64 {
	e.snapMu.Lock()
	defer e.snapMu.Unlock()
	var oldest uint64
	found := false
	for ts := range e.activeSnapshots {
		if !found || ts < oldest {
			oldest = ts
			found = true
		}
	}
	if !found {
		return e.lastCommitTS.Load()
	}
	return oldest
}

// 保证 atomic 包被使用(编译期占位,避免未来删掉最后一个 atomic 字段时到处改 import)。
var _ = atomic.Int64{}
