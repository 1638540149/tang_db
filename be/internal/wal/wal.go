// Package wal 实现预写日志(Write-Ahead Log)。
//
// 记录格式(SPEC-P3 §5.3):
//
//	┌─────────┬──────┬─────────────┬──────────────────────────────────────────────┐
//	│ CRC32C  │ Type │ PayloadLen  │ Payload                                      │
//	│ 4 bytes │ 1    │ 4 bytes 小端 │ 见下                                          │
//	└─────────┴──────┴─────────────┴──────────────────────────────────────────────┘
//
//	Type = BATCH(2):commit_ts(8B 大端) | txn_id(8B 大端) | kvCount(4B 小端)
//	                 | [klen(4B 小端) | k | vlen(4B 小端) | v] × kvCount
//	Type = PUT(1):  key_len(4B 小端) | key | value_len(4B 小端) | value   (P1 老格式,只读兼容)
//
// CRC32C 覆盖 Type + PayloadLen + Payload。记录之间无填充字节。
//
// P3 起所有写入(Put 也不例外)都写成一条 BATCH 记录:一个事务的全部写入
// 物理上连续,整条记录完整 + CRC 通过 = 该事务已提交。库中不存在第二个
// "提交标记"(SPEC-P3 FR-313)。
package wal

import (
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

const (
	// TypePut 表示单条 Put 记录(P1 老格式)。P3 起只保留只读解析能力。
	TypePut byte = 1
	// TypeBatch 表示一个事务的原子批记录(P3 起所有写入都用它)。
	TypeBatch byte = 2

	headerSize     = 4 + 1 + 4 // CRC + Type + PayloadLen
	batchHeadSize  = 8 + 8 + 4 // commit_ts + txn_id + kvCount
	maxPayloadSize = 1 << 31   // 约 2GB,与 32 位长度字段匹配
	maxValueSize   = 1 << 20   // 1MB,仅用于 P1 的 Put 语义
)

// castagnoli 表用于 CRC32C 校验。
var castagnoli = crc32.MakeTable(crc32.Castagnoli)

// KV 是批记录里的一条完整键值对(key 已含版本段)。
type KV struct {
	Key   []byte
	Value []byte
}

// Record 表示一条 WAL 记录。
//
// TypePut 记录只有一个 KV 且 CommitTS 为 0;TypeBatch 记录带 commit_ts / txn_id
// 与多条 KV。
type Record struct {
	Type     byte
	CommitTS uint64
	TxnID    uint64
	KVs      []KV
}

// WAL 提供追加写、fsync 与重放能力。
type WAL struct {
	dir    string
	number uint64
	file   *os.File
	writer *bufWriter
	mu     sync.Mutex
	closed bool
	// syncs 记录成功 fsync 的次数(诊断/验收用,SPEC-P6.1 group commit 合批观测)。
	syncs atomic.Uint64
}

// bufWriter 在内存中缓冲少量数据,减少系统调用次数,但 fsync 前会刷新缓冲区。
type bufWriter struct {
	w   *os.File
	buf []byte
}

// DefaultBufferBytes 是 WAL 追加缓冲的默认大小(SPEC-P6.1 §5.2 未配置时)。
const DefaultBufferBytes = 64 * 1024

func newBufWriter(f *os.File, bufferBytes int) *bufWriter {
	if bufferBytes < 4096 {
		bufferBytes = 4096
	}
	return &bufWriter{w: f, buf: make([]byte, 0, bufferBytes)}
}

func (bw *bufWriter) Write(p []byte) error {
	if len(bw.buf)+len(p) > cap(bw.buf) {
		if err := bw.Flush(); err != nil {
			return err
		}
	}
	if len(p) > cap(bw.buf) {
		_, err := bw.w.Write(p)
		return err
	}
	bw.buf = append(bw.buf, p...)
	return nil
}

func (bw *bufWriter) Flush() error {
	if len(bw.buf) == 0 {
		return nil
	}
	_, err := bw.w.Write(bw.buf)
	bw.buf = bw.buf[:0]
	return err
}

// Open 打开或创建指定编号的 WAL 文件(默认缓冲)。
func Open(dir string, number uint64) (*WAL, error) {
	return OpenWithBuffer(dir, number, DefaultBufferBytes)
}

// OpenWithBuffer 打开或创建指定编号的 WAL 文件,追加缓冲大小为 bufferBytes
// (SPEC-P6.1 FR-699 / `wal_buffer_bytes`)。
func OpenWithBuffer(dir string, number uint64, bufferBytes int) (*WAL, error) {
	if err := os.MkdirAll(dir, 0750); err != nil {
		return nil, fmt.Errorf("create wal dir %s: %w", dir, err)
	}
	path := filepath.Join(dir, WalName(number))
	// 不用 O_APPEND:Windows 上 O_APPEND 只给 FILE_APPEND_DATA 权限,
	// 随后对坏尾部做 Truncate 会被拒绝(Access is denied)。写入端由本结构体的
	// 互斥锁串行化,打开后主动 seek 到文件末尾即可。
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0640)
	if err != nil {
		return nil, fmt.Errorf("open wal %s: %w", path, err)
	}
	if _, err := f.Seek(0, io.SeekEnd); err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("seek wal %s to end: %w", path, err)
	}
	return &WAL{
		dir:    dir,
		number: number,
		file:   f,
		writer: newBufWriter(f, bufferBytes),
	}, nil
}

