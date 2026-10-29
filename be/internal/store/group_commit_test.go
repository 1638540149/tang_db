package store

import (
	"bytes"
	"fmt"
	"sync"
	"testing"
	"time"
)

// TestGroupCommitMergesFsync 并发提交必须被合并成更少的 fsync(FR-698 / FR-699),
// 同时每一条返回 OK 的数据都能读到。
func TestGroupCommitMergesFsync(t *testing.T) {
	e, _, err := OpenWithOptions(t.TempDir(), Options{
		MemtableSize:        64 * 1024 * 1024,
		GroupCommitMaxBatch: 64,
		GroupCommitMaxWait:  5,
	})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	const n = 32
	start := make(chan struct{})
	var wg sync.WaitGroup
	errs := make(chan error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			_, conflict, err := e.Commit(0, 0, []Write{{
				Key:   []byte(fmt.Sprintf("k-%03d", i)),
				Value: []byte("v"),
			}})
			if err != nil {
				errs <- err
				return
			}
			if conflict != nil {
				errs <- fmt.Errorf("unexpected conflict on %d", i)
			}
		}(i)
	}
	close(start)
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatalf("concurrent commit: %v", err)
	}

	e.walMu.Lock()
	syncs := e.wal.SyncCount()
	e.walMu.Unlock()
	t.Logf("%d 条并发提交 → %d 次 fsync", n, syncs)
	if syncs == 0 {
		t.Fatal("没有发生 fsync")
	}
	if syncs >= n {
		t.Fatalf("group commit 未生效:%d 条提交用了 %d 次 fsync", n, syncs)
	}

	for i := 0; i < n; i++ {
		v, ok, err := e.Get([]byte(fmt.Sprintf("k-%03d", i)))
		if err != nil || !ok || string(v) != "v" {
			t.Fatalf("get %d: %q ok=%v err=%v", i, v, ok, err)
		}
	}
}

// TestGroupCommitDurability 返回 OK 之后重启,数据必须还在(FR-728)。
func TestGroupCommitDurability(t *testing.T) {
	dir := t.TempDir()
	e, _, err := OpenWithOptions(dir, Options{
		MemtableSize:        64 * 1024 * 1024,
		GroupCommitMaxBatch: 8,
		GroupCommitMaxWait:  1,
	})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < 50; i++ {
		if _, _, err := e.Commit(0, 0, []Write{{
			Key:   []byte(fmt.Sprintf("durable-%03d", i)),
			Value: []byte("v"),
		}}); err != nil {
			t.Fatalf("commit %d: %v", i, err)
		}
	}
	if err := e.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	e2, stats, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer e2.Close()
	if stats.WALReplayed < 50 {
		t.Fatalf("wal replayed = %d, want >= 50", stats.WALReplayed)
	}
	for i := 0; i < 50; i++ {
		v, ok, err := e2.Get([]byte(fmt.Sprintf("durable-%03d", i)))
		if err != nil || !ok || !bytes.Equal(v, []byte("v")) {
			t.Fatalf("恢复后 get %d 失败: %q ok=%v err=%v", i, v, ok, err)
		}
	}
}

// TestGroupCommitConflictNotMissed 同一快照并发改同一行:只能有一个成功(FR-729)。
func TestGroupCommitConflictNotMissed(t *testing.T) {
	e, _, err := Open(t.TempDir(), 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	if _, _, err := e.Commit(0, 0, []Write{{Key: []byte("row"), Value: []byte("seed")}}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	snapshot := e.MaxCommittedTS()

	const n = 16
	start := make(chan struct{})
	var wg sync.WaitGroup
	var mu sync.Mutex
	okCount, conflictCount, errCount := 0, 0, 0
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			_, conflict, err := e.Commit(snapshot, 0, []Write{{
				Key:   []byte("row"),
				Value: []byte(fmt.Sprintf("v-%d", i)),
			}})
			mu.Lock()
			defer mu.Unlock()
			switch {
			case err != nil:
				errCount++
			case conflict != nil:
				conflictCount++
			default:
				okCount++
			}
		}(i)
	}
	close(start)
	wg.Wait()

	if errCount != 0 {
		t.Fatalf("意外错误 %d 个", errCount)
	}
	if okCount != 1 {
		t.Fatalf("成功者 = %d, want 1(静默覆盖或误报冲突)", okCount)
	}
	if conflictCount != n-1 {
		t.Fatalf("冲突数 = %d, want %d", conflictCount, n-1)
	}
}

// TestVisibleWatermarkOnlyAdvancesAfterApply 读水位不得越过"已 fsync 未应用"的提交。
func TestVisibleWatermarkOnlyAdvancesAfterApply(t *testing.T) {
	e, _, err := Open(t.TempDir(), 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	if _, _, err := e.Commit(0, 0, []Write{{Key: []byte("a"), Value: []byte("1")}}); err != nil {
		t.Fatalf("commit: %v", err)
	}
	if got, want := e.MaxCommittedTS(), e.lastCommitTS.Load(); got != want {
		t.Fatalf("提交返回后可见水位 = %d, 已取号水位 = %d(必须相等)", got, want)
	}
}

// TestGroupCommitBatchLimit group_commit_max_batch 限制单次覆盖的提交数。
func TestGroupCommitBatchLimit(t *testing.T) {
	e, _, err := OpenWithOptions(t.TempDir(), Options{
		MemtableSize:        64 * 1024 * 1024,
		GroupCommitMaxBatch: 4,
		GroupCommitMaxWait:  0,
	})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	const n = 20
	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			if _, _, err := e.Commit(0, 0, []Write{{
				Key:   []byte(fmt.Sprintf("b-%03d", i)),
				Value: []byte("v"),
			}}); err != nil {
				t.Errorf("commit %d: %v", i, err)
			}
		}(i)
	}
	close(start)
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(30 * time.Second):
		t.Fatal("批量上限下提交卡住")
	}
	for i := 0; i < n; i++ {
		if _, ok, err := e.Get([]byte(fmt.Sprintf("b-%03d", i))); err != nil || !ok {
			t.Fatalf("get %d: ok=%v err=%v", i, ok, err)
		}
	}
}
