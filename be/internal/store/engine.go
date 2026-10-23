// Package store 是 LSM 存储引擎的顶层封装,负责 WAL、memtable、segment、manifest
// 的协同、事务提交(commit_ts / 冲突检测 / 原子批写)以及后台刷盘。
package store

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/1638540149/tang_db/be/internal/exec"
	"github.com/1638540149/tang_db/be/internal/manifest"
	"github.com/1638540149/tang_db/be/internal/memtable"
	"github.com/1638540149/tang_db/be/internal/segment"
	"github.com/1638540149/tang_db/be/internal/wal"
)

const (
	defaultMemtableSize    = 64 * 1024 * 1024
	defaultMaxTxnWriteSize = 64 * 1024 * 1024
	maxTxnWriteCount       = 1_000_000
	maxImmutableTables     = 2
	version                = "P3-0.1.0"
)

// 崩溃注入点(SPEC-P3 FR-364 / K55)。仅验收使用,默认空 = 关闭。
const (
	CrashBeforeWAL     = "before_wal"
	CrashAfterFsync    = "after_fsync"
	CrashAfterMemtable = "after_memtable"
	CrashBeforeOK      = "before_ok"
)

// 事务写入超限错误(FR-309 / FR-361)。
var (
	ErrTxnTooLarge     = errors.New("事务写入量超过上限")
	ErrTxnTooManyWrite = errors.New("事务写入条数超过上限")
)

// Stats 汇总恢复与运行统计。
type Stats struct {
	SegmentsLoaded int
	WALReplayed    int
	MaxCommitTS    uint64
}

// Options 是引擎的启动参数。
type Options struct {
	MemtableSize     int64
	MaxTxnWriteBytes int64  // 单事务写入量上限,默认 64MB
	TestCrashPoint   string // 空 = 关闭;仅验收使用
}

// Write 是提交写集里的一条:key 为用户键(不含版本段)。
type Write struct {
	Key   []byte
	Value []byte
}

// Engine 是 LSM 存储引擎。
type Engine struct {
	dataDir  string
	walDir   string
	sstDir   string
	manifest *manifest.Registry
	state    *manifest.Manifest

	mu        sync.RWMutex
	mem       *memtable.MemTable
	imm       []*memtable.MemTable // 不可变表队列,旧到新
	segments  []*segment.Reader    // 有效 segment,新到旧
	wal       *wal.WAL
	threshold int64

	// commitMu 串行化"冲突检测 → 取号 → WAL 追加 → fsync → memtable"这一段。
	// 同一时刻只能有一个批在推进,否则两个并发事务可能都检测通过而互相覆盖
	// (SPEC-P3 FR-332)。不同事务的 memtable 写入本身由跳表内部锁保护。
	commitMu sync.Mutex

	// walMu 保护 e.wal 指针的读取与轮换,确保不会向一个已关闭的 WAL 追加。
	walMu sync.Mutex

	// lastCommitTS 已分配的最大 commit_ts;序号只增不回退(FR-316 / INV-5)。
	lastCommitTS atomic.Uint64

	maxTxnWriteBytes int64
	crashPoint       string

	flushCh   chan struct{}
	flushCond *sync.Cond // 在 mu 上,每次 flush 完成时 Broadcast
	closeCh   chan struct{}
	wg        sync.WaitGroup
	closed    bool

	// 运行统计,供心跳上报。全部是原子量,读统计不阻塞前台读写。
	putCount       atomic.Uint64
	getCount       atomic.Uint64
	scanCount      atomic.Uint64
	putErrCount    atomic.Uint64
	getErrCount    atomic.Uint64
	scanErrCount   atomic.Uint64
	lastFsyncMilli atomic.Int64
}

// Open 打开或创建存储引擎(兼容 P1/P2 的调用方式)。
func Open(dataDir string, memtableSize int64) (*Engine, *Stats, error) {
	return OpenWithOptions(dataDir, Options{MemtableSize: memtableSize})
}

