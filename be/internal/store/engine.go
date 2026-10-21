// Package store 是 LSM 存储引擎的顶层封装,负责 WAL、memtable、segment、manifest
// 的协同以及后台刷盘。
package store

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sync"

	"github.com/1638540149/tang_db/be/internal/manifest"
	"github.com/1638540149/tang_db/be/internal/memtable"
	"github.com/1638540149/tang_db/be/internal/segment"
	"github.com/1638540149/tang_db/be/internal/wal"
)

const (
	defaultMemtableSize = 64 * 1024 * 1024
	maxImmutableTables  = 2
	version             = "P1-0.1.0"
)

// Stats 汇总恢复与运行统计。
type Stats struct {
	SegmentsLoaded int
	WALReplayed    int
}

// Engine 是 P1 的 LSM 存储引擎。
type Engine struct {
	dataDir   string
	walDir    string
	sstDir    string
	manifest  *manifest.Registry
	state     *manifest.Manifest

	mu        sync.RWMutex
	mem       *memtable.MemTable
	imm       []*memtable.MemTable // 不可变表队列,旧到新
	segments  []*segment.Reader    // 有效 segment,新到旧
	wal       *wal.WAL
	threshold int64

	flushCh   chan struct{}
	flushCond *sync.Cond // 在 mu 上,每次 flush 完成时 Broadcast
	closeCh   chan struct{}
	wg        sync.WaitGroup
	closed    bool
}

// Open 打开或创建存储引擎,执行恢复流程。
func Open(dataDir string, memtableSize int64) (*Engine, *Stats, error) {
	if memtableSize <= 0 {
		memtableSize = defaultMemtableSize
	}

	e := &Engine{
		dataDir:   dataDir,
		walDir:    filepath.Join(dataDir, "wal"),
		sstDir:    filepath.Join(dataDir, "sst"),
		threshold: memtableSize,
		flushCh:   make(chan struct{}, 1),
		closeCh:   make(chan struct{}),
	}
	e.flushCond = sync.NewCond(&e.mu)

	for _, d := range []string{e.dataDir, e.walDir, e.sstDir} {
		if err := os.MkdirAll(d, 0750); err != nil {
			return nil, nil, fmt.Errorf("store: create dir %s: %w", d, err)
		}
	}

	reg, m, err := manifest.Open(e.dataDir)
	if err != nil {
		return nil, nil, err
	}
	e.manifest = reg
	e.state = m

	stats := &Stats{}

	// 加载 manifest 引用的 segment;缺失或损坏的 segment 跳过并告警。
	for _, segNum := range m.Segments {
		path := filepath.Join(e.sstDir, segment.Name(segNum))
		r, err := segment.Open(path)
		if err != nil {
			slog.Warn("segment missing or corrupt, skipping", "path", path, "error", err)
			continue
		}
		e.segments = append([]*segment.Reader{r}, e.segments...)
		stats.SegmentsLoaded++
	}

	// 重放所有 WAL 文件(按编号升序)。
	mem := memtable.New()
	walNumbers, err := wal.ListNumbers(e.walDir)
	if err != nil {
		return nil, nil, fmt.Errorf("store: list wal: %w", err)
	}
	if len(walNumbers) == 0 {
		w, err := wal.Open(e.walDir, m.NextWAL)
		if err != nil {
			return nil, nil, err
		}
		e.wal = w
		e.mem = mem
		m.NextWAL++
	} else {
		for _, num := range walNumbers {
			w, err := wal.Open(e.walDir, num)
			if err != nil {
				return nil, nil, fmt.Errorf("store: open wal %d: %w", num, err)
			}
			if err := w.Replay(func(r wal.Record) error {
				mem.Put(r.Key, r.Value)
				stats.WALReplayed++
				return nil
			}); err != nil {
				w.Close()
				return nil, nil, fmt.Errorf("store: replay wal %d: %w", num, err)
			}
			w.Close()
		}
		current := walNumbers[len(walNumbers)-1]
		w, err := wal.Open(e.walDir, current)
		if err != nil {
			return nil, nil, err
		}
		e.wal = w
		e.mem = mem
		m.NextWAL = current + 1
	}

	// 保存一次初始 manifest,确保 NextWAL 等字段持久化。
	if err := e.manifest.Save(e.state); err != nil {
		return nil, nil, fmt.Errorf("store: save initial manifest: %w", err)
	}

	// 恢复后若 memtable 已超阈值,立即冻结。
	if e.mem.ApproximateSize() >= e.threshold {
		if err := e.rotateMemtableLocked(); err != nil {
			return nil, nil, err
		}
	}

	e.wg.Add(1)
	go e.flushWorker()
	return e, stats, nil
}

// Close 关闭引擎,等待后台刷盘完成。
func (e *Engine) Close() error {
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		return nil
	}
	e.closed = true
	e.mu.Unlock()

	close(e.closeCh)
	e.wg.Wait()

	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wal != nil {
		_ = e.wal.Close()
	}
	for _, r := range e.segments {
		_ = r.Close()
	}
	return nil
}

// Put 的写入顺序:① WAL 追加 → ② fsync → ③ memtable → ④ 返回 OK。
func (e *Engine) Put(key, value []byte) error {
	if len(key) == 0 {
		return errors.New("store: empty key")
	}
	if len(value) > 1<<20 {
		return fmt.Errorf("store: value too large %d", len(value))
	}

	e.mu.RLock()
	if e.closed {
		e.mu.RUnlock()
		return errors.New("store: closed")
	}
	walFile := e.wal
	e.mu.RUnlock()

	// ① + ②:先写 WAL 并 fsync。
	if err := walFile.Append(key, value); err != nil {
		return fmt.Errorf("store: wal append: %w", err)
	}

	// ③:再写 memtable。
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return errors.New("store: closed")
	}
	e.mem.Put(key, value)

	// 检查是否需要冻结 memtable。
	if e.mem.ApproximateSize() >= e.threshold {
		if err := e.rotateMemtableLocked(); err != nil {
			return err
		}
	}
	return nil
}

