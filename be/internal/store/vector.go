package store

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/1638540149/tang_db/be/internal/exec"
	"github.com/1638540149/tang_db/be/internal/manifest"
	"github.com/1638540149/tang_db/be/internal/vecidx"
)

// SPEC-P7 B/C 组:向量索引(分段 HNSW 图)。
//
// 生命周期:
//
//	RegisterVectorIndex → 定义落 MANIFEST → 每个 segment 后台建图
//	→ 图写 .tmp → fsync → rename → MANIFEST 记 READY(FR-746)
//	→ 可被 SearchVectorIndex 使用;失败的段标 PENDING,检索退化暴力(FR-747/FR-748)
//
// 边界:这里只有"行键范围 + 列槽表 + 行框架 + 维度 + 度量操作码 + float32",
// 没有表名 / 列名 / 类型名 / SQL(K161 / INV-16)。

// ErrNoVectorIndex 表示索引未注册(调用方参数错误)。
var ErrNoVectorIndex = errors.New("store: vector index not registered")

// ErrNoVectorIndexDefinition 表示索引定义不存在。
var ErrNoVectorIndexDefinition = errors.New("store: vector index definition missing")

// VecDefinition 是 FE 下发的建图/检索参数(纯字节 + 数字)。
type VecDefinition struct {
	IndexID        uint64
	TableID        uint64
	StartKey       []byte
	EndKey         []byte
	ColumnSlots    []byte
	RowFrame       []byte
	VectorOrdinal  int
	Dim            int
	Metric         uint8
	M              int
	EfConstruction int
}

// vecCandidate 是跨段归并的中间结构(用户键 + 近似距离 + 行序号)。
type vecCandidate struct {
	key  []byte
	dist float32
	ord  uint64
}

// VecKeyHit 是一条候选(用户键 + 距离)。FE 用同一个快照回表并重算精确距离。
type VecKeyHit struct {
	Key      []byte
	Distance float32
	Ordinal  uint64
}

// VecStatus 是段级状态(SHOW VECTOR INDEXES 用)。
type VecStatus struct {
	SegmentCount    int
	ReadySegments   int
	PendingSegments int
	NodeCount       uint64
	GraphBytes      uint64
	Metric          uint8
	Dim             int
}

// vecRuntime 是一张已加载的图 + 它需要的向量与行键(向量不存图里,FR-744 / 边界 132)。
//
// SPEC-P7.1 K178 / K179:列部分(keys/vectors)常驻缓存,按字节预算 LRU 淘汰;
// refs > 0 的运行时正在被检索引用,不参与淘汰(边界 156)。列部分的懒加载由
// mu 单飞(并发 8 线程下只解码一次整列,而不是 8 份,FR-803)。
type vecRuntime struct {
	mu      sync.RWMutex
	graph   *vecidx.Graph
	keys    [][]byte
	vectors [][]float32
	bytes   int64
	refs    int32
	lastUse atomic.Int64
}

func (rt *vecRuntime) needsColumnsLocked() bool {
	return rt != nil && rt.graph != nil && rt.keys == nil
}

// hasColumns 报告列部分是否已常驻(供打点区分固定开销与图搜索)。
func (rt *vecRuntime) hasColumns() bool {
	if rt == nil {
		return false
	}
	rt.mu.RLock()
	ok := rt.keys != nil
	rt.mu.RUnlock()
	return ok
}

type vecJob struct {
	indexID uint64
	segment uint64
}

// vectorState 是引擎里的向量索引状态(全部受 mu 保护)。
type vectorState struct {
	mu      sync.Mutex
	defs    map[uint64]*VecDefinition
	ready   map[uint64]map[uint64]*vecRuntime // indexID → segment → runtime
	pending map[uint64]map[uint64]bool        // indexID → segment → 待建/待重建
	failed  map[uint64]map[uint64]bool        // indexID → segment → 建图失败(退化为暴力)
	bySeg   map[uint64]map[uint64]bool        // segment → indexID(便于 flush/compaction 查找)
	queue   chan vecJob
	stop    chan struct{}
	wg      sync.WaitGroup

	// SPEC-P7.1 K178 / K179:列部分(向量 + 行键)的常驻缓存预算与用量。
	cacheBytes int64
	budget     int64
	// 段级并行的并发上限(边界 162:不无限起线程)。
	parallelism int
}

func newVectorState() *vectorState {
	return &vectorState{
		defs:        make(map[uint64]*VecDefinition),
		ready:       make(map[uint64]map[uint64]*vecRuntime),
		pending:     make(map[uint64]map[uint64]bool),
		failed:      make(map[uint64]map[uint64]bool),
		bySeg:       make(map[uint64]map[uint64]bool),
		queue:       make(chan vecJob, 1024),
		stop:        make(chan struct{}),
		budget:      defaultVectorCacheBytes(),
		parallelism: defaultVectorParallelism(),
	}
}

// ---------------------------------------------------------------- 定义注册 / 状态

