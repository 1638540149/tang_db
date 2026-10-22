package store

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestPutGet(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	if err := e.Put([]byte("name"), []byte("wangyu")); err != nil {
		t.Fatalf("put: %v", err)
	}
	got, ok, err := e.Get([]byte("name"))
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if !ok || !bytes.Equal(got, []byte("wangyu")) {
		t.Fatalf("get mismatch: %v %v", got, ok)
	}
}

func TestEmptyKey(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()
	if err := e.Put([]byte{}, []byte("v")); err == nil {
		t.Fatal("expected error for empty key")
	}
}

func TestRecovery(t *testing.T) {
	dir := t.TempDir()
	e1, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		value := []byte(fmt.Sprintf("value-%d", i))
		if err := e1.Put(key, value); err != nil {
			t.Fatalf("put %d: %v", i, err)
		}
	}
	if err := e1.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	e2, stats, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer e2.Close()
	if stats.WALReplayed != 100 {
		t.Fatalf("wal replayed %d, want 100", stats.WALReplayed)
	}

	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		expected := []byte(fmt.Sprintf("value-%d", i))
		got, ok, err := e2.Get(key)
		if err != nil {
			t.Fatalf("get %d: %v", i, err)
		}
		if !ok || !bytes.Equal(got, expected) {
			t.Fatalf("get %d: %v %v, want %v", i, got, ok, expected)
		}
	}
}

func TestRecoveryWithTruncatedWAL(t *testing.T) {
	dir := t.TempDir()
	e1, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < 10; i++ {
		key := []byte(fmt.Sprintf("key-%d", i))
		value := []byte(fmt.Sprintf("value-%d", i))
		if err := e1.Put(key, value); err != nil {
			t.Fatalf("put: %v", err)
		}
	}
	if err := e1.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	// 截断 WAL 尾部。
	walPath := filepath.Join(dir, "wal", WalName(1))
	info, _ := os.Stat(walPath)
	f, _ := os.OpenFile(walPath, os.O_RDWR, 0)
	f.Truncate(info.Size() - 3)
	f.Close()

	e2, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("reopen after truncate: %v", err)
	}
	defer e2.Close()

	// 至少前几条能恢复。
	found := 0
	for i := 0; i < 10; i++ {
		key := []byte(fmt.Sprintf("key-%d", i))
		if _, ok, _ := e2.Get(key); ok {
			found++
		}
	}
	if found == 0 {
		t.Fatal("expected some records after truncated replay")
	}
}

func TestFlushAndReadFromSegment(t *testing.T) {
	dir := t.TempDir()
	// 阈值设得很小,触发刷盘。
	e, _, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		value := []byte(fmt.Sprintf("value-%d", i))
		if err := e.Put(key, value); err != nil {
			t.Fatalf("put %d: %v", i, err)
		}
	}
	if err := e.ForceFlush(); err != nil {
		t.Fatalf("force flush: %v", err)
	}

	// 验证 segment 文件已生成。
	segDir := filepath.Join(dir, "sst")
	entries, err := os.ReadDir(segDir)
	if err != nil {
		t.Fatalf("read sst dir: %v", err)
	}
	if len(entries) == 0 {
		t.Fatal("expected segment file after flush")
	}

	// 从 segment 读回。
	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		expected := []byte(fmt.Sprintf("value-%d", i))
		got, ok, err := e.Get(key)
		if err != nil {
			t.Fatalf("get %d: %v", i, err)
		}
		if !ok || !bytes.Equal(got, expected) {
			t.Fatalf("get %d: %v %v, want %v", i, got, ok, expected)
		}
	}
}

func TestManifestAndCurrent(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < 10; i++ {
		if err := e.Put([]byte(fmt.Sprintf("k%d", i)), []byte("v")); err != nil {
			t.Fatalf("put: %v", err)
		}
	}
	if err := e.ForceFlush(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	if err := e.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	currentPath := filepath.Join(dir, "CURRENT")
	data, err := os.ReadFile(currentPath)
	if err != nil {
		t.Fatalf("read CURRENT: %v", err)
	}
	if len(data) == 0 {
		t.Fatal("CURRENT is empty")
	}
	manifestPath := filepath.Join(dir, string(data[:len(data)-1]))
	if _, err := os.Stat(manifestPath); err != nil {
		t.Fatalf("manifest missing: %v", err)
	}
}

func TestScanAcrossMemoryAndSegments(t *testing.T) {
	dir := t.TempDir()
	// 1KB 阈值,保证前 100 条会被刷进 segment。
	e, _, err := Open(dir, 1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("k%04d", i))
		if err := e.Put(key, []byte("old")); err != nil {
			t.Fatalf("put old %d: %v", i, err)
		}
	}
	if err := e.ForceFlush(); err != nil {
		t.Fatalf("force flush: %v", err)
	}

	// 覆盖一部分(新值在 memtable),再写一批只在内存里的 key。
	for i := 0; i < 50; i++ {
		if err := e.Put([]byte(fmt.Sprintf("k%04d", i)), []byte("new")); err != nil {
			t.Fatalf("put new %d: %v", i, err)
		}
	}
	for i := 100; i < 150; i++ {
		if err := e.Put([]byte(fmt.Sprintf("k%04d", i)), []byte("mem")); err != nil {
			t.Fatalf("put mem %d: %v", i, err)
		}
	}

	sc := e.NewScanner([]byte("k0040"), []byte("k0110"), 0)
	var got []string
	for sc.Next() {
		got = append(got, string(sc.Key())+"="+string(sc.Value()))
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("scan: %v", err)
	}
	if len(got) != 70 {
		t.Fatalf("rows = %d, want 70", len(got))
	}
	if got[0] != "k0040=new" {
		t.Fatalf("first = %s, want k0040=new", got[0])
	}
	if got[9] != "k0049=new" || got[10] != "k0050=old" {
		t.Fatalf("memtable/segment 边界取值错误: %v", got[8:12])
	}
	if got[len(got)-1] != "k0109=mem" {
		t.Fatalf("last = %s", got[len(got)-1])
	}

	// 空范围
	sc = e.NewScanner([]byte("z"), []byte("zz"), 0)
	if sc.Next() {
		t.Fatal("空范围不应有结果")
	}
}
