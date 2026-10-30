package vecidx

import (
	"container/heap"
	"sort"
)

// Hit 是一条检索结果:图节点序号 + 距离。
type Hit struct {
	Node     uint32
	Distance float32
	Ordinal  uint64 // segment 内的行序号(图节点表里的 rowOrdinal)
}

// Search 从入口点贪心下降到第 0 层,再用 ef 宽度做束搜索,返回最近 k 条(按距离升序)。
//
//   - vec 按"图节点序号"取向量(向量不在图里,FR-744 / K177);
//   - accept 非 nil 时只在结果里保留被接受的行(路径 A 的允许集);遍历仍按图走,
//     否则允许集稀疏时图会断连(与 FR-765 的"允许集内展开"等价,过滤发生在收集端);
//   - 返回条数可能少于 k(候选不足),由调用方决定 ef 翻倍 / 回退暴力(FR-758 / FR-759)。
func (g *Graph) Search(query []float32, ef, k int,
	vec func(uint32) []float32, accept func(uint32) bool) []Hit {
	if g == nil || len(g.RowOrdinals) == 0 || len(query) != g.Dim {
		return nil
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
	ep := uint32(g.EntryPoint)
	epDist := Distance(g.Metric, query, vec(ep))
	for level := g.LevelCount - 1; level > 0; level-- {
		lv := g.Levels[level]
		pos := indexOfNode(lv.Nodes, ep)
		if pos < 0 {
			continue
		}
		improved := true
		for improved {
			improved = false
			for _, nb := range lv.Adj[pos] {
				d := Distance(g.Metric, query, vec(nb))
				if d < epDist {
					ep = nb
					epDist = d
					pos = indexOfNode(lv.Nodes, ep)
					improved = true
					if pos < 0 {
						improved = false
						break
					}
				}
			}
			if pos < 0 {
				break
			}
		}
	}
	base := g.Levels[0]
	start := indexOfNode(base.Nodes, ep)
	if start < 0 {
		return nil
	}
	visited := map[uint32]bool{ep: true}
	results := &maxHeap{}
	cands := &minHeap{}
	heap.Push(results, candidate{ep, epDist})
	heap.Push(cands, candidate{ep, epDist})
	for cands.Len() > 0 {
		c := heap.Pop(cands).(candidate)
		worst := results.top()
		if results.Len() >= ef && c.dist > worst {
			break
		}
		pos := indexOfNode(base.Nodes, c.node)
		if pos < 0 {
			continue
		}
		for _, nb := range base.Adj[pos] {
			if visited[nb] {
				continue
			}
			visited[nb] = true
			d := Distance(g.Metric, query, vec(nb))
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
	hits := make([]Hit, 0, len(out))
	for _, c := range out {
		if accept != nil && !accept(c.node) {
			continue
		}
		hits = append(hits, Hit{Node: c.node, Distance: c.dist,
			Ordinal: g.RowOrdinals[c.node]})
		if len(hits) >= k && accept == nil {
			// 无过滤时已经是从近到远的前 k 条;有过滤时继续扫完 ef 个候选。
			break
		}
	}
	sort.SliceStable(hits, func(i, j int) bool {
		if hits[i].Distance != hits[j].Distance {
			return hits[i].Distance < hits[j].Distance
		}
		return hits[i].Ordinal < hits[j].Ordinal
	})
	return hits
}

func indexOfNode(nodes []uint32, id uint32) int {
	lo, hi := 0, len(nodes)-1
	for lo <= hi {
		mid := (lo + hi) / 2
		switch {
		case nodes[mid] == id:
			return mid
		case nodes[mid] < id:
			lo = mid + 1
		default:
			hi = mid - 1
		}
	}
	// 节点表在构建时按升序写入;这里再兜底一次线性查找。
	for i, n := range nodes {
		if n == id {
			return i
		}
	}
	return -1
}
