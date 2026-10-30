package store

import (
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/1638540149/tang_db/be/internal/wal"
)

// errFsyncLost 表示该提交所在批次的 fsync 失败(数据可能没落盘,不能报成功)。
var errFsyncLost = errors.New("该提交所在批次的 fsync 失败")

// pendingCommit 是一条"已追加 WAL、等待 fsync"的提交(SPEC-P6.1 FR-698)。
type pendingCommit struct {
	seq      uint64
	commitTS uint64
	userKeys [][]byte
	kvs      []wal.KV
}

// waitDurableAndApply 等到"覆盖 seq 的 fsync"完成,并把这一组提交应用到 memtable。
//
// 组内只有一个 leader 真正做 fsync:它把当前**所有**已追加的记录一起刷掉,
// 所以一个 Sync 可以覆盖多个事务;其余调用在 applyPipeline 上排队,
// 醒来时发现 durableSeq 已越过自己的 seq 就直接返回(FR-698 / K150)。
func (e *Engine) waitDurableAndApply(seq uint64) error {
	e.applyPipeline.Lock()
	defer e.applyPipeline.Unlock()

	e.groupMu.Lock()
	if seq <= e.failedSeq {
		// 这条提交所在批次的 fsync 失败过:绝不能因为后面的水位追上就报成功。
		err := e.syncErr
		e.groupMu.Unlock()
		if err == nil {
			err = errFsyncLost
		}
		return fmt.Errorf("store: wal fsync failed: %w", err)
	}
	if e.durableSeq >= seq && e.syncErr == nil {
		e.groupMu.Unlock()
		return nil
	}
	e.groupMu.Unlock()

	// 已经有多条待刷时,最多再等 group_commit_max_wait_ms 让这一批更大;
	// 单条提交不等待(否则会把 P99 直接抬到门限边上,SC-407 / 边界 #124)。
	e.waitForBiggerBatch()

	e.commitMu.Lock()
	target := e.appendedSeq
	if n := len(e.pendingList); n > 0 && e.groupMaxBatch > 0 {
		limit := e.pendingList[0].seq + uint64(e.groupMaxBatch) - 1
		if target > limit {
			target = limit
		}
	}
	e.commitMu.Unlock()

	e.walMu.Lock()
	w := e.wal
	e.walMu.Unlock()

	e.injectCrash(CrashFsyncMidGroup)

	syncStart := time.Now()
	if err := w.Sync(); err != nil {
		// fsync 失败:这一组不能算提交成功,丢弃待应用队列并向所有等待者报错。
		e.commitMu.Lock()
		e.dropPendingLocked(target)
		e.commitMu.Unlock()
		e.groupMu.Lock()
		e.syncErr = err
		if target > e.failedSeq {
			e.failedSeq = target
		}
		e.groupCond.Broadcast()
		e.groupMu.Unlock()
		return fmt.Errorf("store: wal fsync: %w", err)
	}
	e.fsyncCount.Add(1)
	e.fsyncMillis.Add(uint64(time.Since(syncStart).Milliseconds()))
	e.lastFsyncMilli.Store(time.Now().UnixMilli())

	// 边界 #110:fsync 已完成、还没回包时崩溃 —— 重启重放后这条事务可见(要么全在)。
	e.injectCrash(CrashAfterFsync)
	e.injectCrash(CrashFsyncAfterDone)

	batch := e.takePending(target)
	if len(batch) > 0 {
		e.groupBatches.Add(1)
		e.groupCommits.Add(uint64(len(batch)))
	}
	if err := e.applyBatch(batch); err != nil {
		e.groupMu.Lock()
		e.syncErr = err
		e.groupCond.Broadcast()
		e.groupMu.Unlock()
		return err
	}

	e.groupMu.Lock()
	if target > e.durableSeq {
		e.durableSeq = target
	}
	e.syncErr = nil
	e.groupCond.Broadcast()
	e.groupMu.Unlock()
	return nil
}

// waitForBiggerBatch 在批量已经形成时短暂等待更多提交,凑成更大的 fsync 组。
func (e *Engine) waitForBiggerBatch() {
	if e.groupMaxWait <= 0 {
		return
	}
	deadline := time.Now().Add(e.groupMaxWait)
	for {
		e.commitMu.Lock()
		n := len(e.pendingList)
		e.commitMu.Unlock()
		if n <= 1 || n >= e.groupMaxBatch {
			return
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return
		}
		// 轮询粒度 200µs:足够在 5ms 内放出若干次提交,又不长期占着锁。
		sleep := 200 * time.Microsecond
		if remaining < sleep {
			sleep = remaining
		}
		time.Sleep(sleep)
	}
}

// takePending 取走 seq <= target 的提交(保持追加顺序)。
func (e *Engine) takePending(target uint64) []*pendingCommit {
	e.commitMu.Lock()
	defer e.commitMu.Unlock()

	var batch, rest []*pendingCommit
	for _, p := range e.pendingList {
		if p.seq <= target {
			batch = append(batch, p)
		} else {
			rest = append(rest, p)
		}
	}
	if len(batch) == 0 {
		return nil
	}
	if rest == nil {
		e.pendingList = nil
	} else {
		e.pendingList = rest
	}
	return batch
}

// dropPendingLocked 在 fsync 失败时丢弃 seq <= target 的待应用提交。调用方须持有 commitMu。
func (e *Engine) dropPendingLocked(target uint64) {
	var rest []*pendingCommit
	for _, p := range e.pendingList {
		if p.seq > target {
			rest = append(rest, p)
			continue
		}
		for _, k := range p.userKeys {
			if e.pending[string(k)] == p.commitTS {
				delete(e.pending, string(k))
			}
		}
	}
	if rest == nil {
		e.pendingList = nil
	} else {
		e.pendingList = rest
	}
}

// applyBatch 把一组提交按 commit_ts 顺序写进 memtable,并推进可见水位。
//
// 先日志后数据:调用它之前 WAL 已经 fsync 完成。
func (e *Engine) applyBatch(batch []*pendingCommit) error {
	if len(batch) == 0 {
		return nil
	}

	e.mu.Lock()
	maxTS := uint64(0)
	for _, p := range batch {
		for _, kv := range p.kvs {
			e.mem.Put(kv.Key, kv.Value)
		}
		if p.commitTS > maxTS {
			maxTS = p.commitTS
		}
	}
	var err error
	if e.mem.ApproximateSize() >= e.threshold {
		err = e.rotateMemtableLocked()
	}
	e.mu.Unlock()

	e.commitMu.Lock()
	for _, p := range batch {
		for _, k := range p.userKeys {
			if e.pending[string(k)] == p.commitTS {
				delete(e.pending, string(k))
			}
		}
	}
	n := len(batch)
	e.commitMu.Unlock()

	if maxTS > e.visibleTS.Load() {
		e.visibleTS.Store(maxTS)
	}
	slog.Debug("group commit 应用", "commits", n, "max_ts", maxTS)
	return err
}