// RegisterVectorIndex 注册(或覆盖)一个向量索引定义:定义落 MANIFEST,并为
// 还没有图的 segment 排队建图(FR-748:后台建,不阻塞前台读写)。
func (e *Engine) RegisterVectorIndex(def VecDefinition) error {
	if def.IndexID == 0 || def.Dim <= 0 || def.Dim > 4096 {
		return fmt.Errorf("store: invalid vector definition (index=%d dim=%d)", def.IndexID, def.Dim)
	}
	if len(def.ColumnSlots) == 0 || len(def.RowFrame) == 0 {
		return errors.New("store: vector definition needs column_slots and row_frame")
	}
	if def.M < 4 || def.M > 64 {
		def.M = 16
	}
	if def.EfConstruction < 16 || def.EfConstruction > 1024 {
		def.EfConstruction = 200
	}
	copyDef := cloneDef(def)

	e.vec.mu.Lock()
	e.vec.defs[def.IndexID] = copyDef
	e.vec.mu.Unlock()

	// 定义落 MANIFEST(重启后不必等 FE 再注册就能检索)
	e.mu.Lock()
	newState := *e.state
	newState.FormatVersion = manifest.FormatVersion
	newState.VecDefs = replaceVecDef(e.state.VecDefs, copyDef)
	if err := e.manifest.Save(&newState); err != nil {
		e.mu.Unlock()
		return fmt.Errorf("store: save vector definition: %w", err)
	}
	e.state = &newState
	// 为当前所有与范围重叠的 segment 排队建图
	segs := make([]uint64, 0, len(e.handles))
	for num, h := range e.handles {
		if segmentOverlaps(h, copyDef.StartKey, copyDef.EndKey) {
			segs = append(segs, num)
		}
	}
	e.mu.Unlock()

	e.vec.mu.Lock()
	for _, seg := range segs {
		e.markPendingLocked(copyDef.IndexID, seg)
	}
	e.vec.mu.Unlock()
	for _, seg := range segs {
		e.enqueueVecBuild(copyDef.IndexID, seg)
	}
	return nil
}

// RebuildVectorIndex 删掉旧图并重新排队(FR-750)。
func (e *Engine) RebuildVectorIndex(indexID uint64) error {
	e.vec.mu.Lock()
	def, ok := e.vec.defs[indexID]
	e.vec.mu.Unlock()
	if !ok {
		return ErrNoVectorIndexDefinition
	}
	e.dropVectorIndexLocked(indexID)
	return e.RegisterVectorIndex(*def)
}

// DropVectorIndex 删除某索引的全部图文件与 MANIFEST 记录(FR-737 / 边界 153)。
func (e *Engine) DropVectorIndex(indexID uint64) error {
	e.dropVectorIndexLocked(indexID)
	return nil
}

func (e *Engine) dropVectorIndexLocked(indexID uint64) {
	e.vec.mu.Lock()
	delete(e.vec.defs, indexID)
	e.vec.ready[indexID] = map[uint64]*vecRuntime{}
	delete(e.vec.pending, indexID)
	delete(e.vec.failed, indexID)
	for seg, set := range e.vec.bySeg {
		delete(set, indexID)
		if len(set) == 0 {
			delete(e.vec.bySeg, seg)
		}
	}
	e.vec.mu.Unlock()

	e.mu.Lock()
	newState := *e.state
	newState.FormatVersion = manifest.FormatVersion
	kept := make([]manifest.VecIndexEntry, 0, len(e.state.VecIndexes))
	for _, entry := range e.state.VecIndexes {
		if entry.IndexID == indexID {
			_ = os.Remove(filepath.Join(e.sstDir, entry.File))
			continue
		}
		kept = append(kept, entry)
	}
	newState.VecIndexes = kept
	newState.VecDefs = removeVecDef(e.state.VecDefs, indexID)
	if err := e.manifest.Save(&newState); err != nil {
		slog.Error("drop vector index: save manifest failed", "index", indexID, "error", err)
		return
	}
	e.state = &newState
	e.mu.Unlock()
}

// VectorIndexStatus 返回段级状态(SHOW VECTOR INDEXES / information_schema)。
func (e *Engine) VectorIndexStatus(indexID uint64) VecStatus {
	status := VecStatus{}
	e.vec.mu.Lock()
	if def, ok := e.vec.defs[indexID]; ok {
		status.Dim = def.Dim
		status.Metric = def.Metric
	}
	status.PendingSegments = len(e.vec.pending[indexID]) + len(e.vec.failed[indexID])
	e.vec.mu.Unlock()
	// 段数以 MANIFEST 里的权威记录为准(bySeg 只是建图排队的辅助索引)
	segs := make(map[uint64]bool)
	e.mu.RLock()
	for _, entry := range e.state.VecIndexes {
		if entry.IndexID == indexID {
			segs[entry.Segment] = true
		}
	}
	e.mu.RUnlock()
	status.SegmentCount = len(segs)
	e.mu.RLock()
	for _, entry := range e.state.VecIndexes {
		if entry.IndexID != indexID {
			continue
		}
		if entry.State == manifest.VecStateReady {
			status.ReadySegments++
			status.NodeCount += entry.NodeCount
		}
		info, err := os.Stat(filepath.Join(e.sstDir, entry.File))
		if err == nil {
			status.GraphBytes += uint64(info.Size())
		}
	}
	e.mu.RUnlock()
	return status
}

// VectorIndexNodeCount 汇总某索引的节点数(SHOW SEGMENTS / 诊断用)。
func (e *Engine) VectorIndexNodeCount(indexID uint64) uint64 {
	var total uint64
	e.mu.RLock()
	for _, entry := range e.state.VecIndexes {
		if entry.IndexID == indexID && entry.State == manifest.VecStateReady {
			total += entry.NodeCount
		}
	}
	e.mu.RUnlock()
	return total
}

// ---------------------------------------------------------------- 建图任务

func (e *Engine) markPendingLocked(indexID, seg uint64) {
	if e.vec.pending[indexID] == nil {
		e.vec.pending[indexID] = make(map[uint64]bool)
	}
	e.vec.pending[indexID][seg] = true
	if e.vec.bySeg[seg] == nil {
		e.vec.bySeg[seg] = make(map[uint64]bool)
	}
	e.vec.bySeg[seg][indexID] = true
	if rt := e.vec.ready[indexID][seg]; rt != nil {
		delete(e.vec.ready[indexID], seg)
	}
}

