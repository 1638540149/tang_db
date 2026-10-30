package store

import (
	"fmt"
	"log/slog"
	"path/filepath"
	"runtime"
	"sync/atomic"

	"github.com/1638540149/tang_db/be/internal/vecidx"
)

// SPEC-P7.1 K178 / K179:向量运行时缓存(常驻 + 字节预算 LRU + 引用计数)。

// defaultVectorCacheBytes 是未配置时的预算(内存的 10%,K178 的推荐值)。
func defaultVectorCacheBytes() int64 {
	total := totalPhysicalMemory()
	if total <= 0 {
		return 256 << 20
	}
	budget := int64(total / 10)
	if budget < 64<<20 {
		budget = 64 << 20
	}
	return budget
}

// defaultVectorParallelism 是段级并行的默认上限(边界 162:收敛到 CPU 数,且不超过 8)。
func defaultVectorParallelism() int {
	n := runtime.NumCPU()
	if n > 8 {
		n = 8
	}
	if n < 1 {
		n = 1
	}
	return n
}

// SetVectorCacheBudget 调整列缓存的字节预算(启动时由配置注入;<=0 表示用默认值)。
func (e *Engine) SetVectorCacheBudget(bytes int64) {
	if bytes <= 0 {
		bytes = defaultVectorCacheBytes()
	}
	e.vec.mu.Lock()
	e.vec.budget = bytes
	e.evictVectorColumnsLocked()
	e.vec.mu.Unlock()
}

// SetVectorParallelism 设置段级并行上限(<=1 表示串行,回到 P7 行为)。
func (e *Engine) SetVectorParallelism(n int) {
	if n <= 0 {
		n = defaultVectorParallelism()
	}
	e.vec.mu.Lock()
	e.vec.parallelism = n
	e.vec.mu.Unlock()
}

// VectorCacheBytes 返回当前列缓存占用(诊断 / SHOW 用)。
func (e *Engine) VectorCacheBytes() int64 {
	e.vec.mu.Lock()
	defer e.vec.mu.Unlock()
	return e.vec.cacheBytes
}

// acquireRuntime 取到某段的运行时并加一次引用(refs++);
// 列部分未就绪时在 rt.mu 下单飞加载(并发 8 线程只解码一次)。
//
// 第二个返回值 cacheHit 表示"这次没有回读整列"(供打点区分固定开销)。
func (e *Engine) acquireRuntime(indexID, seg uint64, def *VecDefinition,
	h *segHandle) (*vecRuntime, bool) {
	e.vec.mu.Lock()
	rt := e.vec.ready[indexID][seg]
	if rt != nil {
		atomic.AddInt32(&rt.refs, 1)
		rt.lastUse.Store(timeNowNanos())
	}
	e.vec.mu.Unlock()
	if rt == nil {
		// 图还没进内存(启动时未加载 / 新段刚发布):冷路径读图 + 回读整列。
		return e.loadRuntimeCold(indexID, seg, def, h)
	}
	if rt.hasColumns() {
		return rt, true
	}
	// 整列回读:同一段只做一次,其余并发者在这里排队(单飞)。
	rt.mu.Lock()
	defer rt.mu.Unlock()
	if !rt.needsColumnsLocked() {
		return rt, true
	}
	keys, vectors, _, err := e.extractVectors(h, def)
	if err != nil || len(keys) != rt.graph.NodeCount() {
		graphNodes := 0
		if rt.graph != nil {
			graphNodes = rt.graph.NodeCount()
		}
		slog.Warn("向量图与 segment 不一致,该段退化为暴力", "index", indexID,
			"segment", seg, "graph_nodes", graphNodes, "seg_rows", len(keys))
		e.releaseRuntime(indexID, seg)
		return nil, false
	}
	rt.keys = keys
	rt.vectors = vectors
	rt.bytes = runtimeColumnsBytes(keys, vectors, def.Dim)
	e.vec.mu.Lock()
	e.vec.cacheBytes += rt.bytes
	e.evictVectorColumnsLocked()
	e.vec.mu.Unlock()
	return rt, false
}

