package segment

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestWriteAndRead(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "000001.seg")

	b := NewBuilder(path)
	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		value := []byte(fmt.Sprintf("value-%d", i))
		b.Add(key, value)
	}
	footer, err := b.Finish()
	if err != nil {
		t.Fatalf("finish: %v", err)
	}
	if footer.TotalRows != 100 {
		t.Fatalf("rows %d, want 100", footer.TotalRows)
	}

	r, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer r.Close()

	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		expected := []byte(fmt.Sprintf("value-%d", i))
		got, ok, err := r.Get(key)
		if err != nil {
			t.Fatalf("get %s: %v", key, err)
		}
		if !ok {
			t.Fatalf("key %s not found", key)
		}
		if !bytes.Equal(got, expected) {
			t.Fatalf("key %s: got %v, want %v", key, got, expected)
		}
	}

	if _, ok, _ := r.Get([]byte("not-exist")); ok {
		t.Fatal("unexpected found not-exist")
	}
}

func TestMultiChunk(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "000002.seg")

	b := NewBuilder(path)
	const rows = chunkRows*3 + 100
	for i := 0; i < rows; i++ {
		key := []byte(fmt.Sprintf("key-%08d", i))
		value := []byte(fmt.Sprintf("value-%d", i))
		b.Add(key, value)
	}
	if _, err := b.Finish(); err != nil {
		t.Fatalf("finish: %v", err)
	}

	r, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer r.Close()

	// 抽查首尾与中间。
	for _, idx := range []int{0, chunkRows - 1, chunkRows, rows - 1, rows / 2} {
		key := []byte(fmt.Sprintf("key-%08d", idx))
		expected := []byte(fmt.Sprintf("value-%d", idx))
		got, ok, err := r.Get(key)
		if err != nil {
			t.Fatalf("get %d: %v", idx, err)
		}
		if !ok || !bytes.Equal(got, expected) {
			t.Fatalf("get %d: got %v %v, want %v", idx, got, ok, expected)
		}
	}
}

func TestFooterChecksum(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "000003.seg")

	b := NewBuilder(path)
	b.Add([]byte("a"), []byte("1"))
	b.Finish()

	// 破坏 footer 一个字节。
	data, _ := os.ReadFile(path)
	data[len(data)-1] ^= 0xFF
	os.WriteFile(path, data, 0640)

	if _, err := Open(path); err == nil {
		t.Fatal("expected checksum error")
	}
}

func TestBinaryKeyValue(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "000004.seg")

	b := NewBuilder(path)
	key := []byte{0x00, 0x01, 0x00, 0xFF}
	value := []byte{0xAB, 0xCD, 0x00}
	b.Add(key, value)
	b.Finish()

	r, _ := Open(path)
	defer r.Close()
	got, ok, _ := r.Get(key)
	if !ok || !bytes.Equal(got, value) {
		t.Fatalf("binary kv failed: %v %v", got, ok)
	}
}

func TestListNumbers(t *testing.T) {
	dir := t.TempDir()
	for _, n := range []uint64{5, 1, 10} {
		b := NewBuilder(filepath.Join(dir, Name(n)))
		b.Add([]byte("k"), []byte("v"))
		b.Finish()
	}
	nums, err := ListNumbers(dir)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(nums) != 3 || nums[0] != 10 || nums[1] != 5 || nums[2] != 1 {
		t.Fatalf("unexpected numbers %v", nums)
	}
}