func (e *Engine) enqueueVecBuild(indexID, seg uint64) {
	select {
	case e.vec.queue <- vecJob{indexID: indexID, segment: seg}:
	default:
		slog.Warn("vector build queue full, dropping job", "index", indexID, "segment", seg)
	}
}

func (e *Engine) vecBuildWorker() {
	defer e.vec.wg.Done()
	for {
		select {
		case <-e.vec.stop:
			return
		case job := <-e.vec.queue:
			if err := e.buildVectorGraph(job.indexID, job.segment); err != nil {
				slog.Warn("vector graph build failed", "index", job.indexID,
					"segment", job.segment, "error", err)
				e.vec.mu.Lock()
				if e.vec.failed[job.indexID] == nil {
					e.vec.failed[job.indexID] = make(map[uint64]bool)
				}
				e.vec.failed[job.indexID][job.segment] = true
				if e.vec.pending[job.indexID] != nil {
					delete(e.vec.pending[job.indexID], job.segment)
				}
				e.vec.mu.Unlock()
			}
		}
	}
}

// buildVectorGraph 为一个 segment 建图并原子发布(FR-746):
// 图写 .tmp → fsync → rename → MANIFEST 记 READY。
func (e *Engine) buildVectorGraph(indexID, segNum uint64) error {
	e.vec.mu.Lock()
	def, ok := e.vec.defs[indexID]
	e.vec.mu.Unlock()
	if !ok {
		return ErrNoVectorIndexDefinition
	}
	e.mu.RLock()
	h, alive := e.handles[segNum]
	e.mu.RUnlock()
	slog.Info("向量建图任务开始", "index", indexID, "segment", segNum, "alive", alive)
	if !alive {
		// 边界 141:建图期间 segment 被回收 → 放弃,不写 MANIFEST。
		e.vec.mu.Lock()
		if e.vec.pending[indexID] != nil {
			delete(e.vec.pending[indexID], segNum)
		}
		e.vec.mu.Unlock()
		return nil
	}
	keys, vectors, ordinals, err := e.extractVectors(h, def)
	if err != nil {
		return err
	}
	slog.Info("向量建图抽取完成", "index", indexID, "segment", segNum,
		"keys", len(keys), "vectors", len(vectors))
	if len(vectors) == 0 {
		e.vec.mu.Lock()
		if e.vec.pending[indexID] != nil {
			delete(e.vec.pending[indexID], segNum)
		}
		e.vec.mu.Unlock()
		return nil
	}
	graph, err := vecidx.Build(vectors, ordinals, def.Metric, vecidx.BuildConfig{
		M:              def.M,
		EfConstruction: def.EfConstruction,
		Seed:           indexID*1_000_003 + segNum,
		BuildSeq:       segNum,
	})
	if err != nil {
		return fmt.Errorf("build hnsw: %w", err)
	}
	file := fmt.Sprintf("%s-%d.hnsw", vecFileName(segNum), indexID)
	path := filepath.Join(e.sstDir, file)
	if _, err := vecidx.WriteFile(path, graph); err != nil {
		_ = os.Remove(path)
		return err
	}
	entry := manifest.VecIndexEntry{
		IndexID:        indexID,
		TableID:        def.TableID,
		Segment:        segNum,
		File:           file,
		Dim:            uint32(def.Dim),
		Metric:         def.Metric,
		M:              uint32(def.M),
		EfConstruction: uint32(def.EfConstruction),
		NodeCount:      uint64(graph.NodeCount()),
		State:          manifest.VecStateReady,
		BuildSeq:       segNum,
	}
	e.mu.Lock()
	newState := *e.state
	newState.FormatVersion = manifest.FormatVersion
	newState.VecIndexes = upsertVecEntry(e.state.VecIndexes, entry)
	if err := e.manifest.Save(&newState); err != nil {
		e.mu.Unlock()
		_ = os.Remove(path)
		return fmt.Errorf("publish vector graph: %w", err)
	}
	e.state = &newState
	e.mu.Unlock()

	rt := &vecRuntime{graph: graph, keys: keys, vectors: vectors,
		bytes: runtimeColumnsBytes(keys, vectors, def.Dim)}
	e.vec.mu.Lock()
	if e.vec.ready[indexID] == nil {
		e.vec.ready[indexID] = make(map[uint64]*vecRuntime)
	}
	if old := e.vec.ready[indexID][segNum]; old != nil {
		// 重建:旧运行时的列部分被替换,先出账(避免重复计数)。
		e.vec.cacheBytes -= old.bytes
	}
	e.vec.ready[indexID][segNum] = rt
	// SPEC-P7.1 K178:建图时同步入账(列部分已经在内存里,不做二次回读)。
	e.vec.cacheBytes += rt.bytes
	e.evictVectorColumnsLocked()
	if e.vec.pending[indexID] != nil {
		delete(e.vec.pending[indexID], segNum)
	}
	if e.vec.failed[indexID] != nil {
		delete(e.vec.failed[indexID], segNum)
	}
	e.vec.mu.Unlock()
	slog.Info("向量图段已发布", "index", indexID, "segment", segNum,
		"nodes", graph.NodeCount(), "levels", graph.LevelCount, "file", file)
	return nil
}

func vecFileName(seg uint64) string {
	return fmt.Sprintf("%d", seg)
}