// Number 返回当前 WAL 文件编号。
func (w *WAL) Number() uint64 { return w.number }

// Append 追加一条 P1 老格式记录并执行 fsync。
//
// P3 起引擎不再调用它(所有写入都走 AppendBatch);保留它是为了单独验证
// TypePut 的只读解析兼容能力与老测试。
func (w *WAL) Append(key, value []byte) error {
	if len(key) == 0 {
		return errors.New("wal: empty key")
	}
	if len(value) > maxValueSize {
		return fmt.Errorf("wal: value too large %d > %d", len(value), maxValueSize)
	}
	rec := &Record{Type: TypePut, KVs: []KV{{Key: key, Value: value}}}
	return w.appendRecord(rec)
}

// AppendBatch 把整个事务作为一个 TypeBatch 记录一次追加,并执行一次 fsync。
//
// 返回 nil 即代表:这条批记录已完整落盘(CRC 覆盖类型、长度与载荷),
// 进程被强杀后重放仍然能看到它 —— 这就是"已提交"的唯一判据。
func (w *WAL) AppendBatch(commitTS, txnID uint64, kvs []KV) error {
	if len(kvs) == 0 {
		return errors.New("wal: empty batch")
	}
	for _, kv := range kvs {
		if len(kv.Key) == 0 {
			return errors.New("wal: empty key in batch")
		}
	}
	rec := &Record{Type: TypeBatch, CommitTS: commitTS, TxnID: txnID, KVs: kvs}
	return w.appendRecord(rec)
}

func (w *WAL) appendRecord(rec *Record) error {
	return w.appendRecordBuffered(rec, true)
}

// AppendBatchBuffered 把批记录写进缓冲但**不做 fsync**(SPEC-P6.1 FR-698 / FR-699)。
//
// 供 group commit 使用:多个提交顺序追加,由 leader 用一次 Sync 覆盖它们,
// 每个事务在"覆盖它的 fsync"完成后才算提交成功。缓冲写失败仍会立刻返回错误。
func (w *WAL) AppendBatchBuffered(commitTS, txnID uint64, kvs []KV) error {
	if len(kvs) == 0 {
		return errors.New("wal: empty batch")
	}
	for _, kv := range kvs {
		if len(kv.Key) == 0 {
			return errors.New("wal: empty key in batch")
		}
	}
	rec := &Record{Type: TypeBatch, CommitTS: commitTS, TxnID: txnID, KVs: kvs}
	return w.appendRecordBuffered(rec, false)
}

