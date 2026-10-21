// Package wal 实现预写日志(Write-Ahead Log)。
//
// 记录格式(小端序):
//
//	┌─────────┬──────┬─────────────┬─────────────────────────────────────┐
//	│ CRC32C  │ Type │ PayloadLen  │ Payload                             │
//	│ 4 bytes │ 1    │ 4 bytes     │ key_len(4) | key | value_len(4) | value│
//	└─────────┴──────┴─────────────┴─────────────────────────────────────┘
//
// CRC32C 覆盖 Type + PayloadLen + Payload。Type 目前仅使用 PUT=1,
// BATCH=2 预留。记录之间无填充字节。
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
)

const (
	// TypePut 表示单条 Put 记录。
	TypePut byte = 1
	// TypeBatch 预留,供 P3 事务使用,P1 不实现。
	TypeBatch byte = 2

	headerSize      = 4 + 1 + 4 // CRC + Type + PayloadLen
	maxPayloadSize  = 1 << 31   // 约 2GB,与 32 位长度字段匹配
	maxValueSize    = 1 << 20   // 1MB,与 P1 规约一致
)

// castagnoli 表用于 CRC32C 校验。
var castagnoli = crc32.MakeTable(crc32.Castagnoli)

// Record 表示一条 WAL 记录。
type Record struct {
	Type  byte
	Key   []byte
	Value []byte
}

// WAL 提供追加写、fsync 与重放能力。
type WAL struct {
	dir      string
	number   uint64
	file     *os.File
	writer   *bufWriter
	mu       sync.Mutex
	closed   bool
}

// bufWriter 在内存中缓冲少量数据,减少系统调用次数,但 fsync 前会刷新缓冲区。
type bufWriter struct {
	w   *os.File
	buf []byte
}

func newBufWriter(f *os.File) *bufWriter {
	return &bufWriter{w: f, buf: make([]byte, 0, 64*1024)}
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

// Open 打开或创建指定编号的 WAL 文件。
func Open(dir string, number uint64) (*WAL, error) {
	if err := os.MkdirAll(dir, 0750); err != nil {
		return nil, fmt.Errorf("create wal dir %s: %w", dir, err)
	}
	path := filepath.Join(dir, WalName(number))
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0640)
	if err != nil {
		return nil, fmt.Errorf("open wal %s: %w", path, err)
	}
	return &WAL{
		dir:    dir,
		number: number,
		file:   f,
		writer: newBufWriter(f),
	}, nil
}

// Number 返回当前 WAL 文件编号。
func (w *WAL) Number() uint64 { return w.number }

// Append 追加一条记录并执行 fsync。
func (w *WAL) Append(key, value []byte) error {
	if len(key) == 0 {
		return errors.New("wal: empty key")
	}
	if len(value) > maxValueSize {
		return fmt.Errorf("wal: value too large %d > %d", len(value), maxValueSize)
	}

	w.mu.Lock()
	defer w.mu.Unlock()

	if w.closed {
		return errors.New("wal: closed")
	}

	rec := Record{Type: TypePut, Key: key, Value: value}
	buf, err := encodeRecord(&rec)
	if err != nil {
		return err
	}
	if err := w.writer.Write(buf); err != nil {
		return fmt.Errorf("wal: write record: %w", err)
	}
	if err := w.writer.Flush(); err != nil {
		return fmt.Errorf("wal: flush record: %w", err)
	}
	if err := w.file.Sync(); err != nil {
		return fmt.Errorf("wal: fsync failed: %w", err)
	}
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
	return w.file.Sync()
}

// encodeRecord 将记录编码为字节序列。
func encodeRecord(r *Record) ([]byte, error) {
	payloadLen := 4 + len(r.Key) + 4 + len(r.Value)
	if payloadLen > maxPayloadSize {
		return nil, fmt.Errorf("wal: payload too large %d", payloadLen)
	}
	buf := make([]byte, headerSize+payloadLen)
	buf[4] = r.Type
	binary.LittleEndian.PutUint32(buf[4+1:], uint32(payloadLen))
	off := headerSize
	binary.LittleEndian.PutUint32(buf[off:], uint32(len(r.Key)))
	off += 4
	copy(buf[off:], r.Key)
	off += len(r.Key)
	binary.LittleEndian.PutUint32(buf[off:], uint32(len(r.Value)))
	off += 4
	copy(buf[off:], r.Value)

	crc := crc32.Checksum(buf[4:], castagnoli)
	binary.LittleEndian.PutUint32(buf[0:], crc)
	return buf, nil
}

// Replay 逐条读取并回调 fn;遇到校验失败或不完整的记录时在此停止,不报错。
func (w *WAL) Replay(fn func(r Record) error) error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if _, err := w.file.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("wal: seek to start: %w", err)
	}
	return replayReader(w.file, fn)
}

// replayReader 从 io.Reader 重放记录,校验失败时停止。
func replayReader(r io.Reader, fn func(r Record) error) error {
	for {
		header := make([]byte, headerSize)
		if _, err := io.ReadFull(r, header); err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				return nil
			}
			return err
		}

		recordCRC := binary.LittleEndian.Uint32(header[0:4])
		recType := header[4]
		payloadLen := binary.LittleEndian.Uint32(header[5:9])
		if payloadLen > maxPayloadSize {
			// 长度非法,视为截断,停止重放。
			return nil
		}

		payload := make([]byte, payloadLen)
		if _, err := io.ReadFull(r, payload); err != nil {
			// 载荷读不完整,视为截断,停止重放。
			return nil
		}

		computedCRC := crc32.Checksum(header[4:], castagnoli)
		computedCRC = crc32.Update(computedCRC, castagnoli, payload)
		if computedCRC != recordCRC {
			// 校验失败,停止重放。
			return nil
		}

		rec, err := decodePayload(payload)
		if err != nil {
			// 载荷格式非法,停止重放。
			return nil
		}
		rec.Type = recType
		if err := fn(rec); err != nil {
			return err
		}
	}
}

func decodePayload(payload []byte) (Record, error) {
	if len(payload) < 4 {
		return Record{}, errors.New("wal: payload too short for key length")
	}
	keyLen := int(binary.LittleEndian.Uint32(payload[0:4]))
	if 4+keyLen+4 > len(payload) {
		return Record{}, errors.New("wal: key length out of range")
	}
	key := make([]byte, keyLen)
	copy(key, payload[4:4+keyLen])
	off := 4 + keyLen
	valueLen := int(binary.LittleEndian.Uint32(payload[off : off+4]))
	off += 4
	if off+valueLen != len(payload) {
		return Record{}, errors.New("wal: value length mismatch")
	}
	value := make([]byte, valueLen)
	copy(value, payload[off:])
	return Record{Key: key, Value: value}, nil
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