// segmentOverlaps 判断 segment 的 key 范围是否与用户键范围 [start,end) 有交集。
//
// segment 的 FirstKey/LastKey 是**完整 key**(用户键 + 8 字节版本段),而 start/end 是
// 用户键级边界,所以先剥掉版本段再比较(P3 §5.1 的 key 约定)。
func segmentOverlaps(h *segHandle, start, end []byte) bool {
	first := stripVersion(h.r.FirstKey())
	last := stripVersion(h.r.LastKey())
	if len(first) == 0 || len(last) == 0 {
		return false
	}
	if len(end) > 0 && bytes.Compare(first, end) >= 0 {
		return false
	}
	if len(start) > 0 && bytes.Compare(last, start) < 0 {
		return false
	}
	return true
}

// extractVectors 从 segment 里按行序取出"每行最新版本"的向量。
//
// 返回的 keys/vectors 与图节点序号一一对应;ordinals 是 segment 内的行序号。
// 全程只用 FE 下发的列槽/行框架做字节切片(BE 不认列语义)。
func (e *Engine) extractVectors(h *segHandle, def *VecDefinition) ([][]byte, [][]float32,
	[]uint64, error) {
	slots, err := exec.ParseSlots(def.ColumnSlots)
	if err != nil {
		return nil, nil, nil, err
	}
	frame, err := exec.ParseRowFrame(def.RowFrame)
	if err != nil {
		return nil, nil, nil, err
	}
	layout, err := exec.NewRowLayout(frame, slots)
	if err != nil {
		return nil, nil, nil, err
	}
	mask := uint32(1) << uint(def.VectorOrdinal)
	cur := exec.NewSegmentCursor(h.r)
	cur.Seek(def.StartKey)
	keys := make([][]byte, 0, 1024)
	vectors := make([][]float32, 0, 1024)
	ordinals := make([]uint64, 0, 1024)
	var lastKey []byte
	for cur.Valid() {
		fullKey := cur.Key()
		userKey, _, err := SplitVersion(fullKey)
		if err != nil {
			return nil, nil, nil, err
		}
		if len(def.EndKey) > 0 && bytes.Compare(userKey, def.EndKey) >= 0 {
			break
		}
		if lastKey != nil && bytes.Equal(userKey, lastKey) {
			// 同一用户键的旧版本:图只认最新一版(旧版本由 MVCC 过滤掉)
			cur.Next()
			continue
		}
		lastKey = append(lastKey[:0], userKey...)
		value := cur.Value()
		cur.Next()
		if IsTombstoneValue(value) {
			continue
		}
		projected, _, ok := layout.ProjectRow(value, mask)
		if !ok {
			slog.Warn("向量抽取:行不可投影", "len", len(value), "first", value[0],
				"colcount", int(binary.LittleEndian.Uint16(value[5:7])),
				"frame_cols", len(slots), "want_dim", def.Dim,
				"ord", def.VectorOrdinal, "slots", slots)
			continue
		}
		vec, ok := frameVector(projected, def.Dim)
		if !ok {
			slog.Warn("向量抽取:槽位不可解析", "frame_len", len(projected),
				"want", def.Dim*4, "nullmap", projected[0])
			continue
		}
		keys = append(keys, append([]byte(nil), userKey...))
		vectors = append(vectors, vec)
		ordinals = append(ordinals, uint64(len(ordinals)))
	}
	if err := cur.Err(); err != nil {
		return nil, nil, nil, err
	}
	return keys, vectors, ordinals, nil
}

// vectorFromPayload 把定长 float32 载荷读进 dst(复用缓冲,不分配);维度不符返回 false。
func vectorFromPayload(payload []byte, dim int, dst []float32) ([]float32, bool) {
	if len(payload) != dim*4 || len(dst) < dim {
		return nil, false
	}
	for i := 0; i < dim; i++ {
		dst[i] = math.Float32frombits(binary.LittleEndian.Uint32(payload[i*4:]))
	}
	return dst[:dim], true
}

// frameVector 从投影帧里取唯一一列(向量)的 float32。
// 帧格式:[nullmap 1B][len u32][payload](与 ProjectedCodec/RowLayout 一致)。
func frameVector(frame []byte, dim int) ([]float32, bool) {
	if len(frame) < 5 {
		return nil, false
	}
	if frame[0]&1 != 0 {
		return nil, false // NULL 向量不建节点
	}
	n := int(binary.LittleEndian.Uint32(frame[1:5]))
	if n != dim*4 || len(frame) < 5+n {
		return nil, false
	}
	out := make([]float32, dim)
	for i := 0; i < dim; i++ {
		bits := binary.LittleEndian.Uint32(frame[5+i*4:])
		out[i] = math.Float32frombits(bits)
	}
	return out, true
}

// ---------------------------------------------------------------- 检索

// VecTimings 是一次检索的五段耗时(SPEC-P7.1 K181 / FR-791)。
//
// 五段与规约 §2.1 对应:
//
//	候选准备   Prepare  —— 定义查找 / 查询向量解码 / 段句柄快照
//	回表前的数据准备 DataPrep —— 图运行时的向量与行键就绪(整列回读 / 缓存命中)
//	图搜索     Graph    —— 各段 HNSW 遍历
//	memtable   Memtable —— 未落盘行的暴力扫描
//	候选归并   Merge    —— 跨段去重、排序、截断与结果装配
//
// 默认关闭:只有请求带 profile 时才计时(时间戳本身也有成本,不能常开)。
type VecTimings struct {
	PrepareMs  float64
	DataPrepMs float64
	GraphMs    float64
	MemtableMs float64
	MergeMs    float64
	TotalMs    float64

	Segments      int
	GraphSegments int
	BruteSegments int
	Examined      int
	Candidates    int
	CacheHits     int
	CacheMisses   int
	// Allow 描述本次检索收到的允许集(none / keys:N / bloom:m=,k= / invalid)。
	Allow string
}

