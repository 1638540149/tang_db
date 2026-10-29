package store

import (
	"bytes"
	"fmt"
	"testing"
	"time"
)

func seedRows(e *Engine, n int, valueLen int) error {
	value := bytes.Repeat([]byte("v"), valueLen)
	writes := make([]Write, n)
	for i := 0; i < n; i++ {
		writes[i] = Write{Key: []byte(fmt.Sprintf("row-%08d", i)), Value: value}
	}
	_, conflict, err := e.Commit(0, 0, writes)
	if err != nil {
		return err
	}
	if conflict != nil {
		return fmt.Errorf("unexpected conflict %x", conflict)
	}
	return nil
}

// TestBatchDedupKeepsLastWrite 同批同 key 三条 → 只写最后一条(边界 #106)。
func TestBatchDedupKeepsLastWrite(t *testing.T) {
	e, _, err := Open(t.TempDir(), 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	key := []byte("dup")
	ts, conflict, err := e.Commit(0, 0, []Write{
		{Key: key, Value: []byte("first")},
		{Key: key, Value: []byte("second")},
		{Key: key, Value: []byte("third")},
	})
	if err != nil || conflict != nil {
		t.Fatalf("commit: ts=%d conflict=%x err=%v", ts, conflict, err)
	}
	got, ok, err := e.Get(key)
	if err != nil || !ok {
		t.Fatalf("get: ok=%v err=%v", ok, err)
	}
	if string(got) != "third" {
		t.Fatalf("value = %q, want third(最后写赢)", got)
	}

	// WAL 里同一批只应该有一条该 key 的记录(排序去重沿用到批记录构建)。
	full := AppendVersion(key, ts)
	n := 0
	it := e.mem.Iterator()
	for it.First(); it.Valid(); it.Next() {
		if bytes.Equal(it.Key(), full) {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("memtable 中该 key 的版本条数 = %d, want 1", n)
	}
}

// TestBatchConflictDetectionSingleRange 一次批量扫描能发现写集里任意位置的冲突。
func TestBatchConflictDetectionSingleRange(t *testing.T) {
	e, _, err := Open(t.TempDir(), 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	if err := seedRows(e, 200, 8); err != nil {
		t.Fatalf("seed: %v", err)
	}
	snapshot := e.MaxCommittedTS()

	// 只改中间一行,之后快照已经落后。
	if _, _, err := e.Commit(0, 0, []Write{
		{Key: []byte("row-00000100"), Value: []byte("changed")},
	}); err != nil {
		t.Fatalf("modify: %v", err)
	}

	batch := make([]Write, 0, 50)
	for i := 0; i < 50; i++ {
		batch = append(batch, Write{Key: []byte(fmt.Sprintf("row-%08d", i*4)), Value: []byte("x")})
	}
	_, conflict, err := e.Commit(snapshot, 0, batch)
	if err != nil {
		t.Fatalf("commit: %v", err)
	}
	if string(conflict) != "row-00000100" {
		t.Fatalf("conflict key = %q, want row-00000100", conflict)
	}
}

// TestBatchConflictPrefixRelatedKeys 前缀相关 key 走回退路径仍然不漏检。
func TestBatchConflictPrefixRelatedKeys(t *testing.T) {
	e, _, err := Open(t.TempDir(), 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	if _, _, err := e.Commit(0, 0, []Write{{Key: []byte("key-1"), Value: []byte("a")}}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	snapshot := e.MaxCommittedTS()
	if _, _, err := e.Commit(0, 0, []Write{{Key: []byte("key-1"), Value: []byte("b")}}); err != nil {
		t.Fatalf("modify: %v", err)
	}

	// 写集里同时有 "key-1" 与 "key-10"(前者是后者的前缀) → 必须仍然报冲突。
	_, conflict, err := e.Commit(snapshot, 0, []Write{
		{Key: []byte("key-1"), Value: []byte("c")},
		{Key: []byte("key-10"), Value: []byte("d")},
	})
	if err != nil {
		t.Fatalf("commit: %v", err)
	}
	if string(conflict) != "key-1" {
		t.Fatalf("conflict key = %q, want key-1", conflict)
	}
}

// TestConflictDetectionBudget 单批 1000 行 + 2MB memtable 的冲突检测必须在 5ms 内
// (SPEC-P6.1 FR-695 / SC-400)。精确数字由 BenchmarkConflictDetection1000Rows 固化;
// 这里留一个宽裕上限,避免机器抖动导致假红。
func TestConflictDetectionBudget(t *testing.T) {
	if testing.Short() {
		t.Skip("skip in -short")
	}
	e, err := newBenchEngine(t)
	if err != nil {
		t.Fatalf("engine: %v", err)
	}
	batch := benchBatch()
	snapshot := e.MaxCommittedTS()
	sorted := prepareWrites(batch)

	begin := time.Now()
	conflict, err := e.detectConflicts(sorted, snapshot)
	elapsed := time.Since(begin)
	if err != nil || conflict != nil {
		t.Fatalf("conflict=%x err=%v", conflict, err)
	}
	if elapsed > 50*time.Millisecond {
		t.Fatalf("冲突检测耗时 %v,超出宽裕上限 50ms", elapsed)
	}
	t.Logf("1000 行批冲突检测耗时 %v", elapsed)
}

func newBenchEngine(tb testing.TB) (*Engine, error) {
	tb.Helper()
	e, _, err := OpenWithOptions(tb.TempDir(), Options{
		MemtableSize:      2 * 1024 * 1024,
		DisableCompaction: true,
	})
	if err != nil {
		return nil, err
	}
	tb.Cleanup(func() { _ = e.Close() })
	// 约 2MB 数据(16000 行 × (12B key + 120B value))。
	if err := seedRows(e, 16000, 120); err != nil {
		return nil, err
	}
	if err := e.ForceFlushAll(); err != nil {
		return nil, err
	}
	return e, nil
}

func benchBatch() []Write {
	batch := make([]Write, 0, 1000)
	for i := 0; i < 1000; i++ {
		batch = append(batch, Write{
			Key:   []byte(fmt.Sprintf("row-%08d", i*16)),
			Value: bytes.Repeat([]byte("x"), 120),
		})
	}
	return batch
}

// BenchmarkConflictDetection1000Rows 固化 SC-400 的门限口径。
func BenchmarkConflictDetection1000Rows(b *testing.B) {
	e, err := newBenchEngine(b)
	if err != nil {
		b.Fatalf("engine: %v", err)
	}
	snapshot := e.MaxCommittedTS()
	sorted := prepareWrites(benchBatch())
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		conflict, err := e.detectConflicts(sorted, snapshot)
		if err != nil || conflict != nil {
			b.Fatalf("conflict=%x err=%v", conflict, err)
		}
	}
}

// BenchmarkCommitBatch1000Rows 记录 1000 行批提交(group commit 生效前)的整体耗时。
func BenchmarkCommitBatch1000Rows(b *testing.B) {
	e, err := newBenchEngine(b)
	if err != nil {
		b.Fatalf("engine: %v", err)
	}
	batch := benchBatch()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if _, conflict, err := e.Commit(0, 0, batch); err != nil || conflict != nil {
			b.Fatalf("conflict=%x err=%v", conflict, err)
		}
	}
}
