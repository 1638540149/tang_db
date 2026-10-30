package segment

import (
	"bytes"
	"fmt"
	"path/filepath"
	"testing"
)

// SPEC-P7.1 K180:chunk 解码缓存 —— 第二次点查同一 chunk 不再解码,结果逐字节一致。
func TestChunkCacheAvoidsRepeatedDecode(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cache.seg")
	b := NewBuilder(path)
	const rows = chunkRows + 64 // 两个 chunk,保证跨块
	for i := 0; i < rows; i++ {
		b.Add([]byte(fmt.Sprintf("key-%06d", i)), []byte(fmt.Sprintf("value-%06d", i)))
	}
	if _, err := b.Finish(); err != nil {
		t.Fatalf("finish: %v", err)
	}
	r, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer r.Close()

	get := func(key string) []byte {
		it := r.NewIterator()
		it.Seek([]byte(key))
		if !it.Valid() || !bytes.Equal(it.Key(), []byte(key)) {
			t.Fatalf("seek %s: not found", key)
		}
		v := it.Value()
		it.Seek([]byte(key)) // 同一个 reader 上重复定位(模拟第二次点查)
		if !bytes.Equal(v, it.Value()) {
			t.Fatalf("value mismatch on second seek")
		}
		return v
	}

	before := r.ChunksRead()
	v1 := get("key-000010")
	afterFirst := r.ChunksRead()
	if afterFirst <= before {
		t.Fatalf("first point read should decode chunks (before=%d after=%d)", before, afterFirst)
	}
	v2 := get("key-000010")
	afterSecond := r.ChunksRead()
	if afterSecond != afterFirst {
		t.Fatalf("second point read decoded again (chunksRead %d → %d):缓存没命中",
			afterFirst, afterSecond)
	}
	if !bytes.Equal(v1, v2) {
		t.Fatalf("cached value differs from decoded value")
	}
}

// 缓存淘汰:预算为 0 时不缓存,结果仍然正确(关掉缓存只是慢,不是错)。
func TestChunkCacheDisabledStillCorrect(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "nocache.seg")
	b := NewBuilder(path)
	for i := 0; i < 100; i++ {
		b.Add([]byte(fmt.Sprintf("k%03d", i)), []byte(fmt.Sprintf("v%03d", i)))
	}
	if _, err := b.Finish(); err != nil {
		t.Fatalf("finish: %v", err)
	}
	r, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer r.Close()

	v, ok, err := r.Get([]byte("k042"))
	if err != nil || !ok {
		t.Fatalf("get k042: ok=%v err=%v", ok, err)
	}
	if !bytes.Equal(v, []byte("v042")) {
		t.Fatalf("value = %q", v)
	}
}