// String 输出 KEY=VALUE 形式,便于日志 grep 与 FE 解析。
func (t *VecTimings) String() string {
	if t == nil {
		return ""
	}
	return fmt.Sprintf("prepare=%.2fms data_prep=%.2fms graph=%.2fms memtable=%.2fms "+
		"merge=%.2fms total=%.2fms segments=%d graph_segments=%d brute_segments=%d "+
		"visited=%d candidates=%d cache_hits=%d cache_misses=%d allow=%s",
		t.PrepareMs, t.DataPrepMs, t.GraphMs, t.MemtableMs, t.MergeMs, t.TotalMs,
		t.Segments, t.GraphSegments, t.BruteSegments, t.Examined, t.Candidates,
		t.CacheHits, t.CacheMisses, t.Allow)
}

// SearchVectorIndex 做一次近似检索(SPEC-P7 FR-754):每段 top-ef → 归并 → MVCC 过滤。
//
// 图缺失 / 待重建的段在此退化为暴力(FR-747 / 边界 138);memtable 部分始终暴力(K163)。
// 返回的候选已按快照做过可见性过滤,条数可以少于 k(由 FE 决定 ef 翻倍或回退)。
//
// profile=true 时同时返回五段耗时(SPEC-P7.1 K181);默认关闭,调用方传 false。
func (e *Engine) SearchVectorIndex(indexID uint64, query []byte, ef, k int,
	snapshot uint64, profile bool, allowRaw []byte, allowBloom bool) ([]VecKeyHit, int,
	*VecTimings, error) {
	allow := parseVecAllow(allowRaw, allowBloom)
	var timings *VecTimings
	allowInfo := "none"
	if len(allowRaw) > 0 {
		switch {
		case allow == nil:
			allowInfo = fmt.Sprintf("invalid:%dbytes", len(allowRaw))
		case allow.bloom != nil:
			allowInfo = fmt.Sprintf("bloom:m=%d,k=%d", allow.bloom.m, allow.bloom.k)
		default:
			allowInfo = fmt.Sprintf("keys:%d", len(allow.keys))
		}
	}
	if profile {
		timings = &VecTimings{}
	}
	started := time.Now()
	// ---- ① 候选准备:定义 / 查询向量 / 段句柄快照 ----
	e.vec.mu.Lock()
	def, ok := e.vec.defs[indexID]
	e.vec.mu.Unlock()
	if !ok {
		return nil, 0, timings, ErrNoVectorIndex
	}
	if len(query) != def.Dim*4 {
		return nil, 0, timings, fmt.Errorf("store: query vector dim mismatch (got %d bytes want %d)",
			len(query), def.Dim*4)
	}
	qv := make([]float32, def.Dim)
	for i := 0; i < def.Dim; i++ {
		qv[i] = math.Float32frombits(binary.LittleEndian.Uint32(query[i*4:]))
	}
	if ef <= 0 {
		ef = 64
	}
	if k <= 0 {
		k = 10
	}
	if ef < k {
		ef = k
	}
	examined := 0

	e.mu.RLock()
	handles := make([]*segHandle, 0, len(e.view))
	for _, h := range e.view {
		handles = append(handles, h)
	}
	e.mu.RUnlock()
	if timings != nil {
		timings.PrepareMs = msSince(started)
	}

	// 跨段归并直接用候选切片:10 万级下 map[string] 的装箱与哈希是纯额外成本(K180)。
	//
	// SPEC-P7.1 C 组(K183 / FR-801~804):段级并行 + memtable 扫描与段搜索并行,
	// 每个 worker 只写自己的候选切片(无共享写);运行时由引用计数保护不被淘汰。
	var targets []*segHandle
	for _, h := range handles {
		if segmentOverlaps(h, def.StartKey, def.EndKey) {
			targets = append(targets, h)
		}
	}
	if timings != nil {
		timings.Segments = len(targets)
	}
	e.vec.mu.Lock()
	parallel := e.vec.parallelism
	e.vec.mu.Unlock()
	if parallel < 1 {
		parallel = 1
	}
	if parallel > len(targets) && len(targets) > 0 {
		parallel = len(targets)
	}

	type segResult struct {
		cands     []vecCandidate
		scanned   int
		cacheHit  bool
		cacheMiss bool
		brute     bool
	}
	results := make([]segResult, len(targets))
	errs := make([]error, len(targets))
	var graphMs, dataPrepMs []float64
	if timings != nil {
		graphMs = make([]float64, len(targets))
		dataPrepMs = make([]float64, len(targets))
	}

	// memtable 暴力与段搜索并行(两者读的都是只读快照)。
	memCh := make(chan struct {
		hits    []VecKeyHit
		scanned int
	}, 1)
	memStart := time.Now()
	go func() {
		top := e.bruteForceMemtables(def, qv, snapshot, ef)
		memCh <- top
	}()

	var wg sync.WaitGroup
	sem := make(chan struct{}, parallel)
	for i, h := range targets {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int, h *segHandle) {
			defer wg.Done()
			defer func() { <-sem }()
			prepStart := time.Now()
			rt, cacheHit := e.acquireRuntime(indexID, h.num, def, h)
			if timings != nil {
				dataPrepMs[i] = msSince(prepStart)
			}
			if rt != nil {
				results[i].cacheHit = cacheHit
				results[i].cacheMiss = !cacheHit
				defer e.releaseRuntime(indexID, h.num)
				graphStart := time.Now()
				rt.mu.RLock()
				lookup := func(id uint32) []float32 { return rt.vectors[id] }
				var accept func(uint32) bool
				if allow != nil {
					keys := rt.keys
					accept = func(id uint32) bool {
						if int(id) >= len(keys) {
							return false
						}
						return allow.contains(keys[id])
					}
				}
				hits := rt.graph.Search(qv, ef, ef, lookup, accept)
				local := make([]vecCandidate, 0, len(hits))
				for _, hit := range hits {
					if int(hit.Node) >= len(rt.keys) {
						continue
					}
					local = append(local, vecCandidate{key: rt.keys[hit.Node],
						dist: hit.Distance, ord: rt.graph.RowOrdinals[hit.Node]})
				}
				rt.mu.RUnlock()
				if timings != nil {
					graphMs[i] = msSince(graphStart)
				}
				results[i].cands = local
				results[i].scanned = len(hits)
				return
			}
			// 没有可用图 → 该段退化为暴力
			results[i].brute = true
			bruteStart := time.Now()
			keys, vectors, ordinals, err := e.extractVectors(h, def)
			if err != nil {
				errs[i] = err
				return
			}
			local := make([]vecCandidate, 0, len(vectors))
			for j := range vectors {
				if allow != nil && !allow.contains(keys[j]) {
					continue
				}
				dist := vecidx.Distance(def.Metric, qv, vectors[j])
				local = append(local, vecCandidate{key: keys[j], dist: dist, ord: ordinals[j]})
			}
			if timings != nil {
				graphMs[i] = msSince(bruteStart)
			}
			results[i].cands = local
			results[i].scanned = len(vectors)
		}(i, h)
	}
	wg.Wait()
	for _, err := range errs {
		if err != nil {
			<-memCh
			return nil, examined, timings, err
		}
	}
	topMem := <-memCh
	if timings != nil {
		timings.MemtableMs = msSince(memStart)
		var gm, dm float64
		for i := range targets {
			gm += graphMs[i]
			dm += dataPrepMs[i]
			if results[i].brute {
				timings.BruteSegments++
			}
			if results[i].cacheHit {
				timings.CacheHits++
			} else if results[i].cacheMiss {
				timings.CacheMisses++
			}
		}
		timings.GraphMs = gm
		timings.DataPrepMs = dm
		timings.GraphSegments = len(targets) - timings.BruteSegments
	}
	cands := make([]vecCandidate, 0, 128)
	for _, res := range results {
		cands = append(cands, res.cands...)
		examined += res.scanned
	}
	examined += topMem.scanned
	for _, hit := range topMem.hits {
		if allow != nil && !allow.contains(hit.Key) {
			continue
		}
		cands = append(cands, vecCandidate{key: hit.Key, dist: hit.Distance})
	}

	// ---- ⑤ 候选归并:按 key 去重(保留最小距离)→ 按 (距离, key) 排序 → 截断 ef ----
	mergeStart := time.Now()
	out := mergeCandidates(cands, ef)
	if timings != nil {
		timings.MergeMs = msSince(mergeStart)
		timings.TotalMs = msSince(started)
		timings.Examined = examined
		timings.Candidates = len(out)
		timings.Allow = allowInfo
	}
	e.vecSearchCount.Add(1)
	if examined > 0 {
		e.vecCandidates.Add(uint64(examined))
	}
	return out, examined, timings, nil
}

