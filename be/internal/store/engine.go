// Package store 是 LSM 存储引擎的顶层封装,负责 WAL、memtable、segment、manifest
// 的协同、事务提交(commit_ts / 冲突检测 / 原子批写)、后台刷盘与 leveled compaction。
package store

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
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
	defaultMemtableSize         = 64 * 1024 * 1024
	defaultMaxTxnWriteSize      = 64 * 1024 * 1024
	defaultCompactionL0Files    = 4
	defaultCompactionRatio      = 10
	defaultCompactionLevelMax   = 256 * 1024 * 1024
	defaultWALBufferBytes       = 1 * 1024 * 1024
	defaultGroupCommitMaxBatch  = 64
	defaultGroupCommitMaxWaitMS = 5
	maxTxnWriteCount            = 1_000_000
	maxImmutableTables          = 2
	version                     = "P5-0.1.0"
	maxLevels                   = 7
)

// 崩溃注入点(SPEC-P3 FR-364 / SPEC-P5 ★P5-2)。仅验收使用,默认空 = 关闭。
const (
	CrashBeforeWAL     = "before_wal"
	CrashAfterFsync    = "after_fsync"
	CrashAfterMemtable = "after_memtable"
	CrashBeforeOK      = "before_ok"
	// compaction 中途:新 segment 已落盘、MANIFEST 尚未切换。
	CrashCompactionAfterWrite = "compaction_after_write"
	// compaction 中途:MANIFEST 已切换、旧文件尚未删除。
	CrashCompactionAfterManifest = "compaction_after_manifest"
	// group commit:一组提交已追加、fsync 尚未完成(SPEC-P6.1 FR-728)。
	CrashFsyncMidGroup = "fsync_mid_group"
	// group commit:fsync 已完成、还没回包(崩溃后重放可见)。
	CrashFsyncAfterDone = "fsync_after_done"
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

	// ---- SPEC-P5 §5.4 ----
	CompactionL0Files       int
	CompactionLevelRatio    int
	CompactionLevelMaxBytes int64
	BloomEnabled            bool
	// DisableCompaction 仅测试用:关闭后台合并,便于验证"只增不合"的老行为。
	DisableCompaction bool

	// ---- SPEC-P6.1 §5.2(写路径)----
	WALBufferBytes      int64 // WAL 追加缓冲大小,默认 1MB
	GroupCommitMaxBatch int   // 一次 fsync 最多合并的提交数,默认 64
	GroupCommitMaxWait  int   // 攒批最长等待(毫秒),默认 5

	// ---- SPEC-P7.1(向量检索)----
	VectorCacheBytes        int64 // 向量运行时缓存预算;<=0 = 自动(内存的 10%)
	VectorSearchParallelism int   // 段级并行上限;<=0 = 自动(min(8, CPU))
}

// Write 是提交写集里的一条:key 为用户键(不含版本段)。
type Write struct {
	Key   []byte
	Value []byte
}

// segHandle 是一个打开中的 segment 文件。
//
// 引用计数用于"旧 MANIFEST 视图不阻塞查询"(K113):合并换掉 MANIFEST 后,旧文件
// 只有在所有正在读它的扫描器退出后才真正删除。
type segHandle struct {
	num     uint64
	path    string
	r       *segment.Reader
	refs    atomic.Int32
	retired atomic.Bool
}

