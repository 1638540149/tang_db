// Package vecidx 实现"分段 HNSW 图段"的内存结构与文件编解码(SPEC-P7 B 组)。
//
// 边界(INV-16 / K161):本包只认"维度 + 度量操作码 + float32 向量 + 行序号",
// 不认识表名 / 列名 / 类型名 / SQL;图段文件写在 BE 的数据目录里(FE 不得触碰)。
//
// 文件布局(SPEC-P7 §5.2):
//
//	magic 8B "TDHNSW\0\0"
//	version u32 = 1
//	dim u32
//	metric u8  (0=L2 1=Cosine 2=InnerProduct)
//	levelCount u32
//	entryPoint u64 (图节点序号)
//	m u32
//	efConstruction u32
//	nodeCount u64
//	buildSeq u64 (构建时 segment 序号)
//	node table: nodeCount × rowOrdinal u64
//	layers: 每层 = [u64 节点数][u32 节点序号×n][每节点: u32 度数 + u32 邻居×度数]
//	checksum 8B CRC64(以上全部字节)
package vecidx

import (
	"errors"
	"fmt"
	"math"
)

// 度量操作码(与 FE 的 VectorValues.METRIC_* 一致)。
const (
	MetricL2      uint8 = 0
	MetricCosine  uint8 = 1
	MetricInnerIP uint8 = 2
)

// Version 是图段文件版本(未来布局变更时升版本,旧版本由加载方标记待重建)。
const Version uint32 = 1

// Magic 是图段文件魔数。
var Magic = [8]byte{'T', 'D', 'H', 'N', 'S', 'W', 0, 0}

// ErrCorrupt 表示图段文件损坏(截断 / 校验和不符 / 布局非法)。
var ErrCorrupt = errors.New("vecidx: corrupt graph file")

// Level 是一层的图:Nodes 是出现在该层的节点序号(升序),Adj 与之一一对应。
type Level struct {
	Nodes []uint32
	Adj   [][]uint32
}

// Graph 是一段 HNSW 图。向量数据不在图里(FR-744 / K177):行序号回到 segment 取向量。
type Graph struct {
	Dim            int
	Metric         uint8
	M              int
	EfConstruction int
	BuildSeq       uint64
	LevelCount     int
	EntryPoint     uint64
	RowOrdinals    []uint64
	Levels         []Level
}

// NodeCount 返回节点数。
func (g *Graph) NodeCount() int { return len(g.RowOrdinals) }

// Check 做基本自检(加载后与构建后都调用)。
func (g *Graph) Check() error {
	if g.Dim <= 0 {
		return fmt.Errorf("%w: dim=%d", ErrCorrupt, g.Dim)
	}
	if g.Metric > MetricInnerIP {
		return fmt.Errorf("%w: metric=%d", ErrCorrupt, g.Metric)
	}
	if g.LevelCount <= 0 || len(g.Levels) != g.LevelCount {
		return fmt.Errorf("%w: levelCount=%d levels=%d", ErrCorrupt, g.LevelCount, len(g.Levels))
	}
	if len(g.RowOrdinals) == 0 {
		return fmt.Errorf("%w: empty node table", ErrCorrupt)
	}
	if g.EntryPoint >= uint64(len(g.RowOrdinals)) {
		return fmt.Errorf("%w: entryPoint=%d nodeCount=%d", ErrCorrupt, g.EntryPoint,
			len(g.RowOrdinals))
	}
	for li, level := range g.Levels {
		if len(level.Nodes) != len(level.Adj) {
			return fmt.Errorf("%w: level %d nodes=%d adj=%d", ErrCorrupt, li,
				len(level.Nodes), len(level.Adj))
		}
		for i, id := range level.Nodes {
			if int(id) >= len(g.RowOrdinals) {
				return fmt.Errorf("%w: level %d node id %d out of range", ErrCorrupt, li, id)
			}
			for _, nb := range level.Adj[i] {
				if int(nb) >= len(g.RowOrdinals) {
					return fmt.Errorf("%w: level %d neighbor %d out of range", ErrCorrupt, li, nb)
				}
			}
		}
	}
	return nil
}

// Distance 计算两个向量的距离(与 FE 的 VectorValues.distance 同口径:越小越相似)。
func Distance(metric uint8, a, b []float32) float32 {
	switch metric {
	case MetricCosine:
		return cosineDistance(a, b)
	case MetricInnerIP:
		return -innerProduct(a, b)
	default:
		return l2(a, b)
	}
}

func l2(a, b []float32) float32 {
	var sum float64
	for i := range a {
		d := float64(a[i]) - float64(b[i])
		sum += d * d
	}
	return float32(math.Sqrt(sum))
}

func innerProduct(a, b []float32) float32 {
	var sum float64
	for i := range a {
		sum += float64(a[i]) * float64(b[i])
	}
	return float32(sum)
}

func cosineDistance(a, b []float32) float32 {
	var dot, na, nb float64
	for i := range a {
		dot += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	if na == 0 || nb == 0 {
		return 1
	}
	return float32(1 - dot/(math.Sqrt(na)*math.Sqrt(nb)))
}