// vectorStatusTotals 汇总全部向量索引的图段数 / 节点数 / 待重建段数(SPEC-P8 FR-828)。
func (e *Engine) vectorStatusTotals() (segments, nodes, pending uint64) {
	e.vec.mu.Lock()
	defer e.vec.mu.Unlock()
	for _, bySeg := range e.vec.ready {
		segments += uint64(len(bySeg))
	}
	for _, bySeg := range e.vec.pending {
		pending += uint64(len(bySeg))
	}
	for _, bySeg := range e.vec.ready {
		for _, rt := range bySeg {
			rt.mu.RLock()
			if rt.graph != nil {
				nodes += uint64(rt.graph.NodeCount())
			}
			rt.mu.RUnlock()
		}
	}
	return segments, nodes, pending
}

// mergeCandidates 做跨段归并:同一用户键在多个段/内存里出现时只保留最小距离。
//
// 用 []byte 直接比较,不做 string 转换(map[string] 在 10 万级候选下是纯开销,K180)。
func mergeCandidates(cands []vecCandidate, ef int) []VecKeyHit {
	if len(cands) == 0 {
		return nil
	}
	// 先按 key 排序,同 key 相邻;排序期间同步带上距离,避免二次查找。
	sort.SliceStable(cands, func(i, j int) bool {
		return bytes.Compare(cands[i].key, cands[j].key) < 0
	})
	deduped := cands[:0]
	for i := range cands {
		if len(deduped) > 0 && bytes.Equal(deduped[len(deduped)-1].key, cands[i].key) {
			if cands[i].dist < deduped[len(deduped)-1].dist {
				deduped[len(deduped)-1].dist = cands[i].dist
			}
			continue
		}
		deduped = append(deduped, cands[i])
	}
	out := make([]VecKeyHit, len(deduped))
	for i, c := range deduped {
		out[i] = VecKeyHit{Key: c.key, Distance: c.dist, Ordinal: c.ord}
	}
	sortHits(out)
	if len(out) > ef {
		out = out[:ef]
	}
	return out
}

// msSince 是分段计时的唯一出口(单位毫秒)。
func msSince(start time.Time) float64 {
	return float64(time.Since(start).Microseconds()) / 1000.0
}