// OpenWithOptions 打开或创建存储引擎,执行恢复流程。
func OpenWithOptions(dataDir string, opts Options) (*Engine, *Stats, error) {
	memtableSize := opts.MemtableSize
	if memtableSize <= 0 {
		memtableSize = defaultMemtableSize
	}
	maxTxnBytes := opts.MaxTxnWriteBytes
	if maxTxnBytes <= 0 {
		maxTxnBytes = defaultMaxTxnWriteSize
	}

	e := &Engine{
		dataDir:          dataDir,
		walDir:           filepath.Join(dataDir, "wal"),
		sstDir:           filepath.Join(dataDir, "sst"),
		threshold:        memtableSize,
		maxTxnWriteBytes: maxTxnBytes,
		crashPoint:       opts.TestCrashPoint,
		flushCh:          make(chan struct{}, 1),
		closeCh:          make(chan struct{}),
	}
	e.flushCond = sync.NewCond(&e.mu)

	for _, d := range []string{e.dataDir, e.walDir, e.sstDir} {
		if err := os.MkdirAll(d, 0750); err != nil {
			return nil, nil, fmt.Errorf("store: create dir %s: %w", d, err)
		}
	}

	// ① MANIFEST:格式版本守卫(老目录在这里就被拒绝)。
	reg, m, err := manifest.Open(e.dataDir)
	if err != nil {
		return nil, nil, err
	}
	e.manifest = reg
	e.state = m

	// ② 加载 segment;缺失或损坏的跳过并告警。
	for _, segNum := range m.Segments {
		path := filepath.Join(e.sstDir, segment.Name(segNum))
		r, err := segment.Open(path)
		if err != nil {
			slog.Warn("segment missing or corrupt, skipping", "path", path, "error", err)
			continue
		}
		e.segments = append([]*segment.Reader{r}, e.segments...)
	}

	// ③ 重放 WAL(按编号升序)。整条记录完整 + CRC 通过 = 已提交。
	mem := memtable.New()
	var replayMaxTS uint64
	stats := &Stats{}
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
				for _, kv := range r.KVs {
					mem.Put(kv.Key, kv.Value)
				}
				if r.CommitTS > replayMaxTS {
					replayMaxTS = r.CommitTS
				}
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

	// ④ 计算 next commit_ts:max(MANIFEST.last_commit_ts, 重放所见最大,
	//    已落盘 segment 里的最大)。BE 在 flush 后会回收旧 WAL,MANIFEST 是
	//    数据只在 segment 里时的唯一序号来源(FR-316 / FR-336 / FR-337)。
	segMaxTS, err := maxCommitTSInSegments(e.segments)
	if err != nil {
		return nil, nil, err
	}
	if m.LastCommitTS < replayMaxTS {
		slog.Warn("MANIFEST.last_commit_ts 小于 WAL 重放所见的最大 commit_ts,"+
			"说明上次是非正常退出;取较大者继续",
			"manifest_last_commit_ts", m.LastCommitTS, "wal_max_commit_ts", replayMaxTS)
	}
	last := m.LastCommitTS
	if replayMaxTS > last {
		last = replayMaxTS
	}
	if segMaxTS > last {
		last = segMaxTS
	}
	e.lastCommitTS.Store(last)

	// 恢复完成时刷新 MANIFEST(FR-336):format_version 固定为 2、last_commit_ts
	// 不小于已落盘数据里的最大 commit_ts。
	m.FormatVersion = manifest.FormatVersion
	m.LastCommitTS = last
	if err := e.manifest.Save(e.state); err != nil {
		return nil, nil, fmt.Errorf("store: save initial manifest: %w", err)
	}

	stats.SegmentsLoaded = len(e.segments)
	stats.MaxCommitTS = last

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

// Put 的语义与 P1 保持不变:单 key 自动提交事务(SPEC-P3 FR-308)。
//
// 内部走与 CommitTxn 完全相同的提交路径:取号 → 写批记录 → fsync → memtable → OK。
func (e *Engine) Put(key, value []byte) error {
	e.putCount.Add(1)
	if err := e.put(key, value); err != nil {
		e.putErrCount.Add(1)
		return err
	}
	return nil
}

func (e *Engine) put(key, value []byte) error {
	if len(key) == 0 {
		return errors.New("store: empty key")
	}
	if len(value) > 1<<20 {
		return fmt.Errorf("store: value too large %d", len(value))
	}
	_, _, err := e.Commit(0, 0, []Write{{Key: key, Value: value}})
	return err
}

// Commit 提交一个写集(SPEC-P3 FR-311 / FR-312):
//
//	① 冲突检测 → ② 追加批记录 → ③ fsync → ④ 写 memtable → ⑤ 返回
//
// 返回 (commitTS, conflictKey, err):conflictKey 非空表示冲突,此时**没有任何写入**。
// snapshot = 0 表示"最新已提交"(自动提交用)。
func (e *Engine) Commit(snapshot, txnID uint64, writes []Write) (uint64, []byte, error) {
	if len(writes) == 0 {
		return 0, nil, nil
	}
	if err := e.validateWrites(writes); err != nil {
		return 0, nil, err
	}

	// 检测 → 取号 → fsync → memtable 全在串行区间内完成。
	e.commitMu.Lock()
	defer e.commitMu.Unlock()

	e.mu.RLock()
	closed := e.closed
	e.mu.RUnlock()
	if closed {
		return 0, nil, errors.New("store: closed")
	}

	if snapshot == 0 {
		snapshot = e.lastCommitTS.Load()
	}

	// ① 冲突检测:该用户键最新已提交版本是否晚于本事务快照。
	for _, w := range writes {
		latest, ok, err := e.latestVersionTS(w.Key)
		if err != nil {
			return 0, nil, err
		}
		if ok && latest > snapshot {
			return 0, append([]byte(nil), w.Key...), nil
		}
	}

	e.injectCrash(CrashBeforeWAL)

	// ② 取号 + 追加整批为同一条 WAL 记录。
	commitTS := e.lastCommitTS.Add(1)
	full := make([]wal.KV, len(writes))
	for i, w := range writes {
		full[i] = wal.KV{Key: AppendVersion(w.Key, commitTS), Value: w.Value}
	}

	e.walMu.Lock()
	currentWAL := e.wal
	err := currentWAL.AppendBatch(commitTS, txnID, full)
	e.walMu.Unlock()
	if err != nil {
		return 0, nil, fmt.Errorf("store: wal append: %w", err)
	}
	e.lastFsyncMilli.Store(time.Now().UnixMilli())

	// ③ fsync 完成后才可能返回 OK。
	e.injectCrash(CrashAfterFsync)

	// ④ 写 memtable。
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		return 0, nil, errors.New("store: closed")
	}
	for _, kv := range full {
		e.mem.Put(kv.Key, kv.Value)
	}
	if e.mem.ApproximateSize() >= e.threshold {
		if err := e.rotateMemtableLocked(); err != nil {
			e.mu.Unlock()
			return 0, nil, err
		}
	}
	e.mu.Unlock()

	e.injectCrash(CrashAfterMemtable)

	// ⑤ 返回 OK(在 fsync 与 memtable 之后)。
	e.injectCrash(CrashBeforeOK)
	return commitTS, nil, nil
}

