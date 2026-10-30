package vecidx

import (
	"container/heap"
	"math"
)

// BuildConfig 是建图参数(SPEC-P7 K159: m=16 / ef_construction=200)。
type BuildConfig struct {
	M              int
	EfConstruction int
	// Seed 固定 → 同一份数据重建得到同一张图(层高掷骰子可复现,§5.2)。
	Seed uint64
	// BuildSeq 是构建时 segment 的序号(与 MANIFEST 对齐,FR-745)。
	BuildSeq uint64
}

func (c BuildConfig) normalized() BuildConfig {
	if c.M < 4 {
		c.M = 16
	}
	if c.M > 64 {
		c.M = 64
	}
	if c.EfConstruction < 16 {
		c.EfConstruction = 200
	}
	return c
}

// Build 用标准 HNSW 策略建图:节点按顺序插入,层高由固定种子的伪随机数决定。
//
// vectors 与 ordinals 一一对应:第 i 个向量是 segment 里的第 ordinals[i] 行。
func Build(vectors [][]float32, ordinals []uint64, metric uint8, cfg BuildConfig) (*Graph, error) {
	cfg = cfg.normalized()
	n := len(vectors)
	if n == 0 {
		return nil, ErrCorrupt
	}
	dim := len(vectors[0])
	for i, v := range vectors {
		if len(v) != dim {
			return nil, errDim(i, dim, len(v))
		}
	}
	// 层高:floor(-ln(u) * 1/ln(M)),u ∈ (0,1)
	levels := make([]int, n)
	rng := splitmix64(cfg.Seed)
	ml := 1.0 / math.Log(float64(cfg.M))
	maxLevel := 0
	for i := 0; i < n; i++ {
		u := rng.nextFloat()
		if u <= 0 {
			u = 1e-12
		}
		lv := int(-math.Log(u) * ml)
		if lv < 0 {
			lv = 0
		}
		if lv > 32 {
			lv = 32
		}
		levels[i] = lv
		if lv > maxLevel {
			maxLevel = lv
		}
	}
	adj := make([][][]uint32, maxLevel+1)
	for l := range adj {
		adj[l] = make([][]uint32, n)
	}
	entryPoint := uint32(0)
	top := levels[0]
	for l := 0; l <= top; l++ {
		adj[l][0] = nil
	}
	for i := 1; i < n; i++ {
		insert(adj, levels, vectors, metric, cfg, i, entryPoint, top)
		if levels[i] > top {
			top = levels[i]
			entryPoint = uint32(i)
		}
	}
	// 组装层结构
	g := &Graph{
		Dim:            dim,
		Metric:         metric,
		M:              cfg.M,
		EfConstruction: cfg.EfConstruction,
		BuildSeq:       cfg.BuildSeq,
		LevelCount:     top + 1,
		EntryPoint:     uint64(entryPoint),
		RowOrdinals:    append([]uint64(nil), ordinals...),
		Levels:         make([]Level, top+1),
	}
	for l := 0; l <= top; l++ {
		level := Level{}
		for node := 0; node < n; node++ {
			if levels[node] < l {
				continue
			}
			level.Nodes = append(level.Nodes, uint32(node))
			level.Adj = append(level.Adj, append([]uint32(nil), adj[l][node]...))
		}
		g.Levels[l] = level
	}
	if err := g.Check(); err != nil {
		return nil, err
	}
	return g, nil
}

type dimError struct {
	node, want, got int
}

func (e dimError) Error() string {
	return "vecidx: vector " + itoa(e.node) + " has dim " + itoa(e.got) + ", want " + itoa(e.want)
}

func errDim(node, want, got int) error { return dimError{node, want, got} }

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

// insert 把节点 i 插入到第 0 层与它自己的最高层之间。
func insert(adj [][][]uint32, levels []int, vectors [][]float32, metric uint8, cfg BuildConfig,
	i int, entryPoint uint32, top int) {
	vec := vectors[i]
	ep := entryPoint
	// 从最高层贪心下降,直到 i 的层高
	for l := top; l > levels[i]; l-- {
		ep = greedyAtLevel(adj[l], vectors, metric, vec, ep)
	}
	for l := minInt(top, levels[i]); l >= 0; l-- {
		candidates := searchLevelBuild(adj[l], vectors, metric, vec, ep, cfg.EfConstruction)
		selected := pickNearest(candidates, cfg.M)
		adj[l][i] = append(adj[l][i], selected...)
		for _, nb := range selected {
			adj[l][nb] = prune(append(adj[l][nb], uint32(i)), vectors, metric, maxDegree(l, cfg.M))
		}
		if len(candidates) > 0 {
			ep = candidates[0].node // 下一层的入口:本层最近的候选
		}
	}
}

func maxDegree(level, m int) int {
	if level == 0 {
		return 2 * m
	}
	return m
}

// candidate 是搜索中的(节点, 距离)。
type candidate struct {
	node uint32
	dist float32
}