// loadRuntimeCold 读图文件并回读整列(图缺失返回 nil,调用方退化暴力)。
func (e *Engine) loadRuntimeCold(indexID, seg uint64, def *VecDefinition,
	h *segHandle) (*vecRuntime, bool) {
	file := fmt.Sprintf("%s-%d.hnsw", vecFileName(seg), indexID)
	path := filepath.Join(e.sstDir, file)
	graph, err := vecidx.ReadFile(path)
	if err != nil {
		slog.Warn("向量图段不可用,该段退化为暴力", "index", indexID, "segment", seg, "error", err)
		return nil, false
	}
	keys, vectors, _, err := e.extractVectors(h, def)
	if err != nil || len(keys) != graph.NodeCount() {
		slog.Warn("向量图与 segment 不一致,该段退化为暴力", "index", indexID, "segment", seg,
			"graph_nodes", graph.NodeCount(), "seg_rows", len(keys))
		return nil, false
	}
	rt := &vecRuntime{graph: graph, keys: keys, vectors: vectors,
		bytes: runtimeColumnsBytes(keys, vectors, def.Dim)}
	e.vec.mu.Lock()
	if e.vec.ready[indexID] == nil {
		e.vec.ready[indexID] = make(map[uint64]*vecRuntime)
	}
	e.vec.ready[indexID][seg] = rt
	atomic.StoreInt32(&rt.refs, 1)
	rt.lastUse.Store(timeNowNanos())
	e.vec.cacheBytes += rt.bytes
	e.evictVectorColumnsLocked()
	e.vec.mu.Unlock()
	return rt, false
}

// releaseRuntime 释放一次引用;refs 归零后允许被 LRU 淘汰(边界 156)。
func (e *Engine) releaseRuntime(indexID, seg uint64) {
	e.vec.mu.Lock()
	if rt := e.vec.ready[indexID][seg]; rt != nil {
		if atomic.AddInt32(&rt.refs, -1) < 0 {
			atomic.StoreInt32(&rt.refs, 0)
		}
		rt.lastUse.Store(timeNowNanos())
	}
	e.vec.mu.Unlock()
}

// evictVectorColumnsLocked 按 LRU 把超出预算的列部分释放掉(调用方持有 e.vec.mu)。
//
// 只动 refs == 0 的运行时 —— 正在检索的段不会被淘汰(K179 / 边界 156)。
// 图本身常驻(启动时就已加载),淘汰的只是向量与行键。
func (e *Engine) evictVectorColumnsLocked() {
	if e.vec.budget <= 0 {
		return
	}
	for e.vec.cacheBytes > e.vec.budget {
		var victim *vecRuntime
		var victimSeg, victimIdx uint64
		for idx, segs := range e.vec.ready {
			for seg, rt := range segs {
				if rt == nil || atomic.LoadInt32(&rt.refs) > 0 {
					continue
				}
				if rt.keys == nil || rt.bytes <= 0 {
					continue
				}
				if victim == nil || rt.lastUse.Load() < victim.lastUse.Load() {
					victim, victimSeg, victimIdx = rt, seg, idx
				}
			}
		}
		if victim == nil {
			// 全部在被引用:保持超预算也不淘汰(等下一次释放后再收敛)。
			return
		}
		victim.mu.Lock()
		e.vec.cacheBytes -= victim.bytes
		victim.keys = nil
		victim.vectors = nil
		victim.bytes = 0
		victim.mu.Unlock()
		slog.Info("向量运行时缓存淘汰(按 LRU)", "index", victimIdx, "segment", victimSeg,
			"cache_bytes", e.vec.cacheBytes, "budget", e.vec.budget)
	}
}

// runtimeColumnsBytes 统计一份运行时的列部分字节数(向量 + 行键 + 切片头)。
func runtimeColumnsBytes(keys [][]byte, vectors [][]float32, dim int) int64 {
	const sliceHeader = 24
	total := int64(len(keys))*sliceHeader + int64(len(vectors))*sliceHeader
	for _, k := range keys {
		total += int64(len(k))
	}
	total += int64(len(vectors)) * int64(dim*4)
	return total
}