func (e *Engine) validateWrites(writes []Write) error {
	if len(writes) > maxTxnWriteCount {
		return fmt.Errorf("%w(%d 条)", ErrTxnTooManyWrite, maxTxnWriteCount)
	}
	var total int64
	for _, w := range writes {
		if len(w.Key) == 0 {
			return errors.New("store: empty key in writes")
		}
		total += int64(len(w.Key) + VersionBytes + len(w.Value) + 8)
		if total > e.maxTxnWriteBytes {
			return fmt.Errorf("%w(%s)", ErrTxnTooLarge, humanBytes(e.maxTxnWriteBytes))
		}
	}
	return nil
}

// injectCrash 命中配置的崩溃注入点时立即退出进程(模拟 taskkill /F,不给清理机会)。
func (e *Engine) injectCrash(name string) {
	if e.crashPoint != name {
		return
	}
	slog.Error("WARN 崩溃注入命中,进程立即退出(仅验收使用)", "crash_point", name)
	os.Exit(137)
}

// Get 读取用户键的最新已提交版本(等价于 snapshot = 0)。
func (e *Engine) Get(key []byte) ([]byte, bool, error) {
	return e.GetSnapshot(key, 0)
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
	return Stats{SegmentsLoaded: len(e.segments), MaxCommitTS: e.lastCommitTS.Load()}
}