// recordBufPool 复用编码缓冲,消除每批一次大块分配(FR-699)。
// 只有不超过 1MB 的缓冲回池,避免池被单次大批撑大。
var recordBufPool = sync.Pool{
	New: func() any {
		b := make([]byte, 0, 64*1024)
		return &b
	},
}

func (w *WAL) appendRecordBuffered(rec *Record, doSync bool) error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.closed {
		return errors.New("wal: closed")
	}

	holder := recordBufPool.Get().(*[]byte)
	buf, err := encodeRecordInto((*holder)[:0], rec)
	if err != nil {
		recordBufPool.Put(holder)
		return err
	}
	writeErr := w.writer.Write(buf)
	if cap(buf) <= 1<<20 {
		*holder = buf
		recordBufPool.Put(holder)
	}
	if writeErr != nil {
		return fmt.Errorf("wal: write record: %w", writeErr)
	}
	if !doSync {
		return nil
	}
	if err := w.writer.Flush(); err != nil {
		return fmt.Errorf("wal: flush record: %w", err)
	}
	if err := w.file.Sync(); err != nil {
		return fmt.Errorf("wal: fsync failed: %w", err)
	}
	w.syncs.Add(1)
	return nil
}

// Close 关闭 WAL 文件。
func (w *WAL) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return nil
	}
	w.closed = true
	_ = w.writer.Flush()
	return w.file.Close()
}

// Sync 强制刷盘。主要用于测试与关键检查点。
func (w *WAL) Sync() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return errors.New("wal: closed")
	}
	if err := w.writer.Flush(); err != nil {
		return err
	}
	if err := w.file.Sync(); err != nil {
		return err
	}
	w.syncs.Add(1)
	return nil
}

// SyncCount 返回成功 fsync 的次数(测试与验收观测 group commit 合批效果)。
func (w *WAL) SyncCount() uint64 { return w.syncs.Load() }

// encodeRecord 将记录编码为字节序列。
func encodeRecord(r *Record) ([]byte, error) {
	return encodeRecordInto(nil, r)
}

// encodeRecordInto 把记录编码进 dst(dst[:0] 起用,容量不够时重新分配)。
// 复用缓冲是 FR-699 的核心:每批不再分配两块大内存。
func encodeRecordInto(dst []byte, r *Record) ([]byte, error) {
	payloadLen, err := recordPayloadLen(r)
	if err != nil {
		return nil, err
	}
	total := headerSize + payloadLen
	if cap(dst) < total {
		dst = make([]byte, total)
	} else {
		dst = dst[:total]
	}
	payload := dst[headerSize:]

	switch r.Type {
	case TypePut:
		k, v := r.KVs[0].Key, r.KVs[0].Value
		off := 0
		binary.LittleEndian.PutUint32(payload[off:], uint32(len(k)))
		off += 4
		copy(payload[off:], k)
		off += len(k)
		binary.LittleEndian.PutUint32(payload[off:], uint32(len(v)))
		off += 4
		copy(payload[off:], v)
	case TypeBatch:
		binary.BigEndian.PutUint64(payload[0:8], r.CommitTS)
		binary.BigEndian.PutUint64(payload[8:16], r.TxnID)
		binary.LittleEndian.PutUint32(payload[16:20], uint32(len(r.KVs)))
		off := batchHeadSize
		for _, kv := range r.KVs {
			binary.LittleEndian.PutUint32(payload[off:], uint32(len(kv.Key)))
			off += 4
			copy(payload[off:], kv.Key)
			off += len(kv.Key)
			binary.LittleEndian.PutUint32(payload[off:], uint32(len(kv.Value)))
			off += 4
			copy(payload[off:], kv.Value)
			off += len(kv.Value)
		}
	default:
		return nil, fmt.Errorf("wal: unknown record type %d", r.Type)
	}

	dst[4] = r.Type
	binary.LittleEndian.PutUint32(dst[5:9], uint32(payloadLen))
	crc := crc32.Checksum(dst[4:], castagnoli)
	binary.LittleEndian.PutUint32(dst[0:4], crc)
	return dst, nil
}

