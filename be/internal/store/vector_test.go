package store

import (
	"encoding/binary"
	"os"
	"math"
	"testing"
	"time"

	"github.com/1638540149/tang_db/be/internal/manifest"
	"github.com/1638540149/tang_db/be/internal/vecidx"
)

// feRow 造一行 FE 行值格式(仅测试用):[0x01][tableId LE4][cols LE2][nullmap][id 8B][vec 4n]
func feRow(tableID uint32, id int64, vec []float32) []byte {
	out := make([]byte, 0, 21)
	out = append(out, 0x01)
	var tmp [8]byte
	binary.LittleEndian.PutUint32(tmp[:4], tableID)
	out = append(out, tmp[:4]...)
	binary.LittleEndian.PutUint16(tmp[:2], 2)
	out = append(out, tmp[:2]...)
	out = append(out, 0x00) // nullmap
	binary.LittleEndian.PutUint64(tmp[:], uint64(id))
	out = append(out, tmp[:]...)
	for _, v := range vec {
		binary.LittleEndian.PutUint32(tmp[:4], math.Float32bits(v))
		out = append(out, tmp[:4]...)
	}
	return out
}

// feSlots 造 column_slots:2 列(id BIGINT ordinal0, vec VECTOR(n) ordinal1)。
func feSlots(dim int) []byte {
	out := make([]byte, 0, 16)
	var tmp [8]byte
	binary.LittleEndian.PutUint16(tmp[:2], 2)
	out = append(out, tmp[:2]...)
	binary.LittleEndian.PutUint16(tmp[:2], 0)
	out = append(out, tmp[:2]...)
	out = append(out, 3, 8) // INT64 / 8
	binary.LittleEndian.PutUint16(tmp[:2], 1)
	out = append(out, tmp[:2]...)
	out = append(out, 6, 0) // VECTOR_FIXED / 宽度见宽槽段
	binary.LittleEndian.PutUint16(tmp[:2], 1)
	out = append(out, tmp[:2]...)
	binary.LittleEndian.PutUint16(tmp[:2], 1)
	out = append(out, tmp[:2]...)
	binary.LittleEndian.PutUint32(tmp[:4], uint32(4*dim))
	out = append(out, tmp[:4]...)
	return out
}