// bruteForceMemtables 只扫 memtable + immutable(段内没有图的数据也已单独处理)。
//
// SPEC-P7.1 K180:只保留**本来源的 top-`limit`**(limit = ef)。全局 top-ef 一定是各来源
// top-ef 的子集,所以这一步不改变结果,却把归并规模从"整个 memtable"降到 ef ——
// 未落盘 1 万行时归并从 6ms 降到 0.2ms。
func (e *Engine) bruteForceMemtables(def *VecDefinition, qv []float32,
	snapshot uint64, limit int) struct {
	hits    []VecKeyHit
	scanned int
} {
	res := struct {
		hits    []VecKeyHit
		scanned int
	}{}
	if limit <= 0 {
		limit = 64
	}
	slots, err := exec.ParseSlots(def.ColumnSlots)
	if err != nil {
		return res
	}
	frame, err := exec.ParseRowFrame(def.RowFrame)
	if err != nil {
		return res
	}
	layout, err := exec.NewRowLayout(frame, slots)
	if err != nil {
		return res
	}
	e.mu.RLock()
	cursors := make([]exec.Cursor, 0, 1+len(e.imm))
	cursors = append(cursors, exec.NewMemCursor(e.mem))
	for i := len(e.imm) - 1; i >= 0; i-- {
		cursors = append(cursors, exec.NewMemCursor(e.imm[i]))
	}
	e.mu.RUnlock()
	sc := exec.NewScanner(cursors, def.StartKey, nil, 0)
	defer sc.Close()
	scanner := newMVCCScanner(sc, def.EndKey, snapshot, 0)
	scratch := make([]float32, def.Dim)
	for scanner.Next() {
		value := scanner.Value()
		if IsTombstoneValue(value) {
			continue
		}
		res.scanned++
		// SPEC-P7.1:直接取向量列的载荷,不建帧、不复制(K180 / 固定开销消除)。
		payload, isNull, ok := layout.ColumnPayload(value, def.VectorOrdinal)
		if !ok {
			continue
		}
		if isNull {
			continue // NULL 向量不参与检索
		}
		vec, ok := vectorFromPayload(payload, def.Dim, scratch)
		if !ok {
			slog.Warn("向量抽取:槽位不可解析", "payload_len", len(payload),
				"want", def.Dim*4, "ord", def.VectorOrdinal)
			continue
		}
		d := vecidx.Distance(def.Metric, qv, vec)
		res.hits = append(res.hits, VecKeyHit{Key: append([]byte(nil), scanner.Key()...),
			Distance: d})
		if len(res.hits) >= limit*4 {
			// 阶段性收敛:避免未落盘行很多时把切片撑到 10 万级(候选最终只需要 limit 条)。
			sortHits(res.hits)
			res.hits = res.hits[:limit]
		}
	}
	sortHits(res.hits)
	if len(res.hits) > limit {
		res.hits = res.hits[:limit]
	}
	return res
}

// stripVersion 去掉完整 key 尾部的 8 字节版本段。
func stripVersion(fullKey []byte) []byte {
	if len(fullKey) <= VersionBytes {
		return fullKey
	}
	return fullKey[:len(fullKey)-VersionBytes]
}

func sortHits(hits []VecKeyHit) {
	// 距离升序,相同距离按用户键字节序(与 FE 的"主键升序"在投影上一致)。
	// 用标准库排序而不是插入排序:暴力退化时候选可达 10 万级(K180 的紧凑归并)。
	sort.SliceStable(hits, func(i, j int) bool {
		if hits[i].Distance != hits[j].Distance {
			return hits[i].Distance < hits[j].Distance
		}
		return bytes.Compare(hits[i].Key, hits[j].Key) < 0
	})
}

// loadVectorDefs 在启动时恢复建图定义(定义是 BE 侧的缓存,权威副本在 FE)。
func (e *Engine) loadVectorDefs() {
	e.vec.mu.Lock()
	defer e.vec.mu.Unlock()
	for _, d := range e.state.VecDefs {
		e.vec.defs[d.IndexID] = &VecDefinition{
			IndexID:        d.IndexID,
			TableID:        d.TableID,
			StartKey:       append([]byte(nil), d.StartKey...),
			EndKey:         append([]byte(nil), d.EndKey...),
			ColumnSlots:    append([]byte(nil), d.ColumnSlots...),
			RowFrame:       append([]byte(nil), d.RowFrame...),
			VectorOrdinal:  int(d.VectorOrdinal),
			Dim:            int(d.Dim),
			Metric:         d.Metric,
			M:              int(d.M),
			EfConstruction: int(d.EfConstruction),
		}
	}
}

// loadVectorGraphs 在启动时校验 MANIFEST 里的图段:文件缺失 / 截断 / 校验和不符 /
// 版本太高 / build_seq 不是当前 segment → 标记待重建(FR-747 / 边界 139),不加载。
func (e *Engine) loadVectorGraphs() {
	e.vec.mu.Lock()
	defer e.vec.mu.Unlock()
	dirty := false
	for i := range e.state.VecIndexes {
		entry := &e.state.VecIndexes[i]
		h, ok := e.handles[entry.Segment]
		if !ok {
			entry.State = manifest.VecStatePending
			dirty = true
			continue
		}
		if entry.BuildSeq != entry.Segment {
			slog.Warn("向量图 build_seq 与 segment 不一致,标记待重建",
				"file", entry.File, "build_seq", entry.BuildSeq, "segment", entry.Segment)
			entry.State = manifest.VecStatePending
			e.markPendingLocked(entry.IndexID, entry.Segment)
			dirty = true
			continue
		}
		graph, err := vecidx.ReadFile(filepath.Join(e.sstDir, entry.File))
		if err != nil {
			slog.Warn("向量图段校验失败,标记待重建", "file", entry.File, "error", err)
			entry.State = manifest.VecStatePending
			e.markPendingLocked(entry.IndexID, entry.Segment)
			dirty = true
			continue
		}
		if h.r.Footer().TotalRows < uint64(graph.NodeCount()) {
			slog.Warn("向量图节点数超过 segment 行数,标记待重建", "file", entry.File,
				"nodes", graph.NodeCount(), "segment_rows", h.r.Footer().TotalRows)
			entry.State = manifest.VecStatePending
			e.markPendingLocked(entry.IndexID, entry.Segment)
			dirty = true
			continue
		}
		entry.State = manifest.VecStateReady
		if e.vec.bySeg[entry.Segment] == nil {
			e.vec.bySeg[entry.Segment] = make(map[uint64]bool)
		}
		e.vec.bySeg[entry.Segment][entry.IndexID] = true
		if e.vec.ready[entry.IndexID] == nil {
			e.vec.ready[entry.IndexID] = make(map[uint64]*vecRuntime)
		}
		e.vec.ready[entry.IndexID][entry.Segment] = &vecRuntime{
			graph: graph,
			// keys/vectors 懒加载(边界 132:按需回读整列)
		}
	}
	if dirty {
		// 待重建的段由后台补建;这里只落状态,不阻塞启动
		for _, entry := range e.state.VecIndexes {
			if entry.State == manifest.VecStatePending {
				e.enqueueVecBuild(entry.IndexID, entry.Segment)
			}
		}
	}
}

