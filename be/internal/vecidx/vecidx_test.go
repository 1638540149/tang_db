package vecidx

import (
	"math/rand"
	"os"
	"path/filepath"
	"testing"
)

func randomVectors(n, dim int, seed int64) [][]float32 {
	r := rand.New(rand.NewSource(seed))
	out := make([][]float32, n)
	for i := range out {
		v := make([]float32, dim)
		for j := range v {
			v[j] = float32(r.Float64()*2 - 1)
		}
		out[i] = v
	}
	return out
}

func TestSaveLoadDistancesMatch(t *testing.T) {
	vectors := randomVectors(200, 8, 42)
	ordinals := make([]uint64, len(vectors))
	for i := range ordinals {
		ordinals[i] = uint64(i * 3) // 行序号不必连续
	}
	g, err := Build(vectors, ordinals, MetricL2, BuildConfig{M: 16, EfConstruction: 100, Seed: 7, BuildSeq: 42})
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	path := filepath.Join(t.TempDir(), "42.hnsw")
	size, err := WriteFile(path, g)
	if err != nil {
		t.Fatalf("write: %v", err)
	}
	if size <= 0 {
		t.Fatal("size should be positive")
	}
	back, err := ReadFile(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if back.NodeCount() != g.NodeCount() || back.Dim != g.Dim || back.Metric != g.Metric {
		t.Fatalf("metadata mismatch: %+v vs %+v", back, g)
	}
	lookup := func(id uint32) []float32 { return vectors[id] }
	query := vectors[5]
	a := g.Search(query, 32, 10, lookup, nil)
	b := back.Search(query, 32, 10, lookup, nil)
	if len(a) != len(b) || len(a) == 0 {
		t.Fatalf("search results differ in length: %d vs %d", len(a), len(b))
	}
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("hit %d differs: %+v vs %+v", i, a[i], b[i])
		}
	}
	if a[0].Node != 5 {
		t.Fatalf("nearest of the query vector should be itself, got node %d", a[0].Node)
	}
	if a[0].Ordinal != ordinals[5] {
		t.Fatalf("ordinal mapping wrong: got %d want %d", a[0].Ordinal, ordinals[5])
	}
}

func TestTruncatedFileIsDetected(t *testing.T) {
	vectors := randomVectors(50, 4, 1)
	ordinals := make([]uint64, len(vectors))
	for i := range ordinals {
		ordinals[i] = uint64(i)
	}
	g, err := Build(vectors, ordinals, MetricL2, BuildConfig{M: 8, EfConstruction: 50, Seed: 1})
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	path := filepath.Join(t.TempDir(), "1.hnsw")
	if _, err := WriteFile(path, g); err != nil {
		t.Fatalf("write: %v", err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, cut := range []int{1, 10, 100} {
		if cut >= len(raw) {
			continue
		}
		if _, err := Decode(raw[:len(raw)-cut]); err == nil {
			t.Fatalf("truncated by %d bytes should be rejected", cut)
		}
	}
	// 改一个字节 → 校验和不符
	bad := append([]byte(nil), raw...)
	bad[20] ^= 0xFF
	if _, err := Decode(bad); err == nil {
		t.Fatal("corrupted byte should be rejected by checksum")
	}
}

func TestBuildIsDeterministicAndRecallHigh(t *testing.T) {
	vectors := randomVectors(500, 16, 9)
	ordinals := make([]uint64, len(vectors))
	for i := range ordinals {
		ordinals[i] = uint64(i)
	}
	cfg := BuildConfig{M: 16, EfConstruction: 200, Seed: 12345, BuildSeq: 1}
	g1, err := Build(vectors, ordinals, MetricL2, cfg)
	if err != nil {
		t.Fatal(err)
	}
	g2, err := Build(vectors, ordinals, MetricL2, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if g1.LevelCount != g2.LevelCount || g1.EntryPoint != g2.EntryPoint {
		t.Fatal("same seed must produce the same graph shape")
	}
	lookup := func(id uint32) []float32 { return vectors[id] }
	recallHits := 0
	total := 20
	for q := 0; q < total; q++ {
		query := randomVectors(1, 16, int64(1000+q))[0]
		hits := g1.Search(query, 64, 10, lookup, nil)
		exact := exactTopK(vectors, query, 10)
		got := map[uint32]bool{}
		for _, h := range hits {
			got[h.Node] = true
		}
		for _, id := range exact {
			if got[id] {
				recallHits++
			}
		}
	}
	recall := float64(recallHits) / float64(total*10)
	if recall < 0.95 {
		t.Fatalf("recall@10 = %.3f, want >= 0.95", recall)
	}
}

func exactTopK(vectors [][]float32, query []float32, k int) []uint32 {
	type pair struct {
		id   uint32
		dist float32
	}
	all := make([]pair, len(vectors))
	for i := range vectors {
		all[i] = pair{uint32(i), Distance(MetricL2, query, vectors[i])}
	}
	for i := 1; i < len(all); i++ {
		for j := i; j > 0 && all[j].dist < all[j-1].dist; j-- {
			all[j], all[j-1] = all[j-1], all[j]
		}
	}
	out := make([]uint32, 0, k)
	for i := 0; i < k && i < len(all); i++ {
		out = append(out, all[i].id)
	}
	return out
}

func TestAcceptFiltersResults(t *testing.T) {
	vectors := randomVectors(100, 4, 3)
	ordinals := make([]uint64, len(vectors))
	for i := range ordinals {
		ordinals[i] = uint64(i)
	}
	g, err := Build(vectors, ordinals, MetricL2, BuildConfig{M: 8, EfConstruction: 60, Seed: 3})
	if err != nil {
		t.Fatal(err)
	}
	lookup := func(id uint32) []float32 { return vectors[id] }
	hits := g.Search(vectors[0], 50, 5, lookup, func(id uint32) bool { return id%2 == 0 })
	if len(hits) == 0 {
		t.Fatal("expected some accepted hits")
	}
	for _, h := range hits {
		if h.Node%2 != 0 {
			t.Fatalf("accept filter leaked node %d", h.Node)
		}
	}
}