func TestVectorIndexLifecycleAndSearch(t *testing.T) {
	dir := t.TempDir()
	e, _, err := OpenWithOptions(dir, Options{MemtableSize: 1 << 20, CompactionL0Files: 100})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	const tableID = 7
	def := VecDefinition{
		IndexID:        1,
		TableID:        tableID,
		StartKey:       append(prefixOf(tableID, 0), 0, 0, 0, 0, 0, 0, 0, 0),
		EndKey:         prefixOf(tableID, 1),
		ColumnSlots:    feSlots(2),
		RowFrame:       []byte{1, 2, 0, 7, 0},
		VectorOrdinal:  1,
		Dim:            2,
		Metric:         vecidx.MetricL2,
		M:              8,
		EfConstruction: 32,
	}
	def.StartKey = prefixOf(tableID, 0)
	if err := e.RegisterVectorIndex(def); err != nil {
		t.Fatalf("register: %v", err)
	}
	writes := make([]Write, 0, 200)
	for i := 0; i < 200; i++ {
		key := append(prefixOf(tableID, 0), byte(i>>24), byte(i>>16), byte(i>>8), byte(i))
		writes = append(writes, Write{Key: key,
			Value: feRow(tableID, int64(i), []float32{float32(i), float32(i % 7)})})
	}
	if _, _, err := e.Commit(0, 0, writes); err != nil {
		t.Fatalf("commit: %v", err)
	}
	if err := e.ForceFlushAll(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	// 等后台建图
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		st := e.VectorIndexStatus(1)
		if st.PendingSegments == 0 && st.ReadySegments > 0 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	st := e.VectorIndexStatus(1)
	if st.ReadySegments == 0 || st.NodeCount != 200 {
		t.Fatalf("status = %+v, want 1 ready segment with 200 nodes", st)
	}
	// 检索:查询 [10,3] 应该最接近第 10 行
	query := make([]byte, 8)
	binary.LittleEndian.PutUint32(query[0:], math.Float32bits(10))
	binary.LittleEndian.PutUint32(query[4:], math.Float32bits(3))
	hits, examined, _, err := e.SearchVectorIndex(1, query, 32, 5, 0, false, nil, false)
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if len(hits) == 0 {
		t.Fatal("expected hits")
	}
	if examined == 0 {
		t.Fatal("candidates_examined should be > 0")
	}
	if hits[0].Distance > 0.01 {
		t.Fatalf("nearest hit distance = %f, want ~0 (key=%x)", hits[0].Distance, hits[0].Key)
	}
}

func prefixOf(tableID uint64, indexID uint32) []byte {
	out := make([]byte, 8)
	binary.BigEndian.PutUint32(out[0:4], uint32(tableID))
	binary.BigEndian.PutUint32(out[4:8], indexID)
	return out
}

func TestVectorIndexRebuildAfterTruncation(t *testing.T) {
	dir := t.TempDir()
	e, _, err := OpenWithOptions(dir, Options{MemtableSize: 1 << 20, CompactionL0Files: 100})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	const tableID = 9
	def := VecDefinition{IndexID: 3, TableID: tableID, StartKey: prefixOf(tableID, 0),
		EndKey: prefixOf(tableID, 1), ColumnSlots: feSlots(2), RowFrame: []byte{1, 2, 0, 7, 0},
		VectorOrdinal: 1, Dim: 2, Metric: vecidx.MetricL2, M: 8, EfConstruction: 32}
	if err := e.RegisterVectorIndex(def); err != nil {
		t.Fatal(err)
	}
	writes := make([]Write, 0, 50)
	for i := 0; i < 50; i++ {
		key := append(prefixOf(tableID, 0), 0, 0, 0, byte(i))
		writes = append(writes, Write{Key: key,
			Value: feRow(tableID, int64(i), []float32{float32(i), 1})})
	}
	if _, _, err := e.Commit(0, 0, writes); err != nil {
		t.Fatal(err)
	}
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}
	waitReady(t, e, 3)
	if err := e.Close(); err != nil {
		t.Fatal(err)
	}
	// 截断图文件 100 字节 → 重启后必须标记待重建并补建(SC-430)
	entries := loadManifestVecEntries(t, dir)
	if len(entries) == 0 {
		t.Fatal("expected a vec_indexes entry")
	}
	path := dir + "/sst/" + entries[0].File
	raw := readFile(t, path)
	if len(raw) <= 100 {
		t.Fatalf("graph file too small: %d", len(raw))
	}
	writeFile(t, path, raw[:len(raw)-100])

	e2, _, err := OpenWithOptions(dir, Options{MemtableSize: 1 << 20, CompactionL0Files: 100})
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer e2.Close()
	waitReady(t, e2, 3)
	st := e2.VectorIndexStatus(3)
	if st.PendingSegments != 0 || st.ReadySegments == 0 {
		t.Fatalf("after rebuild status = %+v, want pending=0 ready>0", st)
	}
}

func waitReady(t *testing.T, e *Engine, indexID uint64) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		st := e.VectorIndexStatus(indexID)
		if st.PendingSegments == 0 && st.ReadySegments > 0 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("vector index %d not ready: %+v", indexID, e.VectorIndexStatus(indexID))
}

func loadManifestVecEntries(t *testing.T, dir string) []manifest.VecIndexEntry {
	t.Helper()
	reg, m, err := manifest.Open(dir)
	if err != nil {
		t.Fatalf("manifest open: %v", err)
	}
	_ = reg
	return m.VecIndexes
}

func readFile(t *testing.T, path string) []byte {
	t.Helper()
	data, err := osReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return data
}

func writeFile(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := osWriteFile(path, data); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

// 小包装,避免测试文件为 os 再引一次。
func osReadFile(path string) ([]byte, error) { return os.ReadFile(path) }
func osWriteFile(path string, data []byte) error { return os.WriteFile(path, data, 0640) }