// runtimeEmpty 判断运行时是否只加载了图(keys 未回读)。
func (rt *vecRuntime) needsColumns() bool {
	return rt != nil && rt.graph != nil && rt.keys == nil
}

// ---------------------------------------------------------------- flush / compaction 钩子

// onSegmentFlushed 在 segment 落盘并进 MANIFEST 后调用:为新段排队建图(FR-748)。
func (e *Engine) onSegmentFlushed(seg uint64) {
	e.vec.mu.Lock()
	jobs := make([]vecJob, 0, len(e.vec.defs))
	for indexID, def := range e.vec.defs {
		e.markPendingLocked(indexID, seg)
		jobs = append(jobs, vecJob{indexID: indexID, segment: seg})
		_ = def
	}
	e.vec.mu.Unlock()
	for _, job := range jobs {
		e.enqueueVecBuild(job.indexID, job.segment)
	}
}

// onCompactionDone 在 MANIFEST 切换后调用:旧图随旧 segment 删除,新段排队重建(FR-749)。
func (e *Engine) onCompactionDone(inputs []uint64, outputs []uint64) {
	e.vec.mu.Lock()
	for _, seg := range inputs {
		for indexID := range e.vec.bySeg[seg] {
			delete(e.vec.ready[indexID], seg)
			if e.vec.pending[indexID] != nil {
				delete(e.vec.pending[indexID], seg)
			}
			if e.vec.failed[indexID] != nil {
				delete(e.vec.failed[indexID], seg)
			}
		}
		delete(e.vec.bySeg, seg)
	}
	e.vec.mu.Unlock()

	e.mu.Lock()
	newState := *e.state
	newState.FormatVersion = manifest.FormatVersion
	kept := make([]manifest.VecIndexEntry, 0, len(e.state.VecIndexes))
	removed := make(map[uint64]bool, len(inputs))
	for _, seg := range inputs {
		removed[seg] = true
	}
	for _, entry := range e.state.VecIndexes {
		if removed[entry.Segment] {
			_ = os.Remove(filepath.Join(e.sstDir, entry.File))
			continue
		}
		kept = append(kept, entry)
	}
	newState.VecIndexes = kept
	if err := e.manifest.Save(&newState); err != nil {
		e.mu.Unlock()
		slog.Error("compaction vector cleanup: save manifest failed", "error", err)
		return
	}
	e.state = &newState
	e.mu.Unlock()

	for _, seg := range outputs {
		e.onSegmentFlushed(seg)
	}
}

// ---------------------------------------------------------------- 小工具

func cloneDef(def VecDefinition) *VecDefinition {
	out := def
	out.StartKey = append([]byte(nil), def.StartKey...)
	out.EndKey = append([]byte(nil), def.EndKey...)
	out.ColumnSlots = append([]byte(nil), def.ColumnSlots...)
	out.RowFrame = append([]byte(nil), def.RowFrame...)
	return &out
}

func replaceVecDef(defs []manifest.VecDefEntry, def *VecDefinition) []manifest.VecDefEntry {
	entry := manifest.VecDefEntry{
		IndexID:        def.IndexID,
		TableID:        def.TableID,
		StartKey:       def.StartKey,
		EndKey:         def.EndKey,
		ColumnSlots:    def.ColumnSlots,
		RowFrame:       def.RowFrame,
		VectorOrdinal:  uint32(def.VectorOrdinal),
		Dim:            uint32(def.Dim),
		Metric:         def.Metric,
		M:              uint32(def.M),
		EfConstruction: uint32(def.EfConstruction),
	}
	out := make([]manifest.VecDefEntry, 0, len(defs)+1)
	for _, d := range defs {
		if d.IndexID != def.IndexID {
			out = append(out, d)
		}
	}
	return append(out, entry)
}

func removeVecDef(defs []manifest.VecDefEntry, indexID uint64) []manifest.VecDefEntry {
	out := make([]manifest.VecDefEntry, 0, len(defs))
	for _, d := range defs {
		if d.IndexID != indexID {
			out = append(out, d)
		}
	}
	return out
}

func upsertVecEntry(entries []manifest.VecIndexEntry, entry manifest.VecIndexEntry) []manifest.VecIndexEntry {
	out := make([]manifest.VecIndexEntry, 0, len(entries)+1)
	for _, e := range entries {
		if e.IndexID == entry.IndexID && e.Segment == entry.Segment {
			continue
		}
		out = append(out, e)
	}
	return append(out, entry)
}