// rotateMemtableLocked 将当前 memtable 冻结并创建新的 WAL/memtable。
// 调用方必须持有写锁。
func (e *Engine) rotateMemtableLocked() error {
	// 不可变表数量达到上限时,前台写等待。
	for len(e.imm) >= maxImmutableTables {
		e.flushCond.Wait()
	}

	oldMem := e.mem

	e.walMu.Lock()
	oldWal := e.wal
	newWal, err := wal.Open(e.walDir, e.state.NextWAL)
	if err != nil {
		e.walMu.Unlock()
		return fmt.Errorf("store: create new wal: %w", err)
	}
	e.wal = newWal
	e.state.NextWAL++
	e.walMu.Unlock()

	e.mem = memtable.New()
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
	var tableMaxTS uint64
	for it.First(); it.Valid(); it.Next() {
		_, ts, err := SplitVersion(it.Key())
		if err != nil {
			return fmt.Errorf("flush segment %d: %w", segNum, err)
		}
		if ts > tableMaxTS {
			tableMaxTS = ts
		}
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
	// 原子更新 MANIFEST:加入新 segment,同时推进 last_commit_ts,
	// 保证它 >= 本次已落盘数据里的最大 commit_ts(FR-336 / FR-337)。
	newState := *e.state
	newState.FormatVersion = manifest.FormatVersion
	newState.Segments = append(newState.Segments, segNum)
	if tableMaxTS > newState.LastCommitTS {
		newState.LastCommitTS = tableMaxTS
	}
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
	e.walMu.Lock()
	current := e.wal.Number()
	e.walMu.Unlock()

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

// newScanner 创建覆盖 [start, end) 的原始有序扫描器(完整 key,含版本段)。
// 数据源按"新 → 旧"排列;每个源只保留当前一条记录。
func (e *Engine) newScanner(start, end []byte, limit int) *exec.Scanner {
	e.mu.RLock()
	mem := e.mem
	imm := make([]*memtable.MemTable, len(e.imm))
	copy(imm, e.imm)
	segs := make([]*segment.Reader, len(e.segments))
	copy(segs, e.segments)
	e.mu.RUnlock()

	cursors := make([]exec.Cursor, 0, 1+len(imm)+len(segs))
	cursors = append(cursors, exec.NewMemCursor(mem))
	for i := len(imm) - 1; i >= 0; i-- {
		cursors = append(cursors, exec.NewMemCursor(imm[i]))
	}
	for _, seg := range segs {
		cursors = append(cursors, exec.NewSegmentCursor(seg))
	}
	return exec.NewScanner(cursors, start, end, limit)
}

// NewScanner 创建覆盖 [start, end) 的原始有序扫描器。
//
// 返回的 key 含版本段;需要用户键级、按快照消解版本的扫描请用 NewMVCCScanner。
func (e *Engine) NewScanner(start, end []byte, limit int) *exec.Scanner {
	e.scanCount.Add(1)
	return e.newScanner(start, end, limit)
}

// RecordScanError 记录一次扫描失败,供心跳上报。
func (e *Engine) RecordScanError() { e.scanErrCount.Add(1) }

// maxCommitTSInSegments 扫描已落盘 segment 的 key,取出最大 commit_ts。
//
// 启动时只有 segment(旧 WAL 已回收)也不能让序号回退 —— 这是 INV-5/SC-206
// 的直接实现。数据量小,启动时全扫一遍是 P3 可以接受的开销。
func maxCommitTSInSegments(segs []*segment.Reader) (uint64, error) {
	var maxTS uint64
	for _, seg := range segs {
		it := seg.NewIterator()
		for it.First(); it.Valid(); it.Next() {
			_, ts, err := SplitVersion(it.Key())
			if err != nil {
				return 0, fmt.Errorf("store: segment %s: %w", seg.Path(), err)
			}
			if ts > maxTS {
				maxTS = ts
			}
		}
		if err := it.Err(); err != nil {
			return 0, fmt.Errorf("store: segment %s: %w", seg.Path(), err)
		}
	}
	return maxTS, nil
}

func humanBytes(n int64) string {
	const mb = 1024 * 1024
	const kb = 1024
	switch {
	case n >= mb && n%mb == 0:
		return fmt.Sprintf("%dMB", n/mb)
	case n >= kb && n%kb == 0:
		return fmt.Sprintf("%dKB", n/kb)
	default:
		return fmt.Sprintf("%dB", n)
	}
}

// Status 是心跳用的运行状态快照。
type Status struct {
	DataDir            string
	MemTableBytes      int64
	ImmutableCount     int
	SegmentCount       int
	SegmentBytes       uint64
	WALBytes           uint64
	PutCount           uint64
	GetCount           uint64
	ScanCount          uint64
	PutErrCount        uint64
	GetErrCount        uint64
	ScanErrCount       uint64
	LastFsyncUnixMilli int64
	DiskTotalBytes     uint64
	DiskAvailableBytes uint64
}

// Status 采集当前状态。目录大小的统计是尽力而为的,失败按 0 处理,
// 绝不让统计影响前台读写。
func (e *Engine) Status() Status {
	e.mu.RLock()
	memBytes := e.mem.ApproximateSize()
	immCount := len(e.imm)
	segCount := len(e.segments)
	dataDir := e.dataDir
	e.mu.RUnlock()

	st := Status{
		DataDir:            dataDir,
		MemTableBytes:      memBytes,
		ImmutableCount:     immCount,
		SegmentCount:       segCount,
		PutCount:           e.putCount.Load(),
		GetCount:           e.getCount.Load(),
		ScanCount:          e.scanCount.Load(),
		PutErrCount:        e.putErrCount.Load(),
		GetErrCount:        e.getErrCount.Load(),
		ScanErrCount:       e.scanErrCount.Load(),
		LastFsyncUnixMilli: e.lastFsyncMilli.Load(),
		SegmentBytes:       dirSize(e.sstDir),
		WALBytes:           dirSize(e.walDir),
	}
	st.DiskTotalBytes, st.DiskAvailableBytes = diskUsage(dataDir)
	return st
}

// dirSize 统计目录下普通文件的总字节数;目录不存在返回 0。
func dirSize(dir string) uint64 {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	var total uint64
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		total += uint64(info.Size())
	}
	return total
}

// WalName 暴露给外部测试使用。
func WalName(n uint64) string { return wal.WalName(n) }
