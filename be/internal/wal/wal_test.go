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
	rec := &Record{Type: TypePut, KVs: []KV{{Key: []byte("hello"), Value: []byte("world")}}}
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

	decoded, err := decodePayload(TypePut, buf[headerSize:])
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(decoded.KVs) != 1 || !bytes.Equal(decoded.KVs[0].Key, []byte("hello")) {
		t.Fatalf("decoded = %+v", decoded)
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
		if replayed[i].Type != TypePut {
			t.Fatalf("record %d type = %d, want PUT", i, replayed[i].Type)
		}
		if !bytes.Equal(replayed[i].KVs[0].Key, []byte(p.k)) ||
			!bytes.Equal(replayed[i].KVs[0].Value, []byte(p.v)) {
			t.Fatalf("record %d mismatch: %v", i, replayed[i])
		}
	}
}

// TestBatchRoundTrip 覆盖批记录往返编解码(多 KV、大 count、txn_id/commit_ts)。
func TestBatchRoundTrip(t *testing.T) {
	dir := t.TempDir()
	w, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer w.Close()

	kvs := make([]KV, 3000)
	for i := range kvs {
		kvs[i] = KV{
			Key:   []byte{byte(i >> 8), byte(i), 0xEE, 0xEE, 0xEE, 0xEE, 0xEE, 0xEE, 0xEE, 0xEE},
			Value: []byte{byte(i)},
		}
	}
	if err := w.AppendBatch(42, 7, kvs); err != nil {
		t.Fatalf("append batch: %v", err)
	}

	var got []Record
	if err := w.Replay(func(r Record) error {
		got = append(got, r)
		return nil
	}); err != nil {
		t.Fatalf("replay: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("records = %d, want 1", len(got))
	}
	rec := got[0]
	if rec.Type != TypeBatch || rec.CommitTS != 42 || rec.TxnID != 7 {
		t.Fatalf("header mismatch: %+v", rec)
	}
	if len(rec.KVs) != len(kvs) {
		t.Fatalf("kv count = %d, want %d", len(rec.KVs), len(kvs))
	}
	for i := range kvs {
		if !bytes.Equal(rec.KVs[i].Key, kvs[i].Key) || !bytes.Equal(rec.KVs[i].Value, kvs[i].Value) {
			t.Fatalf("kv %d mismatch", i)
		}
	}
}

// TestBatchTruncatedTail 手工构造"半条批记录",验证整体丢弃并物理截断。
func TestBatchTruncatedTail(t *testing.T) {
	dir := t.TempDir()
	w, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := w.AppendBatch(1, 1, []KV{{Key: []byte("k1v12345"), Value: []byte("v1")}}); err != nil {
		t.Fatalf("append 1: %v", err)
	}
	firstSize := fileSize(t, filepath.Join(dir, WalName(1)))
	if err := w.AppendBatch(2, 2, []KV{{Key: []byte("k2v12345"), Value: []byte("v2")}}); err != nil {
		t.Fatalf("append 2: %v", err)
	}
	w.Close()

	path := filepath.Join(dir, WalName(1))
	// 把第二条记录截掉一半(保留头,载荷不完整)。
	f, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		t.Fatalf("open wal: %v", err)
	}
	info, _ := f.Stat()
	if err := f.Truncate(info.Size() - 6); err != nil {
		t.Fatalf("truncate: %v", err)
	}
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
	if len(replayed) != 1 || replayed[0].CommitTS != 1 {
		t.Fatalf("replayed = %+v, want only the first batch", replayed)
	}

	// 物理截断:文件应回到第一条记录结束的位置。
	if got := fileSize(t, path); got != firstSize {
		t.Fatalf("file size after replay = %d, want %d (bad tail must be truncated)", got, firstSize)
	}

	// 截断后继续追加,下一次重放必须能看到新记录(证明没有卡在坏记录处)。
	if err := w2.AppendBatch(3, 3, []KV{{Key: []byte("k3v12345"), Value: []byte("v3")}}); err != nil {
		t.Fatalf("append after truncate: %v", err)
	}
	var after []Record
	if err := w2.Replay(func(r Record) error {
		after = append(after, r)
		return nil
	}); err != nil {
		t.Fatalf("replay after append: %v", err)
	}
	if len(after) != 2 || after[1].CommitTS != 3 {
		t.Fatalf("replayed after append = %+v", after)
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
	if got := fileSize(t, path); got != 0 {
		t.Fatalf("file size = %d, want 0 after CRC failure", got)
	}
}

// TestBatchCRCMismatch 破坏批记录载荷,验证整条丢弃。
func TestBatchCRCMismatch(t *testing.T) {
	dir := t.TempDir()
	w, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := w.AppendBatch(9, 9, []KV{{Key: []byte("abc12345"), Value: []byte("v")}}); err != nil {
		t.Fatalf("append: %v", err)
	}
	w.Close()

	path := filepath.Join(dir, WalName(1))
	data, _ := os.ReadFile(path)
	data[len(data)-1] ^= 0xFF
	os.WriteFile(path, data, 0640)

	w2, _ := Open(dir, 1)
	defer w2.Close()
	var replayed []Record
	if err := w2.Replay(func(r Record) error {
		replayed = append(replayed, r)
		return nil
	}); err != nil {
		t.Fatalf("replay: %v", err)
	}
	if len(replayed) != 0 {
		t.Fatalf("replayed %d, want 0", len(replayed))
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

func TestAppendEmptyBatch(t *testing.T) {
	dir := t.TempDir()
	w, _ := Open(dir, 1)
	defer w.Close()
	if err := w.AppendBatch(1, 1, nil); err == nil {
		t.Fatal("expected error for empty batch")
	}
	if err := w.AppendBatch(1, 1, []KV{{Key: []byte{}, Value: []byte("v")}}); err == nil {
		t.Fatal("expected error for empty key in batch")
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

func fileSize(t *testing.T, path string) int64 {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat %s: %v", path, err)
	}
	return info.Size()
}