// recordPayloadLen 校验记录结构并返回载荷长度(编码前的一次遍历,不做分配)。
func recordPayloadLen(r *Record) (int, error) {
	switch r.Type {
	case TypePut:
		if len(r.KVs) != 1 {
			return 0, errors.New("wal: PUT record must have exactly one kv")
		}
		payloadLen := 4 + len(r.KVs[0].Key) + 4 + len(r.KVs[0].Value)
		if payloadLen > maxPayloadSize {
			return 0, fmt.Errorf("wal: payload too large %d", payloadLen)
		}
		return payloadLen, nil
	case TypeBatch:
		if len(r.KVs) == 0 {
			return 0, errors.New("wal: empty batch")
		}
		payloadLen := batchHeadSize
		for _, kv := range r.KVs {
			if len(kv.Key) == 0 {
				return 0, errors.New("wal: empty key in batch")
			}
			payloadLen += 4 + len(kv.Key) + 4 + len(kv.Value)
			if payloadLen > maxPayloadSize {
				return 0, fmt.Errorf("wal: payload too large %d", payloadLen)
			}
		}
		return payloadLen, nil
	default:
		return 0, fmt.Errorf("wal: unknown record type %d", r.Type)
	}
}

// Replay 逐条读取并回调 fn;遇到记录不完整或 CRC 不符时在断点处**物理截断**
// 文件(整条丢弃),不报错、服务照常启动(SPEC-P3 FR-315 / INV-2)。
//
// 截断是必须的:WAL 以 O_APPEND 打开,如果坏记录留在尾部,之后的新记录会排在
// 它后面,下次重放又会在坏记录处停下,导致新数据"看起来没写进去"。
func (w *WAL) Replay(fn func(r Record) error) error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if _, err := w.file.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("wal: seek to start: %w", err)
	}
	validLen, err := replayReader(w.file, fn)
	if err != nil {
		return err
	}
	if err := w.file.Truncate(validLen); err != nil {
		return fmt.Errorf("wal: truncate bad tail: %w", err)
	}
	if _, err := w.file.Seek(0, io.SeekEnd); err != nil {
		return fmt.Errorf("wal: seek to end: %w", err)
	}
	if err := w.file.Sync(); err != nil {
		return fmt.Errorf("wal: fsync after truncate: %w", err)
	}
	return nil
}

// replayReader 从 io.Reader 重放记录,返回"最后一条完整记录的结束偏移"。
// 任何读不满 / CRC 不符 / 载荷格式非法都视为尾部损坏:停止并返回已消费长度。
func replayReader(r io.Reader, fn func(r Record) error) (int64, error) {
	var offset int64
	for {
		header := make([]byte, headerSize)
		if _, err := io.ReadFull(r, header); err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				return offset, nil
			}
			return offset, err
		}

		recordCRC := binary.LittleEndian.Uint32(header[0:4])
		recType := header[4]
		payloadLen := binary.LittleEndian.Uint32(header[5:9])
		if payloadLen > maxPayloadSize {
			// 长度非法,视为截断,停止重放。
			return offset, nil
		}

		payload := make([]byte, payloadLen)
		if _, err := io.ReadFull(r, payload); err != nil {
			// 载荷读不完整,视为截断,停止重放。
			return offset, nil
		}

		computedCRC := crc32.Checksum(header[4:], castagnoli)
		computedCRC = crc32.Update(computedCRC, castagnoli, payload)
		if computedCRC != recordCRC {
			// 校验失败,整条丢弃,停止重放。
			return offset, nil
		}

		rec, err := decodePayload(recType, payload)
		if err != nil {
			// 载荷格式非法,当作损坏记录整条丢弃。
			return offset, nil
		}
		if err := fn(rec); err != nil {
			return offset, err
		}
		offset += int64(headerSize) + int64(payloadLen)
	}
}