// Get 的查找顺序:MemTable → Immutable MemTable → Segment(新到旧)。
func (e *Engine) Get(key []byte) ([]byte, bool, error) {
	e.mu.RLock()
	mem := e.mem
	imm := make([]*memtable.MemTable, len(e.imm))
	copy(imm, e.imm)
	segs := make([]*segment.Reader, len(e.segments))
	copy(segs, e.segments)
	e.mu.RUnlock()

	if v, ok := mem.Get(key); ok {
		return v, true, nil
	}
	for i := len(imm) - 1; i >= 0; i-- {
		if v, ok := imm[i].Get(key); ok {
			return v, true, nil
		}
	}
	for _, seg := range segs {
		v, ok, err := seg.Get(key)
		if err != nil {
			return nil, false, fmt.Errorf("store: segment read %s: %w", seg.Path(), err)
		}
		if ok {
			return v, true, nil
		}
	}
	return nil, false, nil
}

// Version 返回引擎版本字符串。
func (e *Engine) Version() string { return EngineVersion() }

// EngineVersion 返回引擎版本字符串。
func EngineVersion() string { return version }

// DataDir 返回数据目录。
func (e *Engine) DataDir() string { return e.dataDir }

// Stats 返回当前运行统计。
func (e *Engine) Stats() Stats {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return Stats{SegmentsLoaded: len(e.segments)}
}

// rotateMemtableLocked 将当前 memtable 冻结并创建新的 WAL/memtable。
// 调用方必须持有写锁。
func (e *Engine) rotateMemtableLocked() error {
	// 不可变表数量达到上限时,前台写等待。
	for len(e.imm) >= maxImmutableTables {
		e.flushCond.Wait()
	}

	oldMem := e.mem
	oldWal := e.wal

	newWal, err := wal.Open(e.walDir, e.state.NextWAL)
	if err != nil {
		return fmt.Errorf("store: create new wal: %w", err)
	}
	e.wal = newWal
	e.mem = memtable.New()
	e.state.NextWAL++
	e.imm = append(e.imm, oldMem)

	// 关闭旧 WAL 的写入端,它将被后台 flush 任务删除。
	_ = oldWal.Close()

	select {
	case e.flushCh <- struct{}{}:
	default:
	}
	return nil
}

func (e *Engine) flushWorker() {
	defer e.wg.Done()
	for {
		select {
		case <-e.closeCh:
			e.flushAll()
			return
		case <-e.flushCh:
			e.flushAll()
		}
	}
}

func (e *Engine) flushAll() {
	for {
		e.mu.Lock()
		if len(e.imm) == 0 {
			e.flushCond.Broadcast()
			e.mu.Unlock()
			return
		}
		table := e.imm[0]
		segNum := e.state.NextSegment
		e.state.NextSegment++
		e.mu.Unlock()

		if err := e.flushOne(table, segNum); err != nil {
			slog.Error("flush failed", "segment", segNum, "error", err)
			e.mu.Lock()
			e.flushCond.Broadcast()
			e.mu.Unlock()
			return
		}
	}
}

func (e *Engine) flushOne(table *memtable.MemTable, segNum uint64) error {
	path := filepath.Join(e.sstDir, segment.Name(segNum))
	builder := segment.NewBuilder(path)
	it := table.Iterator()
	for it.First(); it.Valid(); it.Next() {
		builder.Add(it.Key(), it.Value())
	}
	if builder.Len() == 0 {
		return nil
	}
	if _, err := builder.Finish(); err != nil {
		return fmt.Errorf("flush segment %d: %w", segNum, err)
	}

	r, err := segment.Open(path)
	if err != nil {
		return fmt.Errorf("open flushed segment %d: %w", segNum, err)
	}

	e.mu.Lock()
	// 原子更新 MANIFEST:加入新 segment。
	newState := *e.state
	newState.Segments = append(newState.Segments, segNum)
	if err := e.manifest.Save(&newState); err != nil {
		e.mu.Unlock()
		r.Close()
		return fmt.Errorf("update manifest: %w", err)
	}
	e.state = &newState

	// 从 immutable 队列移除已刷盘的表。
	if len(e.imm) > 0 {
		e.imm = e.imm[1:]
	}
	e.segments = append([]*segment.Reader{r}, e.segments...)
	e.flushCond.Broadcast()
	e.mu.Unlock()

	// MANIFEST 更新完成后,可安全删除最旧 WAL。
	e.gcWAL()
	return nil
}

func (e *Engine) gcWAL() {
	e.mu.RLock()
	current := e.wal.Number()
	e.mu.RUnlock()

	nums, err := wal.ListNumbers(e.walDir)
	if err != nil {
		slog.Warn("list wal for gc failed", "error", err)
		return
	}
	for _, n := range nums {
		if n < current {
			path := filepath.Join(e.walDir, wal.WalName(n))
			if err := os.Remove(path); err != nil {
				slog.Warn("remove old wal failed", "path", path, "error", err)
			}
		}
	}
}

// ForceFlush 强制刷盘所有 immutable memtable,主要用于测试。
func (e *Engine) ForceFlush() error {
	select {
	case e.flushCh <- struct{}{}:
	default:
	}
	e.mu.Lock()
	for len(e.imm) > 0 {
		e.flushCond.Wait()
	}
	e.mu.Unlock()
	return nil
}

// WalName 暴露给外部测试使用。
func WalName(n uint64) string { return wal.WalName(n) }
