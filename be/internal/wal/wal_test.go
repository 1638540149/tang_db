package wal

import (
	"bytes"
	"encoding/binary"
	"hash/crc32"
	"os"
	"path/filepath"
	"testing"
)

func TestEncodeDecode(t *testing.T) {
	rec := &Record{Type: TypePut, Key: []byte("hello"), Value: []byte("world")}
	buf, err := encodeRecord(rec)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	if len(buf) != headerSize+4+5+4+5 {
		t.Fatalf("unexpected encoded length %d", len(buf))
	}

	// 手动校验 CRC 覆盖范围:Type + PayloadLen + Payload。
	expectedCRC := crc32.Checksum(buf[4:], castagnoli)
	gotCRC := binary.LittleEndian.Uint32(buf[0:4])
	if gotCRC != expectedCRC {
		t.Fatalf("crc mismatch: got %x, want %x", gotCRC, expectedCRC)
	}

	// 长度字段小端序。
	payloadLen := binary.LittleEndian.Uint32(buf[5:9])
	if int(payloadLen) != 4+5+4+5 {
		t.Fatalf("payload len mismatch: %d", payloadLen)
	}
}

func TestAppendAndReplay(t *testing.T) {
	dir := t.TempDir()
	w, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer w.Close()

	pairs := []struct{ k, v string }{
		{"a", "1"},
		{"b", "2"},
		{"c", "3"},
	}
	for _, p := range pairs {
		if err := w.Append([]byte(p.k), []byte(p.v)); err != nil {
			t.Fatalf("append %s: %v", p.k, err)
		}
	}

	var replayed []Record
	if err := w.Replay(func(r Record) error {
		replayed = append(replayed, r)
		return nil
	}); err != nil {
		t.Fatalf("replay: %v", err)
	}
	if len(replayed) != len(pairs) {
		t.Fatalf("replayed %d, want %d", len(replayed), len(pairs))
	}
	for i, p := range pairs {
		if !bytes.Equal(replayed[i].Key, []byte(p.k)) || !bytes.Equal(replayed[i].Value, []byte(p.v)) {
			t.Fatalf("record %d mismatch: %v", i, replayed[i])
		}
	}
}

func TestReplayTruncatedRecord(t *testing.T) {
	dir := t.TempDir()
	w, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := w.Append([]byte("key1"), []byte("value1")); err != nil {
		t.Fatalf("append: %v", err)
	}
	if err := w.Append([]byte("key2"), []byte("value2")); err != nil {
		t.Fatalf("append: %v", err)
	}
	w.Close()

	// 截断最后若干字节,模拟崩溃时的半条写。
	path := filepath.Join(dir, WalName(1))
	info, _ := os.Stat(path)
	f, _ := os.OpenFile(path, os.O_RDWR, 0)
	f.Truncate(info.Size() - 5)
	f.Close()

	w2, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer w2.Close()

	var replayed []Record
	if err := w2.Replay(func(r Record) error {
		replayed = append(replayed, r)
		return nil
	}); err != nil {
		t.Fatalf("replay: %v", err)
	}
	if len(replayed) != 1 {
		t.Fatalf("replayed %d, want 1 (truncated record discarded)", len(replayed))
	}
}

func TestReplayCorruptedCRC(t *testing.T) {
	dir := t.TempDir()
	w, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := w.Append([]byte("key1"), []byte("value1")); err != nil {
		t.Fatalf("append: %v", err)
	}
	if err := w.Append([]byte("key2"), []byte("value2")); err != nil {
		t.Fatalf("append: %v", err)
	}
	w.Close()

	// 破坏第一条记录的 payload 一个字节。
	path := filepath.Join(dir, WalName(1))
	data, _ := os.ReadFile(path)
	data[headerSize+8] ^= 0xFF
	os.WriteFile(path, data, 0640)

	w2, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer w2.Close()

	var replayed []Record
	if err := w2.Replay(func(r Record) error {
		replayed = append(replayed, r)
		return nil
	}); err != nil {
		t.Fatalf("replay: %v", err)
	}
	if len(replayed) != 0 {
		t.Fatalf("replayed %d, want 0 (corrupted record and after must be discarded)", len(replayed))
	}
}

func TestAppendEmptyKey(t *testing.T) {
	dir := t.TempDir()
	w, _ := Open(dir, 1)
	defer w.Close()
	if err := w.Append([]byte{}, []byte("v")); err == nil {
		t.Fatal("expected error for empty key")
	}
}

func TestListNumbers(t *testing.T) {
	dir := t.TempDir()
	for _, n := range []uint64{3, 1, 2} {
		w, err := Open(dir, n)
		if err != nil {
			t.Fatalf("open %d: %v", n, err)
		}
		w.Close()
	}
	nums, err := ListNumbers(dir)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(nums) != 3 || nums[0] != 1 || nums[1] != 2 || nums[2] != 3 {
		t.Fatalf("unexpected numbers %v", nums)
	}
}