func decodePayload(recType byte, payload []byte) (Record, error) {
	switch recType {
	case TypePut:
		if len(payload) < 4 {
			return Record{}, errors.New("wal: PUT payload too short for key length")
		}
		keyLen := int(binary.LittleEndian.Uint32(payload[0:4]))
		if keyLen < 0 || 4+keyLen+4 > len(payload) {
			return Record{}, errors.New("wal: PUT key length out of range")
		}
		key := make([]byte, keyLen)
		copy(key, payload[4:4+keyLen])
		off := 4 + keyLen
		valueLen := int(binary.LittleEndian.Uint32(payload[off : off+4]))
		off += 4
		if valueLen < 0 || off+valueLen != len(payload) {
			return Record{}, errors.New("wal: PUT value length mismatch")
		}
		value := make([]byte, valueLen)
		copy(value, payload[off:])
		return Record{Type: TypePut, KVs: []KV{{Key: key, Value: value}}}, nil

	case TypeBatch:
		if len(payload) < batchHeadSize {
			return Record{}, errors.New("wal: BATCH payload too short")
		}
		commitTS := binary.BigEndian.Uint64(payload[0:8])
		txnID := binary.BigEndian.Uint64(payload[8:16])
		count := int(binary.LittleEndian.Uint32(payload[16:20]))
		if count < 0 {
			return Record{}, errors.New("wal: BATCH kv count out of range")
		}
		off := batchHeadSize
		kvs := make([]KV, 0, count)
		for i := 0; i < count; i++ {
			if off+4 > len(payload) {
				return Record{}, errors.New("wal: BATCH key length truncated")
			}
			keyLen := int(binary.LittleEndian.Uint32(payload[off : off+4]))
			off += 4
			if keyLen < 0 || off+keyLen+4 > len(payload) {
				return Record{}, errors.New("wal: BATCH key truncated")
			}
			key := make([]byte, keyLen)
			copy(key, payload[off:off+keyLen])
			off += keyLen
			valueLen := int(binary.LittleEndian.Uint32(payload[off : off+4]))
			off += 4
			if valueLen < 0 || off+valueLen > len(payload) {
				return Record{}, errors.New("wal: BATCH value truncated")
			}
			value := make([]byte, valueLen)
			copy(value, payload[off:off+valueLen])
			off += valueLen
			kvs = append(kvs, KV{Key: key, Value: value})
		}
		if off != len(payload) {
			return Record{}, errors.New("wal: BATCH trailing bytes")
		}
		return Record{Type: TypeBatch, CommitTS: commitTS, TxnID: txnID, KVs: kvs}, nil

	default:
		return Record{}, fmt.Errorf("wal: unknown record type %d", recType)
	}
}

// WalName 返回 WAL 文件名,例如 000012.wal。
func WalName(number uint64) string {
	return fmt.Sprintf("%06d.wal", number)
}

// ParseName 解析 WAL 文件名,返回编号;如果不是 WAL 文件则 ok=false。
func ParseName(name string) (number uint64, ok bool) {
	if !strings.HasSuffix(name, ".wal") {
		return 0, false
	}
	n, err := strconv.ParseUint(name[:len(name)-4], 10, 64)
	if err != nil {
		return 0, false
	}
	return n, true
}

// ListNumbers 列出目录下所有 WAL 文件编号并按升序返回。
func ListNumbers(dir string) ([]uint64, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	var nums []uint64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if n, ok := ParseName(e.Name()); ok {
			nums = append(nums, n)
		}
	}
	sort.Slice(nums, func(i, j int) bool { return nums[i] < nums[j] })
	return nums, nil
}

// MaxNumber 返回目录下最大的 WAL 编号;不存在时返回 0 与 nil 错误。
func MaxNumber(dir string) (uint64, error) {
	nums, err := ListNumbers(dir)
	if err != nil {
		return 0, err
	}
	if len(nums) == 0 {
		return 0, nil
	}
	return nums[len(nums)-1], nil
}