// greedyAtLevel 在某一层做 ef=1 的贪心搜索。
func greedyAtLevel(adj [][]uint32, vectors [][]float32, metric uint8, vec []float32,
	ep uint32) uint32 {
	best := ep
	bestDist := Distance(metric, vec, vectors[best])
	for {
		improved := false
		for _, nb := range adj[best] {
			d := Distance(metric, vec, vectors[nb])
			if d < bestDist {
				best = nb
				bestDist = d
				improved = true
			}
		}
		if !improved {
			return best
		}
	}
}

// searchLevelBuild 在某一层做 ef 宽度的束搜索(建图用)。
func searchLevelBuild(adj [][]uint32, vectors [][]float32, metric uint8, vec []float32,
	ep uint32, ef int) []candidate {
	visited := map[uint32]bool{ep: true}
	results := &maxHeap{}
	cands := &minHeap{}
	d0 := Distance(metric, vec, vectors[ep])
	heap.Push(results, candidate{ep, d0})
	heap.Push(cands, candidate{ep, d0})
	for cands.Len() > 0 {
		c := heap.Pop(cands).(candidate)
		worst := results.top()
		if results.Len() >= ef && c.dist > worst {
			break
		}
		for _, nb := range adj[c.node] {
			if visited[nb] {
				continue
			}
			visited[nb] = true
			d := Distance(metric, vec, vectors[nb])
			worst = results.top()
			if results.Len() < ef || d < worst {
				heap.Push(results, candidate{nb, d})
				heap.Push(cands, candidate{nb, d})
				for results.Len() > ef {
					heap.Pop(results)
				}
			}
		}
	}
	out := make([]candidate, results.Len())
	for i := len(out) - 1; i >= 0; i-- {
		out[i] = heap.Pop(results).(candidate)
	}
	return out
}

// pickNearest 取最近的 m 个(建图的简单启发式,不做多样性裁剪)。
func pickNearest(cands []candidate, m int) []uint32 {
	if len(cands) > m {
		cands = cands[:m]
	}
	out := make([]uint32, len(cands))
	for i, c := range cands {
		out[i] = c.node
	}
	return out
}

// prune 把邻接表裁剪到 maxDeg(保留最近的)。
func prune(list []uint32, vectors [][]float32, metric uint8, maxDeg int) []uint32 {
	if len(list) <= maxDeg {
		return list
	}
	base := vectors[list[0]]
	cands := make([]candidate, 0, len(list))
	for _, nb := range list {
		cands = append(cands, candidate{nb, Distance(metric, base, vectors[nb])})
	}
	sortCandidates(cands)
	out := make([]uint32, 0, maxDeg)
	seen := map[uint32]bool{}
	for _, c := range cands {
		if seen[c.node] {
			continue
		}
		seen[c.node] = true
		out = append(out, c.node)
		if len(out) >= maxDeg {
			break
		}
	}
	return out
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// ---- 固定种子伪随机(仅用于层高,保证同一份数据重建结果一致) ----

type sm64 struct{ state uint64 }

func splitmix64(seed uint64) *sm64 { return &sm64{state: seed} }

func (s *sm64) next() uint64 {
	s.state += 0x9E3779B97F4A7C15
	z := s.state
	z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9
	z = (z ^ (z >> 27)) * 0x94D049BB133111EB
	return z ^ (z >> 31)
}

// nextFloat 返回 (0,1] 的浮点数。
func (s *sm64) nextFloat() float64 {
	v := s.next() >> 11
	if v == 0 {
		return 1.0 / float64(uint64(1)<<53)
	}
	return float64(v) / float64(uint64(1)<<53)
}

// ---- 堆 ----

type minHeap []candidate

func (h minHeap) Len() int            { return len(h) }
func (h minHeap) Less(i, j int) bool  { return h[i].dist < h[j].dist }
func (h minHeap) Swap(i, j int)       { h[i], h[j] = h[j], h[i] }
func (h *minHeap) Push(x interface{}) { *h = append(*h, x.(candidate)) }
func (h *minHeap) Pop() interface{} {
	old := *h
	n := len(old)
	item := old[n-1]
	*h = old[:n-1]
	return item
}

type maxHeap []candidate

func (h maxHeap) Len() int            { return len(h) }
func (h maxHeap) Less(i, j int) bool  { return h[i].dist > h[j].dist }
func (h maxHeap) Swap(i, j int)       { h[i], h[j] = h[j], h[i] }
func (h *maxHeap) Push(x interface{}) { *h = append(*h, x.(candidate)) }
func (h *maxHeap) Pop() interface{} {
	old := *h
	n := len(old)
	item := old[n-1]
	*h = old[:n-1]
	return item
}

func (h maxHeap) top() float32 {
	if len(h) == 0 {
		return math.MaxFloat32
	}
	return h[0].dist
}

// sortCandidates 按 (距离, 节点号) 升序,保证结果可复现。
func sortCandidates(cands []candidate) {
	for i := 1; i < len(cands); i++ {
		for j := i; j > 0; j-- {
			if cands[j].dist < cands[j-1].dist ||
				(cands[j].dist == cands[j-1].dist && cands[j].node < cands[j-1].node) {
				cands[j], cands[j-1] = cands[j-1], cands[j]
				continue
			}
			break
		}
	}
}