// Engine 是 LSM 存储引擎。
type Engine struct {
	dataDir  string
	walDir   string
	sstDir   string
	manifest *manifest.Registry

	mu      sync.RWMutex
	state   *manifest.Manifest
	mem     *memtable.MemTable
	imm     []*memtable.MemTable // 不可变表队列,旧到新
	handles map[uint64]*segHandle
	view    []*segHandle // 当前读视图,新 → 旧
	wal     *wal.WAL

	threshold int64

	// commitMu 串行化"冲突检测 → 取号 → WAL 追加 → fsync → memtable"。
	commitMu sync.Mutex

	// walMu 保护 e.wal 指针的读取与轮换。
	walMu sync.Mutex

	// compactionMu 串行化后台合并(同一时刻只跑一个)。
	compactionMu sync.Mutex

	// SPEC-P7:分段 HNSW 图索引(定义 / 图运行时 / 后台建图队列)。
	vec *vectorState

	lastCommitTS atomic.Uint64
	// visibleTS 是所有 commit_ts <= 它的写入都已完成 memtable 应用的水位。
	// 读路径只认它 —— group commit 下"已取号但还没应用"的提交对读者不可见。
	visibleTS atomic.Uint64

	// group commit(SPEC-P6.1 FR-698 / FR-699)。
	// commitMu 保护"排序 → 取号 → 追加"以及 pending 索引;
	// fsync 与应用由 applyPipeline 串行成组。
	pending     map[string]uint64 // 用户键 → 已追加未应用的 commit_ts
	pendingList []*pendingCommit
	appendedSeq uint64 // 已追加到 WAL 的记录数(commitMu 保护)
	durableSeq  uint64 // 已 fsync 且已应用的记录数(groupMu 保护)
	failedSeq   uint64 // 因 fsync 失败被丢弃的最大序号(groupMu 保护)

	applyPipeline sync.Mutex
	groupMu       sync.Mutex
	groupCond     *sync.Cond
	syncErr       error

	groupMaxBatch int
	groupMaxWait  time.Duration
	walBufferSize int

	maxTxnWriteBytes int64
	crashPoint       string

	compactionL0Files  int
	compactionRatio    int
	compactionLevelMax int64
	bloomEnabled       bool
	disableCompaction  bool

	// 活跃快照(G-3 / INV-7):compaction 的 GC 水位 = 最老活跃快照。
	snapMu          sync.Mutex
	activeSnapshots map[uint64]int

	flushCh   chan struct{}
	flushCond *sync.Cond
	closeCh   chan struct{}
	wg        sync.WaitGroup
	closed    bool

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
	l0 := opts.CompactionL0Files
	if l0 <= 0 {
		l0 = defaultCompactionL0Files
	}
	ratio := opts.CompactionLevelRatio
	if ratio <= 0 {
		ratio = defaultCompactionRatio
	}
	levelMax := opts.CompactionLevelMaxBytes
	if levelMax <= 0 {
		levelMax = defaultCompactionLevelMax
	}
	walBuffer := opts.WALBufferBytes
	if walBuffer <= 0 {
		walBuffer = defaultWALBufferBytes
	}
	groupBatch := opts.GroupCommitMaxBatch
	if groupBatch <= 0 {
		groupBatch = defaultGroupCommitMaxBatch
	}
	groupWaitMS := opts.GroupCommitMaxWait
	if groupWaitMS < 0 {
		groupWaitMS = defaultGroupCommitMaxWaitMS
	}

	e := &Engine{
		dataDir:            dataDir,
		walDir:             filepath.Join(dataDir, "wal"),
		sstDir:             filepath.Join(dataDir, "sst"),
		threshold:          memtableSize,
		maxTxnWriteBytes:   maxTxnBytes,
		crashPoint:         opts.TestCrashPoint,
		compactionL0Files:  l0,
		compactionRatio:    ratio,
		compactionLevelMax: levelMax,
		bloomEnabled:       opts.BloomEnabled,
		disableCompaction:  opts.DisableCompaction,
		handles:            make(map[uint64]*segHandle),
		activeSnapshots:    make(map[uint64]int),
		pending:            make(map[string]uint64),
		groupMaxBatch:      groupBatch,
		groupMaxWait:       time.Duration(groupWaitMS) * time.Millisecond,
		walBufferSize:      int(walBuffer),
		flushCh:            make(chan struct{}, 1),
		closeCh:            make(chan struct{}),
		vec:                newVectorState(),
	}
	if opts.VectorCacheBytes > 0 {
		e.vec.budget = opts.VectorCacheBytes
	}
	if opts.VectorSearchParallelism > 0 {
		e.vec.parallelism = opts.VectorSearchParallelism
	}
	e.flushCond = sync.NewCond(&e.mu)
	e.groupCond = sync.NewCond(&e.groupMu)

	for _, d := range []string{e.dataDir, e.walDir, e.sstDir, filepath.Join(e.dataDir, "tmp")} {
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
	normalizeLevels(m)

	// ①.5 清理上次崩溃遗留的孤儿 segment(不在 MANIFEST 里的 .seg / .tmp)。
	e.removeOrphans()

	// ② 加载 segment;缺失或损坏的跳过并告警。
	for level, nums := range m.Levels {
		for _, segNum := range nums {
			path := filepath.Join(e.sstDir, segment.Name(segNum))
			r, err := segment.Open(path)
			if err != nil {
				slog.Warn("segment missing or corrupt, skipping", "path", path,
					"level", level, "error", err)
				continue
			}
			h := &segHandle{num: segNum, path: path, r: r}
			h.refs.Store(1) // 引擎自身持有 1 个引用
			e.handles[segNum] = h
		}
	}
	e.rebuildViewLocked()

	// ①.6 SPEC-P7:恢复向量索引定义,并校验已有图段(损坏 → 待重建,不阻塞启动)。
	e.loadVectorDefs()

	// ③ 重放 WAL(按编号升序)。整条记录完整 + CRC 通过 = 已提交。
	mem := memtable.New()
	var replayMaxTS uint64
	stats := &Stats{}
	walNumbers, err := wal.ListNumbers(e.walDir)
	if err != nil {
		return nil, nil, fmt.Errorf("store: list wal: %w", err)
	}
	if len(walNumbers) == 0 {
		w, err := wal.OpenWithBuffer(e.walDir, m.NextWAL, e.walBufferSize)
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
		w, err := wal.OpenWithBuffer(e.walDir, current, e.walBufferSize)
		if err != nil {
			return nil, nil, err
		}
		e.wal = w
		e.mem = mem
		m.NextWAL = current + 1
	}

	// ④ 计算 next commit_ts。
	segMaxTS, err := maxCommitTSInSegments(e.handleReadersLocked())
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
	e.visibleTS.Store(last)

	m.FormatVersion = manifest.FormatVersion
	m.LastCommitTS = last
	syncSegmentsField(m)
	if err := e.manifest.Save(e.state); err != nil {
		return nil, nil, fmt.Errorf("store: save initial manifest: %w", err)
	}

	stats.SegmentsLoaded = len(e.handles)
	stats.MaxCommitTS = last

	if e.mem.ApproximateSize() >= e.threshold {
		if err := e.rotateMemtableLocked(); err != nil {
			return nil, nil, err
		}
	}

	e.wg.Add(1)
	go e.flushWorker()

	// SPEC-P7:后台建图 worker(segment 落盘后建图,不阻塞前台写读,FR-748)。
	e.vec.wg.Add(1)
	go e.vecBuildWorker()
	e.loadVectorGraphs()

	// 启动后补一次合并(老目录可能 L0 文件已经超阈值)。
	if !e.disableCompaction {
		select {
		case e.flushCh <- struct{}{}:
		default:
		}
	}
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
	// SPEC-P7:停后台建图 worker(已在建的图会在当前任务结束后退出)
	close(e.vec.stop)
	e.vec.wg.Wait()

	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wal != nil {
		_ = e.wal.Close()
	}
	// 正常关闭不删除任何数据文件,只关闭句柄。
	for _, h := range e.handles {
		if h.refs.Add(-1) == 0 {
			_ = h.r.Close()
		}
	}
	e.handles = map[uint64]*segHandle{}
	e.view = nil
	return nil
}

// Put 的语义与 P1 保持不变:单 key 自动提交事务(SPEC-P3 FR-308)。
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

// Commit 提交一个写集(SPEC-P3 FR-311 / FR-312;SPEC-P6.1 FR-693 ~ FR-699):
//
//	commitMu 内:排序去重 → 单次区间冲突检测 → 取号 → WAL 追加(不 fsync)
//	commitMu 外:等覆盖本事务的 fsync(世代) → 应用 memtable → 返回 OK
//
// 返回 OK 的前提仍然是"覆盖本事务的那次 fsync 已完成" —— 这是正确性红线,
// group commit 只是把多个事务的 fsync 合并成一次,不改变它。
func (e *Engine) Commit(snapshot, txnID uint64, writes []Write) (uint64, []byte, error) {
	if len(writes) == 0 {
		return 0, nil, nil
	}
	if err := e.validateWrites(writes); err != nil {
		return 0, nil, err
	}

	e.commitMu.Lock()

	e.mu.RLock()
	closed := e.closed
	e.mu.RUnlock()
	if closed {
		e.commitMu.Unlock()
		return 0, nil, errors.New("store: closed")
	}

	if snapshot == 0 {
		snapshot = e.visibleTS.Load()
	}

	// SPEC-P6.1 FR-693 / FR-694:排序去重后单次区间扫描做冲突检测。
	sorted := prepareWrites(writes)
	conflictKey, err := e.detectConflicts(sorted, snapshot)
	if err != nil {
		e.commitMu.Unlock()
		return 0, nil, err
	}
	if conflictKey != nil {
		e.commitMu.Unlock()
		return 0, conflictKey, nil
	}
	// 已追加、还没应用 memtable 的提交同样在 commit_ts 序列里,必须参与冲突检测,
	// 否则 group commit 会开出"两个事务同时改同一行"的口子(FR-729)。
	for _, w := range sorted {
		if ts, ok := e.pending[string(w.Key)]; ok && ts > snapshot {
			e.commitMu.Unlock()
			return 0, append([]byte(nil), w.Key...), nil
		}
	}

	e.injectCrash(CrashBeforeWAL)

	commitTS := e.lastCommitTS.Add(1)
	full := make([]wal.KV, len(sorted))
	userKeys := make([][]byte, len(sorted))
	for i, w := range sorted {
		full[i] = wal.KV{Key: AppendVersion(w.Key, commitTS), Value: w.Value}
		userKeys[i] = w.Key
	}

	e.walMu.Lock()
	currentWAL := e.wal
	appendErr := currentWAL.AppendBatchBuffered(commitTS, txnID, full)
	e.walMu.Unlock()
	if appendErr != nil {
		e.commitMu.Unlock()
		return 0, nil, fmt.Errorf("store: wal append: %w", appendErr)
	}

	e.appendedSeq++
	p := &pendingCommit{
		seq:      e.appendedSeq,
		commitTS: commitTS,
		userKeys: userKeys,
		kvs:      full,
	}
	e.pendingList = append(e.pendingList, p)
	for _, k := range userKeys {
		e.pending[string(k)] = commitTS
	}
	e.commitMu.Unlock()

	if err := e.waitDurableAndApply(p.seq); err != nil {
		return 0, nil, err
	}

	e.injectCrash(CrashAfterMemtable)
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
	return Stats{SegmentsLoaded: len(e.handles), MaxCommitTS: e.lastCommitTS.Load()}
}

// rotateMemtableLocked 将当前 memtable 冻结并创建新的 WAL/memtable。
func (e *Engine) rotateMemtableLocked() error {
	for len(e.imm) >= maxImmutableTables {
		e.flushCond.Wait()
	}

	oldMem := e.mem

	e.walMu.Lock()
	oldWal := e.wal
	newWal, err := wal.OpenWithBuffer(e.walDir, e.state.NextWAL, e.walBufferSize)
	if err != nil {
		e.walMu.Unlock()
		return fmt.Errorf("store: create new wal: %w", err)
	}
	e.wal = newWal
	e.state.NextWAL++
	e.walMu.Unlock()

	e.mem = memtable.New()
	e.imm = append(e.imm, oldMem)

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
			if !e.disableCompaction {
				e.compactLoop()
			}
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
	builder := segment.NewBloomAwareBuilder(path, e.bloomEnabled)
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
	h := &segHandle{num: segNum, path: path, r: r}
	h.refs.Store(1)
	e.handles[segNum] = h

	newState := *e.state
	newState.FormatVersion = manifest.FormatVersion
	newState.Levels = cloneLevels(e.state.Levels)
	ensureLevel(&newState.Levels, 0)
	newState.Levels[0] = append(newState.Levels[0], segNum)
	newState.Segments = append(newState.Segments, segNum)
	if tableMaxTS > newState.LastCommitTS {
		newState.LastCommitTS = tableMaxTS
	}
	if err := e.manifest.Save(&newState); err != nil {
		e.mu.Unlock()
		_ = r.Close()
		delete(e.handles, segNum)
		return fmt.Errorf("update manifest: %w", err)
	}
	e.state = &newState
	e.rebuildViewLocked()

	if len(e.imm) > 0 {
		e.imm = e.imm[1:]
	}
	e.flushCond.Broadcast()
	e.mu.Unlock()

	e.gcWAL()
	// SPEC-P7 FR-748:segment 落盘后后台建图(不阻塞前台)
	e.onSegmentFlushed(segNum)
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

// ForceFlushAll 先把当前 memtable 冻结再刷盘,确保数据落到 segment(测试 / CSV 导入用)。
func (e *Engine) ForceFlushAll() error {
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		return nil
	}
	if e.mem.Len() > 0 {
		if err := e.rotateMemtableLocked(); err != nil {
			e.mu.Unlock()
			return err
		}
	}
	e.mu.Unlock()
	return e.ForceFlush()
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

// ForceCompact 触发一次后台合并并等待完成(测试与验收脚本用)。
//
// 与后台阈值触发不同:即使只有 1 个 L0 文件也会合并,便于验收"版本回收 / 水位"。
func (e *Engine) ForceCompact() error {
	e.compactionMu.Lock()
	defer e.compactionMu.Unlock()
	for i := 0; i < 64; i++ {
		inputs, outLevel, ok := e.pickCompaction()
		if !ok {
			inputs, outLevel, ok = e.forcePick()
		}
		if !ok {
			return nil
		}
		if err := e.runCompaction(inputs, outLevel); err != nil {
			return err
		}
	}
	return nil
}

// forcePick 选择"把所有 L0 合并到 L1"(验收用)。
func (e *Engine) forcePick() ([]uint64, int, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	if len(e.state.Levels) == 0 || len(e.state.Levels[0]) == 0 {
		return nil, 0, false
	}
	inputs := append([]uint64(nil), e.state.Levels[0]...)
	if len(e.state.Levels) > 1 {
		inputs = append(inputs, e.overlapping(e.handleRange(inputs), e.state.Levels[1])...)
	}
	return inputs, 1, true
}

// LevelsSnapshot 返回各层 segment 编号(测试与验收脚本用)。
func (e *Engine) LevelsSnapshot() [][]uint64 {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return cloneLevels(e.state.Levels)
}

// SegmentRange 返回某个 segment 的 key 范围(测试用)。
func (e *Engine) SegmentRange(num uint64) ([]byte, []byte, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	h, ok := e.handles[num]
	if !ok {
		return nil, nil, false
	}
	return h.r.FirstKey(), h.r.LastKey(), true
}

// newScanner 创建覆盖 [start, end) 的原始有序扫描器(完整 key,含版本段)。
func (e *Engine) newScanner(start, end []byte, limit int) *exec.Scanner {
	e.mu.RLock()
	cursors := make([]exec.Cursor, 0, 1+len(e.imm)+len(e.view))
	acquired := make([]*segHandle, 0, len(e.view))
	cursors = append(cursors, exec.NewMemCursor(e.mem))
	for i := len(e.imm) - 1; i >= 0; i-- {
		cursors = append(cursors, exec.NewMemCursor(e.imm[i]))
	}
	for _, h := range e.view {
		h.refs.Add(1)
		acquired = append(acquired, h)
		cursors = append(cursors, exec.NewSegmentCursor(h.r))
	}
	e.mu.RUnlock()

	release := func() {
		for _, h := range acquired {
			e.releaseHandle(h, true)
		}
	}
	return exec.NewScannerWithRelease(cursors, start, end, limit, release)
}

// NewScanner 创建覆盖 [start, end) 的原始有序扫描器。
func (e *Engine) NewScanner(start, end []byte, limit int) *exec.Scanner {
	e.scanCount.Add(1)
	return e.newScanner(start, end, limit)
}

// RecordScanError 记录一次扫描失败,供心跳上报。
func (e *Engine) RecordScanError() { e.scanErrCount.Add(1) }

// maxCommitTSInSegments 扫描已落盘 segment 的 key,取出最大 commit_ts。
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
	// SPEC-P6.1 FR-706:chunk 解码 / 跳过计数(SHOW SEGMENTS)。
	ChunksRead    uint64
	ChunksSkipped uint64
}

// Status 采集当前状态。
func (e *Engine) Status() Status {
	e.mu.RLock()
	memBytes := e.mem.ApproximateSize()
	immCount := len(e.imm)
	segCount := len(e.handles)
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

	e.mu.RLock()
	for _, h := range e.handles {
		st.ChunksRead += h.r.ChunksRead()
		st.ChunksSkipped += h.r.ChunksSkipped()
	}
	e.mu.RUnlock()
	return st
}

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

// ---------------------------------------------------------------- 内部:视图与引用计数

func (e *Engine) handleReadersLocked() []*segment.Reader {
	out := make([]*segment.Reader, 0, len(e.handles))
	for _, num := range e.state.Segments {
		if h, ok := e.handles[num]; ok {
			out = append(out, h.r)
		}
	}
	return out
}

// rebuildViewLocked 依据 Levels 重建读视图(L0 新→旧,再 L1...)。调用方须持有 e.mu。
func (e *Engine) rebuildViewLocked() {
	view := make([]*segHandle, 0, len(e.handles))
	for level, nums := range e.state.Levels {
		if level == 0 {
			for i := len(nums) - 1; i >= 0; i-- {
				if h, ok := e.handles[nums[i]]; ok {
					view = append(view, h)
				}
			}
			continue
		}
		for _, num := range nums {
			if h, ok := e.handles[num]; ok {
				view = append(view, h)
			}
		}
	}
	e.view = view
}

func (e *Engine) releaseHandle(h *segHandle, deleteIfZero bool) {
	if h == nil {
		return
	}
	if h.refs.Add(-1) == 0 {
		_ = h.r.Close()
		if deleteIfZero && h.retired.Load() {
			if err := os.Remove(h.path); err != nil && !os.IsNotExist(err) {
				slog.Warn("remove retired segment failed", "path", h.path, "error", err)
			}
		}
	}
}

// retireHandleLocked 把句柄从引擎视图移除;真正删除等所有读者退出。调用方须持有 e.mu。
func (e *Engine) retireHandleLocked(h *segHandle) {
	delete(e.handles, h.num)
	h.retired.Store(true)
	e.releaseHandle(h, true)
}

// removeOrphans 删除不在 MANIFEST 里的 segment 与临时文件(崩溃残留)。
func (e *Engine) removeOrphans() {
	entries, err := os.ReadDir(e.sstDir)
	if err != nil {
		return
	}
	known := make(map[string]bool, len(e.state.Segments))
	for _, num := range e.state.Segments {
		known[segment.Name(num)] = true
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		name := entry.Name()
		if known[name] {
			continue
		}
		if _, ok := segment.ParseName(name); ok || filepath.Ext(name) == ".tmp" {
			path := filepath.Join(e.sstDir, name)
			if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
				slog.Warn("remove orphan segment failed", "path", path, "error", err)
			} else {
				slog.Info("已清理孤儿 segment", "path", path)
			}
		}
	}
}

// ---------------------------------------------------------------- 分层工具

func cloneLevels(levels [][]uint64) [][]uint64 {
	out := make([][]uint64, len(levels))
	for i := range levels {
		out[i] = append([]uint64(nil), levels[i]...)
	}
	return out
}

func ensureLevel(levels *[][]uint64, level int) {
	for len(*levels) <= level {
		*levels = append(*levels, []uint64{})
	}
}

func normalizeLevels(m *manifest.Manifest) {
	if len(m.Levels) == 0 && len(m.Segments) > 0 {
		m.Levels = [][]uint64{append([]uint64(nil), m.Segments...)}
	}
	if len(m.Levels) == 0 {
		m.Levels = [][]uint64{{}}
	}
	for len(m.Levels) > 1 && len(m.Levels[len(m.Levels)-1]) == 0 {
		m.Levels = m.Levels[:len(m.Levels)-1]
	}
}

func syncSegmentsField(m *manifest.Manifest) {
	m.Segments = m.Segments[:0]
	for _, nums := range m.Levels {
		m.Segments = append(m.Segments, nums...)
	}
	sort.Slice(m.Segments, func(i, j int) bool { return m.Segments[i] < m.Segments[j] })
}
